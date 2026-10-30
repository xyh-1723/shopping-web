package dev.learning.mall.assistant.internal;

import dev.learning.mall.common.MdcTaskDecorator;
import java.util.concurrent.ThreadPoolExecutor;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.servlet.config.annotation.AsyncSupportConfigurer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 助手的异步执行骨架——把「等模型」从 Tomcat 线程上挪走。
 *
 * <p><b>为什么必须异步？</b>一次助手回答要调用大模型 1–6 次，每次几秒。同步 Servlet 模型下，
 * 处理这个请求的 Tomcat 工作线程会<b>全程被占住干等</b>。Tomcat 默认线程数是 200，
 * 也就是说几十个正在「等模型」的助手请求就能把 Web 容器的工作线程吃干净，
 * 之后连商城首页、商品接口、静态资源都排不上队——<b>一个功能把整个站点拖死</b>。
 *
 * <p>改成 {@code Callable} 异步返回后：Tomcat 线程在助手开始思考的那一刻就被释放回池子，
 * 真正耗时的等待由这里定义的<b>助手专用线程池</b>承担。隔离是双向的：
 * 助手慢不会拖垮商城，商城的流量也不会把助手的执行线程吃光。
 *
 * <p>线程池的容量与并发闸门的两道口子要一起看：
 * 线程池的 {@code maxPoolSize} 决定「同时有多少个执行臂」，
 * 而闸门的 {@code maxInFlightGlobal} 决定「同时放进来多少个请求」。两者对齐时，
 * 请求既不会因为抢不到线程而排队，也不会因为放进来太多而互相拖慢。
 */
@Configuration
class AssistantAsyncConfig implements WebMvcConfigurer {

    private final AssistantProperties properties;
    private final ApplicationContext context;

    AssistantAsyncConfig(AssistantProperties properties, ApplicationContext context) {
        this.properties = properties;
        this.context = context;
    }

    /**
     * 助手专用线程池。之所以不复用 Boot 默认的 {@code applicationTaskExecutor}：
     * 异步消息模块（messaging）已经有自己的池，助手再共用就失去了「互不拖垮」的意义。
     */
    @Bean(name = "assistantTurnExecutor")
    ThreadPoolTaskExecutor assistantTurnExecutor() {
        AssistantProperties.Concurrency config = properties.getConcurrency();
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(Math.max(1, config.getCorePoolSize()));
        executor.setMaxPoolSize(Math.max(1, config.getMaxPoolSize()));
        executor.setQueueCapacity(Math.max(0, config.getQueueCapacity()));
        executor.setThreadNamePrefix("assistant-turn-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        // D1：把 MDC（traceId）从提交请求的 Web 线程拷到助手线程，
        // 否则一轮回答的日志在「Web 线程 → 助手线程」的边界上断成两截。
        executor.setTaskDecorator(new MdcTaskDecorator());
        // 关停时把正在跑的轮次跑完：让用户拿到答案，比立刻断开更友好。
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(20);
        return executor;
    }

    /**
     * 把上面的池指给 Spring MVC 的异步处理。
     *
     * <p>不显式指定的话，Spring MVC 会去找名为 {@code applicationTaskExecutor} 的 bean；
     * 由于项目里已有多个线程池 bean，该 bean 不会被自动创建，MVC 会退化成
     * {@code SimpleAsyncTaskExecutor}——<b>每次请求新建一条线程</b>，并发一高直接雪崩。
     * 这类「看起来能跑」的默认降级，正是必须写死配置的原因。
     */
    @Override
    public void configureAsyncSupport(AsyncSupportConfigurer configurer) {
        configurer.setTaskExecutor(context.getBean("assistantTurnExecutor", ThreadPoolTaskExecutor.class));
        // 异步超时必须大于 harness 自己的墙钟预算，否则会在循环给出兜底答复之前就被容器掐断。
        configurer.setDefaultTimeout(properties.getHarness().getTimeoutMs() + 15_000);
    }
}
