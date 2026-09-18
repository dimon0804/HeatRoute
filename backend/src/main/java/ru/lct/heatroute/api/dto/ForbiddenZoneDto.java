package ru.lct.heatroute.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import javax.validation.constraints.DecimalMax;
import javax.validation.constraints.DecimalMin;
import javax.validation.constraints.Positive;

/**
 * Зона, через которую трассе проходить нельзя, — задаётся на запуск расчёта.
 * <p>
 * Отвечает на вопрос, который в жизни возникает первым: «а если здесь копать нельзя?».
 * Стройплощадка, охранная зона, участок, который город не отдаёт, — всё это появляется
 * позже входных данных и в них не описано. Для расчёта зона работает как обычное
 * препятствие: трасса её обходит, а если обхода нет, соответствующие ОКС честно
 * попадают в список неподключенных со штрафом.
 * <p>
 * Круг, а не произвольный полигон: центр и радиус задаются щелчком по карте и одним
 * числом, а любая запретная зона в этой задаче — это «вот здесь и вокруг нельзя».
 */
@Data
@Schema(description = "Запретная зона: круг на местности, через который трасса не пройдёт")
public class ForbiddenZoneDto {

    @DecimalMin(value = "-180", message = "Долгота вне допустимого диапазона")
    @DecimalMax(value = "180", message = "Долгота вне допустимого диапазона")
    @Schema(description = "Долгота центра, WGS 84", example = "37.612")
    private double lon;

    @DecimalMin(value = "-90", message = "Широта вне допустимого диапазона")
    @DecimalMax(value = "90", message = "Широта вне допустимого диапазона")
    @Schema(description = "Широта центра, WGS 84", example = "55.652")
    private double lat;

    @Positive(message = "Радиус запретной зоны должен быть положительным")
    @Schema(description = "Радиус, м", example = "40")
    private double radiusM;
}
