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
}
