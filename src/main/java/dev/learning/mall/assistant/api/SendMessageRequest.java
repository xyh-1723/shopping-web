package dev.learning.mall.assistant.api;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * 发一条消息。文字与图片<b>至少要有一个</b>——「只发图不打字」是真实且常见的用法
 * （看到一件东西，拍下来问有没有同款），不该被表单校验挡在门外。
 */
public record SendMessageRequest(

        @Size(max = 4000, message = "单条消息不超过 4000 字")
        String message,

        /** 附带的图片。最多几张、单张多大由 {@code mall.assistant.vision.*} 决定，见 ImageGuard。 */
        List<ImagePayload> images) {

    @AssertTrue(message = "说点什么，或者上传一张图片")
    public boolean isValid() {
        return (message != null && !message.isBlank()) || (images != null && !images.isEmpty());
    }
}
