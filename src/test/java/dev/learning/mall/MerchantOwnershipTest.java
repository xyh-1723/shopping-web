package dev.learning.mall;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;

import dev.learning.mall.common.BusinessException;
import dev.learning.mall.merchant.api.MerchantDirectory;
import dev.learning.mall.merchant.api.MerchantView;
import dev.learning.mall.order.api.CreateOrderRequest;
import dev.learning.mall.order.api.OrderPayments;
import dev.learning.mall.order.api.OrderView;
import dev.learning.mall.order.api.RefundView;
import dev.learning.mall.order.internal.FulfillmentService;
import dev.learning.mall.order.internal.OrderFacade;
import dev.learning.mall.order.internal.RefundService;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * 阶段 3a 的验收：<b>「商家」从一个全局审批权限，变成一条数据归属维度</b>。
 *
 * <p>这批用例存在的前提是一个已经发生过的事实：在 {@code merchant} 表出现之前，
 * 全系统只有一个 {@code merchant} 账号，而退款审批只判角色不判归属——
 * 于是任何一个带着 {@code ROLE_MERCHANT} 的账号都能看见并批准<b>全部</b>退款。
 * 「多造几个商家账号」在那个地基上不是扩容，是成倍复制同一个越权。
 *
 * <p>因此这里断言的不是「有没有 merchant_id 这一列」，而是四条<b>换了实现方式就会被推翻</b>的性质：
 * <ol>
 *   <li>每家店有自己的商品、自己的店主账号；</li>
 *   <li>商家 A 的队列里没有商家 B 的退款；</li>
 *   <li>商家 A 批不动商家 B 的退款，且那次尝试什么都没发生（钱没动、状态没变）；</li>
 *   <li>店员仍然看得到全站——隔离是收窄商家的范围，不是把运营的眼睛蒙上。</li>
 * </ol>
 *
 * <h3>两条纪律</h3>
 * <ul>
 *   <li><b>身份走真实链路</b>：商家账号从 {@code merchant.owner_username} 查出来，
 *       再交给 {@link UserDetailsService} 解析。手造一个 {@code ROLE_MERCHANT} 的
 *       principal 会绕过「账号到底是哪一家店」这一环，而那正是这次要堵的洞。</li>
 *   <li><b>「另一家店」不能写死</b>：商品与商家的对应关系由 V24 的算术决定，
 *       写死 {@code productId = 174} 会让改一次分配公式就红一大批用例，
 *       而那些红与「隔离是否成立」毫无关系。一律回查。</li>
 * </ul>
 */
@SpringBootTest
@ActiveProfiles("test")
class MerchantOwnershipTest {

    private static final LocalDateTime BASE = LocalDateTime.of(2026, 10, 23, 15, 0, 0);

    /** 每家店的 SKU 数（V24：5000 件 / 200 家）。 */
    private static final int SKU_PER_SHOP = 25;

    @Autowired MerchantDirectory merchants;
    @Autowired UserDetailsService userDetails;
    @Autowired OrderFacade facade;
    @Autowired OrderPayments payments;
    @Autowired FulfillmentService fulfillment;
    @Autowired RefundService refunds;
    @Autowired JdbcClient jdbc;
    @MockitoBean Clock clock;

    private String buyer;
    private long restoredMerchantForProbe = -1;

    @BeforeEach
    void setUp() {
        for (String table : List.of("notification", "outbox_event", "order_item", "mall_order")) {
            jdbc.sql("DELETE FROM " + table).update();
        }
        jdbc.sql("DELETE FROM refund").update();
        at(BASE);
    }

    @AfterEach
    void tearDown() {
        // 归属漂移那条用例动过 product.merchant_id，必须还原，否则后续用例看到的归属是脏的。
        if (restoredMerchantForProbe > 0) {
            jdbc.sql("UPDATE product SET merchant_id = :m WHERE id = :id")
                    .param("m", restoredMerchantForProbe).param("id", probeProduct()).update();
            restoredMerchantForProbe = -1;
        }
        jdbc.sql("DELETE FROM refund").update();
        if (buyer != null) {
            WalletProbe.purge(jdbc, buyer);
            buyer = null;
        }
    }

