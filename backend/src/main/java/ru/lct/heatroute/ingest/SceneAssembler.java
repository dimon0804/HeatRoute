package ru.lct.heatroute.ingest;

import lombok.extern.slf4j.Slf4j;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.MultiLineString;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.index.strtree.STRtree;
import org.locationtech.jts.operation.distance.DistanceOp;
import org.springframework.stereotype.Component;
import ru.lct.heatroute.domain.model.ExistingChamber;
import ru.lct.heatroute.domain.model.ExistingSegment;
import ru.lct.heatroute.domain.model.ExistingTopology;
import ru.lct.heatroute.domain.model.FutureOks;
import ru.lct.heatroute.domain.model.HeatSource;
import ru.lct.heatroute.domain.model.IngestDiagnostics;
import ru.lct.heatroute.domain.model.InputScene;
import ru.lct.heatroute.domain.model.ObjectType;
import ru.lct.heatroute.domain.model.RestrictionObject;
import ru.lct.heatroute.domain.reference.ReferenceCatalog;
import ru.lct.heatroute.domain.reference.RestrictionRule;
import ru.lct.heatroute.geo.Geo;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Сборка нормализованной обстановки из потока объектов GeoJSON.
 * <p>
 * Здесь сосредоточена вся терпимость к входным данным. Техническое приложение
 * описывает полный атрибутивный состав, конкурсный набор 2026 года прислал его
 * частично: без {@code upstream_object_id}, без {@code flow_tph} у существующей сети,
 * без полигонов {@code oks_future}, с целочисленными идентификаторами вместо строковых
 * и с типами ограничений, которых нет в таблице 5.1 дословно. Алгоритм ниже по стеку
 * об этом не знает — он получает заполненную модель и протокол принятых допущений.
 */
@Slf4j
@Component
public class SceneAssembler {

    /** Сколько идентификаторов показывать в одной записи протокола. */
    private static final int DIAGNOSTIC_SAMPLE = 50;

    private final ReferenceCatalog catalog;
    private final TopologyResolver topologyResolver;
    private final IngestProperties props;

    public SceneAssembler(ReferenceCatalog catalog,
                          TopologyResolver topologyResolver,
                          IngestProperties props) {
        this.catalog = catalog;
        this.topologyResolver = topologyResolver;
        this.props = props;
    }

    /** Промежуточный накопитель: заполняется парсером по одному объекту. */
    public static class Collector {
        final List<RawFeature> sources = new ArrayList<>();
        final List<RawFeature> networks = new ArrayList<>();
        final List<RawFeature> chambers = new ArrayList<>();
        final List<RawFeature> oksFuture = new ArrayList<>();
        final List<RawFeature> connectionPoints = new ArrayList<>();
        final List<RawFeature> oksExisting = new ArrayList<>();
        final List<RawFeature> restrictions = new ArrayList<>();
        /**
         * Объекты с нераспознанным {@code object_type} не хранятся: из них нужен
         * только счёт для протокола, а на большом наборе их может быть миллион.
         */
        long unknownCount;
        long total;

        /** Сколько объектов было во входном файле. */
        public long totalRead() {
            return total;
        }

        public void accept(RawFeature f) {
            total++;
            Optional<ObjectType> type = ObjectType.of(f.str("object_type"));
            if (type.isEmpty()) {
                unknownCount++;
                return;
            }
            switch (type.get()) {
                case SOURCE: sources.add(f); break;
                case HEAT_NETWORK: networks.add(f); break;
                case HEAT_CHAMBER: chambers.add(f); break;
                case OKS_FUTURE: oksFuture.add(f); break;
                case OKS_CONNECTION_POINT: connectionPoints.add(f); break;
                case OKS_EXISTING: oksExisting.add(f); break;
                case RESTRICTION: restrictions.add(f); break;
                default: unknownCount++;
            }
        }
    }

