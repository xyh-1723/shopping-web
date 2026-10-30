package dev.learning.mall.order.internal;

import dev.learning.mall.common.BusinessException;
import dev.learning.mall.common.Observed;
import dev.learning.mall.merchant.api.MerchantDirectory;
import dev.learning.mall.merchant.api.MerchantView;
import dev.learning.mall.messaging.api.DomainEventPublisher;
import dev.learning.mall.order.api.OrderView;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.IntSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 成交之后的履约推进：发货 → 收货 → 完成。<b>以子订单为单位</b>。
 *
 * <p>它补的是 {@code PAID} 之后的那一半生命周期。以前 {@code PAID} 是吸收态——付完钱就
 * 再没有下文，退款与评价都没有落点。现在交易态（父单 {@code status}）保持 {@code PAID} 不动，
 * 货走到哪一步由<b>子订单</b>的 {@code fulfillment_status} 回答。
 *
 * <h3>为什么单位是子订单而不是整单（V25）</h3>
 *
 * <p>履约下移之前，「发货」写的是 {@code mall_order} 上的一列。一车三家的货，
 * 那一家店点了发货，父单就变成 {@code SHIPPED}——另外两家还压根没收到通知。
 * 这不是「状态不够细」，而是<b>父单上根本没有能成立的值</b>：两家发了、一家没发，
 * 父单该写什么？所以 V25 把履约整套搬到 {@code order_sub}，父单只管钱。
 *
 * <h3>为什么自动收货是「两步」而不是一条 {@code SHIPPED → COMPLETED}</h3>
 *
 * <p>淘宝的语义是「到期自动确认收货、交易成功」——看着像一步到位，但状态机里它是
 * <b>两件事</b>：先产生了「货已被签收」这个事实（{@code received_at}），再产生
 * 「交易结束」这个事实（{@code completed_at}）。合成一条 UPDATE 写不出这两个时刻：
 * {@code ck_sub_fulfillment_fields} 要求 {@code COMPLETED} 时三个时刻齐全，
 * 而一步跳过去的行 {@code received_at} 还是 NULL，数据库会当场把这条语句打回。
 * 所以扫描器每轮对每个子订单<b>依次执行两条独立 UPDATE</b>。
 * 这条由 {@code FulfillmentFlowTest#skipReceivedCannotReachCompleted} 守着。
 *
 * <h3>形状</h3>
 *
 * <p>照 {@link OrderExpiryService} 的骨架写：{@link Clock} 注入（时间不由 SQL 取）、
 * 单轮条数上限来自配置、<b>影响行数 = 1 才发事件</b>。差异只有一处：关单把每笔订单交给
 * {@link OrderTransactions} 的另一个事务，这里没有第二个 Bean，所以用
 * {@link TransactionTemplate} 显式给出「每笔一个事务」的边界——
 * 一笔推进失败（比如它的行被人为改坏）不该拖累同一轮的其它子订单。
 *
 * <p><b>转换权 = 责任</b>：所有前置条件都写在 UPDATE 的 WHERE 里，「这次状态转换是我完成的」
 * 由影响行数证明。因此这里刻意<b>不在 Java 里先判状态再更新</b>——那样做等于把唯一的判定
 * 挪出 SQL，谁绕过这个 Service 直接改库，规则就消失了。
 *
 * <h3>约束异常不许冒泡成 500</h3>
 *
 * <p>判定权在数据库，但「数据库判完之后」的异常如果原样冒泡到 HTTP，用户拿到的是 500，
 * 调用方会把它归类成「服务端抖动、可重试」，而「订单没付钱不能发货」其实是一个
 * <b>说得清</b>的业务结果。所以五处写调用都包在 {@code guarded} 里：
 * 命中本项目自己起的履约约束名 → 409 业务码；命中不了 → 原样抛出（继续是 500），
 * 免得把真正的数据层 bug 也藏进「业务冲突」。
 * 由 {@code FulfillmentHttpGuardTest} 守着。
 *
 * <h4>诚实边界：这几条约束名在当前实现下都<b>构造不可达</b></h4>
 *
 * <p>必须先说清楚，免得后面读代码的人以为这里守着一条天天在跑的路径：
 * 每一条写 UPDATE 都把前置条件写在 WHERE 里，<b>有资格被它更新的行必然满足对应的 CHECK</b>。
 * 「未支付的订单不能发货」由 {@code markShipped} 的 WHERE 里的
 * {@code EXISTS (… o.status='PAID')} 拦下——未支付的行先在 WHERE 上落空（影响 0 行 →
 * {@code INVALID_STATE}），根本走不到约束那一步。
 *
 * <p>所以这里守的不是「一条现在会发生的路径」，而是一个<b>失败形态的选择（failure-mode policy）</b>：
 * 以后谁把 WHERE 挪走、漏写一个时刻、或加了一条新迁移却忘了同步状态机，数据库仍然会把他打回——
 * 区别在于打回之后对外是「409 + {@code FULFILLMENT_*} 语义」，
 * 还是「500 + 一条被调用方记成可忽略异常、线上彻底静默的堆栈」。
 * <b>这不是不一致性，是刻意的失败形态下限。</b>
 * 因此也不允许反过来把它写成「解释了某条真实可达路径」——那样是伪造事实。
 *
 * <p>非死代码的证明方式见 {@code FulfillmentHttpGuardTest}：翻译层本身由「在写调用上注入约束异常」
 * 的用例守着（删掉翻译层立刻变红），而「几个约束名在现实里能被分别识别」由
 * {@code realH2ConstraintNamesAreToldApart} 用<b>真实 H2 抛出的</b>异常原文证明，
 * 不是手写字符串。
 */
@Service
public class FulfillmentService {

    private static final Logger log = LoggerFactory.getLogger(FulfillmentService.class);

    /** 单笔子订单自动推进的事务超时（秒）：本地库毫秒级完成，给个上限避免极端情况长期持锁。 */
    private static final int TX_TIMEOUT_SECONDS = 10;

    /** 与 {@link dev.learning.mall.assistant.security.AssistantActor#ROLE_STAFF} 同一个字面量。 */
    private static final String ROLE_STAFF = "ROLE_STAFF";

    /** 同上：商家。这里不引用 assistant 包的常量，避免 order 反向依赖 assistant。 */
    private static final String ROLE_MERCHANT = "ROLE_MERCHANT";

    /**
     * 履约约束 → 对外的业务结果。
     *
     * <p><b>这里只做翻译，不做预判</b>：判定权仍然完全在数据库（谁改了 UPDATE 的 WHERE、
     * 或漏写了某个时刻，约束照样把他打回）。这里做的是「异常已经逃逸出来之后，别让 HTTP 层
     * 把它当 Internal Server Error 吐出去」——订单没付钱不能发货是一个<b>说得清</b>的业务
     * 结果，不该伪装成服务器故障，也不该在调用方日志里被归成「可忽略异常」而彻底静默。
     *
     * <p><b>三条都不代表一条今天可达的路径</b>：理由写在类注释「诚实边界」一节。
     * 删掉它们的代价不是「现在就出错」，而是失败形态从 409 退回 500。
     *
     * <p>名字随 V25 从 {@code ck_order_fulfillment_*} 换成 {@code ck_sub_fulfillment_*}：
     * 约束跟着履约一起搬到了子订单上，这里的识别名必须同步换，否则
     * 「翻译层以为自己认得」而实际一条都匹配不上——那正是最难受的一种静默失效。
     */
    private enum FulfillmentConstraint {
        STATUS("ck_sub_fulfillment_status", "FULFILLMENT_STATUS_INVALID",
                "履约状态取值非法，本次推进已被拒绝"),
        FIELDS("ck_sub_fulfillment_fields", "FULFILLMENT_MOMENT_MISSING",
                "履约状态与对应时刻不一致，本次推进已被拒绝"),
        AMOUNTS("ck_sub_amounts", "SUB_AMOUNT_INCONSISTENT",
                "子订单金额与「小计 − 优惠 + 运费」不一致，本次推进已被拒绝");

        private final String constraintName;
        private final String code;
        private final String message;

        FulfillmentConstraint(String constraintName, String code, String message) {
            this.constraintName = constraintName;
            this.code = code;
            this.message = message;
        }
    }

    /**
     * 把履约路径上逃逸的约束异常翻译成业务冲突（409）。
     *
     * <p><b>刻意只认本项目自己起的约束名</b>：其它 {@code DataIntegrityViolationException}
     * （唯一键、非空、别的表的 CHECK）一律原样抛出，让它们继续是 500——把未知的数据层错误
     * 也一并翻译成「业务冲突」，等于把真正的 bug 藏起来。
     *
     * <p>可见性是<b>包级</b>而不是 {@code private}：{@code FulfillmentHttpGuardTest} 与它同包，
     * 要用<b>真实 H2 抛出的</b>约束异常直接喂进来，证明三个约束名在现实里能被分别识别；
     * 它仍然不是对外端口（类与测试都在 {@code order.internal} 里）。
     *
     * @return 命中已知履约约束时的业务异常；否则返回 {@code null}，由调用方原样抛出。
     */
    static BusinessException translate(DataIntegrityViolationException e) {
        String text = constraintText(e);
        for (FulfillmentConstraint known : FulfillmentConstraint.values()) {
            // 按「名字 + 冒号」匹配，而不是裸子串：H2 的真实报错形如
            //   Check constraint violation: "CK_SUB_FULFILLMENT_STATUS: "
            // （这句原文是 {@code FulfillmentHttpGuardTest#realH2ConstraintNamesAreToldApart}
            // 真跑出来的，不是照文档抄的）。
            // 现有三条约束名互不为前缀，所以冒号<b>今天不承载区分功能</b>，它防的是以后新增的
            // 「以既有名字为前缀」的约束名（例如 ck_sub_fulfillment_status_v2）被误认成
            // ck_sub_fulfillment_status —— 指错约束比不翻译更难排查。
            // 两边都取大写后再比：迁移脚本里写的是小写，H2 报出来的是大写。
            String marker = known.constraintName.toUpperCase(Locale.ROOT) + ":";
            if (text.contains(marker)) {
                return BusinessException.conflict(known.code, known.message + "（" + known.constraintName + "）");
            }
        }
        return null;
    }

    /** 把整条异常链上的消息拼成一段文本：H2 的约束名只出现在最内层的那条消息里。 */
    private static String constraintText(Throwable e) {
        StringBuilder text = new StringBuilder();
        for (Throwable t = e; t != null; t = t.getCause()) {
            String message = t.getMessage();
            if (message != null) text.append(message);
            if (text.length() > 2000) break;
        }
        return text.toString().toUpperCase(Locale.ROOT);
    }

    /**
     * 一次履约写调用的边界：命中已知约束 → 409，否则原样抛。
     *
     * @param write 真正执行 UPDATE 的那一步。
     * @return UPDATE 的影响行数。
     */
    private static int guarded(IntSupplier write) {
        try {
            return write.getAsInt();
        } catch (DataIntegrityViolationException e) {
            BusinessException business = translate(e);
            if (business != null) throw business;
            throw e;
        }
    }

    private final OrderRepository orders;
    private final OrderSubRepository subs;
    private final DomainEventPublisher events;
    private final OrderProperties properties;
    private final Clock clock;
    private final UserDetailsService users;
    private final MerchantDirectory merchants;
    /** 扫描循环里逐笔提交用：@Transactional 的方法在本类内部自调用不会经过代理。 */
    private final TransactionTemplate tx;

    public FulfillmentService(OrderRepository orders, OrderSubRepository subs, DomainEventPublisher events,
            OrderProperties properties, Clock clock, UserDetailsService users, MerchantDirectory merchants,
            PlatformTransactionManager transactionManager) {
        this.orders = orders;
        this.subs = subs;
        this.events = events;
        this.properties = properties;
        this.clock = clock;
        this.users = users;
        this.merchants = merchants;
        this.tx = new TransactionTemplate(transactionManager);
        this.tx.setTimeout(TX_TIMEOUT_SECONDS);
    }

    // ——————————————————— 店员 / 商家：发货 ———————————————————

    /**
     * 发货。单位是<b>子订单</b>：谁家的货谁发，发的是自己那一段。
     *
     * <p>不加买家 {@code username} 限定：发货不属于任何买家，同 {@link OrderRepository#lock} 的道理。
     * 这里先读一次子订单确认它存在（不存在 = 404）并取出父单号，然后按
     * <b>「父单 → 子单」</b>的固定顺序取两把行锁——与 {@link RefundService} 同一把顺序，
     * 两条路径都先拿父单，才不会互相等对方手里那把。
     * <b>可否发货完全由 UPDATE 的条件决定</b>（父单已支付 + 尚未发货），取行这一步刻意不判状态——
     * 否则「未支付不许发货」这条规则就只在 Java 里存在一份了。
     *
     * @param subId 子订单号
     * @return 发货后的订单视图
     */
    @Transactional
    @Observed("ship-sub-order")
    public OrderView ship(String subId, String staff, String shippingNo) {
        if (shippingNo == null || shippingNo.isBlank())
            throw BusinessException.badRequest("SHIPPING_NO_REQUIRED", "请填写运单号");
        OrderSubRow sub = subs.find(subId).orElseThrow(() -> BusinessException.notFound("子订单不存在"));
        requireCanShip(sub, staff);
        OrderRow order = orders.lock(sub.orderId())
                .orElseThrow(() -> BusinessException.notFound("订单不存在"));
        // 子订单行锁：让「两个人同时点发货」串行化。正确性由 UPDATE 的 WHERE 保证，
        // 这把锁保证第二个人是「等到并发现已经发了」而不是「立刻拿到 0 行」。
        subs.lock(subId).orElseThrow(() -> BusinessException.notFound("子订单不存在"));
        LocalDateTime now = LocalDateTime.now(clock);
        // 自动收货时刻在发货这一刻算好并落库：之后运营把 10 天改成 7 天，
        // 不该让已经在这批路上的货的对账一起跳变。
        LocalDateTime autoReceiveAt = now.plusDays(properties.getAutoReceiveDays());
        if (guarded(() -> subs.markShipped(subId, staff, shippingNo, now, autoReceiveAt)) != 1)
            throw BusinessException.conflict("INVALID_STATE", "子订单不在可发货状态：须为已支付且尚未发货");
        events.publish(order.id(), order.username(), "ORDER_SHIPPED", "订单已发货，运单号 " + shippingNo
                + "，" + properties.getAutoReceiveDays() + " 天后自动确认收货。");
        return orders.view(orders.lock(order.id()).orElse(order));
    }

    // ——————————————————— 买家：收货 / 完成 ———————————————————

    /**
     * 买家确认收货：{@code SHIPPED → RECEIVED}。
     *
     * <p>归属校验就在这条 UPDATE 的 EXISTS 里（父单的 {@code username}）：影响 0 行有可能是
     * 「子订单不存在 / 不是你的」，也有可能是「状态已经不是待收货」，所以失败后才去查一次原因，
     * 用以外界能看懂的方式报错——但<b>判定本身只发生在 UPDATE 上</b>。
     */
    @Transactional
    @Observed("receive-sub-order")
    public OrderView receive(String subId, String user) {
        LocalDateTime now = LocalDateTime.now(clock);
        if (guarded(() -> subs.markReceived(subId, user, now)) != 1) throw notAdvanceable(subId, user, "待收货");
        OrderSubRow sub = subs.find(subId).orElseThrow(() -> BusinessException.notFound("子订单不存在"));
        OrderRow order = orders.lock(sub.orderId()).orElseThrow(() -> BusinessException.notFound("订单不存在"));
        events.publish(order.id(), user, "ORDER_RECEIVED", "已确认收货，子订单 " + subId + " 待完成。");
        return orders.view(order);
    }

    /**
     * 买家确认完成：{@code RECEIVED → COMPLETED}。
     *
     * <p>这一步与「收货」分开存在，是因为淘宝上它们确实是两次用户动作（签收 ≠ 交易结束），
     * 而且 {@code COMPLETED} 才是退款与评价真正的落点。
     */
    @Transactional
    @Observed("complete-sub-order")
    public OrderView complete(String subId, String user) {
        LocalDateTime now = LocalDateTime.now(clock);
        if (guarded(() -> subs.markCompleted(subId, user, now)) != 1) throw notAdvanceable(subId, user, "待完成");
        OrderSubRow sub = subs.find(subId).orElseThrow(() -> BusinessException.notFound("子订单不存在"));
        OrderRow order = orders.lock(sub.orderId()).orElseThrow(() -> BusinessException.notFound("订单不存在"));
        events.publish(order.id(), user, "ORDER_COMPLETED", "交易已完成，感谢购买。");
        return orders.view(order);
    }

    // ——————————————————— 系统：自动收货扫描 ———————————————————

    /**
     * 一轮自动收货扫描：把到期的在途子订单推进到底（先收货、再完成）。
     *
     * <p>幂等：两条 UPDATE 都带前置条件，已经推进过的子订单下一轮不再命中，
     * 重复扫描不会重复写时刻、也不会重复发事件。
     */
    public AutoReceiveResult autoReceiveScan() {
        if (!properties.isAutoReceiveEnabled()) return new AutoReceiveResult(0, 0, 0, 0);
        LocalDateTime now = LocalDateTime.now(clock);
        List<String> subIds = subs.dueForAutoReceive(now, properties.getAutoReceiveMaxPerScan());
        int received = 0;
        int completed = 0;
        int failed = 0;
        for (String subId : subIds) {
            try {
                AutoAdvance step = autoAdvance(subId, now);
                received += step.received();
                completed += step.completed();
            } catch (Exception e) {
                // 单笔失败只影响这一笔：它下一轮还会被选中，不会静默消失。
                failed++;
                log.warn("自动收货失败，下一轮会重试：sub={} 原因={}", subId, e.toString());
            }
        }
        return new AutoReceiveResult(subIds.size(), received, completed, failed);
    }

    /**
     * 推进一个子订单的自动收货：<b>两步，按顺序，两步都是独立 UPDATE</b>。
     *
     * <p>公开是为了让「到期与否」这条规则能被直接断言，而不必先祈祷候选集把它挑出来——
     * 与 {@link OrderTransactions#closeExpired} 可被单独调用的道理相同。
     * 扫描器与它走同一份实现，不存在第二条自动推进的路径。
     *
     * @param now 本轮的「现在」，同时作为两步的时刻与到期判据（时间由调用方注入）。
     */
    public AutoAdvance autoAdvance(String subId, LocalDateTime now) {
        AutoAdvance step = tx.execute(status -> {
            OrderSubRow sub = subs.lock(subId).orElse(null);
            if (sub == null) return new AutoAdvance(0, 0);
            OrderRow order = orders.lock(sub.orderId()).orElse(null);
            if (order == null) return new AutoAdvance(0, 0);
            // 第一步：SHIPPED → RECEIVED。只有它真的把状态推走，才发 ORDER_RECEIVED。
            int received = guarded(() -> subs.markAutoReceived(subId, now, now));
            if (received == 1)
                events.publish(order.id(), order.username(), "ORDER_RECEIVED", "已自动确认收货。");
            // 第二步：RECEIVED → COMPLETED。条件同样是到期，但是<b>另一条</b> UPDATE：
            // 合成一条会让 received_at 留空，被 ck_sub_fulfillment_fields 打回。
            int completed = guarded(() -> subs.markAutoCompleted(subId, now, now));
            if (completed == 1)
                events.publish(order.id(), order.username(), "ORDER_COMPLETED", "交易已完成（自动确认收货）。");
            return new AutoAdvance(received, completed);
        });
        return step == null ? new AutoAdvance(0, 0) : step;
    }

    // ——————————————————— 判定 ———————————————————

    /**
     * 谁能发这个子订单的货：店员可以发任何人的，商家只能发<b>自己那家店</b>的。
     *
     * <p>3a 之前「商家」是一顶「能操作所有订单」的帽子，因为在库里问不出「你的店」。
     * 有了子订单之后，一个子订单在结构上只属于一家店，于是这条校验第一次变得可表达：
     * {@code order_sub.merchant_id = 我的店}。
     *
     * <p>与退款审批同一个形状（{@code RefundService}）：<b>能力由账号推导</b>，
     * 而不是让调用方传一个「我是不是商家」的开关——那个开关迟早会在某个新入口处传错。
     */
    private void requireCanShip(OrderSubRow sub, String staff) {
        Set<String> authorities = authoritiesOf(staff);
        if (authorities.contains(ROLE_STAFF)) return;
        if (!authorities.contains(ROLE_MERCHANT)) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "SHIP_FORBIDDEN",
                    "只有店员或商家才能发货，当前账号无权执行");
        }
        // 「账号 → 店」走统一入口，不再各自 ofOwner + orElseThrow。
        MerchantView shop = merchants.requireShopOfOwner(staff);
        // 「存在但不是你的」与「不存在」给同一个答复（404），
        // 免得把别家子订单号的存在性泄露出去。
        if (shop.id() != sub.merchantId()) throw BusinessException.notFound("子订单不存在");
    }

    /**
     * 0 行不等于一种原因，但对外只能给一个说得清的答复。
     *
     * <p>别人的子订单与不存在的子订单是<b>同一个答复</b>（404）：返回「存在但不是你的」
     * 等于把别人的订单号的存在性泄露出去。查原因这一步不限定用户，正是为了把这两种情况分开。
     */
    private BusinessException notAdvanceable(String subId, String user, String expected) {
        OrderSubRow sub = subs.find(subId).orElse(null);
        if (sub == null) return BusinessException.notFound("子订单不存在");
        OrderRow order = orders.lock(sub.orderId()).orElse(null);
        if (order == null || !order.username().equals(user)) return BusinessException.notFound("子订单不存在");
        return BusinessException.conflict("INVALID_STATE", "子订单当前不是" + expected + "状态");
    }

    /** 账号的权限集合；查不到账号 = 空集合，绝不因为查不到而放行。 */
    private Set<String> authoritiesOf(String username) {
        Set<String> authorities = new LinkedHashSet<>();
        try {
            UserDetails details = users.loadUserByUsername(username);
            details.getAuthorities().forEach(a -> authorities.add(a.getAuthority()));
        } catch (RuntimeException unknown) {
            authorities.clear();
        }
        return authorities;
    }

    /** 一轮扫描的结果，供测试断言与运营观测。 */
    public record AutoReceiveResult(int candidates, int received, int completed, int failed) {}

    /** 一个子订单在这一轮里各推进了几次（只能是 0 或 1）。 */
    public record AutoAdvance(int received, int completed) {}
}
