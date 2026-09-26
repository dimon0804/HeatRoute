package ru.lct.heatroute.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.lct.heatroute.api.dto.VariantSummaryDto;
import ru.lct.heatroute.domain.result.VariantSummary;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Сводка варианта доходит до ответа API целиком.
 * <p>
 * Преобразование в DTO перечисляет поля вручную, и забытое поле ничем себя не выдаёт:
 * сервис считает правильно, тесты расчёта зелёные, а в ответе пусто. Так и вышло
 * с причинами неподключения — они считались, доезжали до сводки и терялись здесь;
 * заметно это стало только на экране.
 * <p>
 * Поэтому проверка не перечисляет поля заново — она сверяет состав: у каждого поля
 * доменной сводки обязан быть одноимённый заполненный аналог в ответе. Новое поле,
 * которое забудут перенести, уронит этот тест сразу.
 */
class SceneMapperSummaryTest {

    /**
     * Поля, которых в ответе нет намеренно. Идентификаторы сводки и варианта живут
     * в самом ответе о расчёте, дублировать их внутри незачем. Ключи неподключённых
     * точек — служебные: наружу идёт {@code unconnectedOksIds} с исходным типом
     * идентификатора, как требует раздел 7.2, а строковые ключи нужны только расчёту.
     */
    private static final List<String> DOMAIN_ONLY =
            List.of("id", "variantId", "unconnectedPointKeys");

    // Проекция мапперу нужна для геометрии сцены; сводка её не касается, поэтому
    // достаточно настроек по умолчанию — поднимать ради этого контекст незачем.
    private final SceneMapper mapper =
            new SceneMapper(new ru.lct.heatroute.geo.ProjectionService(
                    new ru.lct.heatroute.geo.GeoProperties()));

    private static VariantSummary filled() {
        return VariantSummary.builder()
                .id("summary_v1")
                .variantId("v1")
                .rank(1)
                .constructionCost(1)
                .chamberConstructionCost(2)
                .existingChamberTieInCount(3)
                .existingChamberTieInCost(4)
                .unconnectedPenalty(5)
                .calculatedCost(6)
                .newNetworkLength(7)
                .score(8)
                .unconnectedOksIds(List.of(42L))
                .unconnectedPointKeys(List.of("42"))
                .unconnectedReasons(Map.of("42", "Маршрут не найден"))
                .build();
    }

    @Test
    @DisplayName("Ни одно поле сводки не теряется при переводе в ответ API")
    void everyFieldSurvivesMapping() throws Exception {
        VariantSummaryDto dto = mapper.summary(filled());

        for (Field source : VariantSummary.class.getDeclaredFields()) {
            if (source.isSynthetic() || Modifier.isStatic(source.getModifiers())) {
                continue;
            }
            if (DOMAIN_ONLY.contains(source.getName())) {
                continue;
            }

            Field target = VariantSummaryDto.class.getDeclaredField(source.getName());
            target.setAccessible(true);
            Object value = target.get(dto);

            assertThat(value)
                    .as("поле %s не заполнено в ответе API", source.getName())
                    .isNotNull();
            if (value instanceof Number) {
                assertThat(((Number) value).doubleValue())
                        .as("поле %s пришло нулевым, хотя в сводке не ноль", source.getName())
                        .isNotZero();
            }
            if (value instanceof Collection) {
                assertThat((Collection<?>) value)
                        .as("поле %s пришло пустым", source.getName()).isNotEmpty();
            }
            if (value instanceof Map) {
                assertThat((Map<?, ?>) value)
                        .as("поле %s пришло пустым", source.getName()).isNotEmpty();
            }
        }
    }
}