    public InputScene assemble(Collector c) {
        IngestDiagnostics diag = new IngestDiagnostics();
        catalog.resetUnknownTypes();
        diag.info("input.read", String.format(
                "Прочитано объектов: %d (источник %d, сеть %d, камеры %d, "
                        + "перспективные ОКС %d, точки подключения %d, существующие ОКС %d, ограничения %d)",
                c.total, c.sources.size(), c.networks.size(), c.chambers.size(),
                c.oksFuture.size(), c.connectionPoints.size(), c.oksExisting.size(),
                c.restrictions.size()));
        if (c.unknownCount > 0) {
            diag.warning("input.unknownType", String.format(
                    "Объектов с неизвестным object_type: %d — не участвуют в расчёте",
                    c.unknownCount), List.of());
        }

        HeatSource source = buildSource(c, diag);
        List<ExistingSegment> segments = buildSegments(c, diag);
        List<ExistingChamber> chambers = buildChambers(c, diag);

        ExistingTopology topology = topologyResolver.resolve(segments, chambers, source, diag);
        segments = applyTopologyToSegments(segments, topology);
        chambers = applyTopologyToChambers(chambers, segments, topology, diag);

        List<FutureOks> future = buildFutureOks(c, segments, diag);
        List<RestrictionObject> restrictions = buildRestrictions(c, diag);
        linkOwnFootprints(future, restrictions, diag);
        addExistingNetworkAsRestriction(segments, restrictions, diag);

        Envelope extent = new Envelope();
        segments.forEach(s -> extent.expandToInclude(s.getGeometry().getEnvelopeInternal()));
        restrictions.forEach(r -> extent.expandToInclude(r.getGeometry().getEnvelopeInternal()));
        future.forEach(o -> extent.expandToInclude(o.getConnectionPoint().getEnvelopeInternal()));

        validate(source, segments, future, diag);

        return InputScene.builder()
                .source(source)
                .segments(segments)
                .chambers(chambers)
                .futureOks(future)
                .restrictions(restrictions)
                .segmentsById(segments.stream().collect(Collectors.toMap(
                        ExistingSegment::getId, s -> s, (a, b) -> a, LinkedHashMap::new)))
                .chambersById(chambers.stream().collect(Collectors.toMap(
                        ExistingChamber::getId, ch -> ch, (a, b) -> a, LinkedHashMap::new)))
                .extent(extent)
                .sourceFeatureCount(c.total)
                .diagnostics(diag)
                .topology(topology)
                .build();
    }

    // =================================================================================
    //  Источник
    // =================================================================================

    private HeatSource buildSource(Collector c, IngestDiagnostics diag) {
        if (c.sources.isEmpty()) {
            return null;
        }
        if (c.sources.size() > 1) {
            diag.warning("source.multiple", String.format(
                            "Источников теплоснабжения во входных данных: %d. Базовая модель кейса "
                                    + "предполагает один; принят первый по порядку", c.sources.size()),
                    c.sources.stream().map(f -> f.str("id")).collect(Collectors.toList()));
        }
        RawFeature f = c.sources.get(0);
        Point p = asPoint(f.geometry());
        if (p == null) {
            diag.error("source.geometry", "Геометрия источника не является точкой", List.of(f.str("id")));
            return null;
        }
        return HeatSource.builder()
                .id(requireId(f, "source", diag))
                .location(p)
                .name(f.str("name"))
                .build();
    }

    // =================================================================================
    //  Существующая сеть
    // =================================================================================

