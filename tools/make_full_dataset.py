"""
Изготовление набора с полным атрибутивным составом из конкурсного.

Конкурсный набор ЛЦТ-2026 беднее, чем описывает техническое приложение: в нём нет
upstream_object_id, расхода у существующей сети, полигонов oks_future и диаметра
у камер, а идентификаторы приходят числами. Проверочный набор будет «той же
структуры», то есть, вероятно, полным.

Скрипт достраивает конкурсный набор до состава таблицы 2.2 приложения:

  * upstream_object_id у участков сети и камер — обходом от источника;
  * flow_tph и diameter там, где они обязательны;
  * полигоны oks_future из тех зданий, внутри которых лежат точки подключения,
    с расходом и справочной тепловой нагрузкой;
  * oks_id у точек подключения;
  * остальные здания переводятся в object_type = oks_existing;
  * идентификаторы приводятся к строкам, как объявляет приложение.

Полученный файл нужен не для демонстрации, а для проверки: сервис обязан обработать
оба набора одним и тем же кодом. Соответствующий тест — SceneIngestFullSpecTest.

Запуск:
    python tools/make_full_dataset.py \
        data/samples/dataset_lct2026.geojson \
        backend/src/test/resources/samples/dataset_full_spec.geojson
"""
from __future__ import annotations

import json
import math
import sys
from collections import defaultdict, deque
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from analyze_dataset import to_utm37n  # noqa: E402

SNAP_TOLERANCE_M = 0.5
# Гкал/ч на т/ч при перепаде температур около 25 °C — справочная величина,
# нужна только чтобы заполнить heat_load правдоподобным числом.
GCAL_PER_TPH = 0.025


def point_in_ring(x: float, y: float, ring: list[tuple[float, float]]) -> bool:
    inside = False
    n = len(ring)
    for i in range(n):
        x1, y1 = ring[i]
        x2, y2 = ring[(i + 1) % n]
        if (y1 > y) != (y2 > y):
            xin = (x2 - x1) * (y - y1) / (y2 - y1) + x1
            if x < xin:
                inside = not inside
    return inside


def covers(geometry: dict, x: float, y: float) -> bool:
    """Точка внутри MultiPolygon или Polygon с учётом дыр."""
    polygons = (geometry["coordinates"] if geometry["type"] == "MultiPolygon"
                else [geometry["coordinates"]])
    for polygon in polygons:
        outer = [(c[0], c[1]) for c in polygon[0]]
        if not point_in_ring(x, y, outer):
            continue
        in_hole = False
        for hole in polygon[1:]:
            if point_in_ring(x, y, [(c[0], c[1]) for c in hole]):
                in_hole = True
                break
        if not in_hole:
            return True
    return False


