package ru.lct.heatroute.ingest;

import lombok.extern.slf4j.Slf4j;
import org.locationtech.jts.geom.Coordinate;
import org.springframework.stereotype.Component;
import ru.lct.heatroute.domain.model.ExistingChamber;
import ru.lct.heatroute.domain.model.ExistingSegment;
import ru.lct.heatroute.domain.model.ExistingTopology;
import ru.lct.heatroute.domain.model.HeatSource;
import ru.lct.heatroute.domain.model.IngestDiagnostics;
import ru.lct.heatroute.geo.GeoProperties;
import ru.lct.heatroute.geo.SpatialHash;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Восстановление графа существующей сети и направления к источнику.
 * <p>
 * Работает в двух режимах и всегда выдаёт одну и ту же структуру:
 * <ul>
 *   <li>если во входных данных есть {@code upstream_object_id} — цепочка берётся оттуда
 *       и только проверяется на замыкание к источнику;</li>
 *   <li>если его нет (как в конкурсном наборе 2026 года) — цепочка строится обходом
 *       в ширину от источника по геометрическим примыканиям концов участков.</li>
 * </ul>
 * Смешанный случай, когда атрибут заполнен у части объектов, обрабатывается как
 * восстановление: доверять половине цепочки нельзя, а расхождение попадает в диагностику.
 */
@Slf4j
@Component
public class TopologyResolver {

    private final GeoProperties geoProps;

    public TopologyResolver(GeoProperties geoProps) {
        this.geoProps = geoProps;
    }

