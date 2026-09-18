import { expect, test } from '@playwright/test'

/**
 * Устойчивость интерфейса к тому, что уже лежит в базе.
 * <p>
 * Тест появился после реального падения: проверка ошибочного файла оставила
 * нерасбираемый набор, и при следующем открытии страницы приложение падало
 * на попытке посчитать границы коллекции без объектов. Экран оставался пустым —
 * на защите это выглядело бы как отказ сервиса.
 */
test.describe('Устойчивость', () => {
  test('страница открывается, когда среди наборов есть нерасбираемый', async ({ page }) => {
    const errors: string[] = []
    page.on('pageerror', (error) => errors.push(error.message))

    // Сначала кладём заведомо негодный файл — он останется в списке наборов.
    await page.goto('/')
    await page.getByRole('button', { name: 'Данные', exact: true }).click()
    await page.setInputFiles('input[type=file]', {
      name: 'broken.geojson',
      mimeType: 'application/geo+json',
      buffer: Buffer.from('{"type":"Nonsense"}', 'utf-8'),
    })
    await expect(page.getByText(/Ошибка|Файл не является/).first()).toBeVisible({
      timeout: 60_000,
    })

    // Перезагружаем страницу: список наборов читается заново, и первым идёт битый.
    await page.reload()

    await expect(page.getByRole('heading', { name: 'HeatRoute' })).toBeVisible()
    await expect(page.getByRole('button', { name: 'Данные', exact: true })).toBeVisible()
    expect(errors, 'необработанных ошибок на странице быть не должно').toEqual([])
  })

  test('карта остаётся живой без данных и принимает набор после этого', async ({ page }) => {
    const errors: string[] = []
    page.on('pageerror', (error) => errors.push(error.message))

    await page.goto('/')
    // Кнопка расчёта не должна быть доступна, пока не выбран пригодный набор.
    await expect(page.getByRole('button', { name: /Запустить расчёт/ })).toBeVisible()

    await page.getByRole('button', { name: 'Данные', exact: true }).click()
    await page.setInputFiles('input[type=file]', '../data/samples/dataset_lct2026.geojson')
    await expect(page.getByRole('button', { name: /^Разбор\d+$/ }))
      .toBeVisible({ timeout: 120_000 })

    expect(errors).toEqual([])
  })
})
