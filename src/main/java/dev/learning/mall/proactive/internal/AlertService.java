package dev.learning.mall.proactive.internal;

import dev.learning.mall.proactive.api.AlertCenter;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * 提醒中心：读提醒、标记已读。
 *
 * <p>已读状态看起来是个无关紧要的小功能，但它是「主动服务」能持续存在的前提：
 * 一个不能清空的提醒列表，用三天就会变成用户不再点开的东西。
 * 而提醒一旦不再被点开，整个主动服务就等于不存在。
 */
@Service
public class AlertService {

    private static final int MAX_ALERTS = 50;

    private final ProactiveRepository repository;

    public AlertService(ProactiveRepository repository) {
        this.repository = repository;
    }

    public AlertCenter center(String username) {
        List<AlertCenter.Alert> alerts = repository.alertsOf(username, MAX_ALERTS).stream()
                .map(row -> new AlertCenter.Alert(row.id(), row.type(), row.title(), row.content(),
                        row.productId(), row.createdAt(), row.read()))
                .toList();
        return new AlertCenter(repository.unreadCount(username), alerts);
    }

    public int unreadCount(String username) {
        return repository.unreadCount(username);
    }

    /** 标记一条已读。返回是否真的改动了——重复点击或改别人的提醒都只会返回 false，不报错。 */
    public boolean markRead(String username, String alertId) {
        return repository.markRead(username, alertId) > 0;
    }

    public int markAllRead(String username) {
        return repository.markAllRead(username);
    }
}
