package dev.learning.mall;

import dev.learning.mall.messaging.internal.*;
import dev.learning.mall.order.api.*;
import dev.learning.mall.order.internal.OrderFacade;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import static org.assertj.core.api.Assertions.*;

class RestartPersistenceTest {
    @TempDir Path directory;
    @Test void pendingEventsAndIdempotencySurviveRestart() {
        String url = "jdbc:h2:file:" + directory.resolve("mall").toAbsolutePath().toString().replace('\\','/') + ";DB_CLOSE_ON_EXIT=FALSE";
        var request = new CreateOrderRequest(List.of(new CreateOrderRequest.Line(1,1)));
        String orderId, eventId;
        try (var first = start(url)) {
            orderId = first.getBean(OrderFacade.class).create("student","restart-key-123",request).id();
            eventId = first.getBean(OutboxRepository.class).readyIds().getFirst();
        }
        try (var second = start(url)) {
            assertThat(second.getBean(OrderFacade.class).create("student","restart-key-123",request).id()).isEqualTo(orderId);
            assertThat(second.getBean(OutboxRepository.class).readyIds()).contains(eventId);
            second.getBean(MessageProcessor.class).process(eventId);
            var jdbc = second.getBean(JdbcClient.class);
            assertThat(jdbc.sql("SELECT COUNT(*) FROM notification").query(Integer.class).single()).isEqualTo(1);
            assertThat(jdbc.sql("SELECT stock FROM product WHERE id=1").query(Integer.class).single()).isEqualTo(29);
        }
    }
    private ConfigurableApplicationContext start(String url) {
        return new SpringApplicationBuilder(MallApplication.class).run("--server.port=0", "--spring.datasource.url="+url,
                "--mall.messaging.enabled=false", "--logging.level.root=ERROR", "--spring.main.banner-mode=off");
    }
}
