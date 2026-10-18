package dev.learning.mall;

import dev.learning.mall.messaging.internal.*;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.*;

class DispatcherBackpressureTest {
    @Test void saturatedExecutorLeavesEventAvailableForNextDispatch() throws Exception {
        // 通道相关两项按生产默认填：默认通道 IN_APP + 密钥留空（= 短信不可用，降级到站内信）。
        var properties = new MessagingProperties(true,1,1,1,5,2,false,"IN_APP",new MessagingProperties.Sms(true,"",""));
        ThreadPoolTaskExecutor executor = new ExecutorConfig().messageExecutor(properties);
        executor.initialize();
        var repository = mock(OutboxRepository.class); var worker = mock(MessageWorker.class);
        when(repository.readyIds()).thenReturn(List.of("event-1"));
        var dispatcher = new OutboxDispatcher(repository,worker,executor,properties);
        var release = new CountDownLatch(1); var started = new CountDownLatch(1);
        try {
            executor.execute(() -> { started.countDown(); try { release.await(10,TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } });
            assertThat(started.await(5,TimeUnit.SECONDS)).isTrue();
            executor.execute(() -> {}); // 占满唯一队列槽位。
            dispatcher.dispatch();
            verifyNoInteractions(worker);
            release.countDown();
            await().atMost(5,TimeUnit.SECONDS).until(() -> executor.getActiveCount()==0 && executor.getQueueSize()==0);
            dispatcher.dispatch();
            await().atMost(5,TimeUnit.SECONDS).untilAsserted(() -> verify(worker).handle("event-1"));
            verify(repository,times(2)).readyIds();
            verifyNoMoreInteractions(repository); // 扫描/拒绝阶段没有错误确认消息。
        } finally { release.countDown(); executor.shutdown(); }
    }
}
