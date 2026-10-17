package dev.learning.mall;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;

import dev.learning.mall.assistant.knowledge.KnowledgeBase;
import dev.learning.mall.assistant.knowledge.KnowledgeProperties;
import dev.learning.mall.assistant.knowledge.KnowledgeSegment;
import dev.learning.mall.assistant.knowledge.KnowledgeService;
import dev.learning.mall.assistant.security.AssistantActor;
import dev.learning.mall.assistant.security.AssistantAudit;
import dev.learning.mall.assistant.security.InputGuard;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.embedding.onnx.bgesmallzhv15q.BgeSmallZhV15QuantizedEmbeddingModel;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * RAG 检索防护测试。使用真实的 BGE-small-zh ONNX 嵌入（离线、结果可复现），
 * 验证三道防线：检索层租户隔离、后置租户过滤、检索内容过输入守卫。
 */
class KnowledgeServiceTest {

    private static final AssistantActor CUSTOMER =
            new AssistantActor("student", Set.of(AssistantActor.ROLE_USER));
    private static final AssistantActor STAFF =
            new AssistantActor("admin", Set.of(AssistantActor.ROLE_USER, AssistantActor.ROLE_STAFF));

    private static KnowledgeService service;
    private static AssistantAudit audit;

    @BeforeAll
    static void setUp() {
        EmbeddingModel model = new BgeSmallZhV15QuantizedEmbeddingModel();
        audit = mock(AssistantAudit.class);
        service = new KnowledgeService(new KnowledgeBase(), model,
                new InputGuard(1000, true), audit, new KnowledgeProperties());
    }

    @BeforeEach
    void resetAudit() {
        reset(audit);
    }

    @Test
    @DisplayName("公开政策可被顾客检索：退货期限命中《退换货政策》")
    void publicPolicyIsRetrievable() {
        List<KnowledgeService.KnowledgeHit> hits = service.search("退货要在签收后几天内申请", CUSTOMER, "student");
        hits.forEach(h -> System.out.printf("[score=%.3f] %s | %s%n", h.score(), h.title(),
                h.snippet().substring(0, Math.min(30, h.snippet().length()))));
        assertThat(hits).isNotEmpty();
        assertThat(hits.get(0).title()).contains("退换货");
        assertThat(hits.get(0).snippet()).contains("7 天");
    }

    @Test
    @DisplayName("租户隔离：顾客检索供应商信息，结果绝不含内部文档")
    void customerCannotReachStaffDocs() {
        List<KnowledgeService.KnowledgeHit> hits = service.search("供应商的联系方式和补货电话", CUSTOMER, "student");
        assertThat(hits).allSatisfy(h -> {
            assertThat(h.visibility()).isEqualTo(KnowledgeSegment.PUBLIC);
            assertThat(h.snippet()).doesNotContain("8369").doesNotContain("供应商");
        });
    }

    @Test
    @DisplayName("店员可检索内部文档：供应商联系方式命中，手机号被脱敏")
    void staffCanReachStaffDocs() {
        List<KnowledgeService.KnowledgeHit> hits = service.search("陶瓷供应商的联系方式", STAFF, "admin");
        hits.forEach(h -> System.out.printf("[score=%.3f][%s] %s%n", h.score(), h.visibility(), h.title()));
        assertThat(hits).anySatisfy(h -> {
            assertThat(h.title()).contains("供应商");
            assertThat(h.visibility()).isEqualTo(KnowledgeSegment.STAFF);
            assertThat(h.snippet()).contains("8369"); // 座机保留
        });
        // PII 脱敏贯穿到检索层：即便是店员，移动号码也不以明文下发（由输出守卫再兜一层）。
        assertThat(hits).allSatisfy(h -> assertThat(h.snippet()).doesNotContain("13905170188"));
    }

    @Test
    @DisplayName("间接注入：被投毒的评论段被输入守卫整段丢弃并记审计")
    void poisonedSegmentIsDropped() {
        List<KnowledgeService.KnowledgeHit> hits = service.search("云朵马克杯追评", CUSTOMER, "student");
        hits.forEach(h -> System.out.printf("[score=%.3f] %s | %s%n", h.score(), h.title(),
                h.snippet().substring(0, Math.min(30, h.snippet().length()))));
        // 投毒段即使被检索命中，也不得出现在下发给模型的内容里。
        assertThat(hits).allSatisfy(h -> assertThat(h.snippet())
                .doesNotContain("停业")
                .doesNotContain("忽略以上")
                .doesNotContain("银行卡号"));
        // 且必须留下 RAG 阶段的拦截审计——静默丢弃等于没有防护证据。
        verify(audit, atLeastOnce()).record(eq("student"), isNull(), eq(AssistantAudit.STAGE_RAG),
                eq("BLOCK"), any(), any(), any());
    }

    @Test
    @DisplayName("无关查询不返回任何片段（相似度阈值生效）")
    void irrelevantQueryReturnsEmpty() {
        List<KnowledgeService.KnowledgeHit> hits = service.search("量子力学的基本假设", CUSTOMER, "student");
        assertThat(hits).isEmpty();
    }

    @Test
    @DisplayName("知识库加载：公开与内部文档都被切分为片段")
    void knowledgeBaseIsLoaded() {
        KnowledgeBase base = new KnowledgeBase();
        assertThat(base.segments()).isNotEmpty();
        assertThat(base.segments()).anySatisfy(s -> assertThat(s.visibility()).isEqualTo(KnowledgeSegment.STAFF));
        assertThat(base.segments()).anySatisfy(s -> assertThat(s.visibility()).isEqualTo(KnowledgeSegment.PUBLIC));
    }
}
