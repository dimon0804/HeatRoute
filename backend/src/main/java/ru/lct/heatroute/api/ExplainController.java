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
import ru.lct.heatroute.api.dto.SegmentExplanationDto;
import ru.lct.heatroute.service.ExplainService;

import java.util.List;
import java.util.UUID;

/**
 * Разбор трассы: почему каждый участок прошёл именно здесь.
 * <p>
 * Главное недоверие к автоматической трассировке звучит как «почему труба пошла тут,
 * а не прямее». Ответ у сервиса был всегда, но оставался внутри расчёта. Здесь он
 * доступен по готовому результату: для каждого участка видно, какие ограничения его
 * зажали, на сколько сантиметров он от них отстоит и сколько запаса осталось.
 */
@RestController
@Profile("!nodb")
@RequestMapping("/api/v1/jobs")
@Tag(name = "Разбор трассы", description = "Чем задано место каждого участка")
public class ExplainController {

    private final ExplainService explain;

    public ExplainController(ExplainService explain) {
        this.explain = explain;
    }

    @GetMapping("/{id}/explain")
    @Operation(summary = "Разобрать трассу варианта",
            description = "Для каждого участка возвращает ограничения рядом с ним "
                    + "с фактическим и требуемым расстоянием, специальные проходы, "
                    + "во сколько участок длиннее прямой и короткий вывод о том, "
                    + "чем задано его место. Без параметра берётся вариант, занявший "
                    + "первое место.")
    public List<SegmentExplanationDto> explain(
            @PathVariable UUID id,
            @RequestParam(required = false)
            @Parameter(description = "Код варианта, например v2; по умолчанию — лучший")
            String variantCode) {
        return explain.explain(id, variantCode);
    }
}
