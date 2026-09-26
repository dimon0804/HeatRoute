import { expect, test } from '@playwright/test'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

/**
 * Проверка выгрузки на соответствие техническому приложению.
 * <p>
 * Проверяются оба входа, потому что это разные вопросы. Первый: сходится ли с
 * приложением то, что сервис выдаёт сам. Второй, и он важнее для заказчика:
 * можно ли той же проверкой принять чужую выгрузку. Второй тест поэтому берёт
 * файл, выгруженный интерфейсом в первом, и подаёт его как файл подрядчика.
 */

const HERE = path.dirname(fileURLToPath(import.meta.url))
const DATASET = path.resolve(HERE, '../../data/samples/dataset_lct2026.geojson')

/** Выгрузка, полученная первым тестом: её проверяет второй. */
let exportedResult: string | null = null

test.describe('Соответствие приложению', () => {
  test.describe.configure({ mode: 'serial' })

  test('после расчёта видно число сверок и отсутствие нарушений', async ({ page }, info) => {
    test.setTimeout(300_000)

    await page.goto('/')
    await page.getByRole('button', { name: 'Данные', exact: true }).click()
    await page.setInputFiles('input[type=file]', DATASET)
    await expect(page.getByText('Протокол разбора')).toBeVisible({ timeout: 120_000 })

    // Расчёт на конкурсном наборе идёт около двадцати секунд. Признак окончания —
    // кнопка запуска, снова ставшая доступной: строка итога в шапке при повторном
    // запуске ещё показывает предыдущий расчёт.
    const run = page.getByRole('button', { name: /Запустить расчёт|Расчёт идёт/ })
    await run.click()
    await expect(run).toBeDisabled({ timeout: 60_000 })
    await expect(run).toBeEnabled({ timeout: 180_000 })
    await expect(page.getByText(/Лучший вариант: S =/)).toBeVisible({ timeout: 30_000 })

    // --- отчёт по своей выгрузке --------------------------------------------------------
    await page.getByRole('button', { name: /^Проверка/ }).click()
    const report = page.locator('section')
      .filter({ hasText: 'Соответствие приложению' })
      .first()

    await expect(report.getByText('Нарушений не найдено')).toBeVisible({ timeout: 60_000 })

    // Число сверок — обязательная часть утверждения: «нарушений нет» при нуле
    // сверок ничего не значит, и тест проверяет именно это.
    const checksLine = report.getByText(/^Выполнено [\d\s]+сверо?к/)
    await expect(checksLine).toBeVisible()
    const checks = Number(((await checksLine.textContent()) ?? '').replace(/\D/g, ''))
    expect(checks, 'сверок должно быть больше нуля').toBeGreaterThan(0)

    // Состав выгрузки берётся из того же отчёта: по нему видно, что проверялось.
    await expect(report.getByText('Что проверено')).toBeVisible()
    await expect(report.getByText('Тепловая сеть')).toBeVisible()

    // --- выгрузка для второго теста ------------------------------------------------------
    await page.getByRole('button', { name: /^Варианты/ }).click()
    const [download] = await Promise.all([
      page.waitForEvent('download'),
      page.getByRole('button', { name: /Выгрузить результат/ }).click(),
    ])
    exportedResult = info.outputPath('result.geojson')
    await download.saveAs(exportedResult)
  })

  test('загруженная выгрузка проверяется вместе с входным набором', async ({ page }) => {
    test.setTimeout(180_000)
    test.skip(exportedResult === null, 'Нет выгрузки от предыдущего теста')

    await page.goto('/')
    await page.getByRole('button', { name: /^Проверка/ }).click()

    const upload = page.locator('section')
      .filter({ hasText: 'Проверка чужой выгрузки' })
      .first()

    // Порядок полей в панели: сначала проверяемая выгрузка, затем входной набор.
    const files = upload.locator('input[type=file]')
    await files.nth(0).setInputFiles(exportedResult as string)
    await files.nth(1).setInputFiles(DATASET)

    await upload.getByRole('button', { name: 'Проверить выгрузку' }).click()

    await expect(upload.getByText('Нарушений не найдено')).toBeVisible({ timeout: 60_000 })
    const checksLine = upload.getByText(/^Выполнено [\d\s]+сверо?к/)
    await expect(checksLine).toBeVisible()
    const checks = Number(((await checksLine.textContent()) ?? '').replace(/\D/g, ''))
    expect(checks, 'сверок должно быть больше нуля').toBeGreaterThan(0)
  })
})
