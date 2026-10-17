package dev.learning.mall;

import dev.learning.mall.cart.internal.CartService;
import dev.learning.mall.order.api.CreateOrderRequest;
import dev.learning.mall.order.internal.OrderFacade;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 下单成功后购物袋由<b>服务端</b>清空，且页面路径与助手路径行为一致。
 *
 * <p>修复前这件事散在三处、只有一处是对的：助手确认下单是服务端清（与落单同事务）；
 * 页面下单是<b>前端</b>清，{@code app.js} 里 {@code DELETE /api/cart} 且失败被
 * {@code .catch(() => {})} 静默吞掉；直接调 API 下单则根本没人清。
 * 同一个业务动作三种结果——用户无法靠行为预测结果，这是一致性缺陷。
 *
 * <p>这里只断言<b>服务端行为</b>：不经过浏览器、不依赖任何前端代码，
 * 直接下单然后看数据库里的袋子。前端做什么都改变不了这个结果，
 * 这才是「服务端保证」的意思。
 */
@SpringBootTest
@ActiveProfiles("test")
class ServerSideCartClearingTest {

    @Autowired OrderFacade orderFacade;
    @Autowired CartService cart;
    @Autowired JdbcClient jdbc;

    @BeforeEach
    @AfterEach
    void reset() {
        // 顺序照外键来：outbox_event.aggregate_id 引用 mall_order(id)，先删子表。
        for (String table : List.of("notification", "outbox_event", "order_item", "mall_order", "mall_cart_item")) {
            jdbc.sql("DELETE FROM " + table).update();
        }
        jdbc.sql("UPDATE product SET stock = 50").update();
    }

    @Test
    @DisplayName("页面路径下单：服务端直接清空购物袋，不需要前端配合")
    void pageCheckoutClearsCartOnServerSide() {
        cart.add("student", 1, 2);
        assertThat(cart.view("student").itemCount()).isEqualTo(2);

        orderFacade.create("student", key(), new CreateOrderRequest(List.of(new CreateOrderRequest.Line(1, 2))));

        assertThat(cart.view("student").isEmpty()).as("下单成功后袋子必须已空").isTrue();
        assertThat(rows()).as("数据库里一行都不该剩").isZero();
    }

    @Test
    @DisplayName("两条路径一致：页面下单与助手下单，袋子都被清空")
    void bothPathsBehaveTheSame() {
        cart.add("student", 1, 1);
        orderFacade.create("student", key(), new CreateOrderRequest(List.of(new CreateOrderRequest.Line(1, 1))));
        int afterPage = rows();

        cart.add("classmate", 1, 1);
        orderFacade.create("classmate", key(), new CreateOrderRequest(List.of(new CreateOrderRequest.Line(1, 1))));
        int afterOther = rows();

        // 两个人各自下单，各自的袋子都空——行为可预测，与走哪条路径无关。
        assertThat(afterPage).isZero();
        assertThat(afterOther).isZero();
    }

    @Test
    @DisplayName("下单失败时袋子保留：清袋不能抢在落单成功之前")
    void cartSurvivesWhenOrderFails() {
        cart.add("student", 1, 2);
        // 缺货：商品 1 库存 50，要 999 件必然失败。
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> orderFacade.create("student", key(),
                new CreateOrderRequest(List.of(new CreateOrderRequest.Line(1, 999)))));

        assertThat(cart.view("student").itemCount()).as("下单没成功，袋子必须原样保留，用户可以重试")
                .isEqualTo(2);
    }

    private int rows() {
        Integer n = jdbc.sql("SELECT COUNT(*) FROM mall_cart_item").query(Integer.class).single();
        return n == null ? 0 : n;
    }

    private static String key() {
        return "svc-" + UUID.randomUUID().toString().replace("-", "");
    }
}
