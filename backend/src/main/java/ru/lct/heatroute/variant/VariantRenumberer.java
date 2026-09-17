package ru.lct.heatroute.variant;

import lombok.Value;
import org.springframework.stereotype.Component;
import ru.lct.heatroute.domain.result.NewChamberResult;
import ru.lct.heatroute.domain.result.NewSegment;
import ru.lct.heatroute.domain.result.TechnicalNodeResult;
import ru.lct.heatroute.domain.result.TieInResult;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Сквозная перенумерация объектов варианта перед выдачей.
 * <p>
 * Пока подбирается лучшая точка врезки, перебираются десятки кандидатов, и каждый
 * расходует идентификаторы. В итоговом варианте оставались номера вида {@code new_v2_1663}
 * при двадцати семи участках. Формально это допустимо — идентификаторы уникальны, —
 * но выгрузку читают эксперты, и разрывы в нумерации выглядят как потерянные объекты.
 * <p>
 * Перенумерация идёт с переписыванием ссылок: концы участков указывают на камеры
 * и технические узлы этого же варианта. Идентификаторы объектов входного набора —
 * существующих камер и точек подключения ОКС — не меняются: они принадлежат не нам.
 */
@Component
public class VariantRenumberer {

    /** Результат перенумерации: новые списки и соответствие старых идентификаторов новым. */
    @Value
    public static class Renumbered {
        List<NewSegment> segments;
        List<NewChamberResult> chambers;
        List<TechnicalNodeResult> technicalNodes;
        List<TieInResult> tieIns;
        /** Старый идентификатор узла → новый. Нужен для пересчёта диаметров камер. */
        Map<String, String> nodeIdMapping;
    }

    public Renumbered renumber(String variantId,
                               List<NewSegment> segments,
                               List<NewChamberResult> chambers,
                               List<TechnicalNodeResult> technicalNodes,
                               List<TieInResult> tieIns) {
        Map<String, String> nodeMapping = new LinkedHashMap<>();

        List<NewChamberResult> newChambers = new ArrayList<>(chambers.size());
        int chamberNo = 0;
        for (NewChamberResult chamber : chambers) {
            String id = "ch_" + variantId + "_" + (++chamberNo);
            nodeMapping.put(chamber.getId(), id);
            newChambers.add(chamber.toBuilder().id(id).build());
        }

        List<TechnicalNodeResult> newNodes = new ArrayList<>(technicalNodes.size());
        int nodeNo = 0;
        for (TechnicalNodeResult node : technicalNodes) {
            String id = "tn_" + variantId + "_" + (++nodeNo);
            nodeMapping.put(node.getId(), id);
            newNodes.add(node.toBuilder().id(id).build());
        }

        List<TieInResult> newTieIns = new ArrayList<>(tieIns.size());
        int tieNo = 0;
        for (TieInResult tie : tieIns) {
            String id = "tie_" + variantId + "_" + (++tieNo);
            nodeMapping.put(tie.getId(), id);
            newTieIns.add(tie.toBuilder().id(id).build());
        }

        List<NewSegment> newSegments = new ArrayList<>(segments.size());
        int segmentNo = 0;
        for (NewSegment segment : segments) {
            newSegments.add(segment.toBuilder()
                    .id("new_" + variantId + "_" + (++segmentNo))
                    .startNodeId(nodeMapping.getOrDefault(
                            segment.getStartNodeId(), segment.getStartNodeId()))
                    .endNodeId(nodeMapping.getOrDefault(
                            segment.getEndNodeId(), segment.getEndNodeId()))
                    .build());
        }

        return new Renumbered(newSegments, newChambers, newNodes, newTieIns, nodeMapping);
    }

    /** Переносит наибольшие диаметры камер на новые идентификаторы. */
    public Map<String, Integer> remapChamberDiameters(Map<String, Integer> source,
                                                      Map<String, String> nodeMapping) {
        Map<String, Integer> out = new LinkedHashMap<>();
        source.forEach((id, dn) -> out.put(nodeMapping.getOrDefault(id, id), dn));
        return out;
    }
}
