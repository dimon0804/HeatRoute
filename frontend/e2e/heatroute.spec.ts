import { expect, test, type Page } from '@playwright/test'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

/**
 * Сквозной сценарий работы сервиса — тот же, что показывается на защите
 * (раздел 4 ТЗ): загрузить конкурсный набор, увидеть протокол разбора, запустить
 * расчёт, получить варианты, посмотреть участки новой сети, выгрузить результат.
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

    // Разбор стоимости у раскрытого варианта: три составляющие раздела 6 приложения.
    await expect(page.getByText('Из чего сложилась стоимость')).toBeVisible()
    await expect(page.getByText('Новые участки сети')).toBeVisible()
    await expect(page.getByText('Новые тепловые камеры')).toBeVisible()
    await expect(page.getByText(/Врезки в существующие камеры/)).toBeVisible()
    await expect(page.getByText('Стоимость строительства')).toBeVisible()

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

  test('расчёт с учётом глубины даёт профиль участков по глубине', async ({ page }) => {
    await page.goto('/')
    await uploadDataset(page)

    await page.getByLabel('с учётом глубины').check()
    await page.getByRole('button', { name: 'Запустить расчёт' }).click()
    await waitForCalculation(page)

    // Профиль строится по глубинам концов участков: отдельных объектов
    // пересечения выгрузка больше не содержит.
    await openDepthProfile(page)
    await expect(page.getByText('Профиль по глубине')).toBeVisible()

    // Щелчок по участку раскрывает продольный профиль магистрали.
    // Предельный уклон 0,10 м/м — величина, которую проверяющий спросит первой.
    await page.locator('table tbody tr').first().click()
    // Точное совпадение: те же слова есть и в пояснении к таблице над ней.
    await expect(page.getByText('Продольный профиль магистрали', { exact: true }))
      .toBeVisible()
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
  // Разбор идёт синхронно при приёме файла и занимает секунды. Ждать счётчика
  // записей протокола нельзя: он может быть уже виден по набору, который
  // приложение открыло при старте, и тогда проверка пройдёт до конца загрузки.
  // Признак готовности — переход на протокол разбора: это последнее, что делает
  // загрузка, и по чужому набору он не случается.
  await expect(page.getByText('Протокол разбора')).toBeVisible({ timeout: 120_000 })
  await expect(page.getByRole('button', { name: /^Разбор\d+$/ })).toBeVisible()
}

/**
 * Открывает профиль по глубине у варианта, где он содержателен.
 * <p>
 * Отклонения от обычной отметки 3,0 м есть не у каждого варианта: у лучшего
 * трасса может идти на обычной глубине целиком, и тогда таблица пуста по делу.
 * Интерфейс в этом случае предлагает переключить вариант — тест делает то же.
 */
async function openDepthProfile(page: Page) {
  await page.getByRole('button', { name: /^Варианты/ }).click()
  const variants = page.getByRole('button', { name: /^№ \d+$/ })
  const total = await variants.count()
  expect(total).toBeGreaterThan(0)

  for (let index = 0; index < total; index++) {
    await variants.nth(index).click()
    await page.getByRole('button', { name: 'Участки', exact: true }).click()

    // Вкладка глубины есть, только если у участков заполнены глубины концов:
    // в двумерном режиме её не будет, и это отдельный повод для падения.
    const depthTab = page.getByRole('button', { name: /^Глубина \(\d+\)$/ })
    await expect(depthTab).toBeVisible()
    const label = (await depthTab.textContent()) ?? ''
    if (Number(/\((\d+)\)/.exec(label)?.[1] ?? 0) > 0) {
      await depthTab.click()
      return
    }
    await page.getByRole('button', { name: /^Варианты/ }).click()
  }

  throw new Error('Ни у одного варианта нет участков с отклонением по глубине от 3,0 м')
}

async function waitForCalculation(page: Page) {
  // Пока расчёт идёт, в шапке показывается текущий этап; по завершении — сводка
  // с показателем лучшего варианта.
  await expect(page.getByText(/Лучший вариант: S =/)).toBeVisible({ timeout: 150_000 })
}
