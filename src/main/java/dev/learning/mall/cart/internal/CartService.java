package dev.learning.mall.cart.internal;

import dev.learning.mall.cart.api.CartItemView;
import dev.learning.mall.cart.api.CartView;
import dev.learning.mall.catalog.api.ProductCatalog;
import dev.learning.mall.catalog.api.ProductView;
import dev.learning.mall.common.BusinessException;
import dev.learning.mall.order.api.CreateOrderRequest;
import dev.learning.mall.pricing.api.Pricer;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 购物袋服务。助手与页面共用同一份实现——这是「加购」能被 agent 使用的前提。
 *
 * <p><b>为什么加购不需要 human-in-the-loop 确认，支付需要？</b>
 * 风险分级不是一刀切的。加购的三个特征决定了它可以直写：不产生资金变动、用户随时可见、
 * 一键可撤销。而支付不可逆、不可见（发生在后台）。HITL 应该覆盖后者，
 * 把前者也拦一道只会让用户对确认卡片麻木——而麻木的确认，等于没有确认。
 *
 * <p>仍然保留的防线：库存校验（不能加超过现货）、单品上限 99、袋内不超过 20 行（与下单接口一致）。
 */
@Service
public class CartService {

    private static final int MAX_QUANTITY_PER_LINE = 99;
    private static final int MAX_LINES = 20;

    private final CartRepository repository;
    private final ProductCatalog catalog;
    private final Pricer pricing;

    public CartService(CartRepository repository, ProductCatalog catalog, Pricer pricing) {
        this.repository = repository;
        this.catalog = catalog;
        this.pricing = pricing;
    }

    /**
     * 用自己的行去问唯一的定价器，再把结果翻译回购物袋视图。
     *
     * <p>这一层翻译是「共享领域」的代价，也是它的意义：定价器只认
     * {@code 单价 × 数量}，购物袋特有的商品名、图、库存状态一概不进入算法；
     * 反过来，订单也能用同一套算法，而不必长出一个购物袋出来。
     */
    private CartView quote(List<CartItemView> items) {
        if (items.isEmpty()) return CartView.empty();
        Pricer.Quote q = pricing.quote(items.stream()
                .map(i -> new Pricer.Line(i.productId(), i.unitPrice(), i.quantity())).toList());
        int itemCount = items.stream().mapToInt(CartItemView::quantity).sum();
        return new CartView(List.copyOf(items), itemCount, q.subtotal(), q.discount(),
                q.shippingFee(), q.payable(), q.notes());
    }

    /** 购物袋快照（含实时价格与库存、结算试算）。 */
    public CartView view(String username) {
        return quote(lines(username));
    }

    /**
     * 加购。{@code quantity} 为增量；返回加购后的完整试算，让调用方（页面或助手）
     * 能直接把「现在袋子里一共多少钱」说给用户听。
     */
    @Transactional
    public CartView add(String username, long productId, int quantity) {
        if (quantity <= 0) throw BusinessException.badRequest("INVALID_QUANTITY", "数量必须大于 0");
        ProductView product = catalog.get(productId);
        int held = repository.quantityOf(username, productId).orElse(0);
        int next = held + quantity;
        if (next > MAX_QUANTITY_PER_LINE)
            throw BusinessException.badRequest("QUANTITY_TOO_LARGE",
                    "单件商品最多 " + MAX_QUANTITY_PER_LINE + " 件");
        if (next > product.stock())
            throw BusinessException.conflict("OUT_OF_STOCK",
                    product.name() + " 库存只剩 " + product.stock() + " 件，袋子里已有 " + held + " 件");
        if (held == 0 && repository.countOf(username) >= MAX_LINES)
            throw BusinessException.badRequest("CART_FULL", "购物袋最多放 " + MAX_LINES + " 种商品");
        repository.upsert(username, productId, next);
        return quote(lines(username));
    }

    /** 设置某件商品的数量；{@code quantity <= 0} 表示移除。 */
    @Transactional
    public CartView setQuantity(String username, long productId, int quantity) {
        if (quantity <= 0) return remove(username, productId);
        if (quantity > MAX_QUANTITY_PER_LINE)
            throw BusinessException.badRequest("QUANTITY_TOO_LARGE",
                    "单件商品最多 " + MAX_QUANTITY_PER_LINE + " 件");
        ProductView product = catalog.get(productId);
        if (quantity > product.stock())
            throw BusinessException.conflict("OUT_OF_STOCK",
                    product.name() + " 库存只剩 " + product.stock() + " 件");
        if (repository.quantityOf(username, productId).isEmpty() && repository.countOf(username) >= MAX_LINES)
            throw BusinessException.badRequest("CART_FULL", "购物袋最多放 " + MAX_LINES + " 种商品");
        repository.upsert(username, productId, quantity);
        return quote(lines(username));
    }

    @Transactional
    public CartView remove(String username, long productId) {
        repository.delete(username, productId);
        return quote(lines(username));
    }

    @Transactional
    public CartView clear(String username) {
        repository.clear(username);
        return CartView.empty();
    }

    /**
     * 把购物袋内容解析成「现在这一刻」的行：价格与库存当场查商品表，
     * 不用加购时记下的快照价——商品改价后，袋子里显示旧价是最难解释的一类 bug。
     */
    private List<CartItemView> lines(String username) {
        List<CartItemView> items = new ArrayList<>();
        for (CartRepository.Row row : repository.rowsOf(username)) {
            ProductView p;
            try {
                p = catalog.get(row.productId());
            } catch (BusinessException e) {
                // 商品已下架：静默跳过，别让一条脏数据让整个购物袋打不开。
                continue;
            }
            items.add(new CartItemView(p.id(), p.name(), p.illustration(), p.price(), row.quantity(),
                    p.stock(), p.price().multiply(BigDecimal.valueOf(row.quantity())),
                    p.stock() >= row.quantity()));
        }
        return List.copyOf(items);
    }

    /** 下单用的精简行（下单接口只认商品与数量）。 */
    public List<CreateOrderRequest.Line> orderLines(String username) {
        List<CreateOrderRequest.Line> lines = new ArrayList<>();
        for (CartRepository.Row row : repository.rowsOf(username)) {
            lines.add(new CreateOrderRequest.Line(row.productId(), row.quantity()));
        }
        return List.copyOf(lines);
    }

    public int maxQuantityPerLine() { return MAX_QUANTITY_PER_LINE; }
    public int maxLines() { return MAX_LINES; }
}
