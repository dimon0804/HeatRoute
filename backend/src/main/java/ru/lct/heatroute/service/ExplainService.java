package ru.lct.heatroute.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.LineString;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.lct.heatroute.api.dto.SegmentExplanationDto;
import ru.lct.heatroute.domain.model.FutureOks;
import ru.lct.heatroute.domain.model.InputScene;
import ru.lct.heatroute.explain.RouteExplainer;
import ru.lct.heatroute.geo.ProjectionService;
import ru.lct.heatroute.persistence.CalculationJobEntity;
import ru.lct.heatroute.persistence.CalculationJobRepository;
import ru.lct.heatroute.persistence.VariantEntity;
import ru.lct.heatroute.persistence.VariantFeatureEntity;
import ru.lct.heatroute.persistence.VariantFeatureRepository;
import ru.lct.heatroute.persistence.VariantRepository;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Разбор трассы варианта: чем зажат каждый участок и насколько свободно он лежит.
 * <p>
 * Участки берутся из сохранённой выгрузки, обстановка — из входного набора расчёта.
 * Ни то ни другое не хранится специально для разбора: он считается по запросу, потому
 * что спрашивают его про отдельный участок и уже после того, как результат увиден.
 */
@Slf4j
@Service
@Profile("!nodb")
public class ExplainService {

    private final RouteExplainer explainer;
    private final ProjectionService projection;
    private final ObjectMapper json;
    private final CalculationJobRepository jobs;
    private final VariantRepository variants;
    private final VariantFeatureRepository features;
    private final DatasetService datasets;

    public ExplainService(RouteExplainer explainer,
                          ProjectionService projection,
                          ObjectMapper json,
                          CalculationJobRepository jobs,
                          VariantRepository variants,
                          VariantFeatureRepository features,
                          DatasetService datasets) {
        this.explainer = explainer;
        this.projection = projection;
        this.json = json;
        this.jobs = jobs;
        this.variants = variants;
        this.features = features;
        this.datasets = datasets;
    }

    /**
     * @param variantCode код варианта; пусто — берётся занявший первое место
     */
    @Transactional(readOnly = true)
    public List<SegmentExplanationDto> explain(UUID jobId, String variantCode) {
        CalculationJobEntity job = jobs.findById(jobId)
                .orElseThrow(() -> new IllegalArgumentException("Расчёт не найден: " + jobId));
        if (job.getDataset() == null) {
            throw new IllegalStateException(
                    "У расчёта нет входного набора: разобрать трассу не с чем");
        }
        List<VariantEntity> ranked = variants.findByJobIdOrderByRankAsc(jobId);
        VariantEntity variant = ranked.stream()
                .filter(v -> variantCode == null || variantCode.isEmpty()
                        || variantCode.equals(v.getVariantCode()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "Вариант не найден: " + variantCode));

        InputScene scene = datasets.parse(job.getDataset());
        Set<String> connectionPointIds = scene.getFutureOks().stream()
                .map(FutureOks::getConnectionPointId)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Map<String, String> oksByPoint = new LinkedHashMap<>();
        scene.getFutureOks().forEach(oks ->
                oksByPoint.put(oks.getConnectionPointId(), oks.getId()));

        List<RouteExplainer.Segment> segments = new ArrayList<>();
        for (VariantFeatureEntity row : features.findByVariantIdAndObjectTypeOrderByOrdinalAsc(
                variant.getId(), "heat_network")) {
            Map<String, Object> properties = properties(row);
            Geometry geometry = row.getGeom();
            if (!(geometry instanceof LineString)) {
                continue;
            }
            LineString projected = (LineString) projection.project(geometry);

            // Собственный контур ОКС исключается только у того участка, который заходит
            // к его точке подключения: для остальных трасс это здание остаётся обычным
            // препятствием с полным клиренсом, и показывать его как «зажимающее» неверно.
            String exempt = null;
            for (String node : List.of(String.valueOf(properties.get("start_node_id")),
                    String.valueOf(properties.get("end_node_id")))) {
                if (connectionPointIds.contains(node)) {
                    exempt = oksByPoint.get(node);
                }
            }

            segments.add(new RouteExplainer.Segment(String.valueOf(properties.get("id")),
                    intOf(properties.get("diameter")), projected, exempt));
        }

        List<SegmentExplanationDto> out = explainer.explain(scene, segments).stream()
                .map(ExplainService::toDto)
                .collect(Collectors.toList());
        log.info("Разбор трассы расчёта {} варианта {}: участков {}",
                jobId, variant.getVariantCode(), out.size());
        return out;
    }

    private static SegmentExplanationDto toDto(RouteExplainer.SegmentExplanation source) {
        return SegmentExplanationDto.builder()
                .segmentId(source.getSegmentId())
                .diameter(source.getDiameter())
                .lengthM(source.getLengthM())
                .straightM(source.getStraightM())
                .detourShare(source.getDetourShare())
                .tightestMarginM(Double.isNaN(source.getTightestMarginM())
                        ? null : source.getTightestMarginM())
                .nearby(source.getNearby().stream()
                        .map(n -> SegmentExplanationDto.Nearby.builder()
                                .restrictionId(n.getRestrictionId())
                                .type(n.getType())
                                .rule(n.getRule())
                                .distanceM(n.getDistanceM())
                                .requiredM(n.getRequiredM())
                                .marginM(n.getMarginM())
                                .binding(n.isBinding())
                                .attachment(n.isAttachment())
                                .build())
                        .collect(Collectors.toList()))
                .crossings(source.getCrossings().stream()
                        .map(c -> SegmentExplanationDto.Crossing.builder()
                                .restrictionId(c.getRestrictionId())
                                .type(c.getType())
                                .kSpecial(c.getKSpecial())
                                .share(c.getShare())
                                .build())
                        .collect(Collectors.toList()))
                .verdict(source.getVerdict())
                .build();
    }

    private static int intOf(Object value) {
        return value instanceof Number ? ((Number) value).intValue() : 0;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> properties(VariantFeatureEntity row) {
        try {
            return json.readValue(row.getPropertiesJson(), LinkedHashMap.class);
        } catch (Exception e) {
            log.warn("Не удалось прочитать атрибуты объекта {}: {}",
                    row.getFeatureId(), e.getMessage());
            return new LinkedHashMap<>();
        }
    }
}
