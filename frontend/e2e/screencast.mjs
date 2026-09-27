/**
 * Запись скринкаста демонстрации: то же, что показывается на защите, но без
 * человека за клавиатурой.
 *
 * Нужен он для поля «Прототип» в форме сдачи: развёрнутый стенд там не обязателен,
 * годится запись работы сервиса. Сценарий повторяет раздел 4 ТЗ по порядку
 * и делает паузы там, где зрителю надо успеть прочитать.
 *
 * Запуск против поднятого стека:
 *   cd frontend && node e2e/screencast.mjs [каталог для записи]
 */
import { chromium } from '@playwright/test'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const HERE = path.dirname(fileURLToPath(import.meta.url))
const DATASET = path.resolve(HERE, '../../data/samples/dataset_lct2026.geojson')
const BASE = process.env.E2E_BASE_URL ?? 'http://localhost:3000'
const OUT = process.argv[2] ?? path.resolve(HERE, '../screencast')

/** Пауза для зрителя: прочитать экран человеку нужно дольше, чем отрисовать его. */
const read = (page, ms) => page.waitForTimeout(ms)

const browser = await chromium.launch()
const context = await browser.newContext({
  viewport: { width: 1600, height: 900 },
  locale: 'ru-RU',
  recordVideo: { dir: OUT, size: { width: 1600, height: 900 } },
})
const page = await context.newPage()

try {
  await page.goto(BASE)
  await page.getByRole('heading', { name: 'HeatRoute' }).waitFor()
  await read(page, 2500)

  // --- 1. Загрузка конкурсного набора ---------------------------------------------
  await page.getByRole('button', { name: 'Данные', exact: true }).click()
  await read(page, 1200)
  await page.setInputFiles('input[type=file]', DATASET)
  await page.getByText('Протокол разбора').waitFor({ timeout: 120_000 })
  await read(page, 4000)

  // --- 2. Протокол разбора: допущения видны до расчёта, а не после -----------------
  await page.getByRole('button', { name: 'Данные', exact: true }).click()
  await read(page, 3500)

  // --- 3. Расчёт --------------------------------------------------------------------
  await page.getByRole('button', { name: 'Запустить расчёт' }).click()
  await page.getByText(/Лучший вариант: S =/).waitFor({ timeout: 150_000 })
  await read(page, 2500)

  // --- 4. Варианты и разбор стоимости -----------------------------------------------
  await page.getByRole('button', { name: /^Варианты/ }).click()
  await read(page, 5000)
  for (const label of ['№ 2', '№ 3', '№ 1']) {
    await page.getByRole('button', { name: label, exact: true }).click()
    await read(page, 2500)
  }

  // --- 5. Участки новой сети и камеры ------------------------------------------------
  await page.getByRole('button', { name: 'Участки', exact: true }).click()
  await read(page, 3500)
  await page.getByRole('button', { name: /^Камеры/ }).click()
  await read(page, 3000)

  // --- 6. Разбор трассы: почему труба пошла именно здесь ------------------------------
  await page.getByRole('button', { name: 'Почему здесь' }).click()
  const rows = page.locator('table tbody tr')
  await rows.first().waitFor({ timeout: 60_000 })
  await read(page, 3000)
  const count = await rows.count()
  for (let index = 0; index < count; index++) {
    const text = (await rows.nth(index).textContent()) ?? ''
    if (!text.includes('свободно')) {
      await rows.nth(index).click()
      break
    }
  }
  await read(page, 6000)

  // --- 7. Проверка соответствия приложению ---------------------------------------------
  await page.getByRole('button', { name: /^Проверка/ }).click()
  await read(page, 6000)

  // --- 8. Выгрузка результата ------------------------------------------------------------
  await page.getByRole('button', { name: /^Варианты/ }).click()
  await read(page, 1500)
  await Promise.all([
    page.waitForEvent('download'),
    page.getByRole('button', { name: /Выгрузить результат/ }).click(),
  ])
  await read(page, 2500)
} finally {
  await context.close()
  await browser.close()
}

console.log('запись в', OUT)
