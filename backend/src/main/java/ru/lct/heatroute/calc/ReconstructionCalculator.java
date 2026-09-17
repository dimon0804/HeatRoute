package ru.lct.heatroute.calc;

import lombok.Value;
import lombok.extern.slf4j.Slf4j;
import org.locationtech.jts.geom.LineString;
import org.springframework.stereotype.Component;
import ru.lct.heatroute.domain.model.ExistingChamber;
import ru.lct.heatroute.domain.model.ExistingSegment;
import ru.lct.heatroute.domain.model.ExistingTopology;
import ru.lct.heatroute.domain.model.InputScene;
import ru.lct.heatroute.domain.reference.ReferenceCatalog;
import ru.lct.heatroute.domain.reference.ReferenceProperties.DiameterRow;
import ru.lct.heatroute.domain.result.ChamberReconstructionResult;
import ru.lct.heatroute.domain.result.ReconstructionResult;
import ru.lct.heatroute.domain.result.TieInResult;
import ru.lct.heatroute.geo.Geo;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Проверка существующей сети на пропускную способность и расчёт реконструкции
 * (раздел 7 ТП).
 * <p>
 * Дополнительный расход от каждой точки врезки распространяется по цепочке
 * {@code upstream_object_id} к источнику и складывается с существующим расчётным
 * расходом каждого затронутого участка. Расходы от разных врезок на общей части
 * суммируются.
 * <p>
 * Отдельно разбирается врезка внутрь линейного участка: дополнительный расход
 * действует только на часть участка от точки врезки в сторону источника, и в выгрузку
 * идёт именно эта часть с фактической геометрией, а не весь исходный участок.
 * Если на одном участке оказалось несколько врезок, он делится ими на интервалы,
 * и каждый интервал несёт сумму расходов тех врезок, что находятся дальше от источника.
 */
@Slf4j
@Component
public class ReconstructionCalculator {

    private final ReferenceCatalog catalog;

    public ReconstructionCalculator(ReferenceCatalog catalog) {
        this.catalog = catalog;
    }

    @Value
    public static class Result {
        List<ReconstructionResult> segments;
        List<ChamberReconstructionResult> chambers;
        /** Требуемый после подключения условный диаметр по ID существующего участка. */
        Map<String, Integer> requiredDiameterBySegment;
    }

    /**
     * @param chamberMaxDnFromNew наибольший ДУ новых участков, примыкающих к камере врезки
     */
    public Result compute(InputScene scene,
                          List<TieInResult> tieIns,
                          Map<String, Integer> chamberMaxDnFromNew,
                          String variantId) {
        ExistingTopology topology = scene.getTopology();

        // --- 1. Дополнительный расход по объектам цепочки --------------------------------
        Map<String, Double> addedByObject = new LinkedHashMap<>();
        // Врезки внутрь линейного участка: положение и расход по каждому участку.
        Map<String, List<double[]>> tieInsOnSegment = new LinkedHashMap<>();
        Set<String> chambersUsedForTieIn = new LinkedHashSet<>();

        for (TieInResult tie : tieIns) {
            double flow = tie.getAddedFlowTph();
            if (flow <= 0) {
                continue;
            }
            String startObject = tie.getExistingObjectId();

            if ("heat_chamber".equals(tie.getExistingObjectType())) {
                chambersUsedForTieIn.add(startObject);
            } else {
                // Точка врезки внутри участка: сам участок обрабатывается интервалами.
                tieInsOnSegment.computeIfAbsent(startObject, k -> new ArrayList<>())
                        .add(new double[]{tie.getPositionFraction(), flow});
            }

            for (String objectId : topology.chainToSource(startObject)) {
                if (objectId.equals(topology.getSourceId())) {
                    break;
                }
                addedByObject.merge(objectId, flow, Double::sum);
            }
        }

        // --- 2. Реконструкция линейных участков -------------------------------------------
        List<ReconstructionResult> reconstructions = new ArrayList<>();
        Map<String, Integer> requiredBySegment = new LinkedHashMap<>();
        int counter = 0;

        for (ExistingSegment segment : scene.getSegments()) {
            List<double[]> local = tieInsOnSegment.get(segment.getId());
            double throughFlow = addedByObject.getOrDefault(segment.getId(), 0d);

            if (local == null || local.isEmpty()) {
                if (throughFlow <= 0) {
                    continue;
                }
                counter = addWholeSegment(scene, segment, throughFlow, variantId,
                        reconstructions, requiredBySegment, counter);
                continue;
            }
            counter = addPartialSegment(scene, segment, local, throughFlow, topology,
                    variantId, reconstructions, requiredBySegment, counter);
        }

        // --- 3. Реконструкция камер врезки -------------------------------------------------
        List<ChamberReconstructionResult> chamberResults = new ArrayList<>();
        for (String chamberId : chambersUsedForTieIn) {
            ExistingChamber chamber = scene.getChambersById().get(chamberId);
            if (chamber == null) {
                continue;
            }
            // Раздел 8.2 ТП: требуемый ДУ камеры — наибольший условный диаметр всех
            // примыкающих участков в итоговом варианте, с учётом новых участков
            // и требуемых после реконструкции диаметров существующих.
            int required = chamberMaxDnFromNew.getOrDefault(chamberId, 0);
            Integer nodeIndex = topology.getChamberNode().get(chamberId);
            if (nodeIndex != null) {
                for (String adjacentId : topology.getNodes().get(nodeIndex).getSegmentIds()) {
                    ExistingSegment adjacent = scene.getSegmentsById().get(adjacentId);
                    if (adjacent == null) {
                        continue;
                    }
                    required = Math.max(required, requiredBySegment
                            .getOrDefault(adjacentId, adjacent.getDiameter()));
                }
            }
            if (required <= chamber.getDiameter()) {
                continue;
            }
            chamberResults.add(ChamberReconstructionResult.builder()
                    .id("chrec_" + (chamberResults.size() + 1))
                    .variantId(variantId)
                    .location(chamber.getLocation())
                    .existingObjectId(chamberId)
                    .existingDiameter(chamber.getDiameter())
                    .requiredDiameter(required)
                    .cost(catalog.chamberCost(required))
                    .build());
        }

        return new Result(reconstructions, chamberResults, requiredBySegment);
    }

