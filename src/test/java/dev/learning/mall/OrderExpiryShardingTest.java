package dev.learning.mall;

import dev.learning.mall.order.api.CreateOrderRequest;
import dev.learning.mall.order.api.OrderView;
import dev.learning.mall.order.internal.OrderExpiryService;
import dev.learning.mall.order.internal.OrderFacade;
import dev.learning.mall.order.internal.OrderProperties;
import dev.learning.mall.order.internal.OrderShard;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.TransactionDefinition;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * 超时关单的<b>分片扫描</b>。
 *
 * <p>要守的是两件事，而且它们的失败方式都很安静：
 * <ol>
 *   <li><b>不重</b>：多实例部署时，同一笔订单不该被 N 个实例同时挑中去抢同一把行锁；
 *   <li><b>不漏</b>：分片之后，每一笔订单仍必须恰好落在一个实例的责任范围内。
 *       漏掉的那一批不会报错，只表现为「库存被占死」——是最难查的那类故障。
 * </ol>
 *
 * <p>第二条的隐蔽性来自分片键有两个计算入口：新订单由 Java 算（{@link OrderShard}），
 * 存量订单由迁移脚本里的 SQL 表达式回填。两边一旦分叉，就有一批订单谁都不扫。
 * 所以这里有一条专门盯住这个分叉的断言。
 */
@SpringBootTest
@ActiveProfiles("test")
class OrderExpiryShardingTest {

    private static final LocalDateTime BASE = LocalDateTime.of(2026, 10, 16, 12, 0, 0);

    @Autowired OrderFacade facade;
    @Autowired OrderExpiryService expiry;
    @Autowired OrderProperties properties;
    @Autowired JdbcClient jdbc;
    @Autowired PlatformTransactionManager txManager;
    @MockitoBean Clock clock;

    @BeforeEach void setUp() {
        properties.setAutoCloseEnabled(true);
        properties.setPaymentTimeoutMinutes(15);
        properties.setAutoCloseMaxPerScan(500);
        properties.setAutoCloseShardCount(1);
        properties.setAutoCloseShardIndex(0);
        for (String table : List.of("notification", "outbox_event", "order_item", "mall_order")) {
            jdbc.sql("DELETE FROM " + table).update();
        }
        jdbc.sql("UPDATE product SET stock=1000").update();
        at(BASE);
    }

    @AfterEach void restore() {
        properties.setAutoCloseEnabled(false);
        properties.setAutoCloseShardCount(1);
        properties.setAutoCloseShardIndex(0);
    }

    // ——————————————————— 分片键的两个入口必须一致 ———————————————————

    @Test
    @DisplayName("Java 算出的分片值与迁移脚本的回填表达式逐行一致（分叉会导致一批订单没人扫）")
    void javaAndMigrationAgreeOnShardValue() {
        // 16 个十六进制首字符各来一个，外加一个非十六进制的（迁移里靠 GREATEST(0,...) 夹到 0）。
        List<String> ids = new ArrayList<>();
        for (char c : "0123456789abcdef".toCharArray()) ids.add(c + "000000-0000-0000-0000-000000000000");
        ids.add("zzz-not-hex");
        for (String id : ids) {
            jdbc.sql("INSERT INTO mall_order(id,username,idempotency_key,request_hash,total,status,created_at)"
                            + " VALUES(:id,'student',:key,'hash',39.00,'PENDING',:created)")
                    .param("id", id).param("key", UUID.randomUUID().toString()).param("created", BASE).update();
        }
        // 与 V21__order_expiry_shard.sql 的回填语句逐字一致；改一处就要改另一处。
        jdbc.sql("UPDATE mall_order"
                        + " SET expiry_shard = GREATEST(0, POSITION(SUBSTRING(id FROM 1 FOR 1) IN '0123456789abcdef') - 1)")
                .update();

        List<String> mismatches = jdbc.sql("SELECT id, expiry_shard FROM mall_order")
                .query((rs, row) -> new String[]{rs.getString("id"), String.valueOf(rs.getInt("expiry_shard"))})
                .list().stream()
                .filter(pair -> Integer.parseInt(pair[1]) != OrderShard.of(pair[0]))
                .map(pair -> pair[0] + " -> " + pair[1] + "，Java 算的是 " + OrderShard.of(pair[0]))
                .toList();
        assertThat(mismatches)
                .as("分片键的两个计算入口分叉了：分叉的订单不会落进任何实例的责任范围，表现为库存被占死")
                .isEmpty();
    }

