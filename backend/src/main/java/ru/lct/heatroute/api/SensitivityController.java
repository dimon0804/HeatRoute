package ru.lct.heatroute.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ru.lct.heatroute.api.dto.SensitivityReportDto;
import ru.lct.heatroute.service.SensitivityService;

import java.util.UUID;

/**
 * Анализ чувствительности: что держит цену решения.
 * <p>
 * Ручка считает долго, и это её свойство, а не недостаток: задача решается заново без
 * каждой точки подключения по очереди. Поэтому она вызывается только по запросу
 * и никогда не выполняется вместе с обычным расчётом.
 */
@RestController
@Profile("!nodb")
@RequestMapping("/api/v1/jobs")
@Tag(name = "Чувствительность", description = "Что держит цену решения")
public class SensitivityController {

    private final SensitivityService sensitivity;

    public SensitivityController(SensitivityService sensitivity) {
        this.sensitivity = sensitivity;
    }

    @GetMapping("/{id}/sensitivity")
    @Operation(summary = "Вклад каждой точки подключения в стоимость",
            description = "Решает задачу заново без каждой точки подключения по очереди "
                    + "и показывает, насколько от неё зависит стоимость и протяжённость. "
                    + "Вклад точки не равен длине отвода к ней: убрав точку, алгоритм "
                    + "перестраивает дерево целиком, и разница может оказаться заметно "
                    + "больше или меньше. Прогон на конкурсном наборе идёт около двадцати "
                    + "секунд, поэтому число точек можно ограничить: самые тяжёлые "
                    + "по расходу идут первыми.")
    public SensitivityReportDto analyse(
            @PathVariable UUID id,
            @RequestParam(required = false, defaultValue = "0")
            @Parameter(description = "Сколько точек проверить; 0 — все")
            int limit) {
        return sensitivity.analyse(id, limit);
    }
}
