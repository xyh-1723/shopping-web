package dev.learning.mall.assistant.security;

import java.util.LinkedHashSet;
import java.util.Set;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.stereotype.Component;

/**
 * 从 Spring Security 的 UserDetailsService 解析角色，作为权限判定的唯一权威来源。
 * 这样账号体系只有一处配置（SecurityConfig），助手不会另起一套权限真相。
 */
@Component
public class AssistantActors {

    private final UserDetailsService users;

    public AssistantActors(UserDetailsService users) {
        this.users = users;
    }

    public AssistantActor resolve(String username) {
        Set<String> roles = new LinkedHashSet<>();
        try {
            UserDetails details = users.loadUserByUsername(username);
            details.getAuthorities().forEach(a -> roles.add(a.getAuthority()));
        } catch (RuntimeException notFound) {
            // 解析失败一律降级为最小权限，绝不因为「查不到」而放行。
            roles.add(AssistantActor.ROLE_USER);
        }
        if (roles.isEmpty()) {
            roles.add(AssistantActor.ROLE_USER);
        }
        return new AssistantActor(username, Set.copyOf(roles));
    }
}
