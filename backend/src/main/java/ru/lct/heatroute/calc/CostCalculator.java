package ru.lct.heatroute.calc;

import org.springframework.stereotype.Component;
import ru.lct.heatroute.domain.model.FutureOks;
import ru.lct.heatroute.domain.reference.ReferenceCatalog;
import ru.lct.heatroute.domain.result.ChamberReconstructionResult;
import ru.lct.heatroute.domain.result.NewChamberResult;
import ru.lct.heatroute.domain.result.NewSegment;
import ru.lct.heatroute.domain.result.ReconstructionResult;
import ru.lct.heatroute.domain.result.TieInResult;
import ru.lct.heatroute.domain.result.VariantSummary;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Итоговая стоимость варианта и показатель ранжирования (разделы 8 и 9 ТП).
 * <p>
 * Формула собрана в одном месте намеренно: на защите её показывают построчно,
 * и разбор `S` по составляющим должен сходиться с суммой объектов выгрузки
 * до рубля.
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
                                    Collection<ReconstructionResult> reconstructions,
                                    Collection<ChamberReconstructionResult> chamberReconstructions,
                                    List<String> unconnectedOksIds,
                                    Map<String, FutureOks> oksById) {
        return summarize(variantId, segments, chambers, tieIns, reconstructions,
                chamberReconstructions, unconnectedOksIds, Map.of(), oksById);
    }

    /** То же, с объяснением, почему каждый ОКС остался без подключения. */
    public VariantSummary summarize(String variantId,
                                    Collection<NewSegment> segments,
                                    Collection<NewChamberResult> chambers,
                                    Collection<TieInResult> tieIns,
                                    Collection<ReconstructionResult> reconstructions,
                                    Collection<ChamberReconstructionResult> chamberReconstructions,
                                    List<String> unconnectedOksIds,
                                    Map<String, String> unconnectedReasons,
                                    Map<String, FutureOks> oksById) {
        double construction = segments.stream().mapToDouble(NewSegment::getCost).sum();
        double chamberConstruction = chambers.stream().mapToDouble(NewChamberResult::getCost).sum();
        double tieInCost = tieIns.stream().mapToDouble(TieInResult::getCost).sum();
        double reconstruction = reconstructions.stream()
                .mapToDouble(ReconstructionResult::getCost).sum();
        double chamberReconstruction = chamberReconstructions.stream()
                .mapToDouble(ChamberReconstructionResult::getCost).sum();

        // Раздел 8.3: штраф 100 млн за сам факт неподключения плюс 500 тыс. за 1 т/ч.
        double penalty = unconnectedOksIds.stream()
                .map(oksById::get)
                .filter(java.util.Objects::nonNull)
                .mapToDouble(o -> catalog.unconnectedPenalty(o.getFlowTph()))
                .sum();

        double total = construction + chamberConstruction + tieInCost
                + reconstruction + chamberReconstruction + penalty;

        double newLength = segments.stream().mapToDouble(NewSegment::getLength).sum();
        double reconLength = reconstructions.stream()
                .mapToDouble(ReconstructionResult::getLength).sum();
        double length = newLength + reconLength;

        return VariantSummary.builder()
                .id("summary_" + variantId)
                .variantId(variantId)
                .rank(0)
                .constructionCost(round(construction))
                .chamberConstructionCost(round(chamberConstruction))
                .tieInCost(round(tieInCost))
                .reconstructionCost(round(reconstruction))
                .chamberReconstructionCost(round(chamberReconstruction))
                .unconnectedPenalty(round(penalty))
                .calculatedCost(round(total))
                .newNetworkLength(round2(newLength))
                .reconstructionLength(round2(reconLength))
                .length(round2(length))
                .score(round3(catalog.score(total, length)))
                .unconnectedOksIds(List.copyOf(unconnectedOksIds))
                .unconnectedReasons(Map.copyOf(unconnectedReasons))
                .build();
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