    private List<ExistingSegment> buildSegments(Collector c, IngestDiagnostics diag) {
        List<ExistingSegment> out = new ArrayList<>(c.networks.size());
        List<String> noFlow = new ArrayList<>();
        List<String> noDiameter = new ArrayList<>();
        List<String> nonTableDn = new ArrayList<>();

        for (RawFeature f : c.networks) {
            String id = requireId(f, "heat_network", diag);
            List<LineString> parts = asLineStrings(f.geometry());
            if (parts.isEmpty()) {
                diag.warning("segment.geometry",
                        "Геометрия участка сети не является линией и пропущена", List.of(id));
                continue;
            }

            Integer dn = f.intVal("diameter").orElse(null);
            if (dn == null) {
                noDiameter.add(id);
                dn = catalog.diameters().get(0).getDn();
            } else if (!catalog.hasExactDn(dn)) {
                nonTableDn.add(id + ":" + dn);
            }

            Optional<Double> flow = f.num("flow_tph");
            boolean assumed = flow.isEmpty();
            double flowValue;
            if (flow.isPresent()) {
                flowValue = flow.get();
            } else {
                noFlow.add(id);
                flowValue = assumedExistingFlow(dn);
            }

            for (int i = 0; i < parts.size(); i++) {
                // MultiLineString допустим в выгрузках: каждая часть становится
                // самостоятельным участком с производным идентификатором, иначе
                // цепочка к источнику по такому объекту не строится.
                String partId = parts.size() == 1 ? id : id + "#" + (i + 1);
                out.add(ExistingSegment.builder()
                        .id(partId)
                        .geometry(parts.get(i))
                        .diameter(dn)
                        .flowTph(flowValue)
                        .flowAssumed(assumed)
                        .upstreamObjectId(f.str("upstream_object_id"))
                        .upstreamInferred(false)
                        .build());
            }
        }

        if (!noDiameter.isEmpty()) {
            diag.warning("segment.noDiameter", String.format(
                    "У %d участков существующей сети нет атрибута diameter; "
                            + "принят наименьший условный диаметр справочника", noDiameter.size()), noDiameter);
        }
        if (!nonTableDn.isEmpty()) {
            diag.warning("segment.nonTableDiameter", String.format(
                    "Условный диаметр %d участков отсутствует в таблице 4.1; "
                            + "при расчёте берётся ближайший больший табличный", nonTableDn.size()), nonTableDn);
        }
        if (!noFlow.isEmpty()) {
            diag.assumption("segment.noFlow", String.format(
                    "У %d участков существующей сети нет атрибута flow_tph. Принят режим %s: "
                            + "%s. Объём реконструкции рассчитан исходя из этого допущения",
                    noFlow.size(), props.getExistingFlowMode(),
                    props.getExistingFlowMode() == IngestProperties.ExistingFlowMode.ZERO
                            ? "существующий расход равен нулю"
                            : String.format("существующий расход равен %.0f%% пропускной способности ДУ",
                            props.getExistingFlowCapacityFraction() * 100)), noFlow);
        }
        return out;
    }

    private double assumedExistingFlow(int dn) {
        if (props.getExistingFlowMode() == IngestProperties.ExistingFlowMode.CAPACITY_FRACTION) {
            return catalog.byDnOrNextUp(dn).getCapacityTph() * props.getExistingFlowCapacityFraction();
        }
        return 0d;
    }

    private List<ExistingChamber> buildChambers(Collector c, IngestDiagnostics diag) {
        List<ExistingChamber> out = new ArrayList<>(c.chambers.size());
        for (RawFeature f : c.chambers) {
            String id = requireId(f, "heat_chamber", diag);
            Point p = asPoint(f.geometry());
            if (p == null) {
                diag.warning("chamber.geometry",
                        "Геометрия тепловой камеры не является точкой и пропущена", List.of(id));
                continue;
            }
            out.add(ExistingChamber.builder()
                    .id(id)
                    .location(p)
                    .diameter(f.intVal("diameter").orElse(0))
                    .diameterInferred(false)
                    .upstreamObjectId(f.str("upstream_object_id"))
                    .existingDegree(0)
                    .build());
        }
        return out;
    }

    private List<ExistingSegment> applyTopologyToSegments(List<ExistingSegment> segments,
                                                          ExistingTopology topology) {
        List<ExistingSegment> out = new ArrayList<>(segments.size());
        for (ExistingSegment s : segments) {
            String resolved = topology.getUpstreamOf().get(s.getId());
            boolean inferred = s.getUpstreamObjectId() == null && resolved != null;
            out.add(s.withUpstreamObjectId(resolved != null ? resolved : s.getUpstreamObjectId())
                    .withUpstreamInferred(inferred));
        }
        return out;
    }

