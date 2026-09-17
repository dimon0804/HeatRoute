package ru.lct.heatroute.service;

import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Envelope;
import org.springframework.stereotype.Component;
import ru.lct.heatroute.api.dto.DiagnosticsEntryDto;
import ru.lct.heatroute.api.dto.SceneSummaryDto;
import ru.lct.heatroute.api.dto.VariantSummaryDto;
import ru.lct.heatroute.domain.model.ExistingSegment;
import ru.lct.heatroute.domain.model.IngestDiagnostics;
import ru.lct.heatroute.domain.model.InputScene;
import ru.lct.heatroute.domain.model.RestrictionObject;
import ru.lct.heatroute.domain.result.VariantSummary;
import ru.lct.heatroute.geo.ProjectionService;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** Перевод расчётных структур в представления API. */
@Component
public class SceneMapper {

    private final ProjectionService projection;

    public SceneMapper(ProjectionService projection) {
        this.projection = projection;
    }

    public SceneSummaryDto summarize(InputScene scene) {
        Map<String, Long> counts = new LinkedHashMap<>();
        counts.put("source", scene.getSource() == null ? 0L : 1L);
        counts.put("heat_network", (long) scene.getSegments().size());
        counts.put("heat_chamber", (long) scene.getChambers().size());
        counts.put("oks_future", (long) scene.getFutureOks().size());
        counts.put("restriction", (long) scene.getRestrictions().size());

        Map<String, Long> diameters = scene.getSegments().stream()
                .collect(Collectors.groupingBy(
                        s -> String.valueOf(s.getDiameter()),
                        LinkedHashMap::new, Collectors.counting()));

        Map<String, Long> restrictionTypes = scene.getRestrictions().stream()
                .collect(Collectors.groupingBy(
                        RestrictionObject::getCanonicalType,
                        LinkedHashMap::new, Collectors.counting()));

        Envelope extent = scene.getExtent();
        double[] bbox = null;
        if (extent != null && !extent.isNull()) {
            Coordinate min = projection.unproject(extent.getMinX(), extent.getMinY());
            Coordinate max = projection.unproject(extent.getMaxX(), extent.getMaxY());
            bbox = new double[]{min.x, min.y, max.x, max.y};
        }

        return SceneSummaryDto.builder()
                .objectCounts(counts)
                .futureOksCount(scene.getFutureOks().size())
                .totalFutureFlowTph(round(scene.totalFutureFlowTph(), 2))
                .existingNetworkLength(round(scene.getSegments().stream()
                        .mapToDouble(ExistingSegment::length).sum(), 1))
                .existingDiameters(diameters)
                .restrictionTypes(restrictionTypes)
                .extentMeters(extent == null || extent.isNull() ? null
                        : new double[]{round(extent.getWidth(), 1), round(extent.getHeight(), 1)})
                .bboxWgs84(bbox)
                .build();
    }

    public List<DiagnosticsEntryDto> diagnostics(IngestDiagnostics diagnostics) {
        return diagnostics.getEntries().stream()
                .map(e -> DiagnosticsEntryDto.builder()
                        .severity(e.getSeverity().name())
                        .code(e.getCode())
                        .message(e.getMessage())
                        .objectIds(e.getObjectIds())
                        .build())
                .collect(Collectors.toList());
    }

    public VariantSummaryDto summary(VariantSummary s) {
        return VariantSummaryDto.builder()
                .rank(s.getRank())
                .constructionCost(s.getConstructionCost())
                .chamberConstructionCost(s.getChamberConstructionCost())
                .tieInCost(s.getTieInCost())
                .reconstructionCost(s.getReconstructionCost())
                .chamberReconstructionCost(s.getChamberReconstructionCost())
                .unconnectedPenalty(s.getUnconnectedPenalty())
                .calculatedCost(s.getCalculatedCost())
                .newNetworkLength(s.getNewNetworkLength())
                .reconstructionLength(s.getReconstructionLength())
                .length(s.getLength())
                .score(s.getScore())
                .unconnectedOksIds(s.getUnconnectedOksIds())
                .build();
    }

    private static double round(double v, int digits) {
        double f = Math.pow(10, digits);
        return Math.round(v * f) / f;
    }
}
