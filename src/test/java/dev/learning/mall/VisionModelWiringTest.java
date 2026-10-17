package dev.learning.mall;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 视觉模型 Bean 的装配条件。
 *
 * <p>为什么值得单独测：视觉模型是「可选能力」，用 {@code @ConditionalOnExpression}
 * 挂在两个开关的合取上（助手总开关 ∧ 视觉开关）。这类条件写错的表现非常隐蔽——
 * 要么「开了却没装配」（功能静默失效，用户以为能看图其实一直是降级路径），
 * 要么「没开却装配了」（为用不到的能力多付一次连接）。
 *
 * <p>两种失效都不会让测试变红，所以只能靠显式断言把它钉住。
 *
 * <p><b>关于下面这些密钥：</b>它们全都是不能通过任何服务端校验的占位串。
 * 本测试验证的是<b>装配条件</b>，不是「能不能真的调到模型」——构建 ChatModel 客户端
 * 本身不发任何网络请求，所以这里不需要、也不应该出现真实密钥。
 * 仓库里一旦写死真密钥，就等于把它提交进了每个克隆者的历史里。
 */
@SpringBootTest(properties = {
        "mall.assistant.enabled=true",
        // 对话模型：走 zhipu
        "mall.assistant.zhipu.api-key=dummy-key-not-real",
        // 视觉模型：故意走另一个 provider，这样两个 Bean 连实现类都不同，
        // 「看图没有挪用对话模型」这件事在类型层面就钉死了，而不只是引用不同。
        "mall.assistant.vision.enabled=true",
        "mall.assistant.vision.provider=deepseek",
        "mall.assistant.vision.model=dummy-vision-model",
        "mall.assistant.vision.api-key=dummy-key-not-real",
})
@ActiveProfiles("test")
class VisionModelWiringTest {

    @Autowired ApplicationContext context;

    @Test
    @DisplayName("两个开关都开时，视觉模型 Bean 存在")
    void visionBeanExistsWhenBothSwitchesOn() {
        assertThat(context.containsBean("visionChatModel")).isTrue();
        assertThat(context.getBean("visionChatModel")).isNotNull();
    }

    @Test
    @DisplayName("视觉模型与对话模型是两个独立的 Bean：看图不该动用对话模型")
    void visionBeanIsSeparateFromChatModel() {
        assertThat(context.getBean("visionChatModel"))
                .isNotSameAs(context.getBean("chatModel"));
    }
}
