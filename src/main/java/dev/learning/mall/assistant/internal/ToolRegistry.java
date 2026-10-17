package dev.learning.mall.assistant.internal;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.learning.mall.assistant.harness.Effectful;
import dev.learning.mall.assistant.memory.MemoryTools;
import dev.learning.mall.assistant.security.AssistantActor;
import dev.learning.mall.assistant.security.AssistantAudit;
import dev.learning.mall.common.BusinessException;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.agent.tool.ToolSpecifications;
import dev.langchain4j.service.tool.DefaultToolExecutor;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * 工具注册与路由。一次对话能用哪些工具，由调用者的角色决定（最小权限）。
 * 模型看不到、也就无法声明调用越权工具；即便它「幻觉」出一个工具名，
 * 这里也会拒绝执行并留痕——不存在的工具永远不会被执行。
 *
 * <p>这一层同时承担 harness 里「工具层」的两项职责：
 * <ul>
 *   <li><b>参数与执行都不过模型的手</b>：模型只提交调用意图，参数绑定、身份注入、执行都由这里完成；</li>
 *   <li><b>结构化失败</b>：工具抛异常对模型来说是「环境反馈」，应该变成它能读懂、能据此自纠的
 *       结构化错误，而不是让整个回合 500。一次失败的查询不该毁掉整次对话。</li>
 * </ul>
 */
@Component
public class ToolRegistry {

    /** 工具执行状态：OK 正常、DENIED 未授权、ERROR 执行失败。 */
    public static final String STATUS_OK = "OK";
    public static final String STATUS_DENIED = "DENIED";
    public static final String STATUS_ERROR = "ERROR";

    /** 工具执行结果：给模型的文本 + 机器可读状态（状态用于轨迹与断言，不靠解析文本猜）。 */
    public record Execution(String text, String status) {
        public boolean ok() { return STATUS_OK.equals(status); }
    }

    /**
     * 一次对话的工具包：给模型的声明 + 名字到实现对象的内部路由表 + 效果型工具名单。
     * 效果型名单由 {@link dev.learning.mall.assistant.harness.Effectful} 注解推导，
     * 执行循环据此决定哪些调用必须留在调用线程（见该注解的说明）。
     */
    public record Bundle(List<ToolSpecification> specs, Map<String, Object> routes, Set<String> effectful) {
        public boolean isEmpty() { return routes.isEmpty(); }
        public boolean isEffectful(String toolName) { return effectful.contains(toolName); }
    }

    private final CatalogTools catalogTools;
    private final CustomerTools customerTools;
    private final GuideTools guideTools;
    private final CartTools cartTools;
    private final WatchTools watchTools;
    private final ProactiveTools proactiveTools;
    private final MemoryTools memoryTools;
    private final StaffTools staffTools;
    private final KnowledgeTools knowledgeTools;
    private final PaymentTools paymentTools;
    private final OrderTools orderTools;
    private final AssistantAudit audit;
    private final ObjectMapper json;

    public ToolRegistry(CatalogTools catalogTools, CustomerTools customerTools, GuideTools guideTools,
                        CartTools cartTools, WatchTools watchTools, ProactiveTools proactiveTools,
                        MemoryTools memoryTools, StaffTools staffTools,
                        KnowledgeTools knowledgeTools, PaymentTools paymentTools, OrderTools orderTools,
                        AssistantAudit audit, ObjectMapper json) {
        this.catalogTools = catalogTools;
        this.customerTools = customerTools;
        this.guideTools = guideTools;
        this.cartTools = cartTools;
        this.watchTools = watchTools;
        this.proactiveTools = proactiveTools;
        this.memoryTools = memoryTools;
        this.staffTools = staffTools;
        this.knowledgeTools = knowledgeTools;
        this.paymentTools = paymentTools;
        this.orderTools = orderTools;
        this.audit = audit;
        this.json = json;
    }

