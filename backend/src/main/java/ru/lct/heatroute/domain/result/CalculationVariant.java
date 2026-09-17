package ru.lct.heatroute.domain.result;

import lombok.Builder;
import lombok.Value;
import lombok.With;

import java.util.List;

/**
 * Один содержательно самостоятельный вариант подключения.
 * <p>
 * Сервис формирует основной вариант и до двух отличающихся (раздел 2.8 ТЗ).
 * Отличие должно быть смысловым: другая точка врезки, другое объединение ОКС,
 * другой маршрут или другое разбиение на независимые части сети.
 */
@Value
@Builder(toBuilder = true)
public class CalculationVariant {

    String variantId;

    /** Как этот вариант получен — текст для панели сравнения и для защиты. */
    String description;

    List<NewSegment> segments;
    List<TieInResult> tieIns;
    List<NewChamberResult> chambers;
    List<TechnicalNodeResult> technicalNodes;
    List<ReconstructionResult> reconstructions;
    List<ChamberReconstructionResult> chamberReconstructions;

    @With
    VariantSummary summary;

    /**
     * Отпечаток структуры варианта: множество точек врезки и разбиение ОКС по частям сети.
     * Два варианта с одинаковым отпечатком считаются одним и тем же решением, даже если
     * геометрия немного разошлась (раздел 2.8 ТЗ: «небольшое смещение одной и той же
     * трассы отдельным вариантом не считается»).
     */
    String structureFingerprint;
}