    private List<ExistingChamber> applyTopologyToChambers(List<ExistingChamber> chambers,
                                                          List<ExistingSegment> segments,
                                                          ExistingTopology topology,
                                                          IngestDiagnostics diag) {
        Map<String, ExistingSegment> byId = segments.stream().collect(Collectors.toMap(
                ExistingSegment::getId, s -> s, (a, b) -> a));
        List<ExistingChamber> out = new ArrayList<>(chambers.size());
        List<String> inferredDn = new ArrayList<>();

        for (ExistingChamber ch : chambers) {
            int degree = topology.chamberDegree(ch.getId());
            int dn = ch.getDiameter();
            boolean inferred = false;
            if (dn <= 0) {
                // Раздел 8.2: исходный условный диаметр камеры — максимальный ДУ уже
                // примыкающих к ней участков. Ровно это и восстанавливается по графу.
                Integer idx = topology.getChamberNode().get(ch.getId());
                if (idx != null) {
                    dn = topology.getNodes().get(idx).getSegmentIds().stream()
                            .map(byId::get)
                            .filter(java.util.Objects::nonNull)
                            .mapToInt(ExistingSegment::getDiameter)
                            .max().orElse(0);
                }
                inferred = dn > 0;
                if (inferred) {
                    inferredDn.add(ch.getId());
                }
            }
            String upstream = topology.getUpstreamOf().get(ch.getId());
            out.add(ch.withDiameter(dn)
                    .withDiameterInferred(inferred)
                    .withExistingDegree(degree)
                    .withUpstreamObjectId(upstream != null ? upstream : ch.getUpstreamObjectId())
                    .withUpstreamInferred(ch.getUpstreamObjectId() == null && upstream != null));
        }
        if (!inferredDn.isEmpty()) {
            diag.assumption("chamber.diameterInferred", String.format(
                    "У %d тепловых камер нет атрибута diameter; исходный условный диаметр принят "
                            + "равным наибольшему ДУ примыкающих существующих участков (раздел 8.2 ТП)",
                    inferredDn.size()), inferredDn);
        }
        return out;
    }

    // =================================================================================
    //  Перспективные ОКС
    // =================================================================================

