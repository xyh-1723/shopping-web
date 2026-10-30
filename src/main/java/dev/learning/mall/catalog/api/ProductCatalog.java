package dev.learning.mall.catalog.api;

import java.math.BigDecimal;
import java.util.List;

/** 商品模块的公开边界：订单只依赖此接口，不访问商品 Repository。 */
public interface ProductCatalog {

    /**
     * 分页查商品。<b>这是唯一的列表入口</b>——没有"取全部"的方法。
     *
     * <p>为什么不允许取全部：目录只有 506 件时 {@code SELECT * FROM product} 无害，
     * 实测扩到 1006 件时 {@code GET /api/products} 的响应就从 116KB 涨到 227KB，
     * 线性外推到 5 万件是 11MB、50 万件是 113MB——而绝大多数调用方（货架首屏、
     * 助手搜索）只想要前十几件。把"必须有上限"写进接口签名，比指望每个调用方
     * 自己记得截断可靠。
     *
     * @return 本页数据 + <b>命中总数</b>（不受分页影响，前端靠它算页码）
     */
    ProductPage search(ProductQuery query);

    /**
     * 按店收窄的分页查询：只看 {@code merchantId} 这一家店的货。
     *
     * <p>为什么单独一个方法，而不是给 {@link ProductQuery} 加一个 {@code merchantId} 字段：
     * 那个查询对象被公开货架与购物助手共用，一旦脸上多出「店」，调用方就会开始
     * 从请求参数里填它——而「你是谁家的店」只能由登录账号推导，
     * 不能由调用方声明。作为参数传入，语义就只剩「服务器算出来的那个店」。
     */
    ProductPage searchOfMerchant(long merchantId, ProductQuery query);

    /**
     * 一家店的库存概览（商品种类数 / 库存总量 / 售罄数）。
     *
     * <p>它和 {@link #searchOfMerchant} 是两件事：概览要覆盖<b>整家店</b>，
     * 而分页查询只覆盖当前这一页。用当前页去累加得出「本店库存总量」会得到一个
     * 随翻页变化的数字——那不是概览，是错觉。
     */
    MerchantStockSummary stockSummaryOfMerchant(long merchantId);

    /**
     * 不带任何条件地"逛全店"：每个分类最多 {@code perCategory} 件，总共不超过 {@code limit} 件。
     *
     * <p>为什么不能直接用 {@link #search} 按价格升序截前 N 件：那只等于告诉用户
     * "本店最值得展示的就是最便宜的 N 件"。分类间商品数量并不均衡（数码 20 件、
     * 生活 3000 件），按名次轮流取才能保证小分类也出代表。
     */
    List<ProductView> sampleAcrossCategories(int perCategory, int limit);

    /**
     * 库存低于阈值的商品。
     *
     * <p>独立成方法而不是用 {@link #search} 表达，是因为它需要的是
     * "{@code stock < :threshold} 按库存升序"，而 {@link ProductQuery} 里没有
     * "按库存排序"这个语义——硬塞进去会让查询对象变成一堆布尔开关。
     */
    LowStockReport lowStock(double threshold, int limit);

    ProductView get(long id);

    /**
     * 按一批编号取商品，<b>一次查询</b>。返回顺序不保证，调用方自己按需排列；
     * 不存在的编号直接缺席（不抛异常），因为批量场景下「某个编号已下架」是常态，
     * 让一条脏数据把整批打挂不合理。
     *
     * <p>为什么需要它：{@link #get(long)} 放在循环里就是 N+1——推荐服务曾对
     * 每个种子商品各查一次，种子有 10 个就是 10 条 SQL。批量取把条数压成 1。
     */
    List<ProductView> findByIds(List<Long> ids);

    /**
     * 预占库存（下单时）。
     *
     * @param actor   谁动的（下单者用户名），进流水 {@code actor} 列
     * @param orderId 关联订单号，进流水 {@code order_id} 列；非订单场景传 {@code null}
     */
    void reserve(long productId, int quantity, String actor, String orderId);

    /**
     * 归还库存（取消 / 超时关单时）。参数含义同 {@link #reserve}。
     */
    void release(long productId, int quantity, String actor, String orderId);

    /**
     * 归还库存（退款成功后）。参数含义同 {@link #reserve}。
     *
     * <p>与 {@link #release} 的差异只有一个：<b>流水记成 {@code REFUND} 而不是 {@code RELEASE}</b>。
     * 为什么要一个独立的方法而不是让 {@link #release} 多收一个 {@code reason} 参数：
     * 交给调用方传，就等于允许退款路径传 {@code 'RELEASE'} 把售后退货伪装成弃单释放，
     * 于是「这笔订单还过货没有」这条幂等判据失效（退款方案 §5）。
     * 固定 reason 之后，两类归还仍然收敛在同一个写节点 {@code ProductService} 上。
     *
     * @param actor   谁退的这笔钱（退 AUTO 时是 {@code SYSTEM}，商家批准时是商家账号）
     */
    void returnStock(long productId, int quantity, String actor, String orderId);

    /**
     * 改价（仅店员可用，权限在 Web 层与 SecurityConfig 双重限制）。
     *
     * <p>放在商品模块而不是运营后台，是因为价格<b>属于商品</b>：改价要改的是商品表的那一列，
     * 谁都可以改的话，价格就会变成「谁都能写、没人负责」的字段。
     */
    ProductView updatePrice(long productId, BigDecimal price);

    /**
     * 改库存（上架 / 补货）。同样仅店员可用。
     *
     * @param actor 操作的店员账号，进流水 {@code actor} 列（补货没有关联订单，{@code order_id} 为 null）
     */
    ProductView updateStock(long productId, int stock, String actor);
}
