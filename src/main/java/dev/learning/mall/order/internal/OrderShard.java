package dev.learning.mall.order.internal;

/**
 * 订单的分片键：订单号首字符的十六进制值，取值 {@code 0..15}。
 *
 * <p><b>它必须与 V21 迁移里的回填表达式算出同一个值</b>：
 * 迁移用 {@code GREATEST(0, POSITION(SUBSTRING(id FROM 1 FOR 1) IN '0123456789abcdef') - 1)}
 * 回填存量订单，这里用 Java 算新订单。两边一旦分叉，老订单与新订单会落进不同分片，
 * 而每个实例只扫自己那一份，于是分叉的那一批订单<b>谁都不会扫到</b>——
 * 表现形式是「库存被占死」而不是报错，属于最难查的那类故障。
 * 所以这个类里每一处改动都要同步改迁移脚本，反之亦然。
 *
 * <p>取不到十六进制数字时归 0，与迁移里的 {@code GREATEST(0, ...)} 对齐：
 * 负分片值进到 {@code MOD()} 之后，MySQL 与 H2 的定义不同，同一个订单会被算进不同分片。
 */
public final class OrderShard {

    /** 分片空间大小：订单号首字符只有 16 种取值。 */
    static final int SPACE = 16;

    private OrderShard() {
    }

    public static int of(String orderId) {
        if (orderId == null || orderId.isEmpty()) return 0;
        int digit = Character.digit(orderId.charAt(0), 16);
        return digit < 0 ? 0 : digit;
    }
}
