package ru.lct.heatroute.export;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import lombok.extern.slf4j.Slf4j;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.CoordinateSequence;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.Point;
import org.springframework.stereotype.Component;
import ru.lct.heatroute.domain.result.CalculationVariant;
import ru.lct.heatroute.domain.result.ChamberReconstructionResult;
import ru.lct.heatroute.domain.result.NewChamberResult;
import ru.lct.heatroute.domain.result.NewSegment;
import ru.lct.heatroute.domain.result.ReconstructionResult;
import ru.lct.heatroute.domain.result.TechnicalNodeResult;
import ru.lct.heatroute.domain.result.TieInResult;
import ru.lct.heatroute.domain.result.VariantSummary;
import ru.lct.heatroute.geo.ProjectionService;

import java.io.IOException;
import java.io.OutputStream;
import java.util.List;

/**
 * Выгрузка результата в один совмещённый файл GeoJSON (раздел 10 ТП).
 * <p>
 * Запись потоковая: объекты уходят в выходной поток по одному, документ целиком
 * в памяти не собирается. Требование ТЗ — выгрузка до 500 МБ, а на конкурсном наборе
 * с тремя вариантами это десятки тысяч объектов.
 * <p>
 * Правило раздела 10, которое легко нарушить и которое проверяется: «для каждого типа
 * выходного объекта используется только собственный обязательный набор атрибутов,
 * поля других типов не добавляются со значением null». Поэтому каждый тип пишется
 * своим методом, а не общим отображением с пропуском пустых значений — так забыть
 * или добавить лишнее поле нельзя.
 */
@Slf4j
@Component
public class GeoJsonResultWriter {

    private final JsonFactory factory = new JsonFactory();
    private final ProjectionService projection;

    public GeoJsonResultWriter(ProjectionService projection) {
        this.projection = projection;
    }

    /** Пишет все варианты одной коллекцией. Возвращает число записанных объектов. */
    public long write(OutputStream out, List<CalculationVariant> variants) throws IOException {
        long count = 0;
        JsonGenerator g = factory.createGenerator(out);
        try {
            g.writeStartObject();
            g.writeStringField("type", "FeatureCollection");

            // Геометрия выходного файла — в WGS 84, как требует раздел 10 ТП.
            g.writeFieldName("crs");
            g.writeStartObject();
            g.writeStringField("type", "name");
            g.writeFieldName("properties");
            g.writeStartObject();
            g.writeStringField("name", "urn:ogc:def:crs:OGC:1.3:CRS84");
            g.writeEndObject();
            g.writeEndObject();

            g.writeArrayFieldStart("features");
            for (CalculationVariant v : variants) {
                for (NewSegment s : v.getSegments()) {
                    writeSegment(g, s);
                    count++;
                }
                for (TieInResult t : v.getTieIns()) {
                    writeTieIn(g, t);
                    count++;
                }
                for (ReconstructionResult r : v.getReconstructions()) {
                    writeReconstruction(g, r);
                    count++;
                }
                for (NewChamberResult c : v.getChambers()) {
                    writeChamber(g, c);
                    count++;
                }
                for (ChamberReconstructionResult c : v.getChamberReconstructions()) {
                    writeChamberReconstruction(g, c);
                    count++;
                }
                for (TechnicalNodeResult n : v.getTechnicalNodes()) {
                    writeTechnicalNode(g, n);
                    count++;
                }
                writeSummary(g, v.getSummary());
                count++;
            }
            g.writeEndArray();
            g.writeEndObject();
        } finally {
            g.flush();
        }
        log.debug("Выгружено объектов: {} по {} вариантам", count, variants.size());
        return count;
    }

    // =================================================================================
    //  Типы выходных объектов
    // =================================================================================

