"""
Разведка конкурсного набора ЛЦТ-2026 «Трассы подключения к тепловым сетям».

Отвечает на вопросы, от которых зависит архитектура ingest-слоя:
  1. Какие атрибуты реально приходят и каких обязательных по ТП не хватает.
  2. Связна ли существующая сеть геометрически (можно ли восстановить
     upstream_object_id, если его нет во входе).
  3. Где лежат камеры относительно концов участков.
  4. Геометрический масштаб: длины, габариты, плотность препятствий.

Запуск:  python tools/analyze_dataset.py data/samples/dataset_lct2026.geojson
Вывод:   docs/research/dataset_report.md  (+ краткая сводка в stdout)
"""
from __future__ import annotations

import json
import math
import sys
from collections import Counter, defaultdict
from pathlib import Path

# --- Проекция WGS84 -> UTM 37N (EPSG:32637) без внешних зависимостей ---------
# Транcверсальная Меркатора, WGS84, центральный меридиан 39E, k0=0.9996.
A = 6378137.0
F = 1 / 298.257223563
E2 = F * (2 - F)
K0 = 0.9996
LON0 = math.radians(39.0)
FALSE_EASTING = 500000.0


def to_utm37n(lon: float, lat: float) -> tuple[float, float]:
    lat_r = math.radians(lat)
    lon_r = math.radians(lon)
    n = A / math.sqrt(1 - E2 * math.sin(lat_r) ** 2)
    t = math.tan(lat_r) ** 2
    c = E2 / (1 - E2) * math.cos(lat_r) ** 2
    a_ = math.cos(lat_r) * (lon_r - LON0)
    e4, e6 = E2 * E2, E2 * E2 * E2
    m = A * (
        (1 - E2 / 4 - 3 * e4 / 64 - 5 * e6 / 256) * lat_r
        - (3 * E2 / 8 + 3 * e4 / 32 + 45 * e6 / 1024) * math.sin(2 * lat_r)
        + (15 * e4 / 256 + 45 * e6 / 1024) * math.sin(4 * lat_r)
        - (35 * e6 / 3072) * math.sin(6 * lat_r)
    )
    x = K0 * n * (
        a_
        + (1 - t + c) * a_**3 / 6
        + (5 - 18 * t + t * t + 72 * c - 58 * E2 / (1 - E2)) * a_**5 / 120
    ) + FALSE_EASTING
    y = K0 * (
        m
        + n * math.tan(lat_r)
        * (
            a_**2 / 2
            + (5 - t + 9 * c + 4 * c * c) * a_**4 / 24
            + (61 - 58 * t + t * t + 600 * c - 330 * E2 / (1 - E2)) * a_**6 / 720
        )
    )
    return x, y


def dist(p: tuple[float, float], q: tuple[float, float]) -> float:
    return math.hypot(p[0] - q[0], p[1] - q[1])


def line_length(pts: list[tuple[float, float]]) -> float:
    return sum(dist(pts[i], pts[i + 1]) for i in range(len(pts) - 1))


def poly_area(ring: list[tuple[float, float]]) -> float:
    s = 0.0
    for i in range(len(ring) - 1):
        s += ring[i][0] * ring[i + 1][1] - ring[i + 1][0] * ring[i][1]
    return abs(s) / 2


