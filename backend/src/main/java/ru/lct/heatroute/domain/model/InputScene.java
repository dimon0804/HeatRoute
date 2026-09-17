package ru.lct.heatroute.domain.model;

import lombok.Builder;
import lombok.Value;
import org.locationtech.jts.geom.Envelope;

import java.util.List;
import java.util.Map;

/**
 * Разобранная и нормализованная исходная обстановка: всё, что нужно алгоритму,
 * уже в рабочей проекции EPSG:32637 и с восстановленными недостающими атрибутами.
 * <p>
 * Ниже этого уровня координат WGS 84 не существует — обратное преобразование
 * выполняется единственный раз, на выгрузке.
 */
@Value
@Builder
public class InputScene {

    /** Источник тепловой энергии. По базовой модели кейса он ровно один. */
    HeatSource source;

    List<ExistingSegment> segments;
    List<ExistingChamber> chambers;
    List<FutureOks> futureOks;
    List<RestrictionObject> restrictions;

    /** Индексы по ID для разрешения цепочки {@code upstream_object_id}. */
    Map<String, ExistingSegment> segmentsById;
    Map<String, ExistingChamber> chambersById;

    /** Граф существующей сети с восстановленным направлением к источнику. */
    ExistingTopology topology;

    /** Габарит всей обстановки в рабочей проекции, м. */
    Envelope extent;

    /** Отчёт о качестве входных данных и принятых допущениях. */
    IngestDiagnostics diagnostics;

    public double totalFutureFlowTph() {
        return futureOks.stream().mapToDouble(FutureOks::getFlowTph).sum();
    }

    public double existingNetworkLength() {
        return segments.stream().mapToDouble(ExistingSegment::length).sum();
    }
}
