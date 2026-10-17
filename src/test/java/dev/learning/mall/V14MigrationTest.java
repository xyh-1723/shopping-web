package dev.learning.mall;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

/**
 * V14 迁移的结构性验收：库存变动流水（P1-1）与金额快照表（P1-5）。
 *
 * <p>这一轮<b>只验「库」不验「代码」</b>：{@code price_quote} 的读写实现是 T03 的事，
 * 这里只保证表与约束在迁移完成后确实存在——否则 T03 写好了代码，
 * 却可能跑在一个没有约束的库上，而缺少约束的库会把「金额算错了」悄悄吞掉。
 *
 * <p>三条断言各有分工，缺一不可：
 * <ul>
 *   <li>约束<b>存在</b>：没有约束的表只是一张草稿纸。</li>
 *   <li>约束是<b>显式命名</b>的：H2 给内联 CHECK 起的 {@code CONSTRAINT_x} 在另一个
 *       库实例里就是另一个名字，后续迁移脚本一旦引用就会失配（V10 的教训）。</li>
 *   <li>建账回填<b>可重复执行</b>：测试库每次重建都要重放全部迁移，
 *       多环境部署同理；重复建账会把「现货 == 流水之和」这条恒等式打破。</li>
 * </ul>
 */
@SpringBootTest
@ActiveProfiles("test")
class V14MigrationTest {

    @Autowired JdbcClient jdbc;

    @Test
    @DisplayName("库存流水的四条约束都在，且是显式命名（没有 CONSTRAINT_x 这类自动名）")
    void stockLedgerConstraintsAreNamedAndPresent() {
        Set<String> names = constraintNames("STOCK_LEDGER");

        assertThat(names).contains("CK_LEDGER_REASON", "CK_LEDGER_DELTA_NONZERO",
                "CK_LEDGER_STOCK_NONNEG", "CK_LEDGER_ARITHMETIC");
        // 这条断言守的是「写法」而不是「结果」：将来有人图省事改回内联 CHECK，
        // 约束依然生效、名字却变成自动名，只有这一句会拦住他。
        assertThat(names).noneMatch(name -> name.startsWith("CONSTRAINT_"));

        assertThat(columnsOf("STOCK_LEDGER")).contains("PRODUCT_ID", "DELTA", "STOCK_BEFORE",
                "STOCK_AFTER", "REASON", "ACTOR", "ORDER_ID", "CREATED_AT");
    }

    @Test
    @DisplayName("金额快照表的结构与应付金额约束到位，quote_id 已挂到待确认动作上")
    void priceQuoteTableIsReady() {
        assertThat(columnsOf("PRICE_QUOTE")).contains("ID", "USERNAME", "LINES_JSON",
                "SUBTOTAL", "DISCOUNT", "SHIPPING_FEE", "PAY_AMOUNT", "CREATED_AT", "EXPIRES_AT");
        assertThat(constraintNames("PRICE_QUOTE")).contains("CK_QUOTE_PAYABLE");
        assertThat(constraintNames("PRICE_QUOTE")).noneMatch(n -> n.startsWith("CONSTRAINT_"));

        // 助手路径靠这一列把快照和待确认动作绑在一起（T03 才会写它，这里只验列存在）。
        assertThat(columnsOf("ASSISTANT_PENDING_ACTION")).contains("QUOTE_ID");
    }

    @Test
    @DisplayName("应付金额必须对得上：pay_amount = subtotal - discount + shipping_fee")
    void quotePayableIsEnforcedByDatabase() {
        // 39 元商品、减 10、运费 8，应付应为 37；写成 29 就是在替用户多减一笔。
        assertThatThrownBy(() -> insertQuote("quote-bad", "39.00", "10.00", "8.00", "29.00"))
                .hasMessageContaining("CK_QUOTE_PAYABLE");

        assertThat(insertQuote("quote-ok", "39.00", "10.00", "8.00", "37.00")).isEqualTo(1);
        jdbc.sql("DELETE FROM price_quote WHERE id IN ('quote-bad','quote-ok')").update();
    }

    @Test
    @DisplayName("期初建账可重复执行：再跑一次回填，影响 0 行")
    void baselineBackfillIsIdempotent() {
        // 前提：每个有货的商品都已经有一条流水（迁移完成时建立，或后来被补过账）。
        // 先确认前提，再断言「回填影响 0 行」——不看前提直接断言 0 行，
        // 一旦有商品确实缺基线，这条测试会红得莫名其妙。
        long missingBaseline = jdbc.sql("SELECT COUNT(*) FROM product p"
                        + " WHERE p.stock > 0 AND NOT EXISTS"
                        + " (SELECT 1 FROM stock_ledger l WHERE l.product_id = p.id)")
                .query(Long.class).single();
        assertThat(missingBaseline).isZero();

        // 下面是 V14 里那段回填的原文。第二次执行必须一行都不插。
        int rows = jdbc.sql("INSERT INTO stock_ledger(product_id, delta, stock_before, stock_after, reason, actor)"
                        + " SELECT p.id, p.stock, 0, p.stock, 'RESTOCK', 'flyway-v14'"
                        + "   FROM product p"
                        + "  WHERE p.stock > 0"
                        + "    AND NOT EXISTS (SELECT 1 FROM stock_ledger l WHERE l.product_id = p.id)")
                .update();
        assertThat(rows).as("重复回放迁移不能重复建账，否则对账恒等式会被打破").isZero();

        // 种子商品（1-6）在建账那一刻都有库存，因此各有一条 flyway-v14 的基线。
        long baseline = jdbc.sql("SELECT COUNT(*) FROM stock_ledger"
                        + " WHERE actor = 'flyway-v14' AND product_id BETWEEN 1 AND 6")
                .query(Long.class).single();
        assertThat(baseline).isEqualTo(6);
    }

    private int insertQuote(String id, String subtotal, String discount, String fee, String payable) {
        return jdbc.sql("INSERT INTO price_quote"
                        + "(id, username, lines_json, subtotal, discount, shipping_fee, pay_amount, expires_at)"
                        + " VALUES (:id, 'tester', '[]', :subtotal, :discount, :fee, :payable, CURRENT_TIMESTAMP)")
                .param("id", id)
                .param("subtotal", new java.math.BigDecimal(subtotal))
                .param("discount", new java.math.BigDecimal(discount))
                .param("fee", new java.math.BigDecimal(fee))
                .param("payable", new java.math.BigDecimal(payable))
                .update();
    }

    /**
     * 查某张表上 CHECK 约束的名字（H2 里未加引号的标识符都是大写）。
     *
     * <p>只看 CHECK 是刻意的：主键与外键的自动名（{@code CONSTRAINT_2x}）没人引用，
     * 无害；而 CHECK 的名字会出现在报错信息里，也会被后续迁移脚本引用，
     * 只有它必须稳定——项目约定正是为此把 CHECK 一律写成显式命名。
     */
    private Set<String> constraintNames(String table) {
        List<String> names = jdbc.sql("SELECT constraint_name FROM information_schema.table_constraints"
                        + " WHERE table_schema = 'PUBLIC' AND table_name = :t AND constraint_type = 'CHECK'")
                .param("t", table).query(String.class).list();
        return new HashSet<>(names);
    }

    private Set<String> columnsOf(String table) {
        List<String> names = jdbc.sql("SELECT column_name FROM information_schema.columns"
                        + " WHERE table_schema = 'PUBLIC' AND table_name = :t")
                .param("t", table).query(String.class).list();
        return new HashSet<>(names);
    }
}
