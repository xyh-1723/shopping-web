package dev.learning.mall.assistant.knowledge;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** RAG 知识库配置。 */
@ConfigurationProperties(prefix = "mall.assistant.knowledge")
public class KnowledgeProperties {

    /** 是否启用知识库检索（嵌入模型惰性加载，关闭时不占用内存）。 */
    private boolean enabled = true;

    /** 单次检索返回的最大片段数。 */
    private int maxResults = 3;

    /**
     * 余弦相似度下限，低于该值的片段视为不相关。
     * 按本知识库实测分布校准：相关查询 0.74-0.89，无关查询 0.65-0.69（BGE 中文基线相似度偏高），
     * 阈值取中间空档 0.71。局限：纯阈值法对边界查询敏感，生产环境应加交叉编码器重排。
     */
    private double minScore = 0.71;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public int getMaxResults() { return maxResults; }
    public void setMaxResults(int maxResults) { this.maxResults = maxResults; }
    public double getMinScore() { return minScore; }
    public void setMinScore(double minScore) { this.minScore = minScore; }
}
