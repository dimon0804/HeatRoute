"""Независимая проверка выходного GeoJSON на соответствие техническому приложению.

Смысл в независимости: правила здесь записаны заново, по тексту приложения, а не взяты
из кода сервиса. Совпадение двух независимых реализаций — довод; совпадение кода с самим
собой — нет.

Запуск:
    python tools/validate_result.py deliverable/result_lct2026.geojson \
        --dataset data/samples/dataset_lct2026.geojson

Код возврата 0, если нарушений нет.
"""
from __future__ import annotations

import argparse
import json
import math
import sys
from collections import defaultdict

# Таблица 1 приложения: ДУ, пропускная способность (т/ч), предельная длина (м),
# стоимость нового строительства (руб./м), ширина пары (м), высота (м).
DIAMETERS = [
    (50, 3.5, 181, 74023, 0.400, 0.125),
    (65, 8.3, 245, 78631, 0.430, 0.140),
    (80, 13.2, 327, 83530, 0.470, 0.160),
    (100, 22.3, 419, 89748, 0.510, 0.180),
    (125, 40.2, 554, 97275, 0.600, 0.225),
    (150, 65.1, 696, 105507, 0.650, 0.250),
    (200, 152.3, 1042, 120275, 0.880, 0.315),
    (250, 274.9, 1379, 135323, 1.050, 0.400),
    (300, 437.4, 1718, 150022, 1.150, 0.450),
    (400, 943.1, 2477, 190299, 1.370, 0.560),
    (500, 1663.4, 3245, 224137, 1.670, 0.710),
    (600, 2627.7, 4037, 264790, 1.850, 0.800),
    (700, 3735.1, 4775, 324298, 2.050, 0.900),
    (800, 5296.8, 5644, 325996, 2.250, 1.000),
    (900, 7165.0, 6518, 327693, 2.450, 1.100),
    (1000, 9391.8, 7419, 418777, 2.650, 1.200),
    (1200, 15012.8, 9288, 428074, 3.100, 1.425),
    (1400, 22501.9, 11276, 683417, 3.450, 1.600),
]

# Раздел 3.2: стоимость новой камеры по наибольшему ДУ примыкающих участков.
CHAMBER_COST = [(50, 200, 3_000_000), (250, 500, 5_000_000),
                (600, 1000, 8_000_000), (1200, 1400, 12_000_000)]

TIE_IN_COST = 5_000_000
PENALTY_FIXED = 100_000_000
PENALTY_PER_TPH = 500_000
COST_BASE = 25_000_000
LENGTH_BASE = 100
MAX_TURN_DEG = 90.0
MAX_CHAMBER_DEGREE = 4

ALLOWED_TYPES = {"heat_network", "heat_chamber", "technical_node", "variant_summary"}

REQUIRED_ATTRS = {
    "heat_network": {"id", "object_type", "variant_id", "start_node_id", "end_node_id",
                     "flow_tph", "diameter", "length", "laying_method",
                     "depth_start", "depth_end", "cost"},
    "heat_chamber": {"id", "object_type", "variant_id", "diameter", "cost"},
    "technical_node": {"id", "object_type", "variant_id"},
    "variant_summary": {"id", "object_type", "variant_id", "rank", "construction_cost",
                        "chamber_construction_cost", "existing_chamber_tie_in_count",
                        "existing_chamber_tie_in_cost", "unconnected_penalty",
                        "calculated_cost", "new_network_length", "score",
                        "unconnected_oks_ids"},
}


def capacity_dn(flow: float) -> int | None:
    for dn, capacity, *_ in DIAMETERS:
        if capacity >= flow - 1e-9:
            return dn
    return None


def row(dn: int):
    for entry in DIAMETERS:
        if entry[0] == dn:
            return entry
    return None


def chamber_cost(dn: int) -> int | None:
    for lo, hi, cost in CHAMBER_COST:
        if lo <= dn <= hi:
            return cost
    return None


