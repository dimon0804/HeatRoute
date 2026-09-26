package ru.lct.heatroute.domain.model;

import lombok.Builder;
import lombok.Value;
import org.locationtech.jts.geom.Coordinate;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Граф существующей тепловой сети: узлы, примыкания и направление к источнику.
 * <p>
 * Техническое приложение задаёт направление атрибутом {@code upstream_object_id}.
 * Конкурсный набор 2026 года его не прислал, поэтому цепочка восстанавливается
 * по геометрии обходом от источника. Обе ветки сходятся в этой структуре, и дальше
 * потребитель топологии не знает, откуда взялось направление.
 */
@Value
@Builder
public class ExistingTopology {

    /** Узел сети — точка, в которой сходятся концы участков и, возможно, стоит камера. */
    @Value
    @Builder
    public static class Node {
        int index;
        Coordinate location;
        /** ID примыкающих участков существующей сети. */
        Set<String> segmentIds;
        /** ID камеры в этом узле или {@code null}. */
        String chamberId;
        /** {@code true}, если в узле находится источник. */
        boolean source;
    }

    List<Node> nodes;

    /** Узлы концов участка: {@code [startNodeIndex, endNodeIndex]}. */
    Map<String, int[]> segmentNodes;

    /** Узел, в котором стоит камера. */
    Map<String, Integer> chamberNode;

    /**
     * Следующий объект по направлению к источнику: ID участка или камеры → ID следующего
     * объекта ({@code heat_network}, {@code heat_chamber} или {@code source}).
     */
    Map<String, String> upstreamOf;

    /** Индекс узла, в котором находится источник; {@code -1}, если источник не привязан. */
    int sourceNodeIndex;

    /** ID объекта-источника. */
    String sourceId;

    /**
     * Для участка — индекс его узла, обращённого к источнику. Нужен, чтобы понять,
     * какая половина участка реконструируется при врезке в его середину.
     */
    Map<String, Integer> segmentUpstreamNode;

    /** Число участков существующей сети, примыкающих к узлу камеры. */
    public int chamberDegree(String chamberId) {
        Integer idx = chamberNode.get(chamberId);
        return idx == null ? 0 : nodes.get(idx).getSegmentIds().size();
    }

    /**
     * Цепочка объектов от {@code objectId} к источнику, не включая сам объект.
     * Последним элементом идёт ID источника. Обрыв цепочки возвращает то, что удалось
     * пройти: сервис обязан работать и на неполных данных.
     */
    public List<String> chainToSource(String objectId) {
        List<String> chain = new ArrayList<>();
        Set<String> visited = new LinkedHashSet<>();
        visited.add(objectId);
        String current = upstreamOf.get(objectId);
        while (current != null && visited.add(current)) {
            chain.add(current);
            if (current.equals(sourceId)) {
                break;
            }
            current = upstreamOf.get(current);
        }
        return chain;
    }
}
