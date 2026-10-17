package dev.learning.mall.assistant.vision;

import dev.learning.mall.assistant.api.ImagePayload;
import dev.learning.mall.common.BusinessException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * 图片入口校验：把「用户上传的图片」当作不可信输入来处理（对应 LLM10 无限消耗 与 LLM01 注入载体）。
 *
 * <p>四道检查，逐层收紧：
 *
 * <ol>
 *   <li><b>数量</b>——图片按 token 计费且不可压缩，必须封顶，否则一次请求就能烧掉一天的额度；
 *   <li><b>体积</b>——base64 解码后按字节算，带上界；这一条必须在解码之后判，
 *       因为「先解码再判断」和「先判断长度再解码」差了一个 33% 的膨胀系数；
 *   <li><b>声明类型</b>——MIME 白名单，但<b>只当线索</b>；
 *   <li><b>真实类型</b>——读文件头魔数，与声明类型交叉验证。
 * </ol>
 *
 * <p><b>第 4 条是本类存在的理由。</b>客户端声明的 {@code mime} 完全由调用方控制，
 * 一个 {@code image/png} 的头后面可以跟任何字节：SVG（可携带脚本）、HTML、ZIP，
 * 甚至是精心设计用来触发上游解析器漏洞的畸形数据。只校验声明等于没有校验——
 * 这就是「信了 Content-Type」的经典错误。文件头不能伪造得像，因为它是格式定义的一部分。
 *
 * <p>SVG 被刻意排除在白名单外：它是 XML，能内嵌脚本与外链，历史上是 XSS 与
 * 「十亿次笑声」式实体膨胀攻击的常用载体，而这里只需要位图。
 */
public class ImageGuard {

    /** 允许的位图类型：文件头 → MIME。顺序即检查顺序。 */
    private static final List<String> ALLOWED_MIMES = List.of("image/jpeg", "image/png", "image/webp", "image/gif");

    private static final int MAX_MAGIC_LENGTH = 12;

    private final int maxImages;
    private final int maxBytesPerImage;

    public ImageGuard(int maxImages, int maxBytesPerImage) {
        this.maxImages = maxImages;
        this.maxBytesPerImage = maxBytesPerImage;
    }

    /**
     * 校验并解码一批图片。任何一张不合规就整批拒绝——「部分接受」会让
     * 「这张图到底有没有被看到」变成一个说不清的问题。
     *
     * @return 通过校验的图片；入参为空时返回空列表（不抛异常，没发图是正常情况）
     */
    public List<UploadedImage> inspect(List<ImagePayload> payloads) {
        if (payloads == null || payloads.isEmpty()) {
            return List.of();
        }
        if (payloads.size() > maxImages) {
            throw BusinessException.badRequest("IMAGE_TOO_MANY",
                    "一次最多上传 " + maxImages + " 张图片，这次有 " + payloads.size() + " 张。");
        }
        List<UploadedImage> accepted = new ArrayList<>(payloads.size());
        for (int i = 0; i < payloads.size(); i++) {
            accepted.add(one(payloads.get(i), i + 1));
        }
        return List.copyOf(accepted);
    }

