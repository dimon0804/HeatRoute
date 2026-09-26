package ru.lct.heatroute.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * Документация API (требование раздела 3.2 ТЗ: springdoc-openapi-ui 1.7.0).
 * <p>
 * Описание намеренно подробное: по Swagger UI жюри читает контракт сервиса, и он должен
 * объяснять не только формат запросов, но и порядок работы — загрузить набор, увидеть
 * протокол разбора, запустить расчёт, забрать варианты и выгрузку.
 */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI heatRouteOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("HeatRoute — моделирование трасс подключения к тепловым сетям")
                        .version("1.0.0")
                        .description(String.join("\n",
                                "Сервис автоматически строит варианты подключения перспективных ОКС",
                                "к существующей тепловой сети: выбирает точки врезки, определяет",
                                "совместное или раздельное подключение, прокладывает маршруты",
                                "с соблюдением пространственных ограничений, рассчитывает расходы",
                                "и условные диаметры, считает стоимость и сравнивает",
                                "найденные решения.",
                                "",
                                "**Порядок работы**",
                                "",
                                "1. `POST /api/v1/datasets` — загрузить конкурсный набор GeoJSON.",
                                "   В ответе приходит сводка по набору и протокол разбора:",
                                "   какие атрибуты восстановлены и какие допущения приняты.",
                                "2. `POST /api/v1/jobs` — запустить расчёт. Возвращается идентификатор;",
                                "   расчёт идёт в фоне.",
                                "3. `GET /api/v1/jobs/{id}` — состояние, прогресс и, по завершении,",
                                "   список вариантов с разбором стоимости.",
                                "4. `GET /api/v1/jobs/{id}/result.geojson` — выгрузка результата",
                                "   одним совмещённым файлом по разделу 10 технического приложения.",
                                "",
                                "**Система координат.** Вход и выход — WGS 84 (EPSG:4326).",
                                "Все расчёты длин, расстояний и буферов выполняются",
                                "в EPSG:32637 (UTM зона 37N).",
                                "",
                                "Конкурсный кейс «Лидеры цифровой трансформации» 2026."))
                        .contact(new Contact().name("Команда HeatRoute"))
                        .license(new License().name("Конкурсное решение ЛЦТ 2026")))
                .servers(List.of(
                        new Server().url("/").description("Текущий сервер")));
    }
}
