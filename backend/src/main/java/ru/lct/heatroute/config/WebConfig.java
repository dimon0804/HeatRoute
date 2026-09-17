package ru.lct.heatroute.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Настройки веб-слоя.
 * <p>
 * В собранном виде интерфейс раздаётся тем же nginx, который проксирует /api,
 * поэтому кросс-доменных запросов там нет. Разрешение нужно только для разработки,
 * когда фронтенд поднят отдельным сервером Vite.
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOriginPatterns("http://localhost:*", "http://127.0.0.1:*")
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .maxAge(3600);
    }
}