    // =================================================================================

    private int addWholeSegment(InputScene scene,
                                ExistingSegment segment,
                                double added,
                                String variantId,
                                List<ReconstructionResult> out,
                                Map<String, Integer> requiredBySegment,
                                int counter) {
        double total = segment.getFlowTph() + added;
        int required = requiredDn(total);
        requiredBySegment.put(segment.getId(), Math.max(required, segment.getDiameter()));
        if (required <= segment.getDiameter()) {
            return counter;
        }
        double length = segment.length();
        out.add(ReconstructionResult.builder()
                .id("recon_" + (++counter))
                .variantId(variantId)
                .geometry(segment.getGeometry())
                .existingObjectId(segment.getId())
                .existingFlowTph(segment.getFlowTph())
                .addedFlowTph(round(added))
                .calculatedFlowTph(round(total))
                .existingDiameter(segment.getDiameter())
                .requiredDiameter(required)
                .length(Geo.roundCm(length))
                .cost(Math.round(length * catalog.reconCostPerM(required)))
                .build());
        return counter;
    }

    /**
     * Участок с врезками внутри. Положения врезок приводятся к доле длины от конца,
     * обращённого к источнику, и делят участок на интервалы: каждый несёт сумму
     * расходов тех врезок, что лежат дальше от источника, плюс транзитный расход
     * от врезок ниже по цепочке.
     */
    private int addPartialSegment(InputScene scene,
                                  ExistingSegment segment,
                                  List<double[]> localTieIns,
                                  double throughFlow,
                                  ExistingTopology topology,
                                  String variantId,
                                  List<ReconstructionResult> out,
                                  Map<String, Integer> requiredBySegment,
                                  int counter) {
        LineString line = segment.getGeometry();
        int[] ends = topology.getSegmentNodes().get(segment.getId());
        Integer upstreamNode = topology.getSegmentUpstreamNode().get(segment.getId());
        boolean upstreamIsStart = ends == null || upstreamNode == null || upstreamNode == ends[0];

        // u — доля длины от конца, обращённого к источнику.
        TreeMap<Double, Double> flowAt = new TreeMap<>();
        for (double[] tie : localTieIns) {
            double u = upstreamIsStart ? tie[0] : 1 - tie[0];
            flowAt.merge(clamp(u), tie[1], Double::sum);
        }

        List<Double> cuts = new ArrayList<>(flowAt.keySet());
        cuts.add(0, 0d);
        cuts.add(1d);
        cuts.sort(Comparator.naturalOrder());

        int maxRequired = segment.getDiameter();

        for (int i = 0; i + 1 < cuts.size(); i++) {
            double a = cuts.get(i);
            double b = cuts.get(i + 1);
            if (b - a < 1e-9) {
                continue;
            }
            // Интервал несёт расходы всех врезок, лежащих не ближе его дальнего конца.
            double added = throughFlow;
            for (Map.Entry<Double, Double> e : flowAt.entrySet()) {
                if (e.getKey() >= b - 1e-9) {
                    added += e.getValue();
                }
            }
            if (added <= 0) {
                continue;
            }
            double total = segment.getFlowTph() + added;
            int required = requiredDn(total);
            maxRequired = Math.max(maxRequired, required);
            if (required <= segment.getDiameter()) {
                continue;
            }

            double fa = upstreamIsStart ? a : 1 - a;
            double fb = upstreamIsStart ? b : 1 - b;
            LineString part = Geo.substring(line, Math.min(fa, fb), Math.max(fa, fb));
            double length = part.getLength();
            if (length <= 0) {
                continue;
            }
            out.add(ReconstructionResult.builder()
                    .id("recon_" + (++counter))
                    .variantId(variantId)
                    .geometry(part)
                    .existingObjectId(segment.getId())
                    .existingFlowTph(segment.getFlowTph())
                    .addedFlowTph(round(added))
                    .calculatedFlowTph(round(total))
                    .existingDiameter(segment.getDiameter())
                    .requiredDiameter(required)
                    .length(Geo.roundCm(length))
                    .cost(Math.round(length * catalog.reconCostPerM(required)))
                    .build());
        }
        requiredBySegment.put(segment.getId(), maxRequired);
        return counter;
    }

    private int requiredDn(double flow) {
        return catalog.selectForFlow(flow).map(DiameterRow::getDn)
                .orElseGet(() -> catalog.largest().getDn());
    }

    private static double clamp(double v) {
        return Math.max(0, Math.min(1, v));
    }

    private static double round(double v) {
        return Math.round(v * 1000d) / 1000d;
    }
}