    private List<FutureOks> buildFutureOks(Collector c,
                                           List<ExistingSegment> segments,
                                           IngestDiagnostics diag) {
        Map<String, RawFeature> polygons = new LinkedHashMap<>();
        for (RawFeature f : c.oksFuture) {
            polygons.put(requireId(f, "oks_future", diag), f);
        }

        List<FutureOks> out = new ArrayList<>();
        List<String> flowFromPoint = new ArrayList<>();
        List<String> noFlow = new ArrayList<>();
        List<String> orphanPoints = new ArrayList<>();
        List<String> multiPoint = new ArrayList<>();

        // Несколько точек подключения могут относиться к одному перспективному ОКС:
        // в здании бывает не один ИТП. Расход при этом задан на объекте, а не на точках,
        // и делить его между ними не на чем. Точки считаются равноценными вводами,
        // и берётся ближайший к существующей сети — остальные в расчёт не идут.
        Map<String, List<RawFeature>> pointsByOks = new LinkedHashMap<>();
        List<RawFeature> standalonePoints = new ArrayList<>();
        for (RawFeature pointFeature : c.connectionPoints) {
            String oksId = pointFeature.str("oks_id");
            if (oksId != null && polygons.containsKey(oksId)) {
                pointsByOks.computeIfAbsent(oksId, k -> new ArrayList<>()).add(pointFeature);
            } else {
                standalonePoints.add(pointFeature);
            }
        }

        Geometry network = segments.isEmpty() ? null : Geo.FACTORY.createMultiLineString(
                segments.stream().map(ExistingSegment::getGeometry).toArray(LineString[]::new));

        for (Map.Entry<String, List<RawFeature>> entry : pointsByOks.entrySet()) {
            String oksId = entry.getKey();
            RawFeature polygon = polygons.remove(oksId);
            List<RawFeature> candidates = entry.getValue();

            RawFeature chosen = candidates.get(0);
            if (candidates.size() > 1) {
                if (network != null) {
                    chosen = candidates.stream()
                            .filter(f -> asPoint(f.geometry()) != null)
                            .min(Comparator.comparingDouble(
                                    f -> asPoint(f.geometry()).distance(network)))
                            .orElse(chosen);
                }
                multiPoint.add(oksId + ":" + candidates.size());
            }
            Point p = asPoint(chosen.geometry());
            if (p == null) {
                diag.warning("connectionPoint.geometry",
                        "Геометрия точки подключения не является точкой и пропущена",
                        List.of(requireId(chosen, "oks_connection_point", diag)));
                continue;
            }
            double flow = polygon.num("flow_tph").orElse(0d);
            if (flow <= 0) {
                noFlow.add(oksId);
            }
            out.add(FutureOks.builder()
                    .id(oksId)
                    .footprint(polygon.geometry())
                    .connectionPoint(p)
                    .connectionPointId(requireId(chosen, "oks_connection_point", diag))
                    .flowTph(flow)
                    .heatLoad(polygon.num("heat_load").orElse(null))
                    .flowSource(flow > 0 ? FutureOks.FlowSource.OKS_FUTURE
                            : FutureOks.FlowSource.ASSUMED_ZERO)
                    .footprintSource(FutureOks.FootprintSource.OKS_FUTURE)
                    .build());
        }

        // --- точки без собственного полигона ведут разбор сами ------------------------
        for (RawFeature pointFeature : standalonePoints) {
            String pointId = requireId(pointFeature, "oks_connection_point", diag);
            Point p = asPoint(pointFeature.geometry());
            if (p == null) {
                diag.warning("connectionPoint.geometry",
                        "Геометрия точки подключения не является точкой и пропущена", List.of(pointId));
                continue;
            }
            String oksId = pointFeature.str("oks_id");
            RawFeature polygon = null;

            Double flow = null;
            FutureOks.FlowSource flowSource = null;
            Double heatLoad = null;

            if (polygon != null) {
                flow = polygon.num("flow_tph").orElse(null);
                heatLoad = polygon.num("heat_load").orElse(null);
                if (flow != null) {
                    flowSource = FutureOks.FlowSource.OKS_FUTURE;
                }
            }
            if (flow == null && props.isAllowFlowOnConnectionPoint()) {
                flow = pointFeature.num("flow_tph").orElse(null);
                if (flow != null) {
                    flowSource = FutureOks.FlowSource.CONNECTION_POINT;
                    flowFromPoint.add(pointId);
                }
            }
            if (flow == null) {
                flow = 0d;
                flowSource = FutureOks.FlowSource.ASSUMED_ZERO;
                noFlow.add(pointId);
            }
            if (oksId != null && polygon == null && !c.oksFuture.isEmpty()) {
                orphanPoints.add(pointId + "->" + oksId);
            }

            out.add(FutureOks.builder()
                    .id(oksId != null ? oksId : pointId)
                    .footprint(polygon == null ? null : polygon.geometry())
                    .connectionPoint(p)
                    .connectionPointId(pointId)
                    .flowTph(flow)
                    .heatLoad(heatLoad)
                    .flowSource(flowSource)
                    .build());
        }

        // --- полигоны без своей точки подключения ------------------------------------
        if (!polygons.isEmpty()) {
            if (props.isDeriveMissingConnectionPoint() && network != null) {
                List<String> derived = new ArrayList<>();
                for (Map.Entry<String, RawFeature> e : polygons.entrySet()) {
                    Geometry footprint = e.getValue().geometry();
                    if (footprint == null || footprint.isEmpty()) {
                        continue;
                    }
                    Coordinate[] near = DistanceOp.nearestPoints(footprint.getBoundary(), network);
                    double flow = e.getValue().num("flow_tph").orElse(0d);
                    out.add(FutureOks.builder()
                            .id(e.getKey())
                            .footprint(footprint)
                            .connectionPoint(Geo.point(near[0]))
                            .connectionPointId(e.getKey() + ":derived")
                            .flowTph(flow)
                            .heatLoad(e.getValue().num("heat_load").orElse(null))
                            .flowSource(flow > 0 ? FutureOks.FlowSource.OKS_FUTURE
                                    : FutureOks.FlowSource.ASSUMED_ZERO)
                            .build());
                    derived.add(e.getKey());
                }
                if (!derived.isEmpty()) {
                    diag.assumption("oks.connectionPointDerived", String.format(
                            "У %d перспективных ОКС нет точки подключения; принята ближайшая "
                                    + "к существующей сети точка контура объекта", derived.size()), derived);
                }
            } else {
                diag.warning("oks.noConnectionPoint", String.format(
                                "У %d перспективных ОКС нет точки подключения; объекты исключены из расчёта",
                                polygons.size()), new ArrayList<>(polygons.keySet()));
            }
        }

        if (!multiPoint.isEmpty()) {
            diag.assumption("oks.multipleConnectionPoints", String.format(
                    "У %d перспективных ОКС несколько точек подключения. Расход задан "
                            + "на объекте и делению между вводами не подлежит, поэтому "
                            + "подключение выполняется через ближайшую к существующей сети точку",
                    multiPoint.size()), multiPoint);
        }
        if (!flowFromPoint.isEmpty()) {
            diag.assumption("oks.flowFromConnectionPoint", String.format(
                    "У %d перспективных ОКС расчётный расход взят с точки подключения: "
                            + "атрибут flow_tph полигона oks_future во входных данных отсутствует",
                    flowFromPoint.size()), flowFromPoint);
        }
        if (!noFlow.isEmpty()) {
            diag.warning("oks.noFlow", String.format(
                    "У %d перспективных ОКС не найден расчётный расход; принят ноль, "
                            + "условный диаметр подобран по наименьшему значению справочника",
                    noFlow.size()), noFlow);
        }
        if (!orphanPoints.isEmpty()) {
            diag.warning("oks.orphanConnectionPoint", String.format(
                    "У %d точек подключения атрибут oks_id не сопоставился ни с одним полигоном "
                            + "oks_future; точка обработана как самостоятельный ОКС", orphanPoints.size()),
                    orphanPoints);
        }
        out.sort(Comparator.comparing(FutureOks::getId));
        return out;
    }

