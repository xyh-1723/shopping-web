package dev.learning.mall.messaging.internal;

import java.util.concurrent.ThreadPoolExecutor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration
@EnableConfigurationProperties(MessagingProperties.class)
public class ExecutorConfig {
    @Bean
    public ThreadPoolTaskExecutor messageExecutor(MessagingProperties properties) {
        if (properties.maxPoolSize() < properties.corePoolSize()) throw new IllegalArgumentException("max-pool-size must be >= core-pool-size");
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(properties.corePoolSize());
        executor.setMaxPoolSize(properties.maxPoolSize());
        executor.setQueueCapacity(properties.queueCapacity());
        executor.setThreadNamePrefix("mall-message-");
        // 队列有界；饱和时拒绝，数据库中的事件保留，下次扫描再投递。
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(15);
        return executor;
    }
}
