import { expect, test, type Page } from '@playwright/test'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

/**
 * Сквозной сценарий работы сервиса — тот же, что показывается на защите
 * (раздел 4 ТЗ): загрузить конкурсный набор, увидеть протокол разбора, запустить
 * расчёт, получить варианты, посмотреть участки и реконструкцию, выгрузить результат.
 *
 * Проверка идёт против поднятого стека целиком, включая базу и nginx: смысл именно
 * в том, что части работают вместе. Отдельные слои покрыты тестами на бэкенде.
 */

const HERE = path.dirname(fileURLToPath(import.meta.url))
const DATASET = path.resolve(HERE, '../../data/samples/dataset_lct2026.geojson')

test.describe('Сценарий демонстрации', () => {
  test.describe.configure({ mode: 'serial' })

  test('загрузка набора, расчёт, варианты и выгрузка', async ({ page }) => {
    await page.goto('/')
    await expect(page.getByRole('heading', { name: 'HeatRoute' })).toBeVisible()

    // --- 1. Загрузка конкурсного набора ------------------------------------------------
    // После загрузки приложение само открывает протокол разбора: пользователь должен
    // увидеть принятые допущения до того, как запустит расчёт.
    await uploadDataset(page)
    await expect(page.getByText('Протокол разбора')).toBeVisible()

    // --- 2. Протокол разбора -----------------------------------------------------------
    // Ключевые допущения, без которых расчёт на этом наборе невозможен.
    await expect(page.getByText('upstream.inferred')).toBeVisible()
    await expect(page.getByText('oks.footprintFromRestriction')).toBeVisible()
    await expect(page.getByText('chamber.diameterInferred')).toBeVisible()

    // Сводка по набору: значения из технического приложения и самого файла.
    await page.getByRole('button', { name: 'Данные', exact: true }).click()
    await expect(page.getByText('Перспективных ОКС')).toBeVisible()
    await expect(page.getByText('488,72 т/ч')).toBeVisible()

    // --- 3. Расчёт ----------------------------------------------------------------------
    await page.getByRole('button', { name: 'Запустить расчёт' }).click()
    await waitForCalculation(page)

    // --- 4. Варианты --------------------------------------------------------------------
    await page.getByRole('button', { name: /^Варианты/ }).click()
    await expect(page.getByText('Варианты подключения')).toBeVisible()

    // Показатель ранжирования виден у каждого варианта, лучший — первым.
    const scores = page.locator('text=/^S = \\d+,\\d+$/')
    await expect(scores.first()).toBeVisible()
    const scoreCount = await scores.count()
    expect(scoreCount).toBeGreaterThanOrEqual(1)
    expect(scoreCount).toBeLessThanOrEqual(3)

    // Разбор стоимости у раскрытого варианта: все составляющие раздела 8 ТП.
    await expect(page.getByText('Из чего сложилась стоимость')).toBeVisible()
    await expect(page.getByText('Новые участки сети')).toBeVisible()
    await expect(page.getByText('Врезки', { exact: true })).toBeVisible()
    await expect(page.getByText('Реконструкция участков')).toBeVisible()

    // --- 5. Участки ----------------------------------------------------------------------
    await page.getByRole('button', { name: 'Участки', exact: true }).click()
    await expect(page.getByText('Участки новой сети')).toBeVisible()
    const rows = page.locator('table tbody tr')
    expect(await rows.count()).toBeGreaterThan(5)

    // --- 6. Выгрузка ----------------------------------------------------------------------
    await page.getByRole('button', { name: /^Варианты/ }).click()
    const [download] = await Promise.all([
      page.waitForEvent('download'),
      page.getByRole('button', { name: /Выгрузить результат/ }).click(),
    ])
    expect(download.suggestedFilename()).toContain('.geojson')
  })

  test('расчёт с учётом глубины даёт пересечения и глубины участков', async ({ page }) => {
    await page.goto('/')
    await uploadDataset(page)

    await page.getByLabel('с учётом глубины').check()
    await page.getByRole('button', { name: 'Запустить расчёт' }).click()
    await waitForCalculation(page)

    // Пересечения бывают не у каждого варианта: у лучшего трасса может идти
    // на обычной глубине целиком. Переключаемся на тот, где они есть.
    await page.getByRole('button', { name: /^Варианты/ }).click()
    await page.getByText('по глубине', { exact: false }).first().click()

    await page.getByRole('button', { name: 'Участки', exact: true }).click()
    await page.getByRole('button', { name: /^Глубина/ }).click()

    await expect(page.getByText('Пересечения по глубине')).toBeVisible()
    // Прохождение сверху или снизу и вертикальный просвет — требование раздела 7
    // приложения по глубине.
    await expect(page.locator('table tbody tr').first()).toBeVisible()
    await expect(page.getByText('сверху').first()).toBeVisible()

    // Щелчок по пересечению раскрывает продольный профиль магистрали и разрез.
    // Предельный уклон 0,10 м/м — величина, которую проверяющий спросит первой.
    await page.locator('table tbody tr').first().click()
    await expect(page.getByText('Продольный профиль магистрали')).toBeVisible()
    await expect(page.getByText('Разрез в месте пересечения')).toBeVisible()
    await expect(page.getByText(/Наибольший уклон/)).toBeVisible()
    await expect(page.getByText(/при пределе 0,10 м\/м|при пределе 0\.10 м\/м/)).toBeVisible()
  })

  test('ошибочный файл не роняет сервис и объясняет причину', async ({ page }) => {
    await page.goto('/')
    await page.getByRole('button', { name: 'Данные', exact: true }).click()

    // Подсовываем не GeoJSON: сервис обязан ответить внятно, а не пятисоткой.
    await page.setInputFiles('input[type=file]', {
      name: 'broken.geojson',
      mimeType: 'application/geo+json',
      buffer: Buffer.from('{"type":"Nonsense"}', 'utf-8'),
    })

    // Набор принят, но помечен как нерасбираемый; причина видна пользователю.
    await expect(page.getByText(/Ошибка|Файл не является/).first()).toBeVisible({
      timeout: 60_000,
    })
  })
})

// =====================================================================================

async function uploadDataset(page: Page) {
  await page.getByRole('button', { name: 'Данные', exact: true }).click()
  await page.setInputFiles('input[type=file]', DATASET)
  // Разбор идёт синхронно при загрузке: признак готовности — появление вкладки
  // разбора с непустым счётчиком записей протокола.
  await expect(page.getByRole('button', { name: /^Разбор\d+$/ }))
    .toBeVisible({ timeout: 120_000 })
}

async function waitForCalculation(page: Page) {
  // Пока расчёт идёт, в шапке показывается текущий этап; по завершении — сводка
  // с показателем лучшего варианта.
  await expect(page.getByText(/Лучший вариант: S =/)).toBeVisible({ timeout: 150_000 })
}
