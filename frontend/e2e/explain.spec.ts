import { expect, test, type Page } from '@playwright/test'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

/**
 * Три вещи, которых кейс не требует, но которые решают главный вопрос доверия:
 * почему трасса прошла именно здесь, чем подтверждена оптимальность и что
 * держит цену.
 *
 * Проверяются они здесь, а не в `heatroute.spec.ts`, по одной причине: анализ
 * чувствительности решает задачу заново несколько раз и идёт минуту с лишним.
 * Держать такой тест в основном сценарии значит сделать его неудобным для
 * частого прогона.
 */

const HERE = path.dirname(fileURLToPath(import.meta.url))
const DATASET = path.resolve(HERE, '../../data/samples/dataset_lct2026.geojson')

test.describe('Разбор трассы и чувствительность', () => {
  test.describe.configure({ mode: 'serial' })

  test('разбор трассы называет, чем зажат каждый участок', async ({ page }) => {
    await prepare(page)

    await page.getByRole('button', { name: 'Участки', exact: true }).click()
    await page.getByRole('button', { name: 'Почему здесь' }).click()
    await expect(page.getByText('Почему трасса прошла здесь')).toBeVisible()

    // Строк столько же, сколько участков у варианта: разбор идёт по всей трассе,
    // а не по выбранному куску.
    const rows = page.locator('table tbody tr')
    await expect(rows.first()).toBeVisible()
    expect(await rows.count()).toBeGreaterThan(5)

    // Щелчок по строке раскрывает вывод по участку и числа под ним: фактическое
    // расстояние до ограничения против требуемого. Без этих чисел вывод —
    // просто утверждение.
    await rows.first().click()
    await expect(page.getByText(/Участок (примыкает|лежит свободно)|Место участка задано/))
      .toBeVisible()
    await expect(page.getByRole('columnheader', { name: 'Нужно' })).toBeVisible()
  })

  test('сводка прогона показывает повороты и проверенные ходы', async ({ page }) => {
    await prepare(page)

    await page.getByRole('button', { name: /^Варианты/ }).click()
    await expect(page.getByText('Как получен результат')).toBeVisible()

    // Предел угла поворота появился в редакции приложения от 18.09, и ноль здесь —
    // это утверждение, которое проверяющий может сверить с выгрузкой.
    await expect(page.getByText('Поворотов круче предела')).toBeVisible()
    // Сертификат локальной оптимальности: сколько одиночных ходов проверено
    // на итоговом дереве без улучшения.
    await expect(page.getByText('Проверено ходов без улучшения')).toBeVisible()
  })

  test('анализ чувствительности называет самую дорогую точку подключения', async ({
    page,
  }) => {
    // Каждая проверяемая точка — это полный пересчёт задачи, поэтому времени
    // нужно заметно больше обычного.
    test.setTimeout(420_000)
    await prepare(page)

    await page.getByRole('button', { name: /^Варианты/ }).click()
    await expect(page.getByText('Что держит цену')).toBeVisible()
    await page.getByRole('button', { name: 'Посчитать' }).click()

    await expect(page.getByText(/^Без точки /).first()).toBeVisible({ timeout: 300_000 })
    await expect(page.getByText(/дешевле на /).first()).toBeVisible()
    await expect(page.getByText(/прогон(а|ов)? за /)).toBeVisible()
  })
})

// =====================================================================================

/** Загрузить конкурсный набор и досчитать до конца: разбор нужен готовой трассе. */
async function prepare(page: Page) {
  await page.goto('/')
  await page.getByRole('button', { name: 'Данные', exact: true }).click()
  await page.setInputFiles('input[type=file]', DATASET)
  await expect(page.getByText('Протокол разбора')).toBeVisible({ timeout: 120_000 })

  await page.getByRole('button', { name: 'Запустить расчёт' }).click()
  await expect(page.getByText(/Лучший вариант: S =/)).toBeVisible({ timeout: 150_000 })
}