def main(path: str) -> None:
    data = json.loads(Path(path).read_text(encoding="utf-8"))
    feats = data["features"]
    out: list[str] = []

    def w(line: str = "") -> None:
        out.append(line)

    by_type: dict[str, list] = defaultdict(list)
    for f in feats:
        by_type[f["properties"].get("object_type", "<none>")].append(f)

    w("# Разведочный отчёт по конкурсному набору")
    w()
    w(f"Файл: `{Path(path).name}`, объектов: **{len(feats)}**, "
      f"CRS: `{data.get('crs', {}).get('properties', {}).get('name')}`")
    w()

    # --- 1. Состав и атрибуты -------------------------------------------------
    w("## 1. Состав набора")
    w()
    w("| object_type | шт. | геометрия | атрибуты во входе |")
    w("|---|---|---|---|")
    for t, items in sorted(by_type.items()):
        geoms = sorted({(x["geometry"] or {}).get("type", "null") for x in items})
        keys: set[str] = set()
        for x in items:
            keys |= set(x["properties"])
        w(f"| `{t}` | {len(items)} | {', '.join(geoms)} | "
          f"{', '.join('`%s`' % k for k in sorted(keys))} |")
    w()

    # Обязательный состав по техническому приложению (таблица 2.2)
    required = {
        "heat_network": ["id", "object_type", "diameter", "flow_tph", "upstream_object_id"],
        "heat_chamber": ["id", "object_type", "diameter", "upstream_object_id"],
        "oks_future": ["id", "object_type", "flow_tph", "heat_load"],
        "oks_connection_point": ["id", "object_type", "oks_id"],
        "oks_existing": ["id", "object_type"],
        "restriction": ["id", "object_type", "restriction_type"],
        "source": ["id", "object_type"],
    }
    w("## 2. Расхождения с обязательным составом ТП (таблица 2.2)")
    w()
    w("| object_type | отсутствует во входе | лишнее / нестандартное |")
    w("|---|---|---|")
    for t, req in required.items():
        items = by_type.get(t, [])
        if not items:
            w(f"| `{t}` | *типа нет в наборе вовсе* | — |")
            continue
        present: set[str] = set()
        for x in items:
            present |= set(x["properties"])
        missing = [k for k in req if k not in present]
        extra = sorted(present - set(req))
        w(f"| `{t}` | {', '.join('`%s`' % k for k in missing) or '—'} | "
          f"{', '.join('`%s`' % k for k in extra) or '—'} |")
    w()

    id_types = Counter(type(f["properties"].get("id")).__name__ for f in feats)
    w(f"Тип `id` во входе: {dict(id_types)} — ТП объявляет `string`. "
      "Парсер обязан принимать оба варианта.")
    w()

    rt = Counter(f["properties"].get("restriction_type")
                 for f in by_type.get("restriction", []))
    known = {"oks_existing", "park", "social_area", "prohibited_site", "water",
             "road", "tram_tracks", "gas_pipeline", "power_cable", "heat_network"}
    w("### Типы ограничений в наборе")
    w()
    w("| restriction_type | шт. | есть в таблице 5.1 ТП |")
    w("|---|---|---|")
    for k, v in rt.most_common():
        w(f"| `{k}` | {v} | {'да' if k in known else '**нет**'} |")
    w()

    # --- 3. Топология существующей сети --------------------------------------
    nets = by_type.get("heat_network", [])
    chambers = by_type.get("heat_chamber", [])
    sources = by_type.get("source", [])

    def utm_line(f):
        return [to_utm37n(*c[:2]) for c in f["geometry"]["coordinates"]]

    def utm_pt(f):
        return to_utm37n(*f["geometry"]["coordinates"][:2])

    lines = {str(f["properties"]["id"]): utm_line(f) for f in nets}
    lengths = {k: line_length(v) for k, v in lines.items()}
    ch_pts = {str(f["properties"]["id"]): utm_pt(f) for f in chambers}
    src_pts = {str(f["properties"]["id"]): utm_pt(f) for f in sources}

    w("## 3. Топология существующей сети")
    w()
    w(f"Участков: {len(lines)}; суммарная длина: **{sum(lengths.values()):,.0f} м**; "
      f"мин/медиана/макс: {min(lengths.values()):.1f} / "
      f"{sorted(lengths.values())[len(lengths)//2]:.1f} / {max(lengths.values()):.1f} м")
    w()

    # Кластеризация концов участков с допуском
    TOL = 1.0
    endpoints: list[tuple[str, str, tuple[float, float]]] = []
    for nid, pts in lines.items():
        endpoints.append((nid, "start", pts[0]))
        endpoints.append((nid, "end", pts[-1]))

    clusters: list[dict] = []
    for nid, which, p in endpoints:
        for c in clusters:
            if dist(c["p"], p) <= TOL:
                c["members"].append((nid, which))
                break
        else:
            clusters.append({"p": p, "members": [(nid, which)]})

    deg = Counter(len(c["members"]) for c in clusters)
    w(f"Кластеризация концов участков с допуском {TOL} м: **{len(clusters)}** узлов, "
      f"распределение степеней: {dict(sorted(deg.items()))}")
    w()

    # Связность графа участков
    adj: dict[str, set[str]] = defaultdict(set)
    for c in clusters:
        ids = {m[0] for m in c["members"]}
        for a in ids:
            adj[a] |= ids - {a}
    seen: set[str] = set()
    comps: list[set[str]] = []
    for nid in lines:
        if nid in seen:
            continue
        stack, comp = [nid], set()
        while stack:
            x = stack.pop()
            if x in comp:
                continue
            comp.add(x)
            stack.extend(adj[x] - comp)
        seen |= comp
        comps.append(comp)
    comps.sort(key=len, reverse=True)
    w(f"Компонент связности существующей сети: **{len(comps)}** "
      f"(размеры: {[len(c) for c in comps]})")
    if len(comps) > 1:
        w()
        w("> Сеть геометрически не односвязна — при отсутствии `upstream_object_id` "
          "восстановление цепочки к источнику требует дополнительного сшивания.")
    w()

    # Камеры относительно узлов
    w("### Камеры относительно узлов сети")
    w()
    w("| камера | ближайший узел, м | участков в узле | ближайшая точка на оси участка, м |")
    w("|---|---|---|---|")
    for cid, p in ch_pts.items():
        best_node, best_d, best_deg = None, 1e18, 0
        for c in clusters:
            d = dist(c["p"], p)
            if d < best_d:
                best_d, best_node, best_deg = d, c, len({m[0] for m in c["members"]})
        best_on = min(
            min(_seg_dist(p, pts[i], pts[i + 1]) for i in range(len(pts) - 1))
            for pts in lines.values()
        )
        w(f"| `{cid}` | {best_d:.2f} | {best_deg} | {best_on:.2f} |")
    w()

    for sid, p in src_pts.items():
        d = min(min(_seg_dist(p, pts[i], pts[i + 1]) for i in range(len(pts) - 1))
                for pts in lines.values())
        w(f"Источник `{sid}`: расстояние до ближайшей оси участка **{d:.2f} м**.")
    w()

    diam = Counter(f["properties"].get("diameter") for f in nets)
    w(f"Условные диаметры существующей сети: {dict(sorted(diam.items(), key=lambda x: x[0]))}")
    w()

    # --- 4. Подключаемые объекты ---------------------------------------------
    cps = by_type.get("oks_connection_point", [])
    flows = sorted(f["properties"].get("flow_tph", 0) for f in cps)
    w("## 4. Перспективные ОКС")
    w()
    w(f"Точек подключения: **{len(cps)}**; расходы, т/ч: "
      f"мин {min(flows):.2f}, медиана {flows[len(flows)//2]:.2f}, "
      f"макс {max(flows):.2f}, **сумма {sum(flows):.2f}**")
    w()
    w("Полигонов `oks_future` в наборе нет — расход приходит прямо на точке подключения. "
      "Модель обязана поддерживать обе схемы: расход на `oks_future` (по ТП) "
      "и расход на `oks_connection_point` (как в этом наборе).")
    w()

    # расстояния от точек подключения до существующей сети
    w("| точка | flow_tph | до ближайшей оси сети, м | до ближайшей камеры, м |")
    w("|---|---|---|---|")
    for f in cps:
        p = utm_pt(f)
        dn = min(min(_seg_dist(p, pts[i], pts[i + 1]) for i in range(len(pts) - 1))
                 for pts in lines.values())
        dc = min(dist(p, q) for q in ch_pts.values()) if ch_pts else float("nan")
        w(f"| `{f['properties']['id']}` | {f['properties'].get('flow_tph')} "
          f"| {dn:.1f} | {dc:.1f} |")
    w()

    # --- 5. Геометрический масштаб -------------------------------------------
    xs: list[float] = []
    ys: list[float] = []
    ring_count = 0
    vertex_count = 0
    areas: list[float] = []

    def walk(coords, depth=0):
        nonlocal ring_count, vertex_count
        if coords and isinstance(coords[0], (int, float)):
            x, y = to_utm37n(coords[0], coords[1])
            xs.append(x)
            ys.append(y)
            vertex_count += 1
        else:
            for c in coords:
                walk(c, depth + 1)

    for f in feats:
        g = f["geometry"]
        if not g:
            continue
        walk(g["coordinates"])
        if g["type"] == "MultiPolygon":
            for poly in g["coordinates"]:
                ring_count += len(poly)
                areas.append(poly_area([to_utm37n(*c[:2]) for c in poly[0]]))

    w("## 5. Геометрический масштаб")
    w()
    w(f"- Габарит участка (UTM 37N): **{max(xs)-min(xs):,.0f} × {max(ys)-min(ys):,.0f} м**")
    w(f"- Вершин всего: {vertex_count}; колец в полигонах: {ring_count}")
    if areas:
        areas.sort()
        w(f"- Площадь полигонов-ограничений, м²: мин {areas[0]:,.0f}, "
          f"медиана {areas[len(areas)//2]:,.0f}, макс {areas[-1]:,.0f}")
    w()
    w("Плотность обстановки позволяет строить граф видимости по буферизованным "
      "препятствиям без растеризации: число вершин на порядки меньше, "
      "чем у сеточного A*, а геометрия получается из прямых отрезков.")
    w()

    report = Path(__file__).resolve().parents[1] / "docs" / "research" / "dataset_report.md"
    report.write_text("\n".join(out), encoding="utf-8")
    print(f"OK -> {report}")
    print(f"features={len(feats)} nets={len(lines)} chambers={len(ch_pts)} "
          f"cps={len(cps)} components={len(comps)} sum_flow={sum(flows):.2f}")


def _seg_dist(p, a, b) -> float:
    ax, ay = a
    bx, by = b
    px, py = p
    dx, dy = bx - ax, by - ay
    if dx == 0 and dy == 0:
        return dist(p, a)
    t = max(0.0, min(1.0, ((px - ax) * dx + (py - ay) * dy) / (dx * dx + dy * dy)))
    return math.hypot(px - (ax + t * dx), py - (ay + t * dy))


if __name__ == "__main__":
    main(sys.argv[1] if len(sys.argv) > 1 else "data/samples/dataset_lct2026.geojson")
