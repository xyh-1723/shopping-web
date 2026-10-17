package dev.learning.mall.common;

import java.lang.annotation.*;

/** 演示自定义注解 + AOP：计时属于横切关注点，不写进业务方法。 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Observed { String value(); }