    // ——————————————————— ① 地基：店、商品、账号 ———————————————————

    @Test
    @DisplayName("① 每家店都有自己的商品与自己的店主账号：200 家 × 25 件，账号真能登录且只带 MERCHANT")
    void everyShopHasProductsAndAnOwnerAccount() {
        List<MerchantView> all = merchants.all();
        assertThat(all).as("常驻档是 200 家店").hasSize(200);

        for (MerchantView shop : all) {
            Integer sku = jdbc.sql("SELECT COUNT(*) FROM product WHERE merchant_id = :m")
                    .param("m", shop.id()).query(Integer.class).single();
            assertThat(sku).as("店铺 %s(%s) 的 SKU 数", shop.name(), shop.code()).isEqualTo(SKU_PER_SHOP);

            var account = userDetails.loadUserByUsername(shop.ownerUsername());
            assertThat(authoritiesOf(account))
                    .as("店主账号 %s 必须带 ROLE_MERCHANT，且刻意不带 ROLE_STAFF（并列角色）",
                            shop.ownerUsername())
                    .contains("ROLE_MERCHANT").doesNotContain("ROLE_STAFF");
        }
        // 反向控制：上面那段循环若写成空集合（比如 merchants.all() 返回空），
        // 每条断言都不会被执行而用例照样全绿——这一句把「真的有 200 家被检查过」钉住。
        assertThat(all.stream().map(MerchantView::ownerUsername).distinct())
                .as("店主账号互不重复：一个账号只开一家店").hasSize(200);
        assertThat(merchants.ofOwner("merchant")).as("1 号店的店主沿用既有 merchant 账号").isPresent();
        assertThat(merchants.ofOwner("merchant").orElseThrow().id())
                .as("既有契约不能漂：merchant 这个账号对应的仍然是 1 号店").isEqualTo(1L);
    }

    // ——————————————————— ② 下单那一刻把归属拷进明细 ———————————————————

    @Test
    @DisplayName("② 明细上的商家是下单那一刻的拷贝：商品以后换店，历史订单的归属不跟着漂")
    void orderItemCarriesTheMerchantOfItsOwnMoment() {
        long productId = probeProduct();
        long merchantId = merchantOfProduct(productId);
        jdbc.sql("UPDATE product SET stock = 30 WHERE id = :id").param("id", productId).update();

        String u = buyer("denorm");
        OrderView paid = payWithWallet(u, productId);

        assertThat(merchantOfOrderItem(paid.id(), productId)).isEqualTo(merchantId);

        // 把商品改挂到另一家店，历史明细必须纹丝不动——
        // 这正是「为什么不每次 JOIN product 现查」的理由：现查会让当年的锅算到别人头上。
        long other = jdbc.sql("SELECT id FROM merchant WHERE id <> :m ORDER BY id LIMIT 1")
                .param("m", merchantId).query(Long.class).single();
        restoredMerchantForProbe = merchantId;
        jdbc.sql("UPDATE product SET merchant_id = :other WHERE id = :id")
                .param("other", other).param("id", productId).update();

        assertThat(merchantOfProduct(productId)).as("前提：商品确实换了店").isEqualTo(other);
        assertThat(merchantOfOrderItem(paid.id(), productId))
                .as("历史明细的归属不随商品漂移").isEqualTo(merchantId);
    }

    // ——————————————————— ③ 队列按店收窄 ———————————————————

