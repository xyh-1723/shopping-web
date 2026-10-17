package dev.learning.mall.assistant.knowledge;

/**
 * 知识库中的一个检索片段。visibility 是多租户隔离的关键字段：
 * 检索和后置过滤都以它为准，不依赖「文档放在哪个目录」这种物理约定。
 */
public record KnowledgeSegment(String title, String text, String visibility) {

    public static final String PUBLIC = "PUBLIC";
    public static final String STAFF = "STAFF";
}
