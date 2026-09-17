package ru.lct.heatroute;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * Сервис моделирования трасс подключения перспективных ОКС к тепловым сетям.
 * Конкурсный кейс «Лидеры цифровой трансформации» 2026.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableAsync
public class HeatRouteApplication {

    public static void main(String[] args) {
        SpringApplication.run(HeatRouteApplication.class, args);
    }
}
