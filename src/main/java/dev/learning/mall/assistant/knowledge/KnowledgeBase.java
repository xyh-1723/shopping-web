package dev.learning.mall.assistant.knowledge;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

/**
 * 从 classpath 加载知识库 Markdown 文档并切分为检索片段。
 *
 * <p>目录约定即租户边界：
 * <pre>
 *   assistant-knowledge/public/*.md  → 公开帮助文档（所有角色可检索）
 *   assistant-knowledge/staff/*.md   → 内部运营文档（仅店员可检索）
 * </pre>
 * 切分策略：首个「# 」行为标题，其余按空行分段；过长的段按句号再拆，过短的并入下一段，
 * 使每个片段在语义上自洽且大小适中（约 80-400 字）。
 */
@Component
public class KnowledgeBase {

    private static final int MIN_SEGMENT = 40;
    private static final int MAX_SEGMENT = 420;

    private final List<KnowledgeSegment> segments;

    public KnowledgeBase() {
        this.segments = List.copyOf(load());
    }

    public List<KnowledgeSegment> segments() {
        return segments;
    }

    private List<KnowledgeSegment> load() {
        List<KnowledgeSegment> all = new ArrayList<>();
        all.addAll(loadDir("assistant-knowledge/public", KnowledgeSegment.PUBLIC));
        all.addAll(loadDir("assistant-knowledge/staff", KnowledgeSegment.STAFF));
        return all;
    }

    private List<KnowledgeSegment> loadDir(String dir, String visibility) {
        PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
        List<KnowledgeSegment> out = new ArrayList<>();
        try {
            Resource[] resources = resolver.getResources("classpath*:" + dir + "/*.md");
            for (Resource r : resources) {
                String content = new String(r.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                out.addAll(split(r.getFilename(), content, visibility));
            }
        } catch (IOException e) {
            throw new UncheckedIOException("加载知识库目录失败: " + dir, e);
        }
        return out;
    }

    private List<KnowledgeSegment> split(String file, String content, String visibility) {
        String normalized = content.replace("\r\n", "\n").strip();
        String[] lines = normalized.split("\n", 2);
        String title = lines[0].startsWith("# ") ? lines[0].substring(2).strip() : file;
        String body = lines.length > 1 ? lines[1] : "";
        List<KnowledgeSegment> out = new ArrayList<>();
        StringBuilder pending = new StringBuilder();
        for (String block : body.split("\n\\s*\n")) {
            String paragraph = block.strip();
            if (paragraph.isEmpty()) continue;
            if (pending.length() > 0) pending.append("\n");
            pending.append(paragraph);
            if (pending.length() >= MIN_SEGMENT) {
                flush(out, title, pending.toString(), visibility);
                pending.setLength(0);
            }
        }
        if (pending.length() > 0) flush(out, title, pending.toString(), visibility);
        return out;
    }

    private void flush(List<KnowledgeSegment> out, String title, String text, String visibility) {
        // 过长段按中文句读再拆，避免单个片段过长稀释检索精度。
        if (text.length() <= MAX_SEGMENT) {
            out.add(new KnowledgeSegment(title, text, visibility));
            return;
        }
        StringBuilder current = new StringBuilder();
        for (String sentence : text.split("(?<=[。！？；])")) {
            if (current.length() + sentence.length() > MAX_SEGMENT && current.length() >= MIN_SEGMENT) {
                out.add(new KnowledgeSegment(title, current.toString(), visibility));
                current.setLength(0);
            }
            current.append(sentence);
        }
        if (current.length() > 0) out.add(new KnowledgeSegment(title, current.toString(), visibility));
    }
}
