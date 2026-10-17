package dev.learning.mall;

import dev.learning.mall.assistant.harness.TurnScope;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 轮次上下文（{@link TurnScope}）的单元测试——它承载「多用户并发时上下文不串味」这条性质。
 *
 * <p>这类测试必须离线、确定性：上下文隔离是<b>机制</b>问题，不是概率问题。
 * 「大多数时候不串味」在生产上等于「偶发地把 A 的支付卡片挂给 B」，比稳定报错更难查。
 */
class TurnScopeTest {

    private final ExecutorService pool = Executors.newSingleThreadExecutor();

    @AfterEach
    void shutdown() throws InterruptedException {
        pool.shutdownNow();
        pool.awaitTermination(5, TimeUnit.SECONDS);
    }

    @Test
    @DisplayName("进入轮次可见会话与用户；嵌套退出逐层还原，最外层退出后回到「无轮次」")
    void scopeRestoresPreviousState() {
        assertThat(TurnScope.active()).isFalse();
        try (TurnScope.Scope outer = TurnScope.begin("conv-A", "alice")) {
            assertThat(TurnScope.conversationId()).isEqualTo("conv-A");
            assertThat(TurnScope.username()).isEqualTo("alice");
            try (TurnScope.Scope inner = TurnScope.begin("conv-B", "bob")) {
                assertThat(TurnScope.conversationId()).isEqualTo("conv-B");
            }
            // 内层退出必须回到外层，而不是清空——否则嵌套调用会互相破坏。
            assertThat(TurnScope.conversationId()).isEqualTo("conv-A");
        }
        assertThat(TurnScope.active()).isFalse();
        assertThat(TurnScope.conversationId()).isNull();
    }

    @Test
    @DisplayName("跨线程传播：worker 线程上依然看得到本轮的会话与用户")
    void wrapPropagatesToWorkerThread() throws Exception {
        try (TurnScope.Scope ignored = TurnScope.begin("conv-1", "alice")) {
            String seen = pool.submit(TurnScope.wrap(TurnScope::conversationId)).get(5, TimeUnit.SECONDS);
            String seenUser = pool.submit(TurnScope.wrap(TurnScope::username)).get(5, TimeUnit.SECONDS);
            assertThat(seen).isEqualTo("conv-1");
            assertThat(seenUser).isEqualTo("alice");
        }
    }

    @Test
    @DisplayName("线程池复用的核心保证：worker 上原有的残留上下文被「精确还原」，不是被清空")
    void workerContextIsRestoredNotJustCleared() throws Exception {
        // 模拟池化的 worker 被上一个使用者「污染」：故意 begin 而不 close。
        // 这等价于某人漏写了清理——TurnScope 必须能在这种情况下仍不串味。
        pool.submit(() -> TurnScope.begin("conv-POLLUTED", "mallory")).get(5, TimeUnit.SECONDS);
        assertThat(pool.submit(TurnScope::conversationId).get(5, TimeUnit.SECONDS))
                .isEqualTo("conv-POLLUTED");

        try (TurnScope.Scope ignored = TurnScope.begin("conv-NEW", "alice")) {
            // 任务运行期间：看到的是本轮的上下文，而不是残留的别人的。
            assertThat(pool.submit(TurnScope.wrap(TurnScope::conversationId)).get(5, TimeUnit.SECONDS))
                    .isEqualTo("conv-NEW");
            assertThat(pool.submit(TurnScope.wrap(TurnScope::username)).get(5, TimeUnit.SECONDS))
                    .isEqualTo("alice");
        }

        // 任务退出后：还原成「进入前的样子」（即残留值），而不是 null。
        // 若这里清空成 null，等于把别人的上下文弄丢了——同样是破坏性副作用。
        assertThat(pool.submit(TurnScope::conversationId).get(5, TimeUnit.SECONDS))
                .isEqualTo("conv-POLLUTED");
    }

    @Test
    @DisplayName("不在轮次内时 wrap 不凭空创建上下文")
    void wrapOutsideScopeDoesNotCreateContext() throws Exception {
        assertThat(pool.submit(TurnScope.wrap(TurnScope::conversationId)).get(5, TimeUnit.SECONDS))
                .isNull();
    }

    @Test
    @DisplayName("属性随快照传播，但 worker 上的写入不回流到调用线程（单向隔离）")
    void attributesAreCopiedNotShared() throws Exception {
        try (TurnScope.Scope ignored = TurnScope.begin("conv-1", "alice")) {
            TurnScope.attribute("k", "v-from-caller");
            String workerSaw = pool.submit(TurnScope.wrap(() -> {
                String before = String.valueOf(TurnScope.attribute("k"));
                TurnScope.attribute("k", "changed-in-worker");
                return before;
            })).get(5, TimeUnit.SECONDS);

            assertThat(workerSaw).isEqualTo("v-from-caller");
            // 单向：并行只读工具不该有能力改写轮次状态（写操作走 @Effectful 留在调用线程）。
            assertThat(TurnScope.attribute("k")).isEqualTo("v-from-caller");
        }
    }

    @Test
    @DisplayName("install 装载快照后，读取到的用户与会话与快照一致")
    void installAppliesSnapshot() {
        TurnScope.Snapshot snapshot = new TurnScope.Snapshot("conv-X", "carol",
                Map.of("flag", "on"));
        try (TurnScope.Scope ignored = TurnScope.install(snapshot)) {
            assertThat(TurnScope.conversationId()).isEqualTo("conv-X");
            assertThat(TurnScope.username()).isEqualTo("carol");
            assertThat(TurnScope.attribute("flag")).isEqualTo("on");
        }
        assertThat(TurnScope.active()).isFalse();
    }
}