def to_utm(lon: float, lat: float) -> tuple[float, float]:
    """EPSG:4326 → EPSG:32637, прямая формула Меркатора без внешних библиотек."""
    a, f = 6378137.0, 1 / 298.257223563
    e2 = 2 * f - f * f
    k0, lon0, e0, n0 = 0.9996, math.radians(39.0), 500000.0, 0.0
    lat_r, lon_r = math.radians(lat), math.radians(lon)
    n = a / math.sqrt(1 - e2 * math.sin(lat_r) ** 2)
    t = math.tan(lat_r) ** 2
    c = e2 / (1 - e2) * math.cos(lat_r) ** 2
    aa = (lon_r - lon0) * math.cos(lat_r)
    e4, e6 = e2 * e2, e2 * e2 * e2
    m = a * ((1 - e2 / 4 - 3 * e4 / 64 - 5 * e6 / 256) * lat_r
             - (3 * e2 / 8 + 3 * e4 / 32 + 45 * e6 / 1024) * math.sin(2 * lat_r)
             + (15 * e4 / 256 + 45 * e6 / 1024) * math.sin(4 * lat_r)
             - 35 * e6 / 3072 * math.sin(6 * lat_r))
    x = e0 + k0 * n * (aa + (1 - t + c) * aa ** 3 / 6
                       + (5 - 18 * t + t * t + 72 * c - 58 * e2 / (1 - e2)) * aa ** 5 / 120)
    y = n0 + k0 * (m + n * math.tan(lat_r) * (aa * aa / 2
                   + (5 - t + 9 * c + 4 * c * c) * aa ** 4 / 24
                   + (61 - 58 * t + t * t + 600 * c - 330 * e2 / (1 - e2)) * aa ** 6 / 720))
    return x, y


def plane(coordinate) -> tuple[float, float]:
    """Точка в UTM по координате любой длины.

    Третье число в выгрузке — само по себе нарушение, его называет отдельная
    проверка. Здесь оно отбрасывается: иначе разбор обрывался бы на первом же
    таком объекте и об остальных правилах отчёт не сказал бы ничего.
    """
    return to_utm(float(coordinate[0]), float(coordinate[1]))


def turn_deg(a, b, c) -> float:
    ux, uy = b[0] - a[0], b[1] - a[1]
    vx, vy = c[0] - b[0], c[1] - b[1]
    lu, lv = math.hypot(ux, uy), math.hypot(vx, vy)
    if lu < 1e-9 or lv < 1e-9:
        return 0.0
    cos = max(-1.0, min(1.0, (ux * vx + uy * vy) / (lu * lv)))
    return math.degrees(math.acos(cos))


class Report:
    def __init__(self) -> None:
        self.problems: list[str] = []
        self.checks = 0

    def check(self, condition: bool, message: str) -> None:
        self.checks += 1
        if not condition:
            self.problems.append(message)

    def fail(self, message: str) -> None:
        self.checks += 1
        self.problems.append(message)


def validate(result: dict, dataset: dict | None) -> Report:
    report = Report()
    features = result.get("features", [])

    by_variant: dict[str, dict[str, list]] = defaultdict(lambda: defaultdict(list))
    for feature in features:
        props = feature.get("properties") or {}
        kind = props.get("object_type")
        report.check(kind in ALLOWED_TYPES,
                     f"тип выходного объекта {kind!r} приложением не предусмотрен")
        if kind not in ALLOWED_TYPES:
            continue
        missing = REQUIRED_ATTRS[kind] - set(props)
        report.check(not missing,
                     f"у объекта {props.get('id')!r} типа {kind} нет обязательных "
                     f"атрибутов: {sorted(missing)}")
        by_variant[str(props.get("variant_id"))][kind].append(feature)

        geometry = feature.get("geometry")
        if kind == "variant_summary":
            report.check(geometry is None,
                         f"у сводки {props.get('id')!r} геометрия должна быть пустой")
        else:
            report.check(geometry is not None,
                         f"у объекта {props.get('id')!r} нет геометрии")
            if geometry:
                for index, point in enumerate(_points(geometry)):
                    report.check(len(point) == 2,
                                 f"объект {props.get('id')!r}: в координате {index} "
                                 f"есть третье число, а вертикальное положение "
                                 f"задаётся глубиной начала и конца участка")

    input_nodes = _input_node_ids(dataset) if dataset else set()
    flows = _input_flows(dataset) if dataset else {}
    existing_lines = _existing_lines(dataset) if dataset else []
    existing_chambers = _existing_chamber_ids(dataset) if dataset else set()

    for variant_id, objects in sorted(by_variant.items()):
        _validate_variant(report, variant_id, objects, input_nodes, flows,
                          existing_lines, existing_chambers)

    ranks = sorted(int((f["properties"]["rank"]))
                   for objects in by_variant.values()
                   for f in objects.get("variant_summary", []))
    report.check(ranks == list(range(1, len(ranks) + 1)),
                 f"места вариантов должны идти подряд с единицы, получено {ranks}")
    return report


