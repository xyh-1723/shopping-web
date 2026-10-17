package dev.learning.mall.assistant.harness;

import dev.learning.mall.assistant.api.RunStepEntry;
import dev.learning.mall.assistant.api.RunTraceEntry;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Agent 运行轨迹持久化。
 *
 * <p>观测层有一条铁律：<b>轨迹写入失败绝不能影响回答本身</b>。观测是给运维和调优用的，
 * 不是业务链路的一部分——把「记日志」写成会抛异常的强依赖，等于给自己埋一个
 * 「监控挂了、业务也挂了」的故障。所以这里的写入整体包在 try/catch 里，
 * 失败只降级为一条 warn 日志。
 */
@Repository
public class RunTraceRepository {

    private static final Logger log = LoggerFactory.getLogger(RunTraceRepository.class);

    private final JdbcClient jdbc;

    public RunTraceRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 落一次运行：1 条 run 概要 + N 条 step。
     *
     * @return runId；即使写入失败也会返回 id，方便日志里关联
     */
    public String save(String conversationId, String username, String model, AgentLoop.Outcome outcome) {
        String runId = UUID.randomUUID().toString();
        try {
            jdbc.sql("INSERT INTO assistant_run(id,conversation_id,username,model,stop_reason,steps,"
                            + "tool_calls,wall_ms,answer_excerpt) "
                            + "VALUES(:id,:conv,:user,:model,:stop,:steps,:calls,:wall,:answer)")
                    .param("id", runId)
                    .param("conv", conversationId)
                    .param("user", username)
                    .param("model", model)
                    .param("stop", outcome.stopReason())
                    .param("steps", outcome.steps().size())
                    .param("calls", outcome.toolCalls())
                    .param("wall", outcome.wallMs())
                    .param("answer", clip(outcome.answer(), 500))
                    .update();
            int no = 0;
            for (AgentLoop.Step step : outcome.steps()) {
                no++;
                jdbc.sql("INSERT INTO assistant_run_step(run_id,step_no,round,tool_name,args_excerpt,"
                                + "status,latency_ms,result_chars,truncated) "
                                + "VALUES(:run,:no,:round,:tool,:args,:status,:latency,:chars,:truncated)")
                        .param("run", runId)
                        .param("no", no)
                        .param("round", step.round())
                        .param("tool", step.toolName())
                        .param("args", clip(step.args(), 300))
                        .param("status", step.status())
                        .param("latency", step.latencyMs())
                        .param("chars", step.resultChars())
                        .param("truncated", step.truncated())
                        .update();
            }
        } catch (Exception e) {
            log.warn("运行轨迹落库失败，已忽略（不影响回答）：{}", e.getMessage());
        }
        return runId;
    }

    /** 最近若干次运行，倒序。供店员排查「为什么助手这样答」。 */
    public List<RunTraceEntry> recent(int limit) {
        return jdbc.sql("SELECT id,conversation_id,username,model,stop_reason,steps,tool_calls,wall_ms,"
                        + "answer_excerpt,created_at FROM assistant_run ORDER BY created_at DESC, id LIMIT :limit")
                .param("limit", Math.clamp(limit, 1, 200))
                .query(RunTraceEntry.class)
                .list();
    }

    /** 某次运行的逐步轨迹。 */
    public List<RunStepEntry> stepsOf(String runId) {
        return jdbc.sql("SELECT step_no,round,tool_name,args_excerpt,status,latency_ms,result_chars,truncated "
                        + "FROM assistant_run_step WHERE run_id = :run ORDER BY step_no")
                .param("run", runId)
                .query(RunStepEntry.class)
                .list();
    }

    /**
     * 最近一次「非正常结束」的运行。排查时最先看的就是这个：
     * 预算耗尽或原地打转，说明循环参数或提示词需要调。
     */
    public Optional<RunTraceEntry> latestAbnormal() {
        return jdbc.sql("SELECT id,conversation_id,username,model,stop_reason,steps,tool_calls,wall_ms,"
                        + "answer_excerpt,created_at FROM assistant_run "
                        + "WHERE stop_reason <> 'DONE' ORDER BY created_at DESC, id LIMIT 1")
                .query(RunTraceEntry.class)
                .optional();
    }

    public long countByStopReason(String stopReason) {
        Long n = jdbc.sql("SELECT COUNT(*) FROM assistant_run WHERE stop_reason = :stop")
                .param("stop", stopReason).query(Long.class).single();
        return n == null ? 0 : n;
    }

    private static String clip(String text, int max) {
        if (text == null) return null;
        String flat = text.replaceAll("\\s+", " ").strip();
        return flat.length() > max ? flat.substring(0, max) + "…" : flat;
    }
}