    /** Раздел 10.1: участок новой тепловой сети. */
    private void writeSegment(JsonGenerator g, NewSegment s) throws IOException {
        startFeature(g, s.getGeometry());
        g.writeStringField("id", s.getId());
        g.writeStringField("object_type", "heat_network");
        g.writeStringField("variant_id", s.getVariantId());
        g.writeStringField("start_node_id", s.getStartNodeId());
        g.writeStringField("end_node_id", s.getEndNodeId());
        g.writeNumberField("flow_tph", s.getFlowTph());
        g.writeNumberField("diameter", s.getDiameter());
        g.writeNumberField("length", s.getLength());
        g.writeStringField("laying_method", s.getLayingMethod().code());
        writeNullableNumber(g, "depth_start", s.getDepthStart());
        writeNullableNumber(g, "depth_end", s.getDepthEnd());
        g.writeNumberField("cost", s.getCost());
        endFeature(g);
    }

    /** Раздел 10.2: точка врезки. */
    private void writeTieIn(JsonGenerator g, TieInResult t) throws IOException {
        startFeature(g, t.getLocation());
        g.writeStringField("id", t.getId());
        g.writeStringField("object_type", "tie_in");
        g.writeStringField("variant_id", t.getVariantId());
        g.writeStringField("existing_object_id", t.getExistingObjectId());
        g.writeStringField("existing_object_type", t.getExistingObjectType());
        g.writeNumberField("existing_diameter", t.getExistingDiameter());
        g.writeNumberField("required_diameter", t.getRequiredDiameter());
        g.writeNumberField("cost", t.getCost());
        endFeature(g);
    }

    /** Раздел 10.3: реконструируемая часть существующей сети. */
    private void writeReconstruction(JsonGenerator g, ReconstructionResult r) throws IOException {
        startFeature(g, r.getGeometry());
        g.writeStringField("id", r.getId());
        g.writeStringField("object_type", "heat_network_reconstruction");
        g.writeStringField("variant_id", r.getVariantId());
        g.writeStringField("existing_object_id", r.getExistingObjectId());
        g.writeNumberField("existing_flow_tph", r.getExistingFlowTph());
        g.writeNumberField("added_flow_tph", r.getAddedFlowTph());
        g.writeNumberField("calculated_flow_tph", r.getCalculatedFlowTph());
        g.writeNumberField("existing_diameter", r.getExistingDiameter());
        g.writeNumberField("required_diameter", r.getRequiredDiameter());
        g.writeNumberField("length", r.getLength());
        g.writeNumberField("cost", r.getCost());
        endFeature(g);
    }

    /** Раздел 10.4: новая тепловая камера. */
    private void writeChamber(JsonGenerator g, NewChamberResult c) throws IOException {
        startFeature(g, c.getLocation());
        g.writeStringField("id", c.getId());
        g.writeStringField("object_type", "heat_chamber");
        g.writeStringField("variant_id", c.getVariantId());
        g.writeNumberField("diameter", c.getDiameter());
        g.writeNumberField("cost", c.getCost());
        endFeature(g);
    }

    /** Раздел 10.5: реконструируемая существующая камера. */
    private void writeChamberReconstruction(JsonGenerator g, ChamberReconstructionResult c)
            throws IOException {
        startFeature(g, c.getLocation());
        g.writeStringField("id", c.getId());
        g.writeStringField("object_type", "heat_chamber_reconstruction");
        g.writeStringField("variant_id", c.getVariantId());
        g.writeStringField("existing_object_id", c.getExistingObjectId());
        g.writeNumberField("existing_diameter", c.getExistingDiameter());
        g.writeNumberField("required_diameter", c.getRequiredDiameter());
        g.writeNumberField("cost", c.getCost());
        endFeature(g);
    }

    /** Раздел 10.6: технический узел. */
    private void writeTechnicalNode(JsonGenerator g, TechnicalNodeResult n) throws IOException {
        startFeature(g, n.getLocation());
        g.writeStringField("id", n.getId());
        g.writeStringField("object_type", "technical_node");
        g.writeStringField("variant_id", n.getVariantId());
        endFeature(g);
    }