def _points(geometry: dict):
    kind = geometry.get("type")
    if kind == "Point":
        return [geometry["coordinates"]]
    if kind == "LineString":
        return geometry["coordinates"]
    return []


def _input_node_ids(dataset: dict) -> set[str]:
    out = set()
    for feature in dataset.get("features", []):
        props = feature.get("properties") or {}
        if props.get("object_type") in ("heat_chamber", "oks_connection_point"):
            out.add(str(props.get("id")))
    return out


def _existing_lines(dataset: dict) -> list[tuple[str, int, list]]:
    """Существующие участки сети: (id, ДУ, точки в UTM)."""
    out = []
    for feature in dataset.get("features", []):
        props = feature.get("properties") or {}
        if props.get("object_type") != "heat_network":
            continue
        geometry = feature.get("geometry") or {}
        if geometry.get("type") != "LineString":
            continue
        pts = [plane(c) for c in geometry["coordinates"]]
        out.append((str(props.get("id")), int(props.get("diameter") or 0), pts))
    return out


def _existing_chamber_ids(dataset: dict) -> set[str]:
    return {str((f.get("properties") or {}).get("id"))
            for f in dataset.get("features", [])
            if (f.get("properties") or {}).get("object_type") == "heat_chamber"}


def _distance_to_line(point, pts) -> float:
    best = float("inf")
    for i in range(len(pts) - 1):
        best = min(best, _distance_to_segment(point, pts[i], pts[i + 1]))
    return best


def _distance_to_segment(p, a, b) -> float:
    dx, dy = b[0] - a[0], b[1] - a[1]
    if abs(dx) < 1e-12 and abs(dy) < 1e-12:
        return math.dist(p, a)
    t = ((p[0] - a[0]) * dx + (p[1] - a[1]) * dy) / (dx * dx + dy * dy)
    t = max(0.0, min(1.0, t))
    return math.dist(p, (a[0] + t * dx, a[1] + t * dy))


def _input_flows(dataset: dict) -> dict[str, float]:
    out = {}
    for feature in dataset.get("features", []):
        props = feature.get("properties") or {}
        if props.get("object_type") == "oks_connection_point":
            out[str(props.get("id"))] = float(props.get("flow_tph") or 0)
    return out