    // =================================================================================
    //  Пространственные ограничения
    // =================================================================================

    private List<RestrictionObject> buildRestrictions(Collector c, IngestDiagnostics diag) {
        List<RestrictionObject> out = new ArrayList<>();
        // В протокол попадает не более DIAGNOSTIC_SAMPLE идентификаторов: на наборе,
        // где нераспознан весь слой, полный перечень сам по себе занял бы память,
        // а читать его всё равно никто не станет — важен тип и счёт.
        List<String> unknownTypes = new ArrayList<>();
        long unknownTypeCount = 0;

        // Ограничений на большом наборе бывают сотни тысяч, и держать одновременно
        // сырой объект с картой атрибутов и построенную модель незачем: ссылка
        // на разобранный объект снимается сразу, как только он преобразован.
        for (int i = 0; i < c.restrictions.size(); i++) {
            RawFeature f = c.restrictions.get(i);
            c.restrictions.set(i, null);
            String id = requireId(f, "restriction", diag);
            if (f.geometry() == null || f.geometry().isEmpty()) {
                diag.warning("restriction.geometry",
                        "У пространственного ограничения нет геометрии; объект пропущен", List.of(id));
                continue;
            }
            String raw = f.str("restriction_type");
            boolean known = catalog.isKnownType(raw);
            if (!known) {
                unknownTypeCount++;
                if (unknownTypes.size() < DIAGNOSTIC_SAMPLE) {
                    unknownTypes.add(id + ":" + raw);
                }
            }
            out.add(RestrictionObject.builder()
                    .id(id)
                    .geometry(f.geometry())
                    .rawType(raw)
                    .canonicalType(catalog.canonicalType(raw))
                    .rule(catalog.ruleFor(raw))
                    .unknownType(!known)
                    .diameter(f.intVal("diameter").orElse(null))
                    .address(f.str("address"))
                    .build());
        }

        // Существующие ОКС приходят отдельным типом объекта, а учитываются
        // как пространственное ограничение с правилом «пересечение запрещено».
        for (RawFeature f : c.oksExisting) {
            String id = requireId(f, "oks_existing", diag);
            if (f.geometry() == null || f.geometry().isEmpty()) {
                continue;
            }
            out.add(RestrictionObject.builder()
                    .id(id)
                    .geometry(f.geometry())
                    .rawType(ObjectType.OKS_EXISTING.code())
                    .canonicalType(ObjectType.OKS_EXISTING.code())
                    .rule(catalog.ruleFor(ObjectType.OKS_EXISTING.code()))
                    .unknownType(false)
                    .address(f.str("address"))
                    .build());
        }

        if (unknownTypeCount > 0) {
            diag.warning("restriction.unknownType", String.format(
                    "Типов ограничений вне таблицы 5.1: %d. Применено правило по умолчанию "
                            + "(обход с минимальным расстоянием). Сопоставление настраивается ключом "
                            + "heatroute.reference.restriction-aliases без изменения кода",
                    unknownTypeCount), unknownTypes);
        }

        Map<String, Long> byType = out.stream().collect(Collectors.groupingBy(
                RestrictionObject::getCanonicalType, LinkedHashMap::new, Collectors.counting()));
        diag.info("restriction.summary", "Ограничения по типам: " + byType);
        return out;
    }

