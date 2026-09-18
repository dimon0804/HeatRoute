"""
Уплотнение расчётной обстановки — проверка главного риска по производительности.

Объём входного файла проверяется отдельно (`make_large_dataset.py`), но опасен
не объём. Граф видимости растёт квадратично от числа вершин препятствий и линейно
от числа терминалов, поэтому набор того же размера, но с вдвое большим числом
подключаемых объектов, считается заметно дольше. Конкурсный набор даёт семнадцать
ОКС; проверочный может дать пятьдесят.

Инструмент берёт конкурсный набор и добавляет точки подключения внутрь существующих
зданий — ровно так же, как они расположены в исходных данных. Обстановка остаётся
осмысленной: те же здания, та же сеть, тот же источник, растёт только число
объектов, которые нужно подключить.

    python tools/make_dense_dataset.py 50 data/samples/dense_50.geojson

Расходы берутся из диапазона исходного набора детерминированно: повторный запуск
даёт тот же файл, иначе замеры времени было бы не с чем сравнивать.
"""
import json
import random
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SOURCE = ROOT / 'data' / 'samples' / 'dataset_lct2026.geojson'

# Диапазон расходов конкурсного набора, т/ч.
FLOW_MIN = 4.08
FLOW_MAX = 76.27

# Зерно фиксировано: набор должен быть воспроизводим.
SEED = 20260918


def centroid(coordinates):
    """Средняя точка внешнего контура — приближение центра здания."""
    ring = coordinates
    while isinstance(ring[0][0], list):
        ring = ring[0]
    xs = [p[0] for p in ring]
    ys = [p[1] for p in ring]
    return [sum(xs) / len(xs), sum(ys) / len(ys)]


def main():
    target = int(sys.argv[1]) if len(sys.argv) > 1 else 50
    out_path = Path(sys.argv[2]) if len(sys.argv) > 2 else ROOT / 'data' / 'samples' / 'dense.geojson'

    source = json.loads(SOURCE.read_text(encoding='utf-8'))
    features = source['features']

    existing_points = [f for f in features
                       if f['properties'].get('object_type') == 'oks_connection_point']
    buildings = [f for f in features
                 if f['properties'].get('object_type') == 'restriction'
                 and f['properties'].get('restriction_type') == 'oks'
                 or f['properties'].get('object_type') == 'restriction'
                 and f['properties'].get('restriction_type') == 'oks_existing']

    if not buildings:
        # В конкурсном наборе тип записан как `oks`; на других наборах бывает иначе.
        buildings = [f for f in features
                     if f['properties'].get('object_type') == 'restriction'
                     and f['geometry']['type'] in ('Polygon', 'MultiPolygon')]

    taken = {tuple(f['geometry']['coordinates']) for f in existing_points}
    next_id = max(int(f['properties']['id']) for f in features
                  if str(f['properties'].get('id', '')).isdigit()) + 1

    rng = random.Random(SEED)
    added = 0
    need = target - len(existing_points)

    for building in buildings:
        if added >= need:
            break
        point = centroid(building['geometry']['coordinates'])
        if tuple(point) in taken:
            continue
        taken.add(tuple(point))
        features.append({
            'type': 'Feature',
            'properties': {
                'id': next_id,
                'object_type': 'oks_connection_point',
                'flow_tph': round(rng.uniform(FLOW_MIN, FLOW_MAX), 2),
            },
            'geometry': {'type': 'Point', 'coordinates': point},
        })
        next_id += 1
        added += 1

    out_path.parent.mkdir(parents=True, exist_ok=True)
    out_path.write_text(json.dumps(source, ensure_ascii=False), encoding='utf-8')

    total = len(existing_points) + added
    flow = sum(f['properties'].get('flow_tph', 0) for f in features
               if f['properties'].get('object_type') == 'oks_connection_point')
    print(f'{out_path}: точек подключения {total} (было {len(existing_points)}, '
          f'добавлено {added}), суммарный расход {flow:.2f} т/ч, '
          f'объектов всего {len(features)}')
    if added < need:
        print(f'  зданий хватило только на {total}: свободных контуров больше нет')


if __name__ == '__main__':
    main()