def _validate_variant(report: Report, variant_id: str, objects, input_nodes, flows,
                      existing_lines, existing_chambers) -> None:
    segments = objects.get("heat_network", [])
    chambers = objects.get("heat_chamber", [])
    nodes = objects.get("technical_node", [])
    summaries = objects.get("variant_summary", [])

    report.check(len(summaries) == 1,
                 f"вариант {variant_id}: сводных записей должно быть ровно одна, "
                 f"получено {len(summaries)}")
    if not summaries or not segments:
        return
    summary = summaries[0]["properties"]

    known_nodes = set(input_nodes)
    known_nodes |= {str(c["properties"]["id"]) for c in chambers}
    known_nodes |= {str(n["properties"]["id"]) for n in nodes}

    # --- геометрия и ссылки ------------------------------------------------------------
    coords_by_id = {}
    for segment in segments:
        props = segment["properties"]
        sid = str(props["id"])
        pts = [plane(c) for c in segment["geometry"]["coordinates"]]
        coords_by_id[sid] = pts

        for field in ("start_node_id", "end_node_id"):
            ref = str(props[field])
            report.check(ref in known_nodes or not input_nodes,
                         f"участок {sid}: {field} = {ref!r} не ссылается ни на один узел")

        measured = sum(math.dist(pts[i], pts[i + 1]) for i in range(len(pts) - 1))
        report.check(abs(measured - float(props["length"])) <= max(0.5, measured * 0.002),
                     f"участок {sid}: длина в атрибуте {props['length']} м расходится "
                     f"с геометрией {measured:.2f} м")

        for i in range(1, len(pts) - 1):
            turn = turn_deg(pts[i - 1], pts[i], pts[i + 1])
            report.check(turn <= MAX_TURN_DEG + 1e-6,
                         f"участок {sid}: поворот {turn:.1f}° в вершине {i} круче "
                         f"предела {MAX_TURN_DEG:.0f}°")

        report.check(props["laying_method"] in ("base", "special"),
                     f"участок {sid}: способ прокладки {props['laying_method']!r} "
                     f"не из набора base/special")

        dn = int(props["diameter"])
        entry = row(dn)
        report.check(entry is not None, f"участок {sid}: ДУ {dn} нет в таблице 1")
        if entry:
            report.check(entry[1] >= float(props["flow_tph"]) - 1e-9,
                         f"участок {sid}: расход {props['flow_tph']} т/ч выше пропускной "
                         f"способности ДУ {dn} ({entry[1]} т/ч)")
            expected = measured * entry[3]
            actual = float(props["cost"])
            if props["laying_method"] == "base":
                report.check(abs(actual - expected) <= max(2.0, expected * 0.002),
                             f"участок {sid}: стоимость {actual:,.0f} не равна длине на "
                             f"цену метра ({expected:,.0f})")
            else:
                report.check(actual > expected,
                             f"участок {sid}: специальный проход должен стоить дороже "
                             f"обычного ({actual:,.0f} против {expected:,.0f})")

    # --- дерево: степень камер, монотонность ДУ, предельная длина ----------------------
    adjacency = defaultdict(list)
    for segment in segments:
        props = segment["properties"]
        adjacency[str(props["start_node_id"])].append(segment)
        adjacency[str(props["end_node_id"])].append(segment)

    for chamber in chambers:
        cid = str(chamber["properties"]["id"])
        location = plane(chamber["geometry"]["coordinates"])

        # Новая камера может стоять прямо в точке присоединения к существующему участку.
        # Линия, проходящая через камеру, разделена ею на две части и по разъяснению №12
        # занимает два примыкания; линия, которая в камере заканчивается, — одно.
        # Считаются все линии под камерой: диаметр камеры задаёт самая толстая из них,
        # а примыкание занимает каждая.
        host_dn = 0
        host_count = 0
        for _, line_dn, pts in existing_lines:
            if _distance_to_line(location, pts) > 1.0:
                continue
            host_dn = max(host_dn, line_dn)
            ends_here = min(math.dist(location, pts[0]),
                            math.dist(location, pts[-1])) <= 1.0
            host_count += 1 if ends_here else 2

        degree = len(adjacency.get(cid, [])) + host_count
        report.check(degree <= MAX_CHAMBER_DEGREE,
                     f"камера {cid}: примыкает {degree} участков, предел "
                     f"{MAX_CHAMBER_DEGREE}")

        dn = max([int(s["properties"]["diameter"]) for s in adjacency.get(cid, [])]
                 + [host_dn], default=0)
        report.check(int(chamber["properties"]["diameter"]) == dn,
                     f"камера {cid}: ДУ {chamber['properties']['diameter']} не равен "
                     f"наибольшему ДУ примыкающих участков ({dn})")
        expected = chamber_cost(dn)
        report.check(expected is not None
                     and abs(float(chamber["properties"]["cost"]) - expected) < 1,
                     f"камера {cid}: стоимость {chamber['properties']['cost']:,.0f} "
                     f"не по шкале раздела 3.2 (ожидается {expected})")

    _validate_paths(report, variant_id, segments, chambers, nodes, flows,
                    existing_lines, existing_chambers)

    # --- сводка ------------------------------------------------------------------------
    segment_cost = sum(float(s["properties"]["cost"]) for s in segments)
    chamber_sum = sum(float(c["properties"]["cost"]) for c in chambers)
    tie_in_cost = float(summary["existing_chamber_tie_in_cost"])
    tie_in_count = int(summary["existing_chamber_tie_in_count"])
    construction = float(summary["construction_cost"])
    penalty = float(summary["unconnected_penalty"])
    total = float(summary["calculated_cost"])
    length = float(summary["new_network_length"])

    report.check(abs(chamber_sum - float(summary["chamber_construction_cost"])) < 2,
                 f"вариант {variant_id}: стоимость камер в сводке "
                 f"{summary['chamber_construction_cost']:,.0f} не равна сумме камер "
                 f"{chamber_sum:,.0f}")
    report.check(abs(tie_in_cost - tie_in_count * TIE_IN_COST) < 1,
                 f"вариант {variant_id}: {tie_in_count} врезок должны стоить "
                 f"{tie_in_count * TIE_IN_COST:,.0f}, в сводке {tie_in_cost:,.0f}")
    report.check(abs(construction - (segment_cost + chamber_sum + tie_in_cost)) < 3,
                 f"вариант {variant_id}: стоимость строительства {construction:,.0f} "
                 f"не равна сумме участков, камер и врезок "
                 f"{segment_cost + chamber_sum + tie_in_cost:,.0f}")
    report.check(abs(total - (construction + penalty)) < 2,
                 f"вариант {variant_id}: итоговая стоимость {total:,.0f} не равна "
                 f"стоимости строительства со штрафом")

    measured_length = sum(float(s["properties"]["length"]) for s in segments)
    report.check(abs(measured_length - length) <= max(0.5, measured_length * 0.002),
                 f"вариант {variant_id}: длина новой сети в сводке {length} м не равна "
                 f"сумме участков {measured_length:.2f} м")

    expected_score = 0.7 * (total / COST_BASE) + 0.3 * (length / LENGTH_BASE)
    report.check(abs(expected_score - float(summary["score"])) < 0.002,
                 f"вариант {variant_id}: показатель {summary['score']} не равен "
                 f"{expected_score:.3f} по формуле раздела 6")

    unconnected = summary["unconnected_oks_ids"]
    if flows:
        expected_penalty = sum(PENALTY_FIXED + PENALTY_PER_TPH * flows.get(str(i), 0)
                               for i in unconnected)
        report.check(abs(expected_penalty - penalty) < 1,
                     f"вариант {variant_id}: штраф {penalty:,.0f} не равен "
                     f"{expected_penalty:,.0f} по формуле раздела 6")
        connected = {str(s["properties"]["end_node_id"]) for s in segments}
        connected |= {str(s["properties"]["start_node_id"]) for s in segments}
        for point_id in flows:
            if point_id in connected:
                report.check(point_id not in {str(i) for i in unconnected},
                             f"точка {point_id} и подключена, и указана неподключённой")
            else:
                report.check(point_id in {str(i) for i in unconnected},
                             f"точка {point_id} не подключена, но в списке "
                             f"неподключённых её нет")


