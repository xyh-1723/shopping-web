package dev.learning.mall.assistant.internal;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.community.model.zhipu.ZhipuAiChatModel;
import dev.langchain4j.community.model.zhipu.ZhipuAiStreamingChatModel;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.embedding.onnx.bgesmallzhv15q.BgeSmallZhV15QuantizedEmbeddingModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import dev.learning.mall.assistant.knowledge.KnowledgeProperties;
import dev.learning.mall.assistant.security.AssistantSecurityProperties;
import dev.learning.mall.assistant.security.InputGuard;
import dev.learning.mall.assistant.security.OutputGuard;
import dev.learning.mall.assistant.vision.ImageGuard;
import dev.learning.mall.assistant.vision.VisionDescriber;
import java.time.Duration;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * 仅当 mall.assistant.enabled=true 时才装配模型 Bean，根据 provider 选择实现：
 * - zhipu：智谱 GLM（langchain4j-community-zhipu-ai）
 * - deepseek：DeepSeek（OpenAI 兼容接口，langchain4j-open-ai）
 * 未启用时助手以演示模式运行（落库用户消息并返回提示），不影响其它模块。
 *
 * <p>输入/输出守卫与模型无关，无论是否启用都装配——演示模式下同样能演示拦截逻辑。
 */
@Configuration
@EnableConfigurationProperties({AssistantProperties.class, AssistantSecurityProperties.class, KnowledgeProperties.class})
public class AssistantConfig {

    private static final Logger log = LoggerFactory.getLogger(AssistantConfig.class);

    @Bean
    InputGuard inputGuard(AssistantSecurityProperties security) {
        return new InputGuard(security.getMaxInputLength(), security.isMaskPii());
    }

    /**
     * 进程内中文嵌入模型（BGE-small-zh-v1.5 ONNX 量化版）。
     * RAG 知识库检索使用：离线运行、无需 API Key、结果可复现，符合项目「一次启动就能跑」的约定。
     */
    @Bean
    EmbeddingModel embeddingModel() {
        return new BgeSmallZhV15QuantizedEmbeddingModel();
    }

    @Bean
    OutputGuard outputGuard(AssistantSecurityProperties security) {
        return new OutputGuard(security.getMaxOutputLength());
    }

    @Bean
    ImageGuard imageGuard(AssistantProperties properties) {
        return new ImageGuard(properties.getVision().getMaxImages(),
                properties.getVision().getMaxBytesPerImage());
    }

    /**
     * 看图这一步。即使没有视觉模型也要装配——它的职责之一就是
     * 「在没有视觉模型时给出诚实的降级」，这个职责与模型无关，所以不该由 Bean 的存在与否决定。
     */
    @Bean
    VisionDescriber visionDescriber(@Qualifier("visionChatModel") Optional<ChatModel> visionModel,
                                    ObjectMapper json) {
        return new VisionDescriber(visionModel, json);
    }

    @Bean
    @ConditionalOnProperty(prefix = "mall.assistant", name = "enabled", havingValue = "true")
    ChatModel chatModel(AssistantProperties properties) {
        return switch (properties.getProvider().toLowerCase()) {
            case "deepseek" -> buildDeepSeek(properties);
            default -> buildZhipu(properties);
        };
    }

    /**
     * 流式客户端，只为「逐字输出」服务。
     *
     * <p>它与 {@link #chatModel} 指向同一个模型、同一份配置，参数（temperature / maxTokens /
     * 超时）刻意保持一致，否则「流式回答和整段回答不一样」会变成一个极难排查的灵异问题。
     *
     * <p>注意它不是必需的：没有它时 {@code AgentLoop} 退化成同步调用，一切照常工作。
     * 所以这里不需要额外的开关——装配失败（比如该 provider 没有流式实现）也不该让应用起不来。
     */
    @Bean
    @ConditionalOnProperty(prefix = "mall.assistant", name = "enabled", havingValue = "true")
    StreamingChatModel streamingChatModel(AssistantProperties properties) {
        return switch (properties.getProvider().toLowerCase()) {
            case "deepseek" -> buildStreamingDeepSeek(properties);
            default -> buildStreamingZhipu(properties);
        };
    }

    /**
     * 视觉模型的装配条件：助手总开关 ∧ 视觉开关 ∧ <b>密钥真的配了</b>。
     *
     * <p>前两个条件用 {@code @ConditionalOnExpression} 就够了，第三个必须自己写，
     * 因为三方库的做法是抛 {@code apiKey cannot be null or empty}——
     * 它一不会告诉你是哪一个配置项漏了，二会让<b>整个应用启动失败</b>。
     * 为了一个可选功能搭上商城整体的可用性，这个交换太亏了
     * （本项目在并发闸门上采取的是同一条原则：保护整体、牺牲个体）。
     *
     * <p>所以这里改成「密钥缺失 = 不装配这个 Bean」：{@code VisionDescriber} 拿到的
     * {@code Optional} 为空，自动走「看不了图」的降级路径，应用照常启动，
     * 只在日志里留一条说清怎么修的警告。
     */
    static class VisionAvailable implements Condition {

