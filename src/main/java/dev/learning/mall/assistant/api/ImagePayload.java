package dev.learning.mall.assistant.api;

/**
 * 一条消息里附带的一张图片（以 base64 内联传输）。
 *
 * <p>为什么用 JSON + base64 而不是 {@code multipart/form-data}：助手有两个发送端点
 * （同步与 SSE 流式），流式端点必须自带 CSRF 头与 JSON 正文，走 multipart 就要为同一个动作
 * 维护两套序列化与两套错误处理。base64 的体积开销约 33%，换来的是「一条链路、一套校验」，
 * 对教学项目和这个体量的图片来说划算。真正的代价由 {@code max-bytes-per-image} 封顶。
 */
public record ImagePayload(

        /** 声明的 MIME 类型，如 {@code image/jpeg}。只是线索，真正的判定看文件头（见 ImageGuard）。 */
        String mime,

        /** base64 编码的图片字节；允许带 {@code data:image/xxx;base64,} 前缀，服务端会剥掉。 */
        String data) {}