    /**
     * Идентификатор-маркер: собственным «владельцем» существующей тепловой сети
     * считается точка врезки. Новая сеть начинается прямо на существующей, поэтому
     * первое звено от точки врезки не обязано выдерживать до неё зазор.
     */
    public static final String TIE_IN_OWNER = "tie-in";

    /**
     * Существующая тепловая сеть как пространственное ограничение (таблица 5.1 ТП):
     * пересечение допускается специальным проходом с коэффициентом 1,05, а идти рядом
     * ближе одного метра нельзя.
     * <p>
     * Во входных данных она приходит объектом {@code heat_network}, а не
     * {@code restriction}, и без этого шага новая трасса пересекала бы её бесплатно
     * и могла лечь на неё вплотную.
     */
    private void addExistingNetworkAsRestriction(List<ExistingSegment> segments,
                                                 List<RestrictionObject> restrictions,
                                                 IngestDiagnostics diag) {
        if (segments.isEmpty()) {
            return;
        }
        for (ExistingSegment segment : segments) {
            restrictions.add(RestrictionObject.builder()
                    .id("existing-net:" + segment.getId())
                    .geometry(segment.getGeometry())
                    .rawType(ObjectType.HEAT_NETWORK.code())
                    .canonicalType(ObjectType.HEAT_NETWORK.code())
                    .rule(catalog.ruleFor(ObjectType.HEAT_NETWORK.code()))
                    .unknownType(false)
                    .diameter(segment.getDiameter())
                    .ownerOksIds(java.util.Set.of(TIE_IN_OWNER))
                    .build());
        }
        diag.info("restriction.existingNetwork", String.format(
                "Существующая тепловая сеть учтена как объект со специальным проходом "
                        + "по таблице 5.1: %d участков, пересечение с коэффициентом %.2f, "
                        + "минимальное расстояние при прохождении рядом %.1f м",
                segments.size(),
                catalog.ruleFor(ObjectType.HEAT_NETWORK.code()).getKSpecial(),
                catalog.ruleFor(ObjectType.HEAT_NETWORK.code()).getMinHorizontalDist()));
    }

    // =================================================================================
    //  Собственные контуры перспективных ОКС
    // =================================================================================

