"""
Изготовление большого входного набора для проверки предела из раздела 3.2 ТЗ (до 3 ГБ).

Файл собирается потоково из конкурсного набора: исходные объекты выписываются как есть,
а затем к ним добавляются копии ограничений со смещением и уникальными ID, пока файл
не дорастёт до заданного размера. Расчётная обстановка при этом остаётся осмысленной —
источник, сеть, камеры и ОКС ровно одни, растёт только число пространственных
ограничений, то есть именно та часть, которая в реальных выгрузках и бывает огромной.

    python tools/make_large_dataset.py 1 out.geojson     # ~1 ГБ
"""
import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SOURCE = ROOT / 'data' / 'samples' / 'dataset_lct2026.geojson'


def shift(geometry, dx, dy):
    """Сдвиг геометрии в градусах — копии не должны лежать одна на другой."""
    def walk(node):
        if isinstance(node[0], (int, float)):
            return [node[0] + dx, node[1] + dy]
        return [walk(child) for child in node]

    return {'type': geometry['type'], 'coordinates': walk(geometry['coordinates'])}


def main():
    target_gb = float(sys.argv[1]) if len(sys.argv) > 1 else 1.0
    out_path = Path(sys.argv[2]) if len(sys.argv) > 2 else ROOT / 'data' / 'large.geojson'
    target_bytes = int(target_gb * 1024 ** 3)

    source = json.loads(SOURCE.read_text(encoding='utf-8'))
    features = source['features']
    restrictions = [f for f in features
                    if f['properties'].get('object_type') == 'restriction']
    if not restrictions:
        raise SystemExit('в исходном наборе нет ограничений — нечего размножать')

    written = 0
    copy_index = 0
    out_path.parent.mkdir(parents=True, exist_ok=True)

    with out_path.open('w', encoding='utf-8', newline='\n') as out:
        head = '{"type":"FeatureCollection","features":[\n'
        out.write(head)
        written += len(head)

        first = True
        for feature in features:
            line = ('' if first else ',\n') + json.dumps(feature, ensure_ascii=False)
            out.write(line)
            written += len(line.encode('utf-8'))
            first = False

        # Дальше — копии ограничений, пока файл не дорастёт до цели.
        while written < target_bytes:
            copy_index += 1
            dx = 0.02 * (copy_index % 97)
            dy = 0.02 * (copy_index // 97)
            for original in restrictions:
                if written >= target_bytes:
                    break
                copy = {
                    'type': 'Feature',
                    'properties': dict(original['properties'],
                                       id=f"{original['properties']['id']}_c{copy_index}"),
                    'geometry': shift(original['geometry'], dx, dy),
                }
                line = ',\n' + json.dumps(copy, ensure_ascii=False)
                out.write(line)
                written += len(line.encode('utf-8'))

        tail = '\n]}\n'
        out.write(tail)
        written += len(tail)

    size = out_path.stat().st_size
    print(f'{out_path}: {size / 1024 ** 3:.2f} ГБ, копий ограничений {copy_index}')


if __name__ == '__main__':
    main()