    private UploadedImage one(ImagePayload payload, int ordinal) {
        if (payload == null || payload.data() == null || payload.data().isBlank()) {
            throw BusinessException.badRequest("IMAGE_EMPTY", "第 " + ordinal + " 张图片是空的。");
        }
        String base64 = stripDataUrlPrefix(payload.data()).strip();
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(base64);
        } catch (IllegalArgumentException e) {
            throw BusinessException.badRequest("IMAGE_BAD_BASE64",
                    "第 " + ordinal + " 张图片不是合法的 base64 编码。");
        }
        if (bytes.length == 0) {
            throw BusinessException.badRequest("IMAGE_EMPTY", "第 " + ordinal + " 张图片是空的。");
        }
        if (bytes.length > maxBytesPerImage) {
            throw BusinessException.badRequest("IMAGE_TOO_LARGE",
                    "第 " + ordinal + " 张图片太大（" + (bytes.length / 1024 / 1024 + 1)
                            + "MB），单张不超过 " + (maxBytesPerImage / 1024 / 1024) + "MB。");
        }
        String declared = normalizeMime(payload.mime());
        if (declared == null || !ALLOWED_MIMES.contains(declared)) {
            throw BusinessException.badRequest("IMAGE_TYPE_NOT_ALLOWED",
                    "第 " + ordinal + " 张图片的类型不支持，只接受 JPG / PNG / WebP / GIF。");
        }
        String actual = sniff(bytes);
        if (actual == null) {
            // 这里刻意不回显识别到了什么：给攻击者反馈「你的载荷被识别成了 X」没有好处。
            throw BusinessException.badRequest("IMAGE_CONTENT_MISMATCH",
                    "第 " + ordinal + " 张图片的内容不是有效的 JPG / PNG / WebP / GIF 文件。");
        }
        if (!actual.equals(declared)) {
            throw BusinessException.badRequest("IMAGE_CONTENT_MISMATCH",
                    "第 " + ordinal + " 张图片声明的类型与实际内容不一致，已拒绝。");
        }
        return new UploadedImage(actual, bytes);
    }

    /** 浏览器 FileReader 的 readAsDataURL 会带 {@code data:image/png;base64,} 前缀，服务端也要能剥。 */
    private static String stripDataUrlPrefix(String raw) {
        int comma = raw.indexOf(',');
        if (raw.startsWith("data:") && comma > 0 && comma < 64) {
            return raw.substring(comma + 1);
        }
        return raw;
    }

    /** 去掉 MIME 上可能带的参数（如 {@code image/jpeg; charset=binary}）并转小写。 */
    private static String normalizeMime(String mime) {
        if (mime == null) {
            return null;
        }
        String value = mime.strip().toLowerCase();
        int semicolon = value.indexOf(';');
        return semicolon > 0 ? value.substring(0, semicolon).strip() : value;
    }

    /**
     * 按文件头判断真实类型，与声明类型无关。
     *
     * @return 识别出的 MIME；无法识别时返回 {@code null}
     */
    static String sniff(byte[] bytes) {
        if (bytes.length < MAX_MAGIC_LENGTH) {
            return null;
        }
        int b0 = bytes[0] & 0xFF;
        int b1 = bytes[1] & 0xFF;
        int b2 = bytes[2] & 0xFF;
        int b3 = bytes[3] & 0xFF;

        // JPEG: FF D8 FF
        if (b0 == 0xFF && b1 == 0xD8 && b2 == 0xFF) {
            return "image/jpeg";
        }
        // PNG: 89 50 4E 47 0D 0A 1A 0A
        if (b0 == 0x89 && b1 == 0x50 && b2 == 0x4E && b3 == 0x47
                && (bytes[4] & 0xFF) == 0x0D && (bytes[5] & 0xFF) == 0x0A
                && (bytes[6] & 0xFF) == 0x1A && (bytes[7] & 0xFF) == 0x0A) {
            return "image/png";
        }
        // GIF: "GIF87a" / "GIF89a"
        if (b0 == 0x47 && b1 == 0x49 && b2 == 0x46 && b3 == 0x38
                && ((bytes[4] & 0xFF) == 0x37 || (bytes[4] & 0xFF) == 0x39) && (bytes[5] & 0xFF) == 0x61) {
            return "image/gif";
        }
        // WebP: "RIFF" .... "WEBP"
        if (b0 == 0x52 && b1 == 0x49 && b2 == 0x46 && b3 == 0x46
                && (bytes[8] & 0xFF) == 0x57 && (bytes[9] & 0xFF) == 0x45
                && (bytes[10] & 0xFF) == 0x42 && (bytes[11] & 0xFF) == 0x50) {
            return "image/webp";
        }
        return null;
    }
}
