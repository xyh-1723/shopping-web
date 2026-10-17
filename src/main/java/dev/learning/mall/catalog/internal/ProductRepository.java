package dev.learning.mall.catalog.internal;

import dev.learning.mall.catalog.api.ProductView;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class ProductRepository {
    private final JdbcClient jdbc;
    ProductRepository(JdbcClient jdbc) { this.jdbc = jdbc; }
    List<ProductView> list() { return jdbc.sql("SELECT * FROM product ORDER BY id").query(ProductView.class).list(); }
    Optional<ProductView> find(long id) {
        return jdbc.sql("SELECT * FROM product WHERE id = :id").param("id", id).query(ProductView.class).optional();
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
