package dev.learning.mall.proactive.internal;

import dev.learning.mall.cart.api.CartView;
import dev.learning.mall.cart.internal.CartService;
import dev.learning.mall.catalog.api.ProductCatalog;
import dev.learning.mall.catalog.api.ProductView;
import dev.learning.mall.order.api.OrderInsights;
import dev.learning.mall.order.api.OrderView;
import dev.learning.mall.proactive.api.RelatedProduct;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 关联推荐：从「用户已有的东西」推出「他可能还想要什么」。
 *
 * <p><b>两条路径，先准后广</b>：
 * <ol>
 *   <li><b>共同购买</b>（准）：买过 X 的人还一起买了什么。这是真实的行为信号，
 *       比任何「猜你喜欢」都可靠——它不需要理解商品，只需要统计。</li>
 *   <li><b>同类补充</b>（广）：没有足够共现数据时，退回到「同一个品类里你还没买过的」。
 *       便宜、可解释，代价是精度低。</li>
 * </ol>
 * 新用户没有历史，两条路径都空——此时退回到「最近卖得好的」，
 * 至少不要给出一个空白页。三级降级是推荐系统的常态，不是例外。
 *
 * <p><b>每条推荐都必须带理由</b>（{@link RelatedProduct#reason()}）。没有理由的推荐
 * 看起来像广告位，有理由的推荐看起来像帮忙。这个字段是「导购」和「货架」的分界线。
 *
 * <p>这里的共同购买统计跨了订单模块的表——这是一次有意的取舍：它是只读的聚合分析，
 * 放在哪个模块都会有人问「这算谁的职责」，而放在推荐侧至少保证了
 * 「读的是别人的表，写只写自己的表」。
 */
@Service
public class RelatedRecommendationService {

    /** 只扫最近这么多笔已支付订单，避免一个老用户把全站历史都拉进来。 */
    private static final int HISTORY_ORDERS = 20;
    private static final int MAX_SEEDS = 12;
    private static final int DEFAULT_LIMIT = 3;

    private static final String REASON_CO_PURCHASE = "买过同类的人常一起加它";
    // 措辞刻意避开「关注」二字：这里的种子是购物袋与历史订单，
    // 而「关注商品」在本期已是一个专有功能（降价/补货提醒）。同词不同义会让人以为推荐看了关注列表。
    private static final String REASON_SAME_CATEGORY = "和你近期挑的品类相同";
    private static final String REASON_POPULAR = "最近买的人不少，可以看看";

    private final JdbcClient jdbc;
    private final CartService cart;
    private final OrderInsights orders;
    private final ProductCatalog catalog;

    public RelatedRecommendationService(JdbcClient jdbc, CartService cart,
                                        OrderInsights orders, ProductCatalog catalog) {
        this.jdbc = jdbc;
        this.cart = cart;
        this.orders = orders;
        this.catalog = catalog;
    }

    /**
     * 推荐若干件商品。整个方法只读，用只读事务包住是为了让「种子 → 共现 → 同类」
     * 这几条查询看到同一个快照，不会因为中途有人下单而推出自相矛盾的结果。
     */
    @Transactional(readOnly = true)
    public List<RelatedProduct> recommend(String username, Integer limit) {
        int size = limit == null || limit <= 0 ? DEFAULT_LIMIT : Math.min(limit, 5);
        Set<Long> seeds = seedsOf(username);

        List<RelatedProduct> result = new ArrayList<>();
        Set<Long> picked = new LinkedHashSet<>();

        if (!seeds.isEmpty()) {
            addCoPurchase(result, picked, seeds, size, username);
            if (result.size() < size) addSameCategory(result, picked, seeds, size, username);
        } else {
            // 没有历史就没有个性化可言；此时「不空手而归」比「精准」重要。
            addPopular(result, picked, size, username);
        }
        return result;
    }

    /** 兴趣来源：购物袋里的 + 最近已支付订单里的商品。两者都是「明确表达过兴趣」的证据。 */
    private Set<Long> seedsOf(String username) {
        Set<Long> seeds = new LinkedHashSet<>();
        CartView view = cart.view(username);
        view.items().forEach(item -> seeds.add(item.productId()));
        List<OrderView> history = orders.ordersOf(username);
        int scanned = 0;
        for (OrderView order : history) {
            if (!"PAID".equals(order.status())) continue;
            if (scanned++ >= HISTORY_ORDERS) break;
            order.items().forEach(item -> seeds.add(item.productId()));
        }
        return seeds.stream().limit(MAX_SEEDS).collect(Collectors.toCollection(LinkedHashSet::new));
    }

    /** 路径 A：共同购买。买过我的商品的人，还一起买了什么。 */
    private void addCoPurchase(List<RelatedProduct> out, Set<Long> picked, Set<Long> seeds,
                               int size, String username) {
        List<Map<String, Object>> rows = jdbc.sql("""
                        SELECT oi2.product_id AS pid, COUNT(*) AS freq
                        FROM order_item oi1
                        JOIN mall_order o1 ON o1.id = oi1.order_id AND o1.status = 'PAID'
                        JOIN order_item oi2 ON oi2.order_id = oi1.order_id AND oi2.product_id <> oi1.product_id
                        WHERE oi1.product_id IN (:seeds)
                        GROUP BY oi2.product_id
                        ORDER BY freq DESC, oi2.product_id""")
                .param("seeds", new ArrayList<>(seeds))
                .query((rs, rowNum) -> Map.of("pid", (Object) rs.getLong("pid"),
                        "freq", (Object) rs.getInt("freq"))).list();
        for (Map<String, Object> row : rows) {
            if (out.size() >= size) return;
            long pid = (Long) row.get("pid");
            if (seeds.contains(pid) || !picked.add(pid)) continue;
            ProductView product = productOrNull(pid);
            if (product == null || product.stock() <= 0) continue;
            out.add(toRelated(product, REASON_CO_PURCHASE));
        }
    }

    /** 路径 B：同类补充。种子商品所属品类里，还没买过/没在袋子里、且有货的。 */
    private void addSameCategory(List<RelatedProduct> out, Set<Long> picked, Set<Long> seeds,
                                 int size, String username) {
        Set<String> categories = seeds.stream().map(this::productOrNull).filter(p -> p != null)
                .map(ProductView::category).filter(c -> c != null && !c.isBlank())
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (categories.isEmpty()) return;
        List<String> ordered = new ArrayList<>(categories);
        List<Long> seedList = new ArrayList<>(seeds);
        List<ProductView> candidates = jdbc.sql("""
                        SELECT id, name, description, category, illustration, price, stock FROM product
                        WHERE category IN (:cats) AND stock > 0 AND id NOT IN (:seeds)
                        ORDER BY price""")
                .param("cats", ordered).param("seeds", seedList)
                .query(ProductView.class).list();
        for (ProductView product : candidates) {
            if (out.size() >= size) return;
            if (!picked.add(product.id())) continue;
            out.add(toRelated(product, REASON_SAME_CATEGORY));
        }
    }

    /** 兜底：没有历史时给最热销的，而不是给一个空列表。 */
    private void addPopular(List<RelatedProduct> out, Set<Long> picked, int size, String username) {
        List<ProductView> candidates = jdbc.sql("""
                        SELECT p.id, p.name, p.description, p.category, p.illustration, p.price, p.stock
                        FROM product p LEFT JOIN order_item i ON i.product_id = p.id
                        LEFT JOIN mall_order o ON o.id = i.order_id AND o.status = 'PAID'
                        WHERE p.stock > 0
                        GROUP BY p.id, p.name, p.description, p.category, p.illustration, p.price, p.stock
                        ORDER BY COUNT(o.id) DESC, p.id
                        LIMIT :limit""")
                .param("limit", size).query(ProductView.class).list();
        for (ProductView product : candidates) {
            if (!picked.add(product.id())) continue;
            out.add(toRelated(product, REASON_POPULAR));
        }
    }

    private ProductView productOrNull(long id) {
        try {
            return catalog.get(id);
        } catch (RuntimeException e) {
            // 商品已下架：推荐里静默跳过，别让一条脏数据把整个推荐打挂。
            return null;
        }
    }

    private static RelatedProduct toRelated(ProductView product, String reason) {
        return new RelatedProduct(product.id(), product.name(), product.illustration(),
                product.category(), product.price(), product.stock(), reason);
    }
}
