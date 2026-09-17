package ru.lct.heatroute.export;

import lombok.Value;
import org.locationtech.jts.geom.Geometry;
import org.springframework.stereotype.Component;
import ru.lct.heatroute.domain.result.CalculationVariant;
import ru.lct.heatroute.domain.result.ChamberReconstructionResult;
import ru.lct.heatroute.domain.result.NewChamberResult;
import ru.lct.heatroute.domain.result.NewSegment;
import ru.lct.heatroute.domain.result.ReconstructionResult;
import ru.lct.heatroute.domain.result.TechnicalNodeResult;
import ru.lct.heatroute.domain.result.TieInResult;
import ru.lct.heatroute.domain.result.VariantSummary;
import ru.lct.heatroute.geo.Geo;
import ru.lct.heatroute.geo.ProjectionService;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Единственное место, где определяется состав выходного объекта по разделу 10 ТП.
 * <p>
 * Тот же объект уходит и в файл выгрузки, и в базу. Если бы состав атрибутов
 * описывался дважды, он бы рано или поздно разошёлся, и выгрузка перестала бы
 * соответствовать тому, что показывает интерфейс.
 * <p>
 * Раздел 10 требует, чтобы у каждого типа был только свой обязательный набор полей
 * и чтобы поля чужих типов не добавлялись со значением {@code null}. Поэтому набор
 * собирается поимённо для каждого типа, а не общим отображением объекта.
 */
@Component
public class ResultFeatureFactory {

    private final ProjectionService projection;

    public ResultFeatureFactory(ProjectionService projection) {
        this.projection = projection;
    }

    /** Готовый к записи объект результата. */
    @Value
    public static class ResultFeature {
        String objectType;
        String id;
        /** Геометрия в WGS 84; {@code null} у сводной записи варианта. */
        Geometry geometry;
        /** Атрибуты в порядке таблиц раздела 10 ТП. */
        Map<String, Object> properties;
    }

    /**
     * Обходит все объекты варианта в порядке выгрузки.
     * Обход, а не список: результат может быть большим, и держать его целиком
     * в памяти незачем ни при записи файла, ни при сохранении в базу.
     */
    public void forEach(CalculationVariant variant, Consumer<ResultFeature> consumer) {
        variant.getSegments().forEach(s -> consumer.accept(segment(s)));
        variant.getTieIns().forEach(t -> consumer.accept(tieIn(t)));
        variant.getReconstructions().forEach(r -> consumer.accept(reconstruction(r)));
        variant.getChambers().forEach(c -> consumer.accept(chamber(c)));
        variant.getChamberReconstructions().forEach(c -> consumer.accept(chamberReconstruction(c)));
        variant.getTechnicalNodes().forEach(n -> consumer.accept(technicalNode(n)));
        consumer.accept(summary(variant.getSummary()));
    }

