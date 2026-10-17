package dev.learning.mall.assistant.vision;

/**
 * 一张已通过校验的上传图片。
 *
 * <p><b>它只活在当次请求的内存里</b>：不落库、不写日志、不进审计、不进运行轨迹。
 * 这不是偷懒——图片体积大（几 MB）、可能含隐私（家里的摆设、屏幕上的聊天记录），
 * 落进数据库或日志里就是一笔无法撤回的负债，而且日志系统从来不是为存二进制设计的。
 * 落库的只有它转换出来的那段文字描述。
 */
public record UploadedImage(String mime, byte[] bytes) {

    /** 供日志/提示语使用的人类可读体积，如 {@code 412KB}。刻意不含任何图片内容。 */
    public String readableSize() {
        int kb = bytes.length / 1024;
        return kb >= 1024 ? String.format("%.1fMB", bytes.length / 1024.0 / 1024.0) : kb + "KB";
    }
}
