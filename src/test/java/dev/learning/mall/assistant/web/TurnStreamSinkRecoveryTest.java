package dev.learning.mall.assistant.web;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * SSE 通道「废掉之后怎么收尾」——{@link TurnStreamSink} 最容易长歪的那一处。
 *
 * <p><b>为什么值得单独一个类盯这里</b>：流式端点一旦返回 {@code SseEmitter}，HTTP 状态码
 * （含 200）就已经发出去了，之后发生的一切都只能从流里表达。换句话说，<b>出了任何状况
 * 都没有人会替调用方兜住</b>——唯一还能守住的底线，是「把这一次请求画上句号」。
 * 一旦句号也没了，这次异步请求就只能挂到容器把它掐断，而它表现出来的样子
 * （测试里 {@code getAsyncResult(...)} 整整干等到超时）长得跟「机器慢」一模一样，
 * 排障方向于是被带到「加大超时」上去，而根因其实从来不是慢。
 *
 * <p><b>为什么放在这个包而不是扁平包</b>：{@link TurnStreamSink} 是<b>包级私有</b>
 * （有意不为 {@code public}——它只是助手 Web 层的内部实现，没有理由成为对外可用的一部分）。
 * 想直接用<b>真实类型</b>而不是反射去测它，测试就得待在同一个包里；这条约束比
 * 「一律放扁平包」更强。同包已有先例 {@code ToolProgressLabelTest}（测同一个类的 {@code hasLabel}）。
 */
class TurnStreamSinkRecoveryTest {

    /**
     * 一个「发一条就炸」的 emitter：模拟用户对端已经关掉标签页之后的连接。
     *
     * <p><b>必须数两个量</b>，少一个都会让这份测试变成空转：
     * <ul>
     *   <li>{@code completions}：走到收尾这一步时客户端早就断开了，
     *       <b>唯一还要紧的事就是「这次异步请求到底有没有结束」</b>；</li>
     *   <li>{@code attempts}：确认这个替身<b>真的被调用了</b>。
     *       只写前者会掉进一个很难发现的坑——{@code SseEmitter} 有好几个 {@code send} 重载，
     *       而且 {@code send(SseEventBuilder)} 并不会转调 {@code send(Object, MediaType)}
     *       （它自己组装好 Set 直接调父类），覆盖错重载就得到一个永远不生效的替身，
     *       于是断言无论被测代码怎么改都是绿的。</li>
     * </ul>
     */
    private static final class DeadEmitter extends SseEmitter {

        private final AtomicInteger completions = new AtomicInteger();
        private final AtomicInteger attempts = new AtomicInteger();

        @Override
        public void send(SseEventBuilder builder) throws IOException {
            attempts.incrementAndGet();
            throw new IOException("模拟：连接已被客户端断开");
        }

        @Override
        public void send(Object object, MediaType mediaType) throws IOException {
            attempts.incrementAndGet();
            throw new IOException("模拟：连接已被客户端断开");
        }

        @Override
        public void complete() {
            completions.incrementAndGet();
        }
    }

    @Test
    @DisplayName("事件推不出去也要把响应结束掉：不允许让异步请求挂到容器超时")
    void failedEmitStillCompletesTheResponse() {
        DeadEmitter emitter = new DeadEmitter();
        TurnStreamSink sink = new TurnStreamSink(emitter);

        assertThatCode(() -> sink.emit("open", Map.of("conversationId", "c1")))
                .as("一条事件推不出去，是该被静默吞掉的：用户已经断开，不该把异常抛到整轮之外")
                .doesNotThrowAnyException();
        assertThat(emitter.attempts.get())
                .as("先确认替身真的接住了这次发送——否则下面那条断言就是一句空话")
                .isPositive();
        sink.close();

        assertThat(emitter.completions.get())
                .as("emit 失败只说明这条通道没了，收尾仍必须结束响应一次。"
                        + "少了这一次 complete()，异步请求就没有人画句号，只能干等到容器超时——"
                        + "它的外观（超时）会把排障引向「加大超时」，而根因根本不是慢")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("通道废掉之后的迟到事件：全部静默丢弃，且收尾仍然结束响应")
    void lateEventsAfterFailureAreDroppedQuietly() {
        DeadEmitter emitter = new DeadEmitter();
        TurnStreamSink sink = new TurnStreamSink(emitter);

        assertThatCode(() -> {
            sink.emit("open", Map.of());
            sink.emit("delta", Map.of("text", "你好"));
            sink.emit("done", Map.of());
            sink.close();
        }).doesNotThrowAnyException();

        assertThat(emitter.attempts.get())
                .as("只有第一条真的尝试发送过：之后的两条被「已关闭」挡住，连发送都不尝试——"
                        + "这才是「通道废了以后别再徒劳堆 ValueError」想要的行为")
                .isEqualTo(1);
        assertThat(emitter.completions.get())
                .as("三条事件全部失败，响应也必须且只能结束一次")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("重复收尾不会叠加句号：整条完整路径恰好结束一次")
    void repeatedCloseEndsOnce() {
        DeadEmitter emitter = new DeadEmitter();
        TurnStreamSink sink = new TurnStreamSink(emitter);

        sink.emit("open", Map.of());
        sink.close();
        sink.close();

        assertThat(emitter.completions.get())
                .as("幂等收尾：不会因为多调一次就重复结束，也不会因为通道失败过就跳过结束")
                .isEqualTo(1);
    }
}