    public ExistingTopology resolve(List<ExistingSegment> segments,
                                    List<ExistingChamber> chambers,
                                    HeatSource source,
                                    IngestDiagnostics diagnostics) {
        double tol = geoProps.getSnapTolerance();

        // --- 1. Узлы: сшивание концов участков с допуском --------------------------------
        SpatialHash nodeIndex = new SpatialHash(Math.max(tol, 0.5));
        List<Set<String>> nodeSegments = new ArrayList<>();
        Map<String, int[]> segmentNodes = new LinkedHashMap<>();

        for (ExistingSegment seg : segments) {
            Coordinate[] cs = seg.getGeometry().getCoordinates();
            if (cs.length < 2) {
                diagnostics.warning("segment.degenerate",
                        "Участок существующей сети состоит менее чем из двух точек и исключён из графа",
                        List.of(seg.getId()));
                continue;
            }
            int a = nodeFor(cs[0], tol, nodeIndex, nodeSegments);
            int b = nodeFor(cs[cs.length - 1], tol, nodeIndex, nodeSegments);
            nodeSegments.get(a).add(seg.getId());
            nodeSegments.get(b).add(seg.getId());
            segmentNodes.put(seg.getId(), new int[]{a, b});
        }

        // --- 2. Камеры на узлах ---------------------------------------------------------
        Map<String, Integer> chamberNode = new LinkedHashMap<>();
        Map<Integer, String> nodeChamber = new HashMap<>();
        List<String> detachedChambers = new ArrayList<>();
        for (ExistingChamber ch : chambers) {
            Integer idx = nodeIndex.nearest(ch.getLocation().getCoordinate(), tol);
            if (idx == null) {
                // Камера не совпала ни с одним узлом: она либо стоит на оси участка,
                // либо выгружена со смещением. Примыканий в графе она не получает,
                // но остаётся кандидатом на присоединение по правилу 10 м раздела 2.4.
                detachedChambers.add(ch.getId());
                continue;
            }
            chamberNode.put(ch.getId(), idx);
            nodeChamber.put(idx, ch.getId());
        }
        if (!detachedChambers.isEmpty()) {
            diagnostics.warning("chamber.detached", String.format(
                    "Камер не совпало с узлами сети: %d (допуск %.2f м). "
                            + "Они не участвуют в цепочке к источнику, но остаются кандидатами на врезку",
                    detachedChambers.size(), tol), detachedChambers);
        }

        // --- 3. Узел источника ----------------------------------------------------------
        int sourceNodeIndex = -1;
        String sourceId = source == null ? null : source.getId();
        if (source != null) {
            Coordinate sc = source.getLocation().getCoordinate();
            Integer idx = nodeIndex.nearest(sc, tol);
            if (idx != null) {
                sourceNodeIndex = idx;
            } else {
                // Источник может быть выгружен на оси участка, а не в его конце.
                // Ближайший конец — корректная опора: направление обхода от этого
                // не меняется, а смещение фиксируется в диагностике.
                double best = Double.MAX_VALUE;
                for (int i = 0; i < nodeIndex.size(); i++) {
                    double d = nodeIndex.get(i).distance(sc);
                    if (d < best) {
                        best = d;
                        sourceNodeIndex = i;
                    }
                }
                if (sourceNodeIndex >= 0) {
                    diagnostics.assumption("source.snapped", String.format(
                            "Источник не совпал с узлом сети; принят ближайший узел на расстоянии %.2f м",
                            best), List.of(sourceId));
                }
            }
        } else {
            diagnostics.error("source.missing",
                    "Во входных данных нет объекта source — направление к источнику неопределимо",
                    List.of());
        }

        // --- 4. Направление к источнику --------------------------------------------------
        long withUpstream = segments.stream().filter(s -> s.getUpstreamObjectId() != null).count()
                + chambers.stream().filter(c -> c.getUpstreamObjectId() != null).count();
        long total = (long) segments.size() + chambers.size();
        boolean useInput = total > 0 && withUpstream == total;

        Map<String, String> upstreamOf = new LinkedHashMap<>();
        Map<String, Integer> segmentUpstreamNode = new LinkedHashMap<>();

        if (useInput) {
            segments.forEach(s -> upstreamOf.put(s.getId(), s.getUpstreamObjectId()));
            chambers.forEach(c -> upstreamOf.put(c.getId(), c.getUpstreamObjectId()));
            diagnostics.info("upstream.fromInput",
                    "Направление к источнику взято из атрибута upstream_object_id");
            fillUpstreamNodesFromChain(segments, segmentNodes, nodeChamber, upstreamOf,
                    sourceNodeIndex, segmentUpstreamNode);
        } else {
            if (withUpstream > 0) {
                diagnostics.warning("upstream.partial", String.format(
                                "Атрибут upstream_object_id заполнен у %d из %d объектов существующей сети; "
                                        + "цепочка восстановлена по геометрии целиком", withUpstream, total),
                        List.of());
            } else {
                diagnostics.assumption("upstream.inferred",
                        "Атрибут upstream_object_id во входных данных отсутствует; "
                                + "направление к источнику восстановлено обходом графа от источника",
                        List.of());
            }
            inferUpstream(segments, segmentNodes, nodeSegments, nodeChamber, chamberNode,
                    sourceNodeIndex, sourceId, upstreamOf, segmentUpstreamNode, diagnostics);
        }

        // --- 5. Сборка ------------------------------------------------------------------
        List<ExistingTopology.Node> nodes = new ArrayList<>(nodeIndex.size());
        for (int i = 0; i < nodeIndex.size(); i++) {
            nodes.add(ExistingTopology.Node.builder()
                    .index(i)
                    .location(nodeIndex.get(i))
                    .segmentIds(new LinkedHashSet<>(nodeSegments.get(i)))
                    .chamberId(nodeChamber.get(i))
                    .source(i == sourceNodeIndex)
                    .build());
        }

        return ExistingTopology.builder()
                .nodes(nodes)
                .segmentNodes(segmentNodes)
                .chamberNode(chamberNode)
                .upstreamOf(upstreamOf)
                .sourceNodeIndex(sourceNodeIndex)
                .sourceId(sourceId)
                .segmentUpstreamNode(segmentUpstreamNode)
                .build();
    }

    // =================================================================================

