import { expect, test } from '@playwright/test'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

/**
 * Запретные зоны: «здесь копать нельзя».
 * <p>
 * Проверяется вся цепочка, а не только кнопка: зона ставится щелчком по карте,
 * уходит в запрос расчёта, и построенная сеть её действительно обходит. Последнее
 * проверяется по выгрузке — глазами на карте это не докажешь.
 */

const HERE = path.dirname(fileURLToPath(import.meta.url))
const DATASET = path.resolve(HERE, '../../data/samples/dataset_lct2026.geojson')

/** Метр в градусах широты — для проверки расстояний в выгрузке. */
const METERS_PER_DEGREE_LAT = 111_320

test.describe('Запретные зоны', () => {
  test.describe.configure({ mode: 'serial' })

  test('зона на пути трассы меняет решение, и сеть её обходит', async ({ page }) => {
    test.setTimeout(300_000)
    await page.goto('/')

    await page.getByRole('button', { name: 'Данные', exact: true }).click()
    await page.setInputFiles('input[type=file]', DATASET)
    // Признак окончания приёма файла — переход на протокол разбора. Счётчик
    // записей протокола для этого не годится: он может быть уже виден по набору,
    // который приложение открыло при старте, и тогда расчёт уйдёт по чужому
    // набору, а дописавшаяся загрузка его отбросит.
    await expect(page.getByText('Протокол разбора')).toBeVisible({ timeout: 120_000 })

    // --- расчёт без запретов: он и даёт точку, которую потом запрещаем ---------------
    await runCalculation(page)

    const baseline = await fetchBestGeometry(page)
    expect(baseline.segments.length).toBeGreaterThan(0)

    // Точка на самом длинном участке: место, через которое трасса заведомо шла.
    const longest = baseline.segments.reduce((a, b) => (a.lengthM > b.lengthM ? a : b))
    const [lon, lat] = longest.middle

    // --- ставим зону щелчком по карте ------------------------------------------------
    await page.getByRole('button', { name: 'Указать' }).click()
    await expect(page.getByText('Щёлкните по карте, чтобы поставить зону')).toBeVisible()

    await clickMapAt(page, lon, lat)
    await expect(page.getByText(/№ 1 · R \d+ м/)).toBeVisible()

    // --- пересчёт с запретом -----------------------------------------------------------
    await page.getByRole('button', { name: 'Готово' }).click()
    await runCalculation(page)

    const restricted = await fetchBestGeometry(page)
    // Проверяем именно второй расчёт, а не тот же самый результат заново.
    expect(restricted.jobId).not.toBe(baseline.jobId)
    const radiusM = 40

    // Ни один новый участок не проходит через зону: это и есть смысл запрета.
    for (const segment of restricted.segments) {
      for (const [x, y] of segment.coordinates) {
        const distanceM = Math.hypot(
          (y - lat) * METERS_PER_DEGREE_LAT,
          (x - lon) * METERS_PER_DEGREE_LAT * Math.cos((lat * Math.PI) / 180),
        )
        expect(distanceM, `участок ${segment.id} заходит в запретную зону`)
          .toBeGreaterThan(radiusM - 1)
      }
    }
  })
})

/**
 * Запуск расчёта с ожиданием его окончания.
 * <p>
 * Ждать одного лишь появления строки «Лучший вариант» нельзя: при повторном
 * запуске она ещё показывает итог предыдущего расчёта, и проверка прошла бы
 * мгновенно на старом значении. Признак окончания — кнопка запуска, которая
 * была заблокирована на время этого расчёта и снова стала доступной. Строка
 * итога проверяется уже после этого: она отличает завершённый расчёт от
 * прерванного, у которого кнопка тоже разблокируется.
 */
async function runCalculation(page: import('@playwright/test').Page) {
  const button = page.getByRole('button', { name: /Запустить расчёт|Расчёт идёт/ })
  await button.click()
  await expect(button).toBeDisabled({ timeout: 30_000 })
  await expect(button).toBeEnabled({ timeout: 150_000 })
  await expect(page.getByText(/Лучший вариант: S =/)).toBeVisible({ timeout: 10_000 })
}

/** Геометрия участков лучшего варианта из выгрузки текущего расчёта. */
async function fetchBestGeometry(page: import('@playwright/test').Page) {
  const { jobId, data } = await page.evaluate(async () => {
    // Список расчётов отдаётся от новых к старым, поэтому первый — текущий.
    const jobs = await fetch('/api/v1/jobs?size=1').then((r) => r.json())
    const id = jobs[0].id
    const geo = await fetch(`/api/v1/jobs/${id}/result.geojson`).then((r) => r.json())
    return { jobId: id as string, data: geo }
  })

  const best = data.features
    .filter((f: any) => f.properties.object_type === 'variant_summary')
    .sort((a: any, b: any) => a.properties.rank - b.properties.rank)[0]
  const variantId = best.properties.variant_id

  const segments = data.features
    .filter((f: any) => f.properties.object_type === 'heat_network'
      && f.properties.variant_id === variantId)
    .map((f: any) => {
      const coordinates: [number, number][] = f.geometry.coordinates
        .map((c: number[]) => [c[0], c[1]])
      return {
        id: f.properties.id as string,
        lengthM: f.properties.length as number,
        coordinates,
        middle: coordinates[Math.floor(coordinates.length / 2)],
      }
    })

  return { jobId, variantId, segments }
}

/** Щелчок по карте в заданной географической точке. */
async function clickMapAt(page: import('@playwright/test').Page, lon: number, lat: number) {
  const point = await page.evaluate(([targetLon, targetLat]) => {
    // Экранные координаты точки берём у самой карты: пересчитывать проекцию
    // в тесте — значит проверять свою арифметику, а не поведение сервиса.
    const canvas = document.querySelector('.maplibregl-canvas') as HTMLCanvasElement
    const rect = canvas.getBoundingClientRect()
    const map = (window as any).heatrouteMap
    const projected = map.project([targetLon, targetLat])
    return { x: rect.left + projected.x, y: rect.top + projected.y }
  }, [lon, lat])

  await page.mouse.click(point.x, point.y)
}
