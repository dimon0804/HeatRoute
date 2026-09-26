package ru.lct.heatroute.export;

import lombok.Value;
import org.locationtech.jts.geom.Geometry;
import org.springframework.stereotype.Component;
import ru.lct.heatroute.domain.result.CalculationVariant;
import ru.lct.heatroute.domain.result.NewChamberResult;
import ru.lct.heatroute.domain.result.NewSegment;
import ru.lct.heatroute.domain.result.TechnicalNodeResult;
import ru.lct.heatroute.domain.result.VariantSummary;
import ru.lct.heatroute.geo.Geo;
import ru.lct.heatroute.geo.ProjectionService;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Единственное место, где определяется состав выходного объекта по разделу 7 ТП
 * в редакции от 18.09.
 * <p>
 * Тот же объект уходит и в файл выгрузки, и в базу. Если бы состав атрибутов
 * описывался дважды, он бы рано или поздно разошёлся, и выгрузка перестала бы
 * соответствовать тому, что показывает интерфейс.
 * <p>
 * Раздел 7.1 перечисляет ровно четыре типа выходных объектов: участок новой сети,
 * новая тепловая камера, технический узел и сводка по варианту. Точек врезки
 * и объектов реконструкции больше нет. Дополнительные свойства приложение разрешает
 * и при проверке игнорирует, а вот лишних типов объектов не предусматривает, поэтому
 * состав типов держим ровно по разделу 7.1.
 * <p>
 * Пересечения по глубине — единственное исключение, и оно намеренно выведено из
 * выгрузки: {@link #forEach} их не отдаёт, а {@link #forEachWithDiagnostics} отдаёт.
 * Первый обход питает выходной файл, второй — базу и интерфейс.
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
        /** Атрибуты в порядке таблиц раздела 7 ТП. */
        Map<String, Object> properties;
    }

    /**
     * Обходит все объекты варианта в порядке выгрузки.
     * Обход, а не список: результат может быть большим, и держать его целиком
     * в памяти незачем ни при записи файла, ни при сохранении в базу.
     */
    public void forEach(CalculationVariant variant, Consumer<ResultFeature> consumer) {
        variant.getSegments().forEach(s -> consumer.accept(segment(s)));
        variant.getChambers().forEach(c -> consumer.accept(chamber(c)));
        variant.getTechnicalNodes().forEach(n -> consumer.accept(technicalNode(n)));
        consumer.accept(summary(variant.getSummary()));
    }

    /**
     * То же плюс пересечения по глубине. Этот обход идёт в базу и дальше в интерфейс:
     * на защите пересечения показывают на продольном профиле, и без них панель глубины
     * собрать не из чего. В выходной файл такие объекты не попадают — там состав
     * ровно по разделу 7.1.
     */
    public void forEachWithDiagnostics(CalculationVariant variant,
                                       Consumer<ResultFeature> consumer) {
        forEach(variant, consumer);
        variant.getDepthCrossings().forEach(c -> consumer.accept(depthCrossing(c, variant)));
    }

    /** Типы объектов, которые приложение разрешает в выходном файле (раздел 7.1). */
    public static java.util.Set<String> exportedObjectTypes() {
        return java.util.Set.of("heat_network", "heat_chamber", "technical_node",
                "variant_summary");
    }

    // --- 7.2 участок новой тепловой сети ----------------------------------------------
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

    // --- 7.2 новая тепловая камера --------------------------------------------------------
    public ResultFeature chamber(NewChamberResult c) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("id", c.getId());
        p.put("object_type", "heat_chamber");
        p.put("variant_id", c.getVariantId());
        p.put("diameter", c.getDiameter());
        // Свойство сверх обязательного состава — приложение такие разрешает и при
        // проверке игнорирует. Здесь оно по делу: правило четырёх примыканий проверяют
        // по этому числу, и ведомость печатает его же.
        p.put("degree", c.getDegree());
        p.put("cost", c.getCost());
        return new ResultFeature("heat_chamber", c.getId(), toWgs(c.getLocation()), p);
    }

    // --- 7.2 технический узел -------------------------------------------------------------
    public ResultFeature technicalNode(TechnicalNodeResult n) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("id", n.getId());
        p.put("object_type", "technical_node");
        p.put("variant_id", n.getVariantId());
        return new ResultFeature("technical_node", n.getId(), toWgs(n.getLocation()), p);
    }

    /**
     * Пересечение с существующей коммуникацией по глубине.
     * <p>
     * Тип объекта сверх раздела 7.1 ТП, и поэтому он живёт только внутри сервиса:
     * в базе и в интерфейсе. В выходной файл не попадает. Смысл в нём остался —
     * показать, где трасса проходит над коммуникацией, а где под ней, и с каким
     * просветом. В плоской задаче таких объектов не бывает вовсе.
     */
    public ResultFeature depthCrossing(ru.lct.heatroute.depth.UtilityCrossing c,
                                       ru.lct.heatroute.domain.result.CalculationVariant variant) {
        String id = "cross_" + variant.getVariantId() + "_" + c.getSegmentId()
                + "_" + Math.round(c.getStation());
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("id", id);
        p.put("object_type", "depth_crossing");
        p.put("variant_id", variant.getVariantId());
        p.put("segment_id", c.getSegmentId());
        // Где именно на участке: без этого пересечение не разместить на продольном
        // профиле, а по одной только точке на карте его место в разрезе не прочесть.
        p.put("station", round(c.getStation()));
        p.put("utility_id", c.getUtilityId());
        p.put("utility_type", c.getUtilityType());
        p.put("passage", c.getPassage() == ru.lct.heatroute.depth.UtilityCrossing.Passage.ABOVE
                ? "above" : "below");
        p.put("new_depth", round(c.getNewDepth()));
        p.put("utility_depth", round(c.getUtilityDepthToTop()));
        p.put("required_clearance", round(c.getRequiredClearance()));
        p.put("actual_clearance", round(c.getActualClearance()));
        return new ResultFeature("depth_crossing", id,
                toWgs(Geo.point(c.getLocation())), p);
    }

    private static double round(double v) {
        return Math.round(v * 100d) / 100d;
    }

    // --- 7.2 сводная запись варианта ------------------------------------------------------
    public ResultFeature summary(VariantSummary s) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("id", s.getId());
        p.put("object_type", "variant_summary");
        p.put("variant_id", s.getVariantId());
        p.put("rank", s.getRank());
        p.put("construction_cost", s.getConstructionCost());
        p.put("chamber_construction_cost", s.getChamberConstructionCost());
        p.put("existing_chamber_tie_in_count", s.getExistingChamberTieInCount());
        p.put("existing_chamber_tie_in_cost", s.getExistingChamberTieInCost());
        p.put("unconnected_penalty", s.getUnconnectedPenalty());
        p.put("calculated_cost", s.getCalculatedCost());
        p.put("new_network_length", s.getNewNetworkLength());
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
