package dev.learning.mall.merchant.api;

/**
 * 一家店的对外视图。
 *
 * <p><b>{@code ownerUsername} 是这家店与登录账号之间唯一的绑定点</b>，
 * 也是「谁能看这家店的数据」这件事的判据来源——不是角色，不是店铺名。
 * 一个账号只开一家店（{@code uq_merchant_owner}），所以它是双向可查的：
 * 由账号能找到店，由店也能找到账号。
 *
 * <p><b>刻意不放密码、不放联系方式</b>：它不是账号表，只是「店」这一维的投影。
 * 账号与口令仍然由 {@code SecurityConfig} 从 {@code mall.merchant-password} 生成，
 * 那条路一个字都没改——把凭据塞进这里只会多出一个真相来源。
 */
public record MerchantView(long id, String code, String name, String ownerUsername, String status) {

    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_SUSPENDED = "SUSPENDED";

    public boolean active() {
        return STATUS_ACTIVE.equals(status);
    }
}
