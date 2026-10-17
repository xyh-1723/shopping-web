package dev.learning.mall.catalog.internal;

import dev.learning.mall.catalog.api.*;
import dev.learning.mall.common.BusinessException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ProductService implements ProductCatalog {
    /** 价格上限：防止手误把 39 输成 390000；下限 1 分，不允许白送。 */
    private static final BigDecimal MIN_PRICE = new BigDecimal("0.01");
    private static final BigDecimal MAX_PRICE = new BigDecimal("99999.00");

    private final ProductRepository repository;
    private final ApplicationEventPublisher events;

    public ProductService(ProductRepository repository, ApplicationEventPublisher events) {
        this.repository = repository;
        this.events = events;
    }

    public List<ProductView> list() { return repository.list(); }
    public ProductView get(long id) {
        return repository.find(id).orElseThrow(() -> BusinessException.notFound("商品不存在"));
    }

    /**
     * 改价，并广播 {@link ProductChangedEvent}。
     *
     * <p>发布事件而不是直接调用关注扫描，是为了让商品模块不依赖主动服务：
     * 商品模块不知道也不关心有谁在监听价格变化。新增玩法（降价提醒、比价、价格趋势）
     * 不需要再回来改这个类。
     */
    @Transactional
    public ProductView updatePrice(long productId, BigDecimal price) {
        if (price == null || price.compareTo(MIN_PRICE) < 0 || price.compareTo(MAX_PRICE) > 0) {
            throw BusinessException.badRequest("INVALID_PRICE",
                    "价格必须在 " + MIN_PRICE + " 到 " + MAX_PRICE + " 之间");
        }
        ProductView before = get(productId);
        BigDecimal normalized = price.setScale(2, RoundingMode.HALF_UP);
        repository.updatePrice(productId, normalized);
        ProductView after = get(productId);
        if (before.price().compareTo(after.price()) != 0) {
            events.publishEvent(new ProductChangedEvent(productId, ProductChangedEvent.Change.PRICE));
        }
        return after;
    }

    /**
     * 改库存（上架 / 补货），同样广播事件。
     *
     * <p>只有「从 0 变到有货」才会真正触发补货提醒，判断交给订阅方——
     * 商品模块只负责说「库存变了」。这条边界让「什么算补货」这种业务口径
     * 只存在于一个地方。
     */
    @Transactional
    public ProductView updateStock(long productId, int stock) {
        if (stock < 0 || stock > 9999) {
            throw BusinessException.badRequest("INVALID_STOCK", "库存必须在 0 到 9999 之间");
        }
        ProductView before = get(productId);
        repository.setStock(productId, stock);
        ProductView after = get(productId);
        if (before.stock() != after.stock()) {
            events.publishEvent(new ProductChangedEvent(productId, ProductChangedEvent.Change.STOCK));
        }
        return after;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void reserve(long id, int quantity) {
        if (quantity <= 0) throw new IllegalArgumentException("quantity must be positive");
        if (repository.deduct(id, quantity) != 1)
            throw BusinessException.conflict("OUT_OF_STOCK", "库存不足，请减少数量或换一件商品");
    }
    @Transactional(propagation = Propagation.MANDATORY)
    public void release(long id, int quantity) {
        if (quantity <= 0) throw new IllegalArgumentException("quantity must be positive");
        repository.restore(id, quantity);
    }
}
