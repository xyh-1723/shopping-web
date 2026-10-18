package dev.learning.mall.assistant.web;

import dev.learning.mall.assistant.internal.ToolRegistry;
import dev.learning.mall.assistant.security.AssistantActor;
import dev.langchain4j.agent.tool.ToolSpecification;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 进度文案的完整性守卫。
 *
 * <p>流式输出的价值全在「用户看得懂现在在干什么」。工具名到文案的映射放在服务端，
 * 好处是新增工具前端零改动；代价是<b>没人提醒你补那一行</b>——
 * 忘了补不会报错、不会失败任何现有测试，只会在界面上显示一串
 * {@code recommendRelated} 这样的裸工具名，而且往往到演示时才被看见。
 *
 * <p>所以这里反过来查一遍：把注册表里<b>所有</b>对用户可见的工具拿出来，
 * 逐个要求它有中文文案。新增工具而忘记补文案时，这个测试会直接失败并告诉你少了哪个。
 */
@SpringBootTest
@ActiveProfiles("test")
class ToolProgressLabelTest {

    @Autowired
    private ToolRegistry registry;

    @Test
    @DisplayName("每个对用户可见的工具都有中文进度文案，不会退化成一串裸工具名")
    void everyUserVisibleToolHasAChineseLabel() {
        List<String> missing = new ArrayList<>();
        // MERCHANT 必须也在里面：它有自己的专属工具（退款审批），
        // 只遍历 USER / STAFF 会让那几个工具漏掉中文文案而无人发现。
        for (AssistantActor actor : List.of(
                new AssistantActor("label-user", Set.of(AssistantActor.ROLE_USER)),
                new AssistantActor("label-staff", Set.of(AssistantActor.ROLE_USER, AssistantActor.ROLE_STAFF)),
                new AssistantActor("label-merchant",
                        Set.of(AssistantActor.ROLE_USER, AssistantActor.ROLE_MERCHANT)))) {
            for (ToolSpecification spec : registry.forActor(actor).specs()) {
                if (!TurnStreamSink.hasLabel(spec.name())) {
                    missing.add(spec.name());
                }
            }
        }
        assertThat(missing)
                .as("这些工具在 TurnStreamSink.TOOL_LABELS 里没有中文文案，进度条会显示裸工具名")
                .isEmpty();
    }

    @Test
    @DisplayName("店员工具对普通用户不可见（文案测试顺带确认注册表本身的角色隔离）")
    void staffToolsAreNotVisibleToRegularUsers() {
        Set<String> userTools = new TreeSet<>();
        registry.forActor(new AssistantActor("label-user", Set.of(AssistantActor.ROLE_USER)))
                .specs().forEach(s -> userTools.add(s.name()));
        Set<String> staffTools = new TreeSet<>();
        registry.forActor(new AssistantActor("label-staff",
                Set.of(AssistantActor.ROLE_USER, AssistantActor.ROLE_STAFF)))
                .specs().forEach(s -> staffTools.add(s.name()));

        assertThat(userTools).doesNotContain("inventoryAlerts", "salesOverview", "customerOrders");
        assertThat(staffTools).containsAll(userTools).contains("inventoryAlerts");

        // 退款的两档权限：队列是经营侧<b>只读</b>（店员 + 商家都能看），审批只属于商家。
        // 与 HTTP 同口径：GET /api/refunds/pending → hasAnyRole("STAFF","MERCHANT")，
        // POST .../approve|reject → hasRole("MERCHANT")。
        Set<String> merchantTools = new TreeSet<>();
        registry.forActor(new AssistantActor("label-merchant",
                Set.of(AssistantActor.ROLE_USER, AssistantActor.ROLE_MERCHANT)))
                .specs().forEach(s -> merchantTools.add(s.name()));
        assertThat(userTools).doesNotContain("pendingRefundQueue", "approveRefund", "rejectRefund");
        assertThat(staffTools)
                .as("店员看得到待审批队列（经营侧只读）").contains("pendingRefundQueue")
                .as("审批仍然只属于商家：看得见不等于批得了").doesNotContain("approveRefund", "rejectRefund");
        assertThat(merchantTools).contains("pendingRefundQueue", "approveRefund", "rejectRefund");
    }
}
