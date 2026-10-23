package dev.learning.mall.catalog.internal;

import dev.learning.mall.catalog.api.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class ProductRepository {

    /**
     * 查询列固定列举，不写 {@code SELECT *}：子查询里多带一列（比如排名）就会让
     * {@link ProductView} 的按名映射多出一个对不上的字段，届时报错离出错点很远。
     */
    private static final String COLUMNS =
            "id, name, description, category, illustration, price, stock";

    private final JdbcClient jdbc;
    ProductRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    /**
     * 分页查商品。WHERE 与 ORDER BY 的两处拼装都在本类内完成，SQL 文本与参数绑定
     * 由同一组条件驱动（见 {@link #where} 与 {@link #bind}）——只改一处会立刻报
     * "参数未绑定"，不会静默查错。
     */
    ProductPage search(ProductQuery query) {
        String where = where(query);
        long total = bind(jdbc.sql("SELECT COUNT(*) FROM product" + where), query)
                .query(Long.class).single();
        String sql = "SELECT " + COLUMNS + " FROM product" + where
                + " ORDER BY " + orderBy(query.sort()) + " LIMIT :limit OFFSET :offset";
        List<ProductView> items = bind(jdbc.sql(sql), query)
                .param("limit", query.size())
                .param("offset", query.offset())
                .query(ProductView.class).list();
        return new ProductPage(items, total, query.page(), query.size());
    }

    /**
     * 跨分类均匀取样：每个分类最多 {@code perCategory} 件（分类内部按价格升序）。
     *
     * <p>用窗口函数把"每类只取前几名"下推到数据库，而不是把全表拉进 Java 再分组——
     * 后者在 5 万件时是要先把 5 万个对象装进堆里，再扔掉 49988 个。
     * H2 2.3 与 MySQL 8 都支持 {@code ROW_NUMBER() OVER (PARTITION BY ...)}。
     *
     * <p>排序用 {@code rn, price, id}：先保证"每个分类都有代表"（名次靠前的先出），
     * 同名次内按价格升序，最后用 id 兜底让结果稳定可复现。
     */
    List<ProductView> sampleAcrossCategories(int perCategory, int limit) {
        List<ProductView> picked = jdbc.sql("""
                        SELECT id, name, description, category, illustration, price, stock FROM (
                            SELECT p.id, p.name, p.description, p.category, p.illustration,
                                   p.price, p.stock,
                                   ROW_NUMBER() OVER (PARTITION BY p.category ORDER BY p.price, p.id) AS rn
                              FROM product p
                        ) ranked
                        WHERE rn <= :perCategory
                        ORDER BY rn, price, id
                        LIMIT :limit""")
                .param("perCategory", perCategory)
                .param("limit", limit)
                .query(ProductView.class).list();
        if (picked.size() >= limit || picked.isEmpty()) return picked;
        // 补足：分类之间数量不均衡时（数码只有 1 件、生活有 300 件），名额会空出来。
        // 空着不补等于白白少给模型几条结果，所以按价格升序把坑填满。
        List<Long> seen = picked.stream().map(ProductView::id).toList();
        JdbcClient.StatementSpec filler = jdbc.sql("SELECT " + COLUMNS + " FROM product"
                + " WHERE id NOT IN (:seen) ORDER BY price, id LIMIT :limit");
        List<ProductView> rest = filler.param("seen", seen).param("limit", limit - picked.size())
                .query(ProductView.class).list();
        List<ProductView> all = new ArrayList<>(picked);
        all.addAll(rest);
        return List.copyOf(all);
    }

    /**
     * 低库存告警。总数与明细<b>用两条 SQL 分别取</b>：明细必须带 {@code LIMIT}
     * （结果要进模型上下文，超长会被截断），而总数必须<b>不受截断影响</b>——
     * 用同一条 SQL 取 {@code COUNT(*)} 只会得到"这一页有几件"，那是假总数。
     */
    LowStockReport lowStock(double threshold, int limit) {
        long total = jdbc.sql("SELECT COUNT(*) FROM product WHERE stock < :threshold")
                .param("threshold", threshold).query(Long.class).single();
        List<ProductView> items = jdbc.sql("SELECT " + COLUMNS
                        + " FROM product WHERE stock < :threshold ORDER BY stock, id LIMIT :limit")
                .param("threshold", threshold).param("limit", limit)
                .query(ProductView.class).list();
        return new LowStockReport(total, items);
    }

    Optional<ProductView> find(long id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM product WHERE id = :id")
                .param("id", id).query(ProductView.class).optional();
    }
    int deduct(long id, int quantity) {
        // 条件判断和扣减是同一条 SQL；不要先查库存再无条件扣减。
        return jdbc.sql("UPDATE product SET stock = stock - :qty WHERE id = :id AND stock >= :qty")
                .param("qty", quantity).param("id", id).update();
    }
    void restore(long id, int quantity) {
        jdbc.sql("UPDATE product SET stock = stock + :qty WHERE id = :id").param("qty", quantity).param("id", id).update();
    }
    void updatePrice(long id, java.math.BigDecimal price) {
        jdbc.sql("UPDATE product SET price = :price WHERE id = :id").param("price", price).param("id", id).update();
    }
    void setStock(long id, int stock) {
        jdbc.sql("UPDATE product SET stock = :stock WHERE id = :id").param("stock", stock).param("id", id).update();
    }

    /** 拼 WHERE 子句（无条件时返回空串）。条件集合必须与 {@link #bind} 完全一致。 */
    private static String where(ProductQuery q) {
        List<String> conditions = new ArrayList<>();
        if (q.keyword() != null)
            conditions.add("(LOWER(name) LIKE :kw ESCAPE '!' OR LOWER(description) LIKE :kw ESCAPE '!')");
        if (q.category() != null) conditions.add("category = :cat");
        if (q.maxPrice() != null) conditions.add("price <= :maxPrice");
        if (q.inStockOnly()) conditions.add("stock > 0");
        return conditions.isEmpty() ? "" : " WHERE " + String.join(" AND ", conditions);
    }

    /** 绑定 {@link #where} 里出现的参数。两处条件判断一一对应，缺一个会直接抛"参数未绑定"。 */
    private static JdbcClient.StatementSpec bind(JdbcClient.StatementSpec spec, ProductQuery q) {
        if (q.keyword() != null) spec = spec.param("kw", likePattern(q.keyword()));
        if (q.category() != null) spec = spec.param("cat", q.category());
        if (q.maxPrice() != null) spec = spec.param("maxPrice", q.maxPrice());
        return spec;
    }

    /**
     * 把用户输入变成 LIKE 模式：两侧加 {@code %} 做包含匹配，同时转义 {@code % _} 和转义符本身。
     *
     * <p>不转义的后果是用户搜 "50%" 会变成通配符、搜 "A_B" 会匹配 "AXB"——
     * 这不是安全问题，是<b>搜索结果莫名其妙</b>，而且只在包含这些字符时才犯病，最难查。
     * 转义符选 {@code !} 而不是反斜杠：反斜杠在 MySQL 字符串字面量里本身是转义符，
     * 换成 {@code !} 就不用去猜两种方言对 {@code '\\'} 的解释是否一致。
     */
    private static String likePattern(String keyword) {
        String escaped = keyword.toLowerCase()
                .replace("!", "!!").replace("%", "!%").replace("_", "!_");
        return "%" + escaped + "%";
    }

    private static String orderBy(ProductSort sort) {
        return switch (sort) {
            case PRICE_ASC -> "price, id";
            case PRICE_DESC -> "price DESC, id";
            case ID_ASC -> "id";
        };
    }

    /**
     * 取当前库存并加行锁：保证「读到的前值」与接下来的写入之间无人插队。
     *
     * <p>为什么必须先锁再读：流水要记 {@code stock_before}，而这个前值只有在「读与写中间
     * 没有别人插队」时才成立。若改成「写完再读一次」，另一个事务可能在中间改过库存，
     * 于是 {@code before + delta ≠ after}，{@code ck_ledger_arithmetic} 会把整笔事务打回——
     * 那是一次响亮的误报：库存明明是对的，只是前值记脏了。
     *
     * <p>调用方必须已经在事务里（reserve / release 标了 MANDATORY，updateStock 自带事务）。
     */
    int stockForUpdate(long id) {
        return jdbc.sql("SELECT stock FROM product WHERE id = :id FOR UPDATE")
                .param("id", id).query(Integer.class).single();
    }
}
