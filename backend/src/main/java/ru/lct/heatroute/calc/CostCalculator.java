package ru.lct.heatroute.calc;

import org.springframework.stereotype.Component;
import ru.lct.heatroute.domain.model.FutureOks;
import ru.lct.heatroute.domain.reference.ReferenceCatalog;
import ru.lct.heatroute.domain.result.NewChamberResult;
import ru.lct.heatroute.domain.result.NewSegment;
import ru.lct.heatroute.domain.result.TieInResult;
import ru.lct.heatroute.domain.result.VariantSummary;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Итоговая стоимость варианта и показатель ранжирования (раздел 6 ТП в редакции от 18.09).
 * <p>
 * Формула собрана в одном месте намеренно: на защите её показывают построчно,
 * и разбор {@code S} по составляющим должен сходиться с суммой объектов выгрузки
 * до рубля.
 * <p>
 * Стоимость строительства теперь складывается из трёх слагаемых, а не из шести:
 * новые участки, новые камеры и врезки в существующие камеры. Реконструкции
 * существующей сети и существующих камер в расчётной модели нет, поэтому и
 * в протяжённость {@code L} входит только новая сеть.
 */
@Component
public class CostCalculator {

    private final ReferenceCatalog catalog;

    public CostCalculator(ReferenceCatalog catalog) {
        this.catalog = catalog;
    }

    public VariantSummary summarize(String variantId,
                                    Collection<NewSegment> segments,
                                    Collection<NewChamberResult> chambers,
                                    Collection<TieInResult> tieIns,
                                    List<String> unconnectedPointIds,
                                    Map<String, FutureOks> oksById) {
        return summarize(variantId, segments, chambers, tieIns,
                unconnectedPointIds, Map.of(), oksById);
    }

    /** То же, с объяснением, почему каждая точка подключения осталась без маршрута. */
    public VariantSummary summarize(String variantId,
                                    Collection<NewSegment> segments,
                                    Collection<NewChamberResult> chambers,
                                    Collection<TieInResult> tieIns,
                                    List<String> unconnectedPointIds,
                                    Map<String, String> unconnectedReasons,
                                    Map<String, FutureOks> oksById) {
        double segmentCost = segments.stream().mapToDouble(NewSegment::getCost).sum();
        double chamberConstruction = chambers.stream().mapToDouble(NewChamberResult::getCost).sum();

        // Раздел 3.2: врезка стоит денег только там, где новая сеть заходит
        // в уже существующую камеру. Присоединение через новую камеру отдельной
        // строкой не платится — оно включено в стоимость самой камеры.
        double tieInCost = tieIns.stream().mapToDouble(TieInResult::getCost).sum();
        int tieInCount = tieIns.stream().mapToInt(TieInResult::getTieInCount).sum();

        double construction = segmentCost + chamberConstruction + tieInCost;

        // Раздел 6: штраф 100 млн за сам факт неподключения плюс 500 тыс. за 1 т/ч,
        // и считается он за каждую неподключённую точку подключения.
        double penalty = unconnectedPointIds.stream()
                .map(oksById::get)
                .filter(java.util.Objects::nonNull)
                .mapToDouble(o -> catalog.unconnectedPenalty(o.getFlowTph()))
                .sum();

        double total = construction + penalty;
        double newLength = segments.stream().mapToDouble(NewSegment::getLength).sum();

        return VariantSummary.builder()
                .id("summary_" + variantId)
                .variantId(variantId)
                .rank(0)
                .constructionCost(round(construction))
                .chamberConstructionCost(round(chamberConstruction))
                .existingChamberTieInCount(tieInCount)
                .existingChamberTieInCost(round(tieInCost))
                .unconnectedPenalty(round(penalty))
                .calculatedCost(round(total))
                .newNetworkLength(round2(newLength))
                .score(round3(catalog.score(total, newLength)))
                .unconnectedOksIds(rawIds(unconnectedPointIds, oksById))
                .unconnectedPointKeys(List.copyOf(unconnectedPointIds))
                .unconnectedReasons(Map.copyOf(unconnectedReasons))
                .build();
    }

    /**
     * Идентификаторы неподключённых точек в том виде, в каком они пришли во входном
     * файле: раздел 7.2 требует сохранять тип каждого идентификатора. Если исходное
     * значение почему-то неизвестно, отдаём строку — это всё же лучше, чем потерять
     * объект из списка.
     */
    private static List<Object> rawIds(List<String> ids, Map<String, FutureOks> oksById) {
        List<Object> out = new ArrayList<>(ids.size());
        for (String id : ids) {
            FutureOks oks = oksById.get(id);
            Object raw = oks == null ? null : oks.getRawConnectionPointId();
            out.add(raw != null ? raw : id);
        }
        return out;
    }

    private static double round(double v) {
        return Math.round(v);
    }

    private static double round2(double v) {
        return Math.round(v * 100d) / 100d;
    }

    private static double round3(double v) {
        return Math.round(v * 1000d) / 1000d;
    }
}