        @Override
        public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            Environment env = context.getEnvironment();
            if (!env.getProperty("mall.assistant.enabled", Boolean.class, false)) return false;
            if (!env.getProperty("mall.assistant.vision.enabled", Boolean.class, false)) return false;
            if (hasText(env.getProperty("mall.assistant.vision.api-key"))) return true;
            // 视觉密钥留空时回落到同 provider 的对话密钥，这里要跟着一起看。
            String provider = env.getProperty("mall.assistant.vision.provider", "zhipu");
            String fallbackKey = "deepseek".equalsIgnoreCase(provider)
                    ? env.getProperty("mall.assistant.deepseek.api-key")
                    : env.getProperty("mall.assistant.zhipu.api-key");
            if (hasText(fallbackKey)) return true;
            log.warn("已开启 mall.assistant.vision.enabled，但没有配置任何视觉模型密钥"
                    + "（mall.assistant.vision.api-key，或同 provider 的对话密钥）。"
                    + "以图搜款已降级为「如实告知看不了图」，应用其余功能不受影响。"
                    + "想启用请填入密钥后重启。");
            return false;
        }

        private static boolean hasText(String value) {
            return value != null && !value.isBlank();
        }
    }

    /**
     * 视觉模型，只为「以图搜款」的看图一步服务，与对话模型解耦。
     *
     * <p>用 {@code Optional<ChatModel>} 注入，没有这个 Bean 时
     * {@link dev.learning.mall.assistant.vision.VisionDescriber} 自动退化为
     * 「看不了图」的诚实提示——而不是启动失败，也不是拿对话模型凑数。
     *
     * <p>参数与对话模型刻意<b>不同</b>：识别任务要的是稳定复现而不是文采，
     * 所以温度压到 0.2、只给 512 token、只重试 1 次。让视觉模型写 1024 字的抒情散文毫无意义。
     */
    @Bean("visionChatModel")
    @Conditional(VisionAvailable.class)
    ChatModel visionChatModel(AssistantProperties properties) {
        return switch (properties.getVision().getProvider().toLowerCase()) {
            case "deepseek" -> buildVisionDeepSeek(properties);
            default -> buildVisionZhipu(properties);
        };
    }

    private ChatModel buildVisionZhipu(AssistantProperties properties) {
        AssistantProperties.Vision vision = properties.getVision();
        return ZhipuAiChatModel.builder()
                .apiKey(firstNonBlank(vision.getApiKey(), properties.getZhipu().getApiKey()))
                .baseUrl(firstNonBlank(vision.getBaseUrl(), properties.getZhipu().getBaseUrl()))
                .model(vision.getModel())
                .temperature(0.2)
                .maxToken(512)
                .maxRetries(1)
                .callTimeout(Duration.ofMillis(vision.getTimeoutMs()))
                .connectTimeout(Duration.ofSeconds(15))
                .readTimeout(Duration.ofMillis(vision.getTimeoutMs()))
                .build();
    }

    private ChatModel buildVisionDeepSeek(AssistantProperties properties) {
        AssistantProperties.Vision vision = properties.getVision();
        return OpenAiChatModel.builder()
                .apiKey(firstNonBlank(vision.getApiKey(), properties.getDeepseek().getApiKey()))
                .baseUrl(firstNonBlank(vision.getBaseUrl(), properties.getDeepseek().getBaseUrl()))
                .modelName(vision.getModel())
                .temperature(0.2)
                .maxTokens(512)
                .maxRetries(1)
                .timeout(Duration.ofMillis(vision.getTimeoutMs()))
                .build();
    }

    /** 视觉密钥/地址留空时回落到对话模型的同款配置——少一处要填的字段，就少一处填错。 */
    private static String firstNonBlank(String preferred, String fallback) {
        if (preferred != null && !preferred.isBlank()) {
            return preferred;
        }
        return fallback;
    }

    private ChatModel buildZhipu(AssistantProperties properties) {
        return ZhipuAiChatModel.builder()
                .apiKey(properties.getZhipu().getApiKey())
                .baseUrl(properties.getZhipu().getBaseUrl())
                .model(properties.getModel())
                .temperature(0.6)
                .maxToken(1024)
                .maxRetries(2)
                .callTimeout(Duration.ofSeconds(60))
                .connectTimeout(Duration.ofSeconds(15))
                .readTimeout(Duration.ofSeconds(60))
                .build();
    }

    private ChatModel buildDeepSeek(AssistantProperties properties) {
        return OpenAiChatModel.builder()
                .apiKey(properties.getDeepseek().getApiKey())
                .baseUrl(properties.getDeepseek().getBaseUrl())
                .modelName(properties.getModel())
                .temperature(0.6)
                .maxTokens(1024)
                .maxRetries(2)
                .timeout(Duration.ofSeconds(60))
                .build();
    }

    private StreamingChatModel buildStreamingZhipu(AssistantProperties properties) {
        return ZhipuAiStreamingChatModel.builder()
                .apiKey(properties.getZhipu().getApiKey())
                .baseUrl(properties.getZhipu().getBaseUrl())
                .model(properties.getModel())
                .temperature(0.6)
                .maxToken(1024)
                .callTimeout(Duration.ofSeconds(60))
                .connectTimeout(Duration.ofSeconds(15))
                .readTimeout(Duration.ofSeconds(60))
                .build();
    }

    private StreamingChatModel buildStreamingDeepSeek(AssistantProperties properties) {
        return OpenAiStreamingChatModel.builder()
                .apiKey(properties.getDeepseek().getApiKey())
                .baseUrl(properties.getDeepseek().getBaseUrl())
                .modelName(properties.getModel())
                .temperature(0.6)
                .maxTokens(1024)
                .timeout(Duration.ofSeconds(60))
                .build();
    }
}
