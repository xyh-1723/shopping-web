package dev.learning.mall.messaging.internal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class MessageWorker {
    private static final Logger log = LoggerFactory.getLogger(MessageWorker.class);
    private final MessageProcessor processor;
    public MessageWorker(MessageProcessor processor) { this.processor = processor; }
    public void handle(String id) {
        try {
            processor.process(id);
        } catch (Exception error) {
            // process 的事务已由代理回滚；此处开启另一个事务记录重试。
            log.warn("事件 {} 消费失败，将安排重试：{}", id, error.getClass().getSimpleName());
            processor.recordFailure(id, error);
        }
    }
}
