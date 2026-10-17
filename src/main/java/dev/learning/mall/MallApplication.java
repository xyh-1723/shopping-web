package dev.learning.mall;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/** 从这里开始。Spring 会扫描这个包及其子包中的组件。 */
@SpringBootApplication
@EnableScheduling
public class MallApplication {
    public static void main(String[] args) {
        SpringApplication.run(MallApplication.class, args);
    }
}