    @Test
    @DisplayName("③ 队列按店收窄：别家店的退款不出现在我的待办里，店员仍然看得到全站")
    void pendingQueueIsScopedToTheMerchantsOwnShop() {
        long productId = probeProduct();
        String owner = ownerOfProduct(productId);
        String foreign = anotherShopOwner(owner);

        String u = buyer("queue");
        OrderView paid = payWithWallet(u, productId);
        fulfillment.ship(SubOrders.only(paid), "admin", "SF-SCOPE-1");
        RefundView queued = refunds.apply(paid.id(), SubOrders.only(paid), u, null, "WRONG_ITEM", "发错款式");
        assertThat(queued.route()).as("用例前提：这笔在等商家").isEqualTo(RefundView.ROUTE_MERCHANT_APPROVAL);

        List<String> mine = refunds.pendingQueueFor(owner, 20).stream().map(RefundView::id).toList();
        List<String> theirs = refunds.pendingQueueFor(foreign, 20).stream().map(RefundView::id).toList();
        List<String> staff = refunds.pendingQueueFor("admin", 20).stream().map(RefundView::id).toList();

        assertThat(mine).as("自己的店：这笔必须在我的待办里").contains(queued.id());
        assertThat(theirs).as("别家店：这笔不该出现在 %s 的待办里", foreign).doesNotContain(queued.id());
        assertThat(staff).as("反向控制：店员看全站，隔离是收窄商家而不是蒙住运营").contains(queued.id());
    }

    // ——————————————————— ④ 批不动别家店的单 ———————————————————

    @Test
    @DisplayName("④ 商家批不动别家店的退款：抛异常，且那笔钱一分没动、状态纹丝不动")
    void approvingAnotherShopsRefundChangesNothing() {
        long productId = probeProduct();
        String owner = ownerOfProduct(productId);
        String foreign = anotherShopOwner(owner);

        String u = buyer("cross");
        OrderView paid = payWithWallet(u, productId);
        fulfillment.ship(SubOrders.only(paid), "admin", "SF-CROSS-1");
        RefundView queued = refunds.apply(paid.id(), SubOrders.only(paid), u, null, "DAMAGED", "有磕碰");
        long balanceBefore = WalletProbe.balanceCents(jdbc, u);

        // 批的人是「别家店」的店主——在加 merchant 表之前，这一步会成功放款。
        assertThatThrownBy(() -> refunds.approve(queued.id(), foreign, "我看没问题"))
                .isInstanceOf(BusinessException.class)
                .as("「存在但不是你的店」与「不存在」给同一个答复，免得把别家店退款单号的存在性泄露出去")
                .extracting(e -> ((BusinessException) e).code()).isEqualTo("NOT_FOUND");

        assertThat(statusOf(queued.id())).as("状态必须停在 PENDING_APPROVAL")
                .isEqualTo(RefundView.STATUS_PENDING_APPROVAL);
        assertThat(WalletProbe.balanceCents(jdbc, u)).as("钱一分没动").isEqualTo(balanceBefore);
        assertThat(WalletProbe.deltas(jdbc, u, "REFUND")).as("没有产生任何退款流水").isEmpty();
        assertThat(WalletProbe.reconciliationMismatch(jdbc, u)).isZero();

        // 正向对照：自己的店主批准就是一次完整放款——
        // 少了这一句，「批不动」也可能来自「审批功能整个坏了」。
        RefundView approved = refunds.approve(queued.id(), owner, "核实后同意");
        assertThat(approved.status()).isEqualTo(RefundView.STATUS_REFUNDED);
        assertThat(WalletProbe.deltas(jdbc, u, "REFUND")).isNotEmpty();
    }

    @Test
    @DisplayName("④b 驳回同样判归属：让 A 把 B 的申请驳回去，与让 A 批准 B 的退款是同一个洞的两半")
    void rejectingAnotherShopsRefundIsAlsoRejected() {
        long productId = probeProduct();
        String owner = ownerOfProduct(productId);
        String foreign = anotherShopOwner(owner);

        String u = buyer("cross-reject");
        OrderView paid = payWithWallet(u, productId);
        fulfillment.ship(SubOrders.only(paid), "admin", "SF-CROSS-2");
        RefundView queued = refunds.apply(paid.id(), SubOrders.only(paid), u, null, "OTHER", "其他原因");

        assertThatThrownBy(() -> refunds.reject(queued.id(), foreign, "不归我管"))
                .isInstanceOf(BusinessException.class);

        assertThat(statusOf(queued.id())).as("驳回没生效：它还在等真正归属的那家店")
                .isEqualTo(RefundView.STATUS_PENDING_APPROVAL);
        // 正向对照：归属方驳回就是一次真正的终结。
        assertThat(refunds.reject(queued.id(), owner, "已与买家沟通").status())
                .isEqualTo(RefundView.STATUS_REJECTED);
    }