def main(source: str, target: str) -> None:
    data = json.loads(Path(source).read_text(encoding="utf-8"))
    features = data["features"]

    by_type: dict[str, list] = defaultdict(list)
    for feature in features:
        by_type[feature["properties"]["object_type"]].append(feature)

    # --- 1. Топология существующей сети -------------------------------------------
    nodes: list[tuple[float, float]] = []
    node_of: dict[str, list[int]] = {}
    segments_at: dict[int, set[str]] = defaultdict(set)

    def node_for(x: float, y: float) -> int:
        for index, (nx, ny) in enumerate(nodes):
            if math.hypot(nx - x, ny - y) <= SNAP_TOLERANCE_M:
                return index
        nodes.append((x, y))
        return len(nodes) - 1

    for feature in by_type["heat_network"]:
        coords = feature["geometry"]["coordinates"]
        sid = str(feature["properties"]["id"])
        a = node_for(*to_utm37n(*coords[0][:2]))
        b = node_for(*to_utm37n(*coords[-1][:2]))
        node_of[sid] = [a, b]
        segments_at[a].add(sid)
        segments_at[b].add(sid)

    chamber_node: dict[str, int] = {}
    node_chamber: dict[int, str] = {}
    for feature in by_type["heat_chamber"]:
        x, y = to_utm37n(*feature["geometry"]["coordinates"][:2])
        for index, (nx, ny) in enumerate(nodes):
            if math.hypot(nx - x, ny - y) <= SNAP_TOLERANCE_M:
                cid = str(feature["properties"]["id"])
                chamber_node[cid] = index
                node_chamber[index] = cid
                break

    source_feature = by_type["source"][0]
    source_id = str(source_feature["properties"]["id"])
    sx, sy = to_utm37n(*source_feature["geometry"]["coordinates"][:2])
    source_node = min(range(len(nodes)),
                      key=lambda i: math.hypot(nodes[i][0] - sx, nodes[i][1] - sy))

    # Обход в ширину от источника: тот же алгоритм, что восстанавливает цепочку
    # в сервисе, — здесь он нужен, чтобы записать её в файл явно.
    upstream: dict[str, str] = {}
    parent_segment: dict[int, str] = {}
    visited_segments: set[str] = set()
    queue = deque([source_node])
    depth = {source_node: 0}

    while queue:
        node = queue.popleft()
        chamber_here = node_chamber.get(node)
        if chamber_here and chamber_here not in upstream:
            upstream[chamber_here] = (source_id if node == source_node
                                      else parent_segment.get(node, source_id))
        for sid in segments_at[node]:
            if sid in visited_segments:
                continue
            visited_segments.add(sid)
            ends = node_of[sid]
            other = ends[1] if ends[0] == node else ends[0]

            if chamber_here:
                upstream[sid] = chamber_here
            elif node == source_node:
                upstream[sid] = source_id
            else:
                upstream[sid] = parent_segment.get(node, source_id)

            if other not in depth:
                depth[other] = depth[node] + 1
                parent_segment[other] = sid
                queue.append(other)

    # --- 2. Диаметры камер по примыкающим участкам ---------------------------------
    diameter_of = {str(f["properties"]["id"]): f["properties"]["diameter"]
                   for f in by_type["heat_network"]}
    chamber_diameter = {
        cid: max((diameter_of[s] for s in segments_at[index] if s in diameter_of),
                 default=100)
        for cid, index in chamber_node.items()
    }

    # --- 3. Перспективные ОКС из зданий, содержащих точки подключения --------------
    oks_polygons: dict[str, dict] = {}
    point_to_oks: dict[str, str] = {}

    for point in by_type["oks_connection_point"]:
        px, py = point["geometry"]["coordinates"][:2]
        best = None
        for restriction in by_type["restriction"]:
            if restriction["properties"].get("restriction_type") != "oks":
                continue
            if covers(restriction["geometry"], px, py):
                best = restriction
                break
        if best is not None:
            rid = str(best["properties"]["id"])
            oks_polygons[rid] = best
            point_to_oks[str(point["properties"]["id"])] = "oks_" + rid

    # Суммарный расход здания — сумма расходов всех его точек подключения:
    # в наборе есть здание с двумя ИТП.
    flow_by_oks: dict[str, float] = defaultdict(float)
    for point in by_type["oks_connection_point"]:
        pid = str(point["properties"]["id"])
        if pid in point_to_oks:
            flow_by_oks[point_to_oks[pid]] += float(point["properties"]["flow_tph"])

    # --- 4. Сборка выходного набора -------------------------------------------------
    out: list[dict] = []

    out.append({
        "type": "Feature",
        "geometry": source_feature["geometry"],
        "properties": {
            "id": source_id,
            "object_type": "source",
            "name": source_feature["properties"].get("name", "Источник"),
        },
    })

    for feature in by_type["heat_network"]:
        sid = str(feature["properties"]["id"])
        out.append({
            "type": "Feature",
            "geometry": feature["geometry"],
            "properties": {
                "id": sid,
                "object_type": "heat_network",
                "diameter": feature["properties"]["diameter"],
                # Существующая загрузка: доля пропускной способности, правдоподобная
                # и достаточная, чтобы реконструкция в расчёте действительно возникала.
                "flow_tph": round(_capacity(feature["properties"]["diameter"]) * 0.35, 2),
                "upstream_object_id": upstream.get(sid, source_id),
            },
        })

    for feature in by_type["heat_chamber"]:
        cid = str(feature["properties"]["id"])
        out.append({
            "type": "Feature",
            "geometry": feature["geometry"],
            "properties": {
                "id": cid,
                "object_type": "heat_chamber",
                "diameter": chamber_diameter.get(cid, 100),
                "upstream_object_id": upstream.get(cid, source_id),
            },
        })

    for rid, polygon in oks_polygons.items():
        oks_id = "oks_" + rid
        out.append({
            "type": "Feature",
            "geometry": polygon["geometry"],
            "properties": {
                "id": oks_id,
                "object_type": "oks_future",
                "flow_tph": round(flow_by_oks[oks_id], 2),
                "heat_load": round(flow_by_oks[oks_id] * GCAL_PER_TPH, 3),
                "address": polygon["properties"].get("address", ""),
            },
        })

    for point in by_type["oks_connection_point"]:
        pid = str(point["properties"]["id"])
        properties = {
            "id": "cp_" + pid,
            "object_type": "oks_connection_point",
        }
        if pid in point_to_oks:
            properties["oks_id"] = point_to_oks[pid]
        out.append({
            "type": "Feature",
            "geometry": point["geometry"],
            "properties": properties,
        })

    for restriction in by_type["restriction"]:
        rid = str(restriction["properties"]["id"])
        if rid in oks_polygons:
            continue    # стало перспективным ОКС
        kind = restriction["properties"].get("restriction_type")
        if kind == "oks":
            # Существующие здания в полном составе приходят собственным типом объекта.
            out.append({
                "type": "Feature",
                "geometry": restriction["geometry"],
                "properties": {
                    "id": "ex_" + rid,
                    "object_type": "oks_existing",
                    "address": restriction["properties"].get("address", ""),
                },
            })
        else:
            out.append({
                "type": "Feature",
                "geometry": restriction["geometry"],
                "properties": {
                    "id": "r_" + rid,
                    "object_type": "restriction",
                    # Каноническое написание типа по таблице 5.1.
                    "restriction_type": "tram_tracks" if kind == "railway" else kind,
                },
            })

    result = {
        "type": "FeatureCollection",
        "name": "full_spec",
        "crs": data.get("crs"),
        "features": out,
    }
    Path(target).parent.mkdir(parents=True, exist_ok=True)
    Path(target).write_text(json.dumps(result, ensure_ascii=False), encoding="utf-8")

    counts: dict[str, int] = defaultdict(int)
    for feature in out:
        counts[feature["properties"]["object_type"]] += 1
    print(f"OK -> {target}")
    print(f"объектов: {len(out)}, по типам: {dict(counts)}")
    print(f"перспективных ОКС: {len(oks_polygons)}, "
          f"суммарный расход: {sum(flow_by_oks.values()):.2f} т/ч")


_CAPACITY = {50: 3.5, 65: 8.3, 80: 13.2, 100: 22.3, 125: 40.2, 150: 65.1,
             200: 152.3, 250: 274.9, 300: 437.4, 400: 943.1, 500: 1663.4,
             600: 2627.7, 700: 3735.1, 800: 5296.8, 900: 7165.0, 1000: 9391.8,
             1200: 15012.8, 1400: 22501.9}


def _capacity(dn: int) -> float:
    return _CAPACITY.get(dn, 22.3)


if __name__ == "__main__":
    main(sys.argv[1] if len(sys.argv) > 1 else "data/samples/dataset_lct2026.geojson",
         sys.argv[2] if len(sys.argv) > 2
         else "backend/src/test/resources/samples/dataset_full_spec.geojson")