    public Bundle forActor(AssistantActor actor) {
        List<Object> targets = new ArrayList<>();
        targets.add(catalogTools);   // 公开商品查询：所有登录用户
        targets.add(guideTools);     // 导购只读：看购物袋、比商品、发起澄清
        targets.add(proactiveTools); // 主动服务只读：我的提醒、关联推荐
        targets.add(customerTools);  // 本人数据：所有登录用户，作用域限本人
        targets.add(memoryTools);    // 长期记忆：所有登录用户，作用域限本人，键有白名单
        targets.add(cartTools);      // 购物袋写操作：改本人的袋子，低风险，不要求二次确认
        targets.add(watchTools);     // 关注写操作：盯价/盯货，同样低风险、可撤销
        targets.add(knowledgeTools); // 知识库检索：所有登录用户；数据范围由工具内部按角色过滤（多租户 RAG）
        targets.add(paymentTools);   // 资金写操作：只「发起」待确认支付，真正执行由用户本人确认（human-in-the-loop）
        targets.add(orderTools);     // 订单写操作：只「发起」下单/取消确认，落单与取消同样由用户本人确认
        if (actor.isStaff()) targets.add(staffTools); // 店员才看得见店员工具
        Map<String, Object> routes = new LinkedHashMap<>();
        List<ToolSpecification> specs = new ArrayList<>();
        Set<String> effectful = new LinkedHashSet<>();
        for (Object target : targets) {
            // 效果型判定看的是「实现类」而不是「工具方法」：一个类里的工具共享同一份状态与
            // 线程上下文假设，按类标注不会有漏网的方法。
            boolean writes = target.getClass().isAnnotationPresent(Effectful.class);
            for (ToolSpecification spec : ToolSpecifications.toolSpecificationsFrom(target)) {
                routes.put(spec.name(), target);
                specs.add(spec);
                if (writes) effectful.add(spec.name());
            }
        }
        return new Bundle(List.copyOf(specs), Map.copyOf(routes), Set.copyOf(effectful));
    }

    /** 执行模型请求的工具调用；用户名由框架以 memoryId 注入，模型改不了。 */
    public Execution execute(Bundle bundle, ToolExecutionRequest request, AssistantActor actor) {
        Object target = bundle.routes().get(request.name());
        if (target == null) {
            audit.record(actor.username(), null, AssistantAudit.STAGE_TOOL, "DENY",
                    "TOOL_NOT_ALLOWED", "LLM06", "模型请求了未授权工具 " + request.name());
            return new Execution(notAllowed(request.name()), STATUS_DENIED);
        }
        try {
            String out = new DefaultToolExecutor(target, request).execute(request, actor.username());
            return new Execution(out == null ? "{}" : out, STATUS_OK);
        } catch (BusinessException e) {
            // 可预期的失败（订单不存在、状态不对、参数非法）：把原因交给模型，让它自己纠正
            // 或如实告知用户。这类错误占工具失败的大多数，不该升级成系统级异常。
            audit.record(actor.username(), null, AssistantAudit.STAGE_TOOL, "ERROR",
                    "TOOL_BUSINESS_ERROR", "LLM06",
                    "工具 " + request.name() + " 业务失败：" + e.code() + " " + e.getMessage());
            return new Execution(error(e.code(), e.getMessage(),
                    "这是可恢复的错误：请据此向用户说明原因，或换一个参数重试。"), STATUS_ERROR);
        } catch (Exception e) {
            // 意料之外的失败：留完整原因在服务端日志和审计里，给模型的只有能行动的信息。
            audit.record(actor.username(), null, AssistantAudit.STAGE_TOOL, "ERROR",
                    "TOOL_FAILED", "LLM06",
                    "工具 " + request.name() + " 执行异常：" + e.getClass().getSimpleName() + " " + e.getMessage());
            return new Execution(error("TOOL_FAILED", "工具执行失败",
                    "不要用相同参数重试；请改用其他工具，或直接回答并说明这项查询暂时不可用。"),
                    STATUS_ERROR);
        }
    }

    private String notAllowed(String toolName) {
        ObjectNode node = json.createObjectNode();
        node.put("error", "FORBIDDEN");
        node.put("message", "工具 " + toolName + " 当前账号不可用，请用可用的工具回答，并说明原因。");
        return node.toString();
    }

    private String error(String code, String message, String hint) {
        ObjectNode node = json.createObjectNode();
        node.put("error", code);
        node.put("message", message == null ? "" : message);
        node.put("hint", hint);
        return node.toString();
    }
}
