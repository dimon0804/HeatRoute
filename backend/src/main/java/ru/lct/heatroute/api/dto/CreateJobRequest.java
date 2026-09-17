package ru.lct.heatroute.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import javax.validation.constraints.NotNull;
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
}
