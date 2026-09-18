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

step() { printf '%-58s' "$1"; }
ok()   { echo "PASS"; }
bad()  { echo "FAIL  $1"; FAILED=1; }

cleanup() {
    if [ -d "$WORK" ]; then
        (cd "$WORK" && docker compose down -v >/dev/null 2>&1)
        rm -rf "$WORK"
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
