package dev.learning.mall.merchant.web;

import dev.learning.mall.common.BusinessException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.UserDetailsManager;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 商家改密。
 *
 * <p>改密是<b>唯一能让一个泄露的密码失效</b>的动作，所以它的校验要比注册严：
 * <ol>
 *   <li>旧密码必须对——否则谁都能替别人改密。</li>
 *   <li>新密码必须够强（≥8 位、含字母和数字）——弱密码等于没改。</li>
 *   <li>新旧不能相同——「改密」改成同一个等于没改。</li>
 * </ol>
 *
 * <p>密码存 {@code users.password}（BCrypt），改完立即生效，不重启。
 * 这是 A3（账号入库）才能做的事——在内存里改密码，重启就丢回去了。
 *
 * <p><b>为什么不用 {@code JdbcUserDetailsManager.changePassword}</b>：
 * 它内部需要一个 {@code PasswordEncoder} 来比对旧密码，但当前版本不直接暴露
 * {@code setPasswordEncoder}。手动用 {@link PasswordEncoder} 比对 + {@code updateUser}
 * 更新，语义一样，控制权更清楚。
 */
@RestController
@RequestMapping("/api/merchant")
public class MerchantPasswordController {

    private final UserDetailsManager users;
    private final PasswordEncoder encoder;

    public MerchantPasswordController(UserDetailsManager users, PasswordEncoder encoder) {
        this.users = users;
        this.encoder = encoder;
    }

    @PostMapping("/password")
    public void changePassword(Authentication auth, @Valid @RequestBody ChangePasswordRequest request) {
        String username = auth.getName();
        String oldPassword = request.oldPassword();
        String newPassword = request.newPassword();

        if (oldPassword.equals(newPassword)) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "PASSWORD_SAME_AS_OLD",
                    "新密码不能与旧密码相同");
        }
        if (!isStrongEnough(newPassword)) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "PASSWORD_TOO_WEAK",
                    "新密码强度不足：至少 8 位，且必须同时包含字母和数字");
        }
        // 比对旧密码：用 BCrypt 的 matches，不对就 400。
        UserDetails current = users.loadUserByUsername(username);
        if (!encoder.matches(oldPassword, current.getPassword())) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "OLD_PASSWORD_WRONG",
                    "旧密码不正确");
        }
        // 编码新密码，保留原角色，更新入库。
        String encoded = encoder.encode(newPassword);
        UserDetails updated = User.withUserDetails(current).password(encoded).build();
        users.updateUser(updated);
    }

    /**
     * 强度规则：长度 ≥ 8，且同时包含字母和数字。
     *
     * <p>刻意不要求特殊字符——对商家日常使用来说，强制特殊字符只会让他们把密码写在便签上，
     * 反而更不安全。「够长 + 字母数字混合」是安全与可用性的平衡点。
     */
    private boolean isStrongEnough(String password) {
        if (password == null || password.length() < 8) return false;
        boolean hasLetter = false;
        boolean hasDigit = false;
        for (int i = 0; i < password.length(); i++) {
            char c = password.charAt(i);
            if (Character.isLetter(c)) hasLetter = true;
            if (Character.isDigit(c)) hasDigit = true;
        }
        return hasLetter && hasDigit;
    }

    public record ChangePasswordRequest(
            @NotBlank(message = "旧密码不能为空") String oldPassword,
            @NotBlank(message = "新密码不能为空") String newPassword) {}
}
