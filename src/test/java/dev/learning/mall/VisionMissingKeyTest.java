package dev.learning.mall;

import dev.learning.mall.assistant.vision.VisionDescriber;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 开了视觉开关却没配密钥时会发生什么。
 *
 * <p><b>这个测试存在的原因，是它曾经抓到一个真实缺陷。</b>最初视觉模型 Bean 只挂在两个开关上，
 * 密钥缺失时三方库抛 {@code apiKey cannot be null or empty}，结果是——
 * <b>整个应用启动失败</b>。
 *
 * <p>这是用户最可能踩到的第一个坑：照着文档把 {@code enabled} 改成 true，却还没来得及申请密钥。
 * 后果却是商城整个起不来，而启动日志里只有一句来自三方库的断言错误，看不出是哪个配置项漏了。
 * 为一个可选功能搭上整体可用性，这个交换太亏了
 * （本项目在并发闸门上采取的是同一条原则：保护整体、牺牲个体）。
 *
 * <p>现在的行为是：<b>密钥缺失 = 不装配这个 Bean</b>。视觉能力降级为「如实告知看不了图」，
 * 应用其余部分照常工作，日志里留一条说清怎么修的警告。
 *
 * <p><b>下面的密钥全是占位串。</b>这里要隔离的变量只有一个：视觉 provider 的密钥缺失。
 * 所以对话模型的密钥必须给（占位即可），否则测到的就是「连助手都没开起来」，
 * 而不是我们要的「助手照常、只有视觉降级」。
 * 视觉侧故意选 deepseek：它的回落目标是 {@code deepseek.api-key}，
 * 不会因为对话侧那份 zhipu 密钥的存在而被意外救活。
 */
@SpringBootTest(properties = {
        "mall.assistant.enabled=true",
        "mall.assistant.zhipu.api-key=dummy-key-not-real",
        "mall.assistant.vision.enabled=true",
        "mall.assistant.vision.provider=deepseek",
        // 必须显式清空：视觉密钥留空时会回落到同 provider 的对话密钥，而那份密钥在
        // 开发环境里通常是通过环境变量注入的（DEEPSEEK_API_KEY）。不清空的话，
        // 本机跑就会「被本机密钥救活」、CI 上跑又是另一套结果——测试隔离变量失败。
        // 换台机器就变结论的测试，比没有测试更危险。
        "mall.assistant.deepseek.api-key=",
        // 故意不给 mall.assistant.vision.api-key
})
@ActiveProfiles("test")
class VisionMissingKeyTest {

    @Autowired ApplicationContext context;
    @Autowired VisionDescriber visionDescriber;

    @Test
    @DisplayName("缺密钥时应用照常启动：这个测试类能被实例化，本身就是断言")
    void applicationStillStarts() {
        assertThat(context).isNotNull();
    }

    @Test
    @DisplayName("缺密钥时不装配视觉模型 Bean，而不是让启动失败")
    void visionBeanIsAbsent() {
        assertThat(context.containsBean("visionChatModel")).isFalse();
    }

    @Test
    @DisplayName("缺密钥时视觉能力降级为不可用：走「如实告知」而不是让对话模型去猜")
    void visionDegradesToUnavailable() {
        assertThat(visionDescriber.isAvailable()).isFalse();
    }
}
