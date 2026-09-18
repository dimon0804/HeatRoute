package ru.lct.heatroute.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import ru.lct.heatroute.ingest.IngestProperties;

import javax.validation.Valid;
import javax.validation.constraints.DecimalMax;
import javax.validation.constraints.DecimalMin;
import javax.validation.constraints.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Запрос на расчёт вариантов по загруженному набору. */
@Data
@Schema(description = "Запуск расчёта")
public class CreateJobRequest {

    @NotNull
    @Schema(description = "Идентификатор загруженного набора")
    private UUID datasetId;

    @Schema(description = "Условный диаметр для расчёта клиренсов, мм. "
            + "Ноль — подобрать по суммарному расходу перспективных ОКС", example = "0")
    private int designDiameter;

    @Schema(description = "Рассчитать профиль по глубине — дополнительная задача кейса. "
            + "Подбирается глубина каждого участка, места пересечений с существующими "
            + "коммуникациями решаются проходом сверху или снизу, стоимость "
            + "пересчитывается с коэффициентом по глубине", example = "false")
    private boolean withDepth;

    @Schema(description = "Допущение о загрузке существующей сети, если расхода нет "
            + "во входных данных: ZERO — расход нулевой, CAPACITY_FRACTION — доля "
            + "пропускной способности. Пусто — как настроено в сервисе. Самое влиятельное "
            + "допущение решения: от него зависит объём реконструкции",
            example = "ZERO")
    private IngestProperties.ExistingFlowMode existingFlowMode;

    @DecimalMin(value = "0", message = "Доля пропускной способности не может быть отрицательной")
    @DecimalMax(value = "1", message = "Доля пропускной способности не может превышать единицу")
    @Schema(description = "Доля пропускной способности для режима CAPACITY_FRACTION",
            example = "0.5")
    private Double existingFlowFraction;

    @Valid
    @Schema(description = "Зоны, через которые трассе проходить нельзя. "
            + "Задаются на запуск: стройплощадка, охранная зона, участок, который город "
            + "не отдаёт. Для расчёта это такое же препятствие, как здание — трасса его "
            + "обходит, а если обхода нет, ОКС попадает в список неподключенных со штрафом")
    private List<ForbiddenZoneDto> forbiddenZones = new ArrayList<>();
}