    @Test
    @DisplayName("真实下单路径写入的分片值，同样落在 0..15 且与订单号一致")
    void ordersCreatedThroughTheRealPathCarryTheirShard() {
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < 12; i++) ids.add(create().id());
        for (String id : ids) {
            int shard = jdbc.sql("SELECT expiry_shard FROM mall_order WHERE id=:id").param("id", id).query(Integer.class).single();
            assertThat(shard).isBetween(0, 15);
            assertThat(shard).isEqualTo(OrderShard.of(id));
        }
    }

    // ——————————————————— 不重不漏 ———————————————————

    @Test
    @DisplayName("4 个分片各扫各的：同一笔订单不重复关闭，也没有订单被漏掉")
    void everyOrderIsClosedByExactlyOneShard() {
        int total = 24;
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < total; i++) ids.add(create().id());
        at(BASE.plusMinutes(15));

        Set<String> closedBySomeone = new LinkedHashSet<>();
        for (int index = 0; index < 4; index++) {
            properties.setAutoCloseShardCount(4);
            properties.setAutoCloseShardIndex(index);
            var result = expiry.closeExpired();
            assertThat(result.failed()).isZero();
            List<String> closedNow = closedOrders();
            closedNow.removeAll(closedBySomeone);
            // 本轮关掉的每一笔，都必须真的落在本实例的分片里——否则分片条件没起作用。
            for (String id : closedNow) {
                int shard = shardOf(id);
                assertThat(shard % 4)
                        .as("订单 %s 的分片是 %d，不该由第 %d 号实例关掉", id, shard, index)
                        .isEqualTo(index);
            }
            closedBySomeone.addAll(closedNow);
        }

        assertThat(closedBySomeone).as("4 个分片扫完，每一笔到期订单都必须恰好被关掉一次").hasSize(total);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM mall_order WHERE status='PENDING'").query(Integer.class).single()).isZero();
    }

    @Test
    @DisplayName("分片数为 1 时行为与分片前完全一致：所有到期订单都会被扫到")
    void singleShardSeesEverything() {
        for (int i = 0; i < 8; i++) create();
        at(BASE.plusMinutes(15));

        var result = expiry.closeExpired();
        assertThat(result.candidates()).isEqualTo(8);
        assertThat(result.closed()).isEqualTo(8);
        assertThat(result.skipped()).isZero();
    }

    @Test
    @DisplayName("本实例只报自己那份积压：不在自己分片里的到期订单不算进 backlog")
    void backlogCountsOnlyThisShardsWork() {
        for (int i = 0; i < 12; i++) create();
        // 订单号是随机 UUID 派生的，12 单全落在「非 0 号分片」的概率是 (3/4)^12 ≈ 3%。
        // 补几单直到确实有一单落在 0 号分片：这里要验的是「只报自己那份的积压」，
        // 不该被随机数是否够幸运决定成败（这个断言之前偶发失败过）。
        // 注意必须在下面前推时钟之前补单——补出来的订单到期时刻跟着时钟走，
        // 推完时钟再补就落在「尚未到期」里，永远数不到。
        for (int guard = 0; guard < 60 && countExpiredInShard(0, 4) == 0; guard++) create();
        at(BASE.plusMinutes(15));

        properties.setAutoCloseShardCount(4);
        properties.setAutoCloseShardIndex(0);
        int mine = countExpiredInShard(0, 4);
        var result = expiry.closeExpired();
        // 关完再看：第 0 号实例已经把自己那份清空了，它的积压必须是 0，
        // 而此时库里还有另外 3 个分片的订单没处理——全局数字会误报成「我积压了一堆」。
        assertThat(result.backlog()).isZero();
        assertThat(mine).isGreaterThan(0);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM mall_order WHERE status='PENDING'").query(Integer.class).single())
                .as("其它分片的订单还在，说明 backlog 为 0 不是因为活干完了")
                .isPositive();
    }

    // ——————————————————— SKIP LOCKED ———————————————————

    @Test
    @DisplayName("行锁被别人持有时立刻跳过，而不是排队等（跳过的那一笔下一轮还会被选中）")
    void orderLockedElsewhereIsSkippedNotWaited() throws Exception {
        String id = create().id();
        at(BASE.plusMinutes(15));

        TransactionTemplate newTx = new TransactionTemplate(txManager);
        newTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        ExecutorService holder = Executors.newSingleThreadExecutor();
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try {
            var holding = holder.submit(() -> newTx.execute(status -> {
                jdbc.sql("SELECT id FROM mall_order WHERE id=:id FOR UPDATE").param("id", id).query(String.class).optional();
                locked.countDown();
                try {
                    release.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
                return null;
            }));
            assertThat(locked.await(10, TimeUnit.SECONDS)).as("持锁事务没能进入").isTrue();

            // 锁还在别人手上：这一轮应当「跳过」而不是卡住等锁，也不是报错。
            var result = expiry.closeExpired();
            assertThat(result.closed()).isZero();
            assertThat(result.failed()).isZero();
            assertThat(result.skipped()).as("被锁住的那笔应当被跳过").isEqualTo(1);
            assertThat(statusOf(id)).isEqualTo("PENDING");

            release.countDown();
            holding.get(15, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            holder.shutdownNow();
        }

        // 锁释放后下一轮就该正常关掉——「跳过」不等于「放弃」。
        assertThat(expiry.closeExpired().closed()).isEqualTo(1);
        assertThat(statusOf(id)).isEqualTo("CANCELLED");
    }

    // ——————————————————— 配置错了要在启动时炸 ———————————————————

    @Test
    @DisplayName("分片序号越界会被校验挡下：否则实例会静默地一笔订单都不扫")
    void shardIndexBeyondCountIsRejected() {
        Validator validator = Validation.buildDefaultValidatorFactory().getValidator();
        OrderProperties out = new OrderProperties();
        out.setAutoCloseShardCount(2);
        out.setAutoCloseShardIndex(5);
        assertThat(validator.validate(out)).isNotEmpty();

        OrderProperties ok = new OrderProperties();
        ok.setAutoCloseShardCount(2);
        ok.setAutoCloseShardIndex(1);
        assertThat(validator.validate(ok)).isEmpty();
    }

    // ——————————————————— 辅助 ———————————————————

    private OrderView create() {
        return facade.create("student", UUID.randomUUID().toString(),
                new CreateOrderRequest(List.of(new CreateOrderRequest.Line(1, 1))));
    }

    private void at(LocalDateTime now) {
        ZoneId zone = ZoneId.systemDefault();
        when(clock.instant()).thenReturn(now.atZone(zone).toInstant());
        when(clock.getZone()).thenReturn(zone);
        when(clock.withZone(any())).thenReturn(clock);
    }

    private String statusOf(String id) {
        return jdbc.sql("SELECT status FROM mall_order WHERE id=:id").param("id", id).query(String.class).single();
    }

    private int shardOf(String id) {
        return jdbc.sql("SELECT expiry_shard FROM mall_order WHERE id=:id").param("id", id).query(Integer.class).single();
    }

    private List<String> closedOrders() {
        return jdbc.sql("SELECT id FROM mall_order WHERE status='CANCELLED'").query(String.class).list();
    }

    private int countExpiredInShard(int index, int count) {
        return jdbc.sql("SELECT COUNT(*) FROM mall_order WHERE status='PENDING' AND expires_at <= :now"
                        + " AND MOD(expiry_shard, :c) = :i")
                .param("now", BASE.plusMinutes(15))
                .param("c", count).param("i", index)
                .query(Integer.class).single();
    }
}
