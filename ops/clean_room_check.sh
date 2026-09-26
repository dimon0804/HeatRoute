#!/usr/bin/env bash
# Проверка развёртывания с нуля: ровно то, что получит жюри, и ничего из локального.
#
# Клонирует репозиторий в отдельный каталог (в клон попадает только закоммиченное),
# собирает образы без кеша, поднимает стек одной командой и проверяет сквозной сценарий:
# загрузка конкурсного набора, расчёт, выгрузка результата, справочник, интерфейс.
#
# Запуск:  bash ops/clean_room_check.sh
# Итог:    строка PASS или FAIL по каждому шагу и код возврата.

set -u

SRC="$(cd "$(dirname "$0")/.." && pwd)"
WORK="${TMPDIR:-/tmp}/heatroute-clean-$(date +%H%M%S)"
FAILED=0

# Метка запоминается, а печатается после результата: выравнивать колонки по кириллице
# бессмысленно — оболочка считает длину строки в байтах, и столбцы разъезжаются.
CURRENT=""
step() { CURRENT="$1"; }
ok()   { echo "PASS  $CURRENT"; }
bad()  { echo "FAIL  $CURRENT — $1"; FAILED=1; }

# Локальный стек занимает те же имена контейнеров и те же порты, что и проверяемый
# клон: имена в docker-compose.yml заданы явно, чтобы на демонстрации можно было
# сказать `docker logs heatroute-backend`. Поэтому перед проверкой рабочий стек
# останавливается, а после — поднимается обратно. Без этого проверка падает на шаге
# запуска, и падает не по делу.
LOCAL_WAS_UP=0
if [ -n "$(docker ps --filter name=heatroute- --format '{{.ID}}' 2>/dev/null)" ]; then
    LOCAL_WAS_UP=1
    echo "--- Локальный стек поднят, останавливаю его на время проверки ---"
    (cd "$SRC" && docker compose stop >/dev/null 2>&1)
fi

cleanup() {
    if [ -d "$WORK" ]; then
        (cd "$WORK" && docker compose down -v >/dev/null 2>&1)
        rm -rf "$WORK"
    fi
    if [ "$LOCAL_WAS_UP" = "1" ]; then
        echo
        echo "--- Возвращаю локальный стек ---"
        (cd "$SRC" && docker compose start >/dev/null 2>&1)
    fi
}
trap cleanup EXIT

echo "=== Развёртывание с нуля: $WORK"
echo

step "Клонирование репозитория"
if git clone --quiet "$SRC" "$WORK" 2>/dev/null; then ok; else bad "git clone"; exit 1; fi

step "Набор данных есть в клоне"
[ -f "$WORK/data/samples/dataset_lct2026.geojson" ] && ok || bad "нет data/samples"

step "Локальных артефактов сборки в клоне нет"
if [ -d "$WORK/backend/target" ] || [ -d "$WORK/frontend/node_modules" ] || [ -f "$WORK/.env" ]; then
    bad "клон не чист"
else
    ok
fi

step "Настройка по .env.example"
if cp "$WORK/.env.example" "$WORK/.env"; then ok; else bad "нет .env.example"; fi

cd "$WORK" || exit 1

echo
echo "--- Сборка образов без кеша (несколько минут) ---"
if ! docker compose build --no-cache > "$WORK/build.log" 2>&1; then
    tail -20 "$WORK/build.log"
    bad "docker compose build"
    exit 1
fi
echo

step "Сборка образов без кеша"
ok

step "Запуск стека одной командой"
if docker compose up -d > "$WORK/up.log" 2>&1; then ok; else tail -20 "$WORK/up.log"; bad "docker compose up"; exit 1; fi

step "Бэкенд отвечает"
DEADLINE=$((SECONDS + 240))
until [ "$(curl -s -o /dev/null -w '%{http_code}' --max-time 5 http://localhost:8080/actuator/health)" = "200" ]; do
    [ $SECONDS -gt $DEADLINE ] && break
    sleep 3
done
[ "$(curl -s -o /dev/null -w '%{http_code}' --max-time 5 http://localhost:8080/actuator/health)" = "200" ] \
    && ok || bad "actuator/health не 200"