    // --- 10.1 участок новой тепловой сети ---------------------------------------------
    public ResultFeature segment(NewSegment s) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("id", s.getId());
        p.put("object_type", "heat_network");
        p.put("variant_id", s.getVariantId());
        p.put("start_node_id", s.getStartNodeId());
        p.put("end_node_id", s.getEndNodeId());
        p.put("flow_tph", s.getFlowTph());
        p.put("diameter", s.getDiameter());
        p.put("length", s.getLength());
        p.put("laying_method", s.getLayingMethod().code());
        p.put("depth_start", s.getDepthStart());
        p.put("depth_end", s.getDepthEnd());
        p.put("cost", s.getCost());
        return new ResultFeature("heat_network", s.getId(), toWgs(s.getGeometry()), p);
    }

    // --- 10.2 точка врезки --------------------------------------------------------------
    public ResultFeature tieIn(TieInResult t) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("id", t.getId());
        p.put("object_type", "tie_in");
        p.put("variant_id", t.getVariantId());
        p.put("existing_object_id", t.getExistingObjectId());
        p.put("existing_object_type", t.getExistingObjectType());
        p.put("existing_diameter", t.getExistingDiameter());
        p.put("required_diameter", t.getRequiredDiameter());
        p.put("cost", t.getCost());
        return new ResultFeature("tie_in", t.getId(), toWgs(t.getLocation()), p);
    }

    // --- 10.3 реконструируемая часть существующей сети ----------------------------------
    public ResultFeature reconstruction(ReconstructionResult r) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("id", r.getId());
        p.put("object_type", "heat_network_reconstruction");
        p.put("variant_id", r.getVariantId());
        p.put("existing_object_id", r.getExistingObjectId());
        p.put("existing_flow_tph", r.getExistingFlowTph());
        p.put("added_flow_tph", r.getAddedFlowTph());
        p.put("calculated_flow_tph", r.getCalculatedFlowTph());
        p.put("existing_diameter", r.getExistingDiameter());
        p.put("required_diameter", r.getRequiredDiameter());
        p.put("length", r.getLength());
        p.put("cost", r.getCost());
        return new ResultFeature("heat_network_reconstruction", r.getId(),
                toWgs(r.getGeometry()), p);
    }

    // --- 10.4 новая тепловая камера ------------------------------------------------------
    public ResultFeature chamber(NewChamberResult c) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("id", c.getId());
        p.put("object_type", "heat_chamber");
        p.put("variant_id", c.getVariantId());
        p.put("diameter", c.getDiameter());
        p.put("cost", c.getCost());
        return new ResultFeature("heat_chamber", c.getId(), toWgs(c.getLocation()), p);
    }

    // --- 10.5 реконструируемая существующая камера ---------------------------------------
    public ResultFeature chamberReconstruction(ChamberReconstructionResult c) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("id", c.getId());
        p.put("object_type", "heat_chamber_reconstruction");
        p.put("variant_id", c.getVariantId());
        p.put("existing_object_id", c.getExistingObjectId());
        p.put("existing_diameter", c.getExistingDiameter());
        p.put("required_diameter", c.getRequiredDiameter());
        p.put("cost", c.getCost());
        return new ResultFeature("heat_chamber_reconstruction", c.getId(),
                toWgs(c.getLocation()), p);
    }

    // --- 10.6 технический узел ------------------------------------------------------------
    public ResultFeature technicalNode(TechnicalNodeResult n) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("id", n.getId());
        p.put("object_type", "technical_node");
        p.put("variant_id", n.getVariantId());
        return new ResultFeature("technical_node", n.getId(), toWgs(n.getLocation()), p);
    }

    // --- 10.7 сводная запись варианта ------------------------------------------------------
    public ResultFeature summary(VariantSummary s) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("id", s.getId());
        p.put("object_type", "variant_summary");
        p.put("variant_id", s.getVariantId());
        p.put("rank", s.getRank());
        p.put("construction_cost", s.getConstructionCost());
        p.put("chamber_construction_cost", s.getChamberConstructionCost());
        p.put("tie_in_cost", s.getTieInCost());
        p.put("reconstruction_cost", s.getReconstructionCost());
        p.put("chamber_reconstruction_cost", s.getChamberReconstructionCost());
        p.put("unconnected_penalty", s.getUnconnectedPenalty());
        p.put("calculated_cost", s.getCalculatedCost());
        p.put("new_network_length", s.getNewNetworkLength());
        p.put("reconstruction_length", s.getReconstructionLength());
        p.put("length", s.getLength());
        p.put("score", s.getScore());
        p.put("unconnected_oks_ids", s.getUnconnectedOksIds());
        return new ResultFeature("variant_summary", s.getId(), null, p);
    }

    /**
     * Обратное преобразование в WGS 84 с округлением до седьмого знака — около
     * сантиметра на широте Москвы. Больше знаков смысла не несут и только
     * раздувают файл.
     */
    private Geometry toWgs(Geometry projected) {
        if (projected == null || projected.isEmpty()) {
            return null;
        }
        Geometry wgs = projection.unproject(projected);
        wgs.apply((org.locationtech.jts.geom.CoordinateFilter) c -> {
            c.x = Math.round(c.x * 1e7) / 1e7;
            c.y = Math.round(c.y * 1e7) / 1e7;
        });
        wgs.geometryChanged();
        wgs.setSRID(4326);
        return wgs;
    }

    /** Точность вывода координат — для документации контракта. */
    public static int coordinatePrecision() {
        return 7;
    }

    static {
        // Ссылка на Geo нужна, чтобы фабрика геометрии инициализировалась единожды
        // вместе с остальным расчётным слоем.
        assert Geo.FACTORY != null;
    }
}
