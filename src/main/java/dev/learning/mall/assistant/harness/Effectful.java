package dev.learning.mall.assistant.harness;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标记「效果型」工具集合：调用它会产生副作用或依赖调用线程的上下文。
 *
 * <p>为什么需要这个标记？执行循环会把同一轮里互不依赖的工具调用并行化以降低延迟
 * （harness 的标准做法，独立工具 fan-out 能把多工具回合的墙钟时间从「求和」变成「取最大」）。
 * 但并行化有两个前提，效果型工具都不满足：
 *
 * <ol>
 *   <li><b>产物需要回流</b>：{@code PendingActionService} 把「本轮发起的支付确认」写进
 *       {@link TurnScope} 的轮次上下文，由调用线程在轮次结束时取出并随响应回给前端。
 *       而 {@code TurnScope.wrapRunnable} 的传播是<b>单向快照</b>——worker 线程能看到本轮的
 *       会话与用户（所以不会串味到别人身上，也不会静默读到 null），但它在快照副本上的写入
 *       <b>不会回流</b>到调用线程。结果就是：不报错，但确认卡片不出现。
 *       跨线程的「读得到、写不回」是有意设计（见 {@link TurnScope} 的说明），
 *       所以需要产生产物的工具必须留在调用线程。</li>
 *   <li><b>副作用顺序</b>：写操作与只读查询并行时，读到的可能是不确定的中间状态。</li>
 * </ol>
 *
 * <p>所以：标注了这个注解的类，其工具调用一律留在调用线程上顺序执行；其余只读工具并行。
 * 加新的写工具时记得标注，否则它会以「看起来正常」的方式出错。
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface Effectful {

    /** 备注该工具为什么是效果型的，便于后来者判断要不要放进并行组。 */
    String value() default "";
}