step "Справочник отдаётся из конфигурации"
DIAMETERS=$(curl -s --max-time 10 http://localhost:8080/api/v1/reference/diameters | grep -o '"dn"' | wc -l)
[ "$DIAMETERS" -eq 18 ] && ok || bad "диаметров $DIAMETERS вместо 18"

step "Загрузка конкурсного набора"
DATASET=$(curl -s --max-time 120 -F "file=@data/samples/dataset_lct2026.geojson" \
    http://localhost:8080/api/v1/datasets | sed -n 's/.*"id":"\([^"]*\)".*/\1/p')
[ -n "$DATASET" ] && ok || bad "набор не загрузился"

step "Протокол разбора отдаётся"
curl -s --max-time 20 "http://localhost:8080/api/v1/datasets/$DATASET/diagnostics" \
    | grep -q '\[' && ok || bad "нет протокола"

step "Расчёт запускается и доходит до конца"
JOB=$(curl -s --max-time 20 -X POST -H "Content-Type: application/json" \
    -d "{\"datasetId\":\"$DATASET\",\"designDiameter\":0,\"withDepth\":false}" \
    http://localhost:8080/api/v1/jobs | sed -n 's/.*"id":"\([^"]*\)".*/\1/p')
STATUS=""
DEADLINE=$((SECONDS + 600))
while [ $SECONDS -lt $DEADLINE ]; do
    STATUS=$(curl -s --max-time 10 "http://localhost:8080/api/v1/jobs/$JOB" \
        | sed -n 's/.*"status":"\([^"]*\)".*/\1/p')
    if [ "$STATUS" = "COMPLETED" ] || [ "$STATUS" = "FAILED" ]; then break; fi
    sleep 5
done
[ "$STATUS" = "COMPLETED" ] && ok || bad "статус расчёта: ${STATUS:-нет ответа}"

step "Выгрузка результата — корректный GeoJSON"
curl -s --max-time 60 "http://localhost:8080/api/v1/jobs/$JOB/result.geojson" -o result.geojson
python -c "
import json, sys
d = json.load(open('result.geojson', encoding='utf-8'))
kinds = {f['properties'].get('object_type') for f in d['features']}
assert d['type'] == 'FeatureCollection', 'не FeatureCollection'
assert len(d['features']) > 0, 'пустая выгрузка'
assert 'variant_summary' in kinds, 'нет сводной записи варианта'
" 2>/dev/null && ok || bad "выгрузка не прошла проверку"

step "Проверка соответствия приложению проходит без нарушений"
COMPLIANCE=$(curl -s --max-time 120 "http://localhost:8080/api/v1/jobs/$JOB/compliance")
echo "$COMPLIANCE" > compliance.json
python -c "
import json
d = json.load(open('compliance.json', encoding='utf-8'))
assert d['checks'] > 500, 'сверок всего %d: «нарушений нет» при этом ничего не значит' % d['checks']
assert d['compliant'], 'нарушений %d, первое: %s' % (
    d['violations'], (d['findings'] or [{}])[0].get('detail'))
" 2>/dev/null && ok || bad "выгрузка не прошла проверку по правилам приложения"

step "Проверка соответствия работает и по загруженным файлам"
python -c "
import json
d = json.load(open('compliance.json', encoding='utf-8'))
print(d['checks'])
" > checks_by_job.txt 2>/dev/null
BY_FILES=$(curl -s --max-time 180 -X POST http://localhost:8080/api/v1/compliance     -F "result=@result.geojson"     -F "dataset=@data/samples/dataset_lct2026.geojson")
echo "$BY_FILES" > compliance_files.json
python -c "
import json
a = json.load(open('compliance.json', encoding='utf-8'))
b = json.load(open('compliance_files.json', encoding='utf-8'))
assert b['compliant'], 'по файлам найдены нарушения: %d' % b['violations']
assert a['checks'] == b['checks'], (
    'сверок по расчёту %d, по файлам %d: проверка должна не зависеть от того, '
    'откуда взялась выгрузка' % (a['checks'], b['checks']))
" 2>/dev/null && ok || bad "проверка по файлам расходится с проверкой по расчёту"

step "Интерфейс отдаётся"
[ "$(curl -s -o /dev/null -w '%{http_code}' --max-time 10 http://localhost:3000/)" = "200" ] \
    && ok || bad "интерфейс не отвечает"

step "Swagger открывается"
[ "$(curl -s -o /dev/null -w '%{http_code}' --max-time 10 http://localhost:8080/swagger-ui/index.html)" = "200" ] \
    && ok || bad "Swagger не отвечает"

echo
if [ $FAILED -eq 0 ]; then
    echo "=== Развёртывание с нуля прошло полностью"
else
    echo "=== Есть провалившиеся шаги, см. выше"
fi
exit $FAILED