    /**
     * Обход в ширину от узла источника. Участок, через который узел был достигнут,
     * становится его родителем; конец участка, оказавшийся ближе к источнику, —
     * его узлом со стороны источника.
     */
    private void inferUpstream(List<ExistingSegment> segments,
                               Map<String, int[]> segmentNodes,
                               List<Set<String>> nodeSegments,
                               Map<Integer, String> nodeChamber,
                               Map<String, Integer> chamberNode,
                               int sourceNodeIndex,
                               String sourceId,
                               Map<String, String> upstreamOf,
                               Map<String, Integer> segmentUpstreamNode,
                               IngestDiagnostics diagnostics) {
        if (sourceNodeIndex < 0) {
            return;
        }
        int nodeCount = nodeSegments.size();
        int[] depth = new int[nodeCount];
        Arrays.fill(depth, Integer.MAX_VALUE);
        String[] parentSegment = new String[nodeCount];
        depth[sourceNodeIndex] = 0;

        Deque<Integer> queue = new ArrayDeque<>();
        queue.add(sourceNodeIndex);
        Set<String> visitedSegments = new LinkedHashSet<>();

        while (!queue.isEmpty()) {
            int n = queue.poll();
            String chamberHere = nodeChamber.get(n);
            if (chamberHere != null && !upstreamOf.containsKey(chamberHere)) {
                upstreamOf.put(chamberHere,
                        n == sourceNodeIndex ? sourceId : parentSegment[n]);
            }
            for (String segId : nodeSegments.get(n)) {
                if (!visitedSegments.add(segId)) {
                    continue;
                }
                int[] ends = segmentNodes.get(segId);
                int other = ends[0] == n ? ends[1] : ends[0];
                segmentUpstreamNode.put(segId, n);

                // Следующий объект к источнику: камера в этом узле, иначе родительский
                // участок, иначе сам источник, если узел — узел источника.
                if (chamberHere != null) {
                    upstreamOf.put(segId, chamberHere);
                } else if (n == sourceNodeIndex) {
                    upstreamOf.put(segId, sourceId);
                } else {
                    upstreamOf.put(segId, parentSegment[n]);
                }

                if (depth[other] == Integer.MAX_VALUE) {
                    depth[other] = depth[n] + 1;
                    parentSegment[other] = segId;
                    queue.add(other);
                }
            }
        }

        chamberNode.forEach((chId, nodeIdx) -> {
            if (upstreamOf.containsKey(chId)) {
                return;
            }
            if (nodeIdx == sourceNodeIndex) {
                upstreamOf.put(chId, sourceId);
            } else if (parentSegment[nodeIdx] != null) {
                upstreamOf.put(chId, parentSegment[nodeIdx]);
            }
        });

        List<String> unreachable = segments.stream()
                .map(ExistingSegment::getId)
                .filter(id -> !upstreamOf.containsKey(id))
                .collect(Collectors.toList());
        if (!unreachable.isEmpty()) {
            diagnostics.warning("upstream.unreachable", String.format(
                    "Участков существующей сети, не связанных с источником: %d. "
                            + "Присоединение к ним не рассматривается",
                    unreachable.size()), unreachable);
        }
    }

    /**
     * Когда цепочка пришла во входных данных, узел участка со стороны источника
     * определяется по тому, какой из его концов принадлежит следующему объекту цепочки:
     * узел с одноимённой камерой, узел источника либо узел, общий со следующим участком.
     */
    private void fillUpstreamNodesFromChain(List<ExistingSegment> segments,
                                            Map<String, int[]> segmentNodes,
                                            Map<Integer, String> nodeChamber,
                                            Map<String, String> upstreamOf,
                                            int sourceNodeIndex,
                                            Map<String, Integer> segmentUpstreamNode) {
        for (ExistingSegment seg : segments) {
            int[] ends = segmentNodes.get(seg.getId());
            if (ends == null) {
                continue;
            }
            String upstream = upstreamOf.get(seg.getId());
            Integer chosen = null;

            for (int end : ends) {
                if (upstream != null && upstream.equals(nodeChamber.get(end))) {
                    chosen = end;
                    break;
                }
            }
            if (chosen == null && upstream != null) {
                int[] nextEnds = segmentNodes.get(upstream);
                if (nextEnds != null) {
                    for (int end : ends) {
                        if (end == nextEnds[0] || end == nextEnds[1]) {
                            chosen = end;
                            break;
                        }
                    }
                }
            }
            if (chosen == null) {
                for (int end : ends) {
                    if (end == sourceNodeIndex) {
                        chosen = end;
                        break;
                    }
                }
            }
            segmentUpstreamNode.put(seg.getId(), chosen == null ? ends[0] : chosen);
        }
    }

    // =================================================================================

    private int nodeFor(Coordinate c, double tol, SpatialHash index, List<Set<String>> nodeSegments) {
        Integer existing = index.nearest(c, tol);
        if (existing != null) {
            return existing;
        }
        int idx = index.add(c);
        nodeSegments.add(new LinkedHashSet<>());
        return idx;
    }
}
