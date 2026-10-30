package dev.learning.mall.common;

import java.util.Map;
import org.slf4j.MDC;
import org.springframework.core.task.TaskDecorator;

/**
 * 把 MDC（主要是 traceId）从提交任务的线程拷贝到执行任务的线程（D1 的异步段）。
 *
 * <p><b>为什么需要它：</b>MDC 是 ThreadLocal。助手回答走 Spring MVC 的 {@code Callable}
 * 异步处理：Tomcat 线程提交任务后就释放了，真正干活的「等模型」发生在助手线程池的
 * 另一条线程上。没有这层拷贝，异步段的日志会丢掉 traceId，一次请求的日志在
 * 「Web 线程」和「助手线程」之间断成两截——而这正是多表写、多步工具调用
 * 最需要串联的那段。
 *
 * <p><b>为什么要还原而不是清掉：</b>池化线程会被反复复用。进去时先存下执行线程
 * 原本的 MDC（理论上为 null，但不赌），出来时精确还原——既不让上一个任务的
 * traceId 漏给下一个任务，也不影响别的装饰器放进去的东西。
 */
public class MdcTaskDecorator implements TaskDecorator {

    @Override
    public Runnable decorate(Runnable runnable) {
        // 捕获发生在「提交那一刻」的提交线程上——MDC 必须在这里读，
        // 等到任务真正跑起来时，提交线程早就去处理别的请求了。
        Map<String, String> captured = MDC.getCopyOfContextMap();
        return () -> {
            if (captured == null) {
                // 提交方没有 MDC（定时任务、消息派发）：执行线程保持原样即可。
                runnable.run();
                return;
            }
            Map<String, String> previous = MDC.getCopyOfContextMap();
            MDC.setContextMap(captured);
            try {
                runnable.run();
            } finally {
                if (previous == null) {
                    MDC.clear();
                } else {
                    MDC.setContextMap(previous);
                }
            }
        };
    }
}
