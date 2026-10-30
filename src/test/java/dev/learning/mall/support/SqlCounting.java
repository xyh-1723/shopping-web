package dev.learning.mall.support;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.Statement;
import javax.sql.DataSource;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * 测试专用：数一次业务调用到底发了几条 SQL。
 *
 * <p><b>为什么需要它</b>：N+1 只有靠「条数」才能断言。用耗时断言必然 flaky
 * ——同一段代码在 CI 上可能 3ms 也可能 30ms；而「10 单和 50 单发的 SQL 条数相同」
 * 是个非真即假的命题，不随机器快慢变化。
 *
 * <p><b>怎么做到的</b>：在 {@link DataSource} 外面套三层动态代理
 * （DataSource → Connection → Statement），只在 {@link Statement} 的
 * {@code execute*} 系列上加一。不修改任何生产代码，也不改 SQL。
 *
 * <p><b>用法</b>：测试类上 {@code @Import(SqlCounting.Config.class)}，
 * 测量前后各调一次 {@link #reset()} / {@link #count()}。
 */
public final class SqlCounting {

    private static final ThreadLocal<int[]> HITS = ThreadLocal.withInitial(() -> new int[1]);

    private SqlCounting() {}

    /** 清零。放在「准备数据」之后、「被测调用」之前。 */
    public static void reset() {
        HITS.get()[0] = 0;
    }

    /** 自上次 {@link #reset()} 以来发出去的 SQL 条数。 */
    public static int count() {
        return HITS.get()[0];
    }

    private static void hit() {
        HITS.get()[0]++;
    }

    @TestConfiguration
    public static class Config {
        /**
         * 必须是 {@code static}：BeanPostProcessor 要在普通 bean 实例化之前就位，
         * 非静态工厂方法会导致整个配置类被提前创建，进而漏掉一批 bean。
         */
        @Bean
        static BeanPostProcessor sqlCountingDataSourcePostProcessor() {
            return new BeanPostProcessor() {
                @Override
                public Object postProcessAfterInitialization(Object bean, String beanName) {
                    if (bean instanceof DataSource ds && !Proxy.isProxyClass(bean.getClass())) {
                        return wrapDataSource(ds);
                    }
                    return bean;
                }
            };
        }
    }

    private static DataSource wrapDataSource(DataSource delegate) {
        return (DataSource) Proxy.newProxyInstance(DataSource.class.getClassLoader(),
                new Class<?>[] {DataSource.class}, (proxy, method, args) -> {
                    Object result = invoke(delegate, method, args);
                    if (result instanceof Connection conn && !Proxy.isProxyClass(conn.getClass())) {
                        return wrapConnection(conn);
                    }
                    return result;
                });
    }

    private static Connection wrapConnection(Connection delegate) {
        return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(),
                new Class<?>[] {Connection.class}, (proxy, method, args) -> {
                    Object result = invoke(delegate, method, args);
                    if (result instanceof Statement st && !Proxy.isProxyClass(st.getClass())) {
                        return wrapStatement(st);
                    }
                    return result;
                });
    }

    private static Statement wrapStatement(Statement delegate) {
        return (Statement) Proxy.newProxyInstance(Statement.class.getClassLoader(),
                new Class<?>[] {java.sql.PreparedStatement.class}, (proxy, method, args) -> {
                    String name = method.getName();
                    if (name.startsWith("execute")) hit();
                    return invoke(delegate, method, args);
                });
    }

    private static Object invoke(Object target, Method method, Object[] args) throws Throwable {
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException e) {
            // 代理会把真实异常包一层，必须剥出来，否则业务异常在测试里变成 UndeclaredThrowable。
            throw e.getCause();
        }
    }
}