    /** Раздел 10.7: сводная запись варианта, ровно одна, с geometry = null. */
    private void writeSummary(JsonGenerator g, VariantSummary s) throws IOException {
        g.writeStartObject();
        g.writeStringField("type", "Feature");
        g.writeNullField("geometry");
        g.writeObjectFieldStart("properties");
        g.writeStringField("id", s.getId());
        g.writeStringField("object_type", "variant_summary");
        g.writeStringField("variant_id", s.getVariantId());
        g.writeNumberField("rank", s.getRank());
        g.writeNumberField("construction_cost", s.getConstructionCost());
        g.writeNumberField("chamber_construction_cost", s.getChamberConstructionCost());
        g.writeNumberField("tie_in_cost", s.getTieInCost());
        g.writeNumberField("reconstruction_cost", s.getReconstructionCost());
        g.writeNumberField("chamber_reconstruction_cost", s.getChamberReconstructionCost());
        g.writeNumberField("unconnected_penalty", s.getUnconnectedPenalty());
        g.writeNumberField("calculated_cost", s.getCalculatedCost());
        g.writeNumberField("new_network_length", s.getNewNetworkLength());
        g.writeNumberField("reconstruction_length", s.getReconstructionLength());
        g.writeNumberField("length", s.getLength());
        g.writeNumberField("score", s.getScore());
        g.writeArrayFieldStart("unconnected_oks_ids");
        for (String id : s.getUnconnectedOksIds()) {
            g.writeString(id);
        }
        g.writeEndArray();
        g.writeEndObject();
        g.writeEndObject();
    }

    // =================================================================================
    //  Геометрия
    // =================================================================================

    private void startFeature(JsonGenerator g, Geometry projected) throws IOException {
        g.writeStartObject();
        g.writeStringField("type", "Feature");
        g.writeFieldName("geometry");
        writeGeometry(g, projected);
        g.writeObjectFieldStart("properties");
    }

    private void endFeature(JsonGenerator g) throws IOException {
        g.writeEndObject();
        g.writeEndObject();
    }

    /**
     * Геометрия переводится обратно в WGS 84 покоординатно, без создания промежуточной
     * копии: это единственное место, где расчётная проекция покидает сервис.
     */
    private void writeGeometry(JsonGenerator g, Geometry geometry) throws IOException {
        if (geometry == null || geometry.isEmpty()) {
            g.writeNull();
            return;
        }
        g.writeStartObject();
        if (geometry instanceof Point) {
            g.writeStringField("type", "Point");
            g.writeFieldName("coordinates");
            writeCoordinate(g, geometry.getCoordinate());
        } else if (geometry instanceof LineString) {
            g.writeStringField("type", "LineString");
            g.writeArrayFieldStart("coordinates");
            CoordinateSequence seq = ((LineString) geometry).getCoordinateSequence();
            for (int i = 0; i < seq.size(); i++) {
                writeCoordinate(g, seq.getCoordinate(i));
            }
            g.writeEndArray();
        } else {
            throw new IllegalArgumentException(
                    "Выходная геометрия должна быть точкой или линией, получено: "
                            + geometry.getGeometryType());
        }
        g.writeEndObject();
    }

    private void writeCoordinate(JsonGenerator g, Coordinate c) throws IOException {
        Coordinate wgs = projection.unproject(c.x, c.y);
        g.writeStartArray();
        // Семь знаков после запятой — около сантиметра по широте Москвы; больше
        // не несёт смысла и только раздувает файл.
        g.writeNumber(round7(wgs.x));
        g.writeNumber(round7(wgs.y));
        if (!Double.isNaN(c.getZ())) {
            g.writeNumber(Math.round(c.getZ() * 1000d) / 1000d);
        }
        g.writeEndArray();
    }

    private void writeNullableNumber(JsonGenerator g, String field, Double value)
            throws IOException {
        if (value == null) {
            g.writeNullField(field);
        } else {
            g.writeNumberField(field, value);
        }
    }

    private static double round7(double v) {
        return Math.round(v * 1e7) / 1e7;
    }
}
