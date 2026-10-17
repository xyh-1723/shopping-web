package dev.learning.mall;

import dev.learning.mall.cart.api.CartView;
import dev.learning.mall.cart.internal.CartService;
import dev.learning.mall.order.api.CreateOrderRequest;
import dev.learning.mall.order.api.OrderView;
import dev.learning.mall.order.internal.OrderFacade;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.*;

/**
 * <b>跨模块守护：金额只有一个口径。</b>
 *
 * <p>这个类的存在本身就是一次教训。项目里最漂亮的一段架构设计是「用结构消除事故，
 * 而不是用提示词叮嘱模型别算错」——购物车页面、下单接口、助手对话三处共用同一个定价器，
 * 于是「同一车货算出三个价」在结构上不可能发生。
 *
 * <p>而事实是：下单接口<b>从来没调用过它</b>，自己裸求和，把满减与运费全丢了。
 * 更值得警惕的是当时 <b>257 个测试全绿</b>——因为购物袋测试与订单测试各测各的，
 * <b>没有任何一条断言同时走过两条链路</b>。
 *
 * <p>所以这个类只做一件事：<b>对同一组行，同时走两条链路，断言它们的金额差值为 0。</b>
 * 单模块内再严格的反向验证，也照不到模块与模块之间那条边。
 */
@SpringBootTest
@ActiveProfiles("test")
class CheckoutAmountConsistencyTest {

    @Autowired CartService cart;
    @Autowired OrderFacade facade;
    @Autowired JdbcClient jdbc;

    @AfterEach
    void reset() {
        for (String table : List.of("notification", "outbox_event", "order_item", "mall_order",
                "mall_cart_item")) {
            jdbc.sql("DELETE FROM " + table).update();
        }
        jdbc.sql("UPDATE product SET stock = 50").update();
    }

    // ——————————————————— 核心：两条链路必须给出同一个数 ———————————————————

    @Test
    @DisplayName("未达满减门槛且含运费：购物袋 86，订单也必须是 86（差值为 0）")
    void cartAndOrderAgreeWhenShippingApplies() {
        // 商品1（¥39）×2 = 小计 78；78 < 99 → 无满减、不包邮 → 78 + 运费 8 = 86
        assertCartAndOrderAgree(List.of(line(1, 2)), "86.00");
    }

    @Test
    @DisplayName("命中满减且包邮：购物袋 174，订单也必须是 174")
    void cartAndOrderAgreeWhenDiscountApplies() {
        // 商品5（¥199）×1 → 满 199 减 25 → 174；174 >= 99 → 包邮
        assertCartAndOrderAgree(List.of(line(5, 1)), "174.00");
    }

    @Test
    @DisplayName("满减后跌破包邮门槛：两边仍必须一致（这是最容易算错的一类场景）")
    void cartAndOrderAgreeWhenDiscountDropsBelowFreeShipping() {
        // 商品2（¥29）×2 + 商品4（¥49）×1 = 小计 107
        // 107 >= 99 → 减 10 → 97；而 97 < 99 → 不再包邮 → 97 + 8 = 105
        // 这正是「用了满减反而跌破包邮线」——两条链路只要有一处算错顺序就会不一致。
        assertCartAndOrderAgree(List.of(line(2, 2), line(4, 1)), "105.00");
    }

    // ——————————————————— 落库后的不变量 ———————————————————

    @Test
    @DisplayName("落库后恒等：pay_amount = subtotal − discount + shipping_fee")
    void payableMatchesBreakdownInDatabase() {
        cart.add("student", 1, 2);
        facade.create("student", key(), request());

        // 直接用 SQL 查，绕开一切 Java 侧的换算——它守的是「库里的数自洽」。
        assertThat(jdbc.sql("SELECT COUNT(*) FROM mall_order "
                        + "WHERE pay_amount <> subtotal - discount + shipping_fee")
                .query(Integer.class).single()).isZero();
    }

    @Test
    @DisplayName("历史列 total 恒等于 pay_amount（它只为兼容保留，不得漂移）")
    void totalAlwaysEqualsPayAmount() {
        cart.add("student", 1, 2);
        facade.create("student", key(), request());
        cart.add("student", 5, 1);
        facade.create("student", key(), request());

        // 这两列由 OrderRepository.insert 用同一个值写入、写后不可变。
        // 一旦有人在别处补写其中一列，这条断言立刻变红。
        assertThat(jdbc.sql("SELECT COUNT(*) FROM mall_order WHERE total <> pay_amount")
                .query(Integer.class).single()).isZero();
    }

    // ——————————————————— 助手口径也必须一致 ———————————————————

    @Test
    @DisplayName("订单视图暴露的四项金额与购物袋逐项一致")
    void orderViewExposesTheSameBreakdown() {
        cart.add("student", 2, 2);
        cart.add("student", 4, 1);
        CartView view = cart.view("student");
        OrderView order = facade.create("student", key(), request());

        assertThat(order.subtotal()).isEqualByComparingTo(view.subtotal());
        assertThat(order.discount()).isEqualByComparingTo(view.discount());
        assertThat(order.shippingFee()).isEqualByComparingTo(view.shippingFee());
        assertThat(order.payAmount()).isEqualByComparingTo(view.total());
        assertThat(order.payAmount()).isEqualByComparingTo(BigDecimal.valueOf(105).setScale(2));
    }

    // ——————————————————— 辅助 ———————————————————

    /** 同一组行先问购物袋、再走下单，断言两个口径<b>差值为 0</b>。 */
    private void assertCartAndOrderAgree(List<CreateOrderRequest.Line> lines, String expectedPayable) {
        for (CreateOrderRequest.Line line : lines) cart.add("student", line.productId(), line.quantity());
        CartView view = cart.view("student");
        OrderView order = facade.create("student", key(), new CreateOrderRequest(lines));

        // 核心断言：两个口径不得有任何差值。
        assertThat(order.payAmount()).isEqualByComparingTo(view.total());
        // 顺带钉住具体金额：它让「规则变了」这件事显形，而不是悄悄改掉一个数。
        assertThat(order.payAmount()).isEqualByComparingTo(expectedPayable);
        cart.clear("student");
    }

    private static CreateOrderRequest.Line line(long productId, int quantity) {
        return new CreateOrderRequest.Line(productId, quantity);
    }

    private CreateOrderRequest request() {
        return new CreateOrderRequest(cart.orderLines("student"));
    }

    private static String key() {
        return "ck-" + java.util.UUID.randomUUID().toString().replace("-", "");
    }
}