    // ——————————————————— ⑤ 既不是店员也不是商家 ———————————————————

    @Test
    @DisplayName("⑤ 普通买家看队列：403（队列对经营侧开放这条边界不能因为改了归属就松开）")
    void buyerCannotSeeTheQueue() {
        assertThatThrownBy(() -> refunds.pendingQueueFor("student", 20))
                .isInstanceOf(BusinessException.class)
                .as("必须是 403 而不是空列表：返回空会让「没权限」与「没有待办」长成同一副样子")
                .extracting(e -> ((BusinessException) e).status().value()).isEqualTo(403);
    }

    // ——————————————————— 工具 ———————————————————

    /**
     * 一件确定的商品：刻意挑<b>不是</b> 1 号店的货。
     *
     * <p>用 1 号店的商品会让「明细拷贝了归属」这条断言蒙对——1 号店的 id 恰好是 1、
     * 加列时的临时默认值也是 1，于是「根本没拷贝」与「拷贝对了」长得一模一样。
     * 换一家店之后，这两个答案才分得开。
     */
    private long probeProduct() {
        return jdbc.sql("SELECT id FROM product WHERE merchant_id <> 1 ORDER BY id LIMIT 1")
                .query(Long.class).single();
    }

    private long merchantOfProduct(long productId) {
        return jdbc.sql("SELECT merchant_id FROM product WHERE id = :id")
                .param("id", productId).query(Long.class).single();
    }

    private String ownerOfProduct(long productId) {
        return jdbc.sql("SELECT m.owner_username FROM merchant m JOIN product p ON p.merchant_id = m.id"
                        + " WHERE p.id = :id").param("id", productId).query(String.class).single();
    }

    /** 随便另一家店的店主账号：查出来而不是写死，改一次分配公式也不影响用例。 */
    private String anotherShopOwner(String notThisOne) {
        return jdbc.sql("SELECT owner_username FROM merchant WHERE owner_username <> :u ORDER BY id LIMIT 1")
                .param("u", notThisOne).query(String.class).single();
    }

    private long merchantOfOrderItem(String orderId, long productId) {
        return jdbc.sql("SELECT merchant_id FROM order_item WHERE order_id = :o AND product_id = :p")
                .param("o", orderId).param("p", productId).query(Long.class).single();
    }

    private List<String> authoritiesOf(UserDetails account) {
        return account.getAuthorities().stream().map(GrantedAuthority::getAuthority).toList();
    }

    private void at(LocalDateTime now) {
        ZoneId zone = ZoneId.systemDefault();
        org.mockito.Mockito.when(clock.instant()).thenReturn(now.atZone(zone).toInstant());
        org.mockito.Mockito.when(clock.getZone()).thenReturn(zone);
        org.mockito.Mockito.when(clock.withZone(any())).thenReturn(clock);
    }

    private OrderView payWithWallet(String username, long productId) {
        OrderView order = facade.create(username, UUID.randomUUID().toString(),
                new CreateOrderRequest(List.of(new CreateOrderRequest.Line(productId, 1))));
        return payments.pay(order.id(), username, "DEMO_WALLET");
    }

    private String statusOf(String refundId) {
        return jdbc.sql("SELECT status FROM refund WHERE id = :id").param("id", refundId)
                .query(String.class).single();
    }

    private String buyer(String suffix) {
        buyer = "merchant-own-" + suffix;
        return buyer;
    }
}
