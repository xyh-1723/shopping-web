package dev.learning.mall.assistant.internal;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 购物助手配置。未提供 API Key 时 enabled 保持 false，助手页以演示模式运行，
 * 不调用外部大模型。provider 支持 zhipu（智谱 GLM）与 deepseek（DeepSeek，OpenAI 兼容）。
 */
@ConfigurationProperties("mall.assistant")
public class AssistantProperties {
    private boolean enabled = false;
    private String provider = "zhipu";
    private Zhipu zhipu = new Zhipu();
    private Deepseek deepseek = new Deepseek();
    private String model = "glm-4-flash";
    private int memoryWindow = 20;
    private Harness harness = new Harness();
    private Concurrency concurrency = new Concurrency();
    private Memory memory = new Memory();
    private Vision vision = new Vision();

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }
    public Zhipu getZhipu() { return zhipu; }
    public void setZhipu(Zhipu zhipu) { this.zhipu = zhipu; }
    public Deepseek getDeepseek() { return deepseek; }
    public void setDeepseek(Deepseek deepseek) { this.deepseek = deepseek; }
    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }
    public int getMemoryWindow() { return memoryWindow; }
    public void setMemoryWindow(int memoryWindow) { this.memoryWindow = memoryWindow; }
    public Harness getHarness() { return harness; }
    public void setHarness(Harness harness) { this.harness = harness; }
    public Concurrency getConcurrency() { return concurrency; }
    public void setConcurrency(Concurrency concurrency) { this.concurrency = concurrency; }
    public Memory getMemory() { return memory; }
    public void setMemory(Memory memory) { this.memory = memory; }
    public Vision getVision() { return vision; }
    public void setVision(Vision vision) { this.vision = vision; }

    /**
     * 上下文工程（context engineering）相关配置。
     *
     * <p>「窗口」和「摘要」解决的是两个不同问题：窗口限制的是每轮发给模型多少条消息（成本），
     * 摘要解决的是被窗口裁掉的那部分怎么不丢（质量）。只做前者，长对话聊到第十轮
     * 模型就会忘记用户开头说的「预算 300 以内」——那句话还在数据库里，但已经不在上下文里了。
     */
    public static class Memory {
        /** 是否启用长对话滚动摘要。 */
        private boolean summaryEnabled = true;
        /** 新消息累积多少条后刷新一次摘要（不是每轮都刷新，省一次模型调用）。 */
        private int summaryRefreshEvery = 10;
        /** 单次最多把多少条消息喂给模型做增量摘要，避免摘要请求本身变的很大。 */
        private int summaryChunkSize = 40;
        /** 摘要长度上限（字符）。摘要是要长期占据上下文的，必须比原文短得多才有意义。 */
        private int maxSummaryChars = 400;

        // ——— 轮后自动抽取偏好 ———
        // 只依赖模型「主动想起来调工具」写记忆，实际效果是「大部分时候没记住」。
        // 自动抽取把这件事从模型的自觉变回 harness 的职责。
        /** 是否在轮次结束后自动抽取偏好（规则路径，零额外成本）。 */
        private boolean autoExtract = true;
        /** 是否额外调用一次模型做语义抽取。有冷却时间，但仍是真实花费，可按需关闭。 */
        private boolean autoExtractModel = true;
        /** 同一用户两次模型抽取之间的最小间隔（秒），用来给额外调用封顶。 */
        private int autoExtractCooldownSeconds = 60;

        public boolean isAutoExtract() { return autoExtract; }
        public void setAutoExtract(boolean autoExtract) { this.autoExtract = autoExtract; }
        public boolean isAutoExtractModel() { return autoExtractModel; }
        public void setAutoExtractModel(boolean autoExtractModel) { this.autoExtractModel = autoExtractModel; }
        public int getAutoExtractCooldownSeconds() { return autoExtractCooldownSeconds; }
        public void setAutoExtractCooldownSeconds(int autoExtractCooldownSeconds) {
            this.autoExtractCooldownSeconds = autoExtractCooldownSeconds;
        }

        public boolean isSummaryEnabled() { return summaryEnabled; }
        public void setSummaryEnabled(boolean summaryEnabled) { this.summaryEnabled = summaryEnabled; }
        public int getSummaryRefreshEvery() { return summaryRefreshEvery; }
        public void setSummaryRefreshEvery(int summaryRefreshEvery) { this.summaryRefreshEvery = summaryRefreshEvery; }
        public int getSummaryChunkSize() { return summaryChunkSize; }
        public void setSummaryChunkSize(int summaryChunkSize) { this.summaryChunkSize = summaryChunkSize; }
        public int getMaxSummaryChars() { return maxSummaryChars; }
        public void setMaxSummaryChars(int maxSummaryChars) { this.maxSummaryChars = maxSummaryChars; }
    }

    /**
     * 视觉模型（以图搜款的「看图」那一步）的独立配置。
     *
     * <p><b>为什么跟对话模型分开配：</b>能看图和支持工具调用是两件不同的事。当前对话模型
     * 用 DeepSeek 的 {@code deepseek-chat}（快、便宜、工具调用稳），但它<b>不支持图像输入</b>；
     * 而支持视觉的模型通常更贵、更慢。把两者解耦，才能做到「只在用户真的发了图时，才付一次
     * 视觉调用的钱」——否则每条消息都要为用不到的视觉能力埋单。
     *
     * <p><b>默认关闭是有意的：</b>没配视觉模型时，收到图片会明确告诉用户「我看不了图」，
     * 而不是把图片丢给一个看不懂它的模型、再让模型编一段描述出来。后者叫幻觉，不叫功能。
     */
    public static class Vision {
        /** 是否启用看图能力。关闭时收到图片会给出明确的降级提示。 */
        private boolean enabled = false;
        /** 视觉模型提供商：zhipu（智谱 GLM-4V）或 deepseek（OpenAI 兼容端点）。 */
        private String provider = "zhipu";
        /** 视觉模型名。智谱的 glm-4v-flash 是免费档，适合教学与本地验证。 */
        private String model = "glm-4v-flash";
        /** 视觉服务密钥。留空时回落到对应 provider 的对话密钥。 */
        private String apiKey = "";
        /** 视觉服务地址。留空时用 provider 的默认地址。 */
        private String baseUrl = "";
        /** 单条消息最多几张图。图片按 token 计费且不可压缩，必须封顶。 */
        private int maxImages = 3;
        /** 单张图片解码后的字节上限（默认 4MB）。 */
        private int maxBytesPerImage = 4 * 1024 * 1024;
        /** 看图这一步的超时（毫秒）。它是整轮预算之外的一次额外调用，必须单独设上限。 */
        private long timeoutMs = 30_000;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public String getProvider() { return provider; }
        public void setProvider(String provider) { this.provider = provider; }
        public String getModel() { return model; }
        public void setModel(String model) { this.model = model; }
        public String getApiKey() { return apiKey; }
        public void setApiKey(String apiKey) { this.apiKey = apiKey; }
        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
        public int getMaxImages() { return maxImages; }
        public void setMaxImages(int maxImages) { this.maxImages = maxImages; }
        public int getMaxBytesPerImage() { return maxBytesPerImage; }
        public void setMaxBytesPerImage(int maxBytesPerImage) { this.maxBytesPerImage = maxBytesPerImage; }
        public long getTimeoutMs() { return timeoutMs; }
        public void setTimeoutMs(long timeoutMs) { this.timeoutMs = timeoutMs; }
    }

    /**
     * Agent 执行循环（harness）的预算与开关。
     *
     * <p>为什么这些值要可配置而不是写死在代码里：循环的步数、超时、工具结果大小上限
     * 是<b>运营参数</b>——换模型、换成本档位、换场景都要调。写死的预算最后一定会被改成
     * 「反正先设大一点」，而预算一旦形同虚设，agent 就会空转到烧完额度。
     */
    public static class Harness {
        /** 最多几轮「模型 → 工具」。多意图请求通常 2-3 轮，6 轮已能覆盖绝大多数场景。 */
        private int maxSteps = 6;
        /** 整轮墙钟上限（毫秒）。到点停止并给诚实兜底答复，不让用户干等。 */
        private long timeoutMs = 30_000;
        /** 单个工具结果进模型前的字符上限。超长结果只截断给模型的副本，原始结果仍用于卡片渲染。 */
        private int maxToolResultChars = 4000;
        /** 同一工具 + 同一参数允许重复出现的次数，超过即视为原地打转。 */
        private int maxSameToolRepeat = 2;
        /** 并行执行只读工具的工作线程数。 */
        private int maxParallelTools = 4;
        /** 是否把每次运行的轨迹落库（停止原因、每步工具与耗时），便于复盘「这次回答怎么来的」。 */
        private boolean traceEnabled = true;

        public int getMaxSteps() { return maxSteps; }
        public void setMaxSteps(int maxSteps) { this.maxSteps = maxSteps; }
        public long getTimeoutMs() { return timeoutMs; }
        public void setTimeoutMs(long timeoutMs) { this.timeoutMs = timeoutMs; }
        public int getMaxToolResultChars() { return maxToolResultChars; }
        public void setMaxToolResultChars(int maxToolResultChars) { this.maxToolResultChars = maxToolResultChars; }
        public int getMaxSameToolRepeat() { return maxSameToolRepeat; }
        public void setMaxSameToolRepeat(int maxSameToolRepeat) { this.maxSameToolRepeat = maxSameToolRepeat; }
        public int getMaxParallelTools() { return maxParallelTools; }
        public void setMaxParallelTools(int maxParallelTools) { this.maxParallelTools = maxParallelTools; }
        public boolean isTraceEnabled() { return traceEnabled; }
        public void setTraceEnabled(boolean traceEnabled) { this.traceEnabled = traceEnabled; }

        /** 把配置转成执行循环的预算对象。循环只认预算，不认配置类，便于单测里直接构造小预算。 */
        public dev.learning.mall.assistant.harness.AgentLoop.Budget budget() {
            return new dev.learning.mall.assistant.harness.AgentLoop.Budget(
                    maxSteps, timeoutMs, maxToolResultChars, maxSameToolRepeat);
        }
    }

    /**
     * 多用户并发下的隔离与异步配置。
     *
     * <p>这里的数字都在回答同一个问题：<b>当很多人同时跟助手说话时，谁先被牺牲？</b>
     * 默认策略是「保护整体、牺牲个体」——宁可让个别请求快速失败并给出可重试的提示，
     * 也不让所有请求一起排队、一起超时，最后谁都拿不到答案。
     *
     * <p>两个约束要一起看：{@code max-in-flight-global} 决定「同时最多处理多少条」，
     * 而线程池的 {@code max-pool-size} 决定「同时最多有多少执行线程」。
     * 前者必须 ≤ 后者，否则任务会在队列里干等，把响应时间拖成超时。
     */
    public static class Concurrency {
        /** 助手专用线程池的核心线程数。低于此数不排队，直接起线程。 */
        private int corePoolSize = 4;
        /** 线程池最大线程数，与「全局在途上限」对齐：入池的每条请求占一个线程直到答完。 */
        private int maxPoolSize = 16;
        /** 队列容量。正常情况下用不到（在途上限先拦住），是第二道保险丝。 */
        private int queueCapacity = 64;
        /** 全局同时在途轮次上限。超过返回 503，提示稍后再试。 */
        private int maxInFlightGlobal = 16;
        /** 单个用户同时在途轮次上限。超过返回 429——防止一个人开多个会话占满全局额度。 */
        private int maxInFlightPerUser = 3;
        /** 同一会话是否串行。开启后并发发消息的第二条返回 409，避免历史顺序错乱。 */
        private boolean serializePerConversation = true;

        public int getCorePoolSize() { return corePoolSize; }
        public void setCorePoolSize(int corePoolSize) { this.corePoolSize = corePoolSize; }
        public int getMaxPoolSize() { return maxPoolSize; }
        public void setMaxPoolSize(int maxPoolSize) { this.maxPoolSize = maxPoolSize; }
        public int getQueueCapacity() { return queueCapacity; }
        public void setQueueCapacity(int queueCapacity) { this.queueCapacity = queueCapacity; }
        public int getMaxInFlightGlobal() { return maxInFlightGlobal; }
        public void setMaxInFlightGlobal(int maxInFlightGlobal) { this.maxInFlightGlobal = maxInFlightGlobal; }
        public int getMaxInFlightPerUser() { return maxInFlightPerUser; }
        public void setMaxInFlightPerUser(int maxInFlightPerUser) { this.maxInFlightPerUser = maxInFlightPerUser; }
        public boolean isSerializePerConversation() { return serializePerConversation; }
        public void setSerializePerConversation(boolean serializePerConversation) {
            this.serializePerConversation = serializePerConversation;
        }
    }

    public static class Zhipu {
        private String apiKey = "";
        private String baseUrl = "https://open.bigmodel.cn/api/paas/v4";
        public String getApiKey() { return apiKey; }
        public void setApiKey(String apiKey) { this.apiKey = apiKey; }
        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
    }

    public static class Deepseek {
        private String apiKey = "";
        private String baseUrl = "https://api.deepseek.com/v1";
        public String getApiKey() { return apiKey; }
        public void setApiKey(String apiKey) { this.apiKey = apiKey; }
        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
    }
}