def _validate_paths(report, variant_id, segments, chambers, nodes, flows,
                    existing_lines, existing_chambers):
    """Монотонность ДУ и предельная длина по каждому пути от места присоединения.

    Ходить надо строго от места присоединения к точкам подключения: если идти
    от точки подключения «в любую непосещённую сторону», на развилке обход уходит
    в соседнюю ветвь, и падение диаметра вниз по ветви выглядит как нарушение,
    которого нет. Поэтому сначала находится корень, затем от него строится дерево
    родителей, и только потом путь читается от листа вверх.
    """
    adjacency = defaultdict(list)
    for segment in segments:
        props = segment["properties"]
        adjacency[str(props["start_node_id"])].append(segment)
        adjacency[str(props["end_node_id"])].append(segment)

    chamber_at = {}
    for chamber in chambers:
        chamber_at[str(chamber["properties"]["id"])] = plane(
            chamber["geometry"]["coordinates"])

    # Корень независимой части сети: существующая камера либо новая камера,
    # поставленная в точке присоединения к существующему участку.
    roots = set()
    for node_id in adjacency:
        if node_id in existing_chambers:
            roots.add(node_id)
            continue
        location = chamber_at.get(node_id)
        if location is None:
            continue
        for _, _, pts in existing_lines:
            if _distance_to_line(location, pts) <= 1.0:
                roots.add(node_id)
                break

    if not roots:
        report.fail(f"вариант {variant_id}: не удалось опознать ни одного места "
                    f"присоединения к существующей сети")
        return

    # Обход в ширину от всех корней сразу: части сети между собой не связаны.
    parent_segment = {}
    visited = set(roots)
    queue = list(roots)
    while queue:
        node = queue.pop(0)
        for segment in adjacency[node]:
            props = segment["properties"]
            other = (str(props["end_node_id"]) if str(props["start_node_id"]) == node
                     else str(props["start_node_id"]))
            if other in visited:
                continue
            visited.add(other)
            parent_segment[other] = segment
            queue.append(other)

    unreached = set(adjacency) - visited
    report.check(not unreached,
                 f"вариант {variant_id}: узлы {sorted(unreached)} не связаны ни с одним "
                 f"местом присоединения")

    for point_id in sorted(flows):
        if point_id not in parent_segment:
            continue
        # Путь от точки подключения к корню, затем разворачиваем: нужен порядок
        # «от точки подключения к месту присоединения».
        path = []
        current = point_id
        guard = 0
        while current in parent_segment:
            guard += 1
            if guard > len(segments) + 5:
                report.fail(f"вариант {variant_id}: путь от точки {point_id} не "
                            f"замкнулся на место присоединения")
                break
            segment = parent_segment[current]
            path.append(segment)
            props = segment["properties"]
            current = (str(props["end_node_id"])
                       if str(props["start_node_id"]) == current
                       else str(props["start_node_id"]))
            if current in parent_segment and parent_segment[current] is segment:
                break

        if len(path) < 2:
            continue

        # ДУ не уменьшается по направлению от точки подключения к месту присоединения.
        previous = None
        for segment in path:
            dn = int(segment["properties"]["diameter"])
            if previous is not None:
                report.check(dn >= previous,
                             f"вариант {variant_id}: на пути от точки {point_id} условный "
                             f"диаметр падает с {previous} до {dn} в участке "
                             f"{segment['properties']['id']}")
            previous = dn

        # Предельная длина по непрерывным участкам одного ДУ.
        run_dn = None
        run_length = 0.0
        for segment in path + [None]:
            dn = int(segment["properties"]["diameter"]) if segment else None
            if dn != run_dn:
                if run_dn is not None:
                    limit = row(run_dn)[2]
                    report.check(run_length <= limit + 1e-6,
                                 f"вариант {variant_id}: путь от точки {point_id} содержит "
                                 f"{run_length:.1f} м подряд на ДУ {run_dn}, предел {limit} м")
                run_dn, run_length = dn, 0.0
            if segment:
                run_length += float(segment["properties"]["length"])


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("result", help="выходной GeoJSON сервиса")
    parser.add_argument("--dataset", help="исходный набор: нужен для проверки ссылок "
                                         "и штрафа")
    args = parser.parse_args()

    with open(args.result, encoding="utf-8") as handle:
        result = json.load(handle)
    dataset = None
    if args.dataset:
        with open(args.dataset, encoding="utf-8") as handle:
            dataset = json.load(handle)

    report = validate(result, dataset)
    print(f"Проверок выполнено: {report.checks}")
    if not report.problems:
        print("Нарушений не найдено.")
        return 0
    print(f"Нарушений: {len(report.problems)}")
    for problem in report.problems:
        print("  -", problem)
    return 1


if __name__ == "__main__":
    sys.exit(main())
