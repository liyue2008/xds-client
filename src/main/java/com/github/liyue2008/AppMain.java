package com.github.liyue2008;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Hello world!
 */
@SpringBootApplication
public class AppMain {
    private static final Logger logger = LoggerFactory.getLogger(AppMain.class);

    public static void main(String[] args) {
        logger.info("服务开始启动，初始化appContext...");
        SpringApplication.run(AppMain.class, args);
        logger.info("服务已启动。");
    }
}