    /**
     * Опознание полигона, внутри которого лежит точка подключения, как собственного
     * контура этого перспективного ОКС.
     * <p>
     * В конкурсном наборе все 17 точек подключения оказались внутри полигонов
     * {@code restriction_type = oks} на 0,06–8,3 м от их границы: это контуры самих
     * подключаемых зданий, а точка отмечает положение ИТП внутри. Без такого опознания
     * собственное здание закрывает подход к своей же точке буфером в 5 м, и ни один
     * маршрут до терминала не доходит.
     * <p>
     * Связь односторонняя: для трассы этого ОКС контур перестаёт быть препятствием,
     * для всех прочих трасс он остаётся обычным зданием с полным клиренсом. Один полигон
     * может принадлежать нескольким ОКС — в наборе есть здание с двумя точками подключения.
     */
    private void linkOwnFootprints(List<FutureOks> future,
                                   List<RestrictionObject> restrictions,
                                   IngestDiagnostics diag) {
        if (future.isEmpty() || restrictions.isEmpty()) {
            return;
        }
        STRtree index = new STRtree();
        for (RestrictionObject r : restrictions) {
            index.insert(r.getGeometry().getEnvelopeInternal(), r);
        }
        index.build();

        Map<String, java.util.Set<String>> ownersByRestriction = new LinkedHashMap<>();
        List<String> matched = new ArrayList<>();

        for (int i = 0; i < future.size(); i++) {
            FutureOks oks = future.get(i);
            if (oks.getFootprint() != null) {
                continue;   // контур пришёл полигоном oks_future — опознавать нечего
            }
            Point cp = oks.getConnectionPoint();
            @SuppressWarnings("unchecked")
            List<RestrictionObject> candidates = index.query(cp.getEnvelopeInternal());
            RestrictionObject owner = candidates.stream()
                    .filter(r -> r.getRule().getRule() == RestrictionRule.FORBIDDEN)
                    .filter(r -> r.getGeometry().covers(cp))
                    // Наименьший из накрывающих полигонов: точка внутри квартала и внутри
                    // здания одновременно означает, что здание — искомый контур.
                    .min(Comparator.comparingDouble(r -> r.getGeometry().getArea()))
                    .orElse(null);
            if (owner == null) {
                continue;
            }
            future.set(i, oks.toBuilder()
                    .footprint(owner.getGeometry())
                    .footprintRestrictionId(owner.getId())
                    .footprintSource(FutureOks.FootprintSource.RESTRICTION_MATCH)
                    .build());
            ownersByRestriction.computeIfAbsent(owner.getId(),
                    k -> new java.util.LinkedHashSet<>()).add(oks.getId());
            matched.add(oks.getId() + "->" + owner.getId());
        }

        for (int i = 0; i < restrictions.size(); i++) {
            java.util.Set<String> owners = ownersByRestriction.get(restrictions.get(i).getId());
            if (owners != null) {
                restrictions.set(i, restrictions.get(i).toBuilder()
                        .ownerOksIds(java.util.Set.copyOf(owners)).build());
            }
        }

        for (int i = 0; i < future.size(); i++) {
            if (future.get(i).getFootprintSource() == null) {
                future.set(i, future.get(i).toBuilder()
                        .footprintSource(future.get(i).getFootprint() != null
                                ? FutureOks.FootprintSource.OKS_FUTURE
                                : FutureOks.FootprintSource.NONE)
                        .build());
            }
        }

        if (!matched.isEmpty()) {
            diag.assumption("oks.footprintFromRestriction", String.format(
                    "У %d перспективных ОКС точка подключения оказалась внутри полигона-ограничения; "
                            + "этот полигон опознан как собственный контур объекта. Для трассы своего ОКС "
                            + "он не является препятствием, для остальных трасс сохраняет полный клиренс",
                    matched.size()), matched);
        }
    }

    // =================================================================================

    private void validate(HeatSource source, List<ExistingSegment> segments,
                          List<FutureOks> future, IngestDiagnostics diag) {
        if (source == null) {
            diag.error("scene.noSource", "Источник теплоснабжения не найден", List.of());
        }
        if (segments.isEmpty()) {
            diag.error("scene.noNetwork",
                    "Во входных данных нет существующей тепловой сети — врезаться некуда", List.of());
        }
        if (future.isEmpty()) {
            diag.error("scene.noFutureOks",
                    "Во входных данных нет перспективных ОКС — подключать нечего", List.of());
        }
    }

    private String requireId(RawFeature f, String type, IngestDiagnostics diag) {
        String id = f.str("id");
        if (id == null || id.isBlank()) {
            String generated = type + ":auto:" + System.identityHashCode(f);
            diag.warning("object.noId", String.format(
                    "У объекта типа %s нет атрибута id; присвоен служебный идентификатор %s",
                    type, generated), List.of(generated));
            return generated;
        }
        return id;
    }

    private Point asPoint(Geometry g) {
        if (g instanceof Point) {
            return (Point) g;
        }
        if (g != null && !g.isEmpty()) {
            return Geo.point(g.getCoordinate());
        }
        return null;
    }

    private List<LineString> asLineStrings(Geometry g) {
        List<LineString> out = new ArrayList<>();
        if (g instanceof LineString) {
            out.add((LineString) g);
        } else if (g instanceof MultiLineString) {
            for (int i = 0; i < g.getNumGeometries(); i++) {
                out.add((LineString) g.getGeometryN(i));
            }
        }
        return out;
    }
}
