package ru.lct.heatroute.domain.result;

import lombok.Builder;
import lombok.Value;
import lombok.With;

import java.util.List;
import java.util.Map;

/**
 * Сводная информация по варианту (раздел 10.7 ТП). В выгрузке идёт одной записью
 * с {@code geometry = null} и содержит только итоговые показатели.
 */
@Value
@Builder(toBuilder = true)
public class VariantSummary {

    String id;
    String variantId;

    /** Место в ранжировании: 1 — лучший вариант. */
    @With
    int rank;

    /** Стоимость новых участков тепловой сети, руб. */
    double constructionCost;

    /** Стоимость строительства новых тепловых камер, руб. */
    double chamberConstructionCost;

    /** Суммарная стоимость врезок, руб. */
    double tieInCost;

    /** Стоимость реконструкции существующих линейных участков, руб. */
    double reconstructionCost;

    /** Стоимость реконструкции существующих камер, руб. */
    double chamberReconstructionCost;

    /** Штраф за неподключенные ОКС, руб. */
    double unconnectedPenalty;

    /** Итоговая стоимость варианта, руб. */
    double calculatedCost;

    /** Суммарная длина новых линейных участков, м. */
    double newNetworkLength;

    /** Суммарная длина реконструируемых частей, м. */
    double reconstructionLength;

    /** Новая сеть плюс реконструкция, м. */
    double length;

    /** Итоговый показатель S раздела 9 ТП: чем меньше, тем выше вариант. */
    double score;

    /** ID перспективных ОКС, для которых маршрут не найден автоматически. */
    List<String> unconnectedOksIds;

    /**
     * Почему каждый из них остался без подключения: ID → причина по-русски.
     * <p>
     * Список идентификаторов без объяснения бесполезен: раздел 2.9 ТЗ требует
     * обработать такой случай, а обработать — значит не только назвать объект,
     * но и сказать, что с ним не так. Причина известна ровно там, где объект
     * выбывает, и дальше её остаётся донести.
     */
    Map<String, String> unconnectedReasons;
}
