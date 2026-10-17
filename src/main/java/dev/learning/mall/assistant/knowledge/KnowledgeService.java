package dev.learning.mall.assistant.knowledge;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;
import dev.learning.mall.assistant.security.AssistantActor;
import dev.learning.mall.assistant.security.AssistantAudit;
import dev.learning.mall.assistant.security.InputGuard;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * RAG 检索服务。这是 JD 中「RAG 与检索防护」的落地点，三道防线缺一不可：
 *
 * <ol>
 *   <li><b>检索层租户隔离</b>：公开文档与内部文档分别进入两个向量库，
 *       普通顾客的查询物理上不会触碰内部库（多租户数据权限控制）。
 *   <li><b>后置租户过滤</b>（纵深防御）：命中结果再按角色过滤一次——
 *       即便上面的库选择逻辑出 bug，STAFF 片段也到不了顾客手里，且留审计。
 *   <li><b>检索内容过输入守卫</b>：知识库是不可信内容源（评论可以被投毒），
 *       每个片段进上下文前先过 InputGuard——命中拦截规则整段丢弃并记审计，
 *       命中清洗规则使用清洗后文本。RAG 是间接注入的头号通道，这一步是核心防护。
 * </ol>
 *
 * 嵌入模型为进程内 BGE-small-zh-v1.5（ONNX 量化版），离线运行、无需 API Key，
 * 首次检索时惰性初始化（约 1-2 秒）。
 */
@Service
public class KnowledgeService {

    /** 一次命中：标题、（已清洗的）片段正文、可见性、相似度。 */
    public record KnowledgeHit(String title, String snippet, String visibility, double score) {}

    private static final Logger log = LoggerFactory.getLogger(KnowledgeService.class);

    private final KnowledgeBase base;
    private final EmbeddingModel embeddingModel;
    private final InputGuard inputGuard;
    private final AssistantAudit audit;
    private final KnowledgeProperties props;

    private volatile boolean ready;
    private InMemoryEmbeddingStore<TextSegment> publicStore;
    private InMemoryEmbeddingStore<TextSegment> staffStore;

    public KnowledgeService(KnowledgeBase base, EmbeddingModel embeddingModel, InputGuard inputGuard,
                            AssistantAudit audit, KnowledgeProperties props) {
        this.base = base;
        this.embeddingModel = embeddingModel;
        this.inputGuard = inputGuard;
        this.audit = audit;
        this.props = props;
    }

    /** 按角色检索知识库。username 用于审计留痕。 */
    public List<KnowledgeHit> search(String query, AssistantActor actor, String username) {
        if (!props.isEnabled() || query == null || query.isBlank()) return List.of();
        ensureReady();
        Embedding queryEmbedding = embeddingModel.embed(query.strip()).content();

        // —— 第 1 道：检索层租户隔离。顾客只查公开库，经营侧（店员 / 商家）两个库都查。 ——
        List<EmbeddingMatch<TextSegment>> matches = new ArrayList<>(
                publicStore.search(request(queryEmbedding)).matches());
        if (actor.isOperatingSide()) {
            matches.addAll(staffStore.search(request(queryEmbedding)).matches());
        }

        List<KnowledgeHit> hits = new ArrayList<>();
        for (EmbeddingMatch<TextSegment> match : matches) {
            String visibility = match.embedded().metadata().getString("visibility");
            // —— 第 2 道：后置租户过滤。库选错了也漏不出去，且留下痕迹。 ——
            if (!actor.isOperatingSide() && KnowledgeSegment.STAFF.equals(visibility)) {
                audit.record(username, null, AssistantAudit.STAGE_RAG, "DENY",
                        "RAG_TENANT_FILTER", "LLM02", match.embedded().text());
                continue;
            }
            // —— 第 3 道：检索内容是外部文档，先过输入守卫再进上下文。 ——
            String raw = match.embedded().text();
            InputGuard.Verdict verdict = inputGuard.inspect(raw);
            if (verdict.action() == InputGuard.Action.BLOCK) {
                audit.record(username, null, AssistantAudit.STAGE_RAG, "BLOCK",
                        verdict.ruleId(), verdict.owasp(), raw);
                log.info("知识库片段被输入守卫拦截: title={} rule={}",
                        match.embedded().metadata().getString("title"), verdict.ruleId());
                continue;
            }
            if (verdict.action() == InputGuard.Action.MASK) {
                audit.record(username, null, AssistantAudit.STAGE_RAG, "MASK",
                        verdict.ruleId(), verdict.owasp(), raw);
            }
            hits.add(new KnowledgeHit(
                    match.embedded().metadata().getString("title"),
                    verdict.sanitized(), visibility, match.score()));
        }
        hits.sort(Comparator.comparingDouble(KnowledgeHit::score).reversed());
        return hits.size() > props.getMaxResults()
                ? List.copyOf(hits.subList(0, props.getMaxResults()))
                : List.copyOf(hits);
    }

    /** 知识库规模，供状态展示与测试断言。 */
    public int segmentCount() {
        return base.segments().size();
    }

    private EmbeddingSearchRequest request(Embedding queryEmbedding) {
        return EmbeddingSearchRequest.builder()
                .queryEmbedding(queryEmbedding)
                .maxResults(props.getMaxResults())
                .minScore(props.getMinScore())
                .build();
    }

    /** 惰性初始化：嵌入全部片段并写入两个向量库（按可见性物理分库）。 */
    private synchronized void ensureReady() {
        if (ready) return;
        publicStore = new InMemoryEmbeddingStore<>();
        staffStore = new InMemoryEmbeddingStore<>();
        List<TextSegment> publicSegments = new ArrayList<>();
        List<TextSegment> staffSegments = new ArrayList<>();
        for (KnowledgeSegment seg : base.segments()) {
            Metadata metadata = new Metadata()
                    .put("title", seg.title())
                    .put("visibility", seg.visibility());
            // 标题并入嵌入文本，提升「政策名 ↔ 文档」的匹配率；正文保持原样用于展示。
            TextSegment segment = TextSegment.from(seg.text(), metadata);
            if (KnowledgeSegment.STAFF.equals(seg.visibility())) staffSegments.add(segment);
            else publicSegments.add(segment);
        }
        index(publicStore, publicSegments);
        index(staffStore, staffSegments);
        ready = true;
        log.info("知识库索引完成: 公开 {} 段, 内部 {} 段", publicSegments.size(), staffSegments.size());
    }

    private void index(InMemoryEmbeddingStore<TextSegment> store, List<TextSegment> segments) {
        if (segments.isEmpty()) return;
        Response<List<Embedding>> response = embeddingModel.embedAll(segments);
        List<Embedding> embeddings = response.content();
        for (int i = 0; i < segments.size(); i++) {
            store.add(embeddings.get(i), segments.get(i));
        }
    }
}
