import { defineConfig, devices } from '@playwright/test'

/**
 * Сквозные проверки интерфейса.
 * <p>
 * Тесты идут против уже поднятого стека, а не против моков: смысл проверки в том,
 * что загрузка, расчёт, отрисовка и выгрузка работают вместе. Адрес берётся
 * из переменной окружения, поэтому одним и тем же набором проверяется и сборка
 * в docker-compose, и сервер разработки.
 *
 *   docker-compose up -d
 *   npm run e2e
 */
export default defineConfig({
  testDir: './e2e',
  // Расчёт на конкурсном наборе занимает около двадцати секунд, плюс загрузка
  // и отрисовка. Значение по умолчанию в тридцать секунд для такого сценария мало.
  timeout: 180_000,
  expect: { timeout: 30_000 },
  fullyParallel: false,
  workers: 1,
  retries: 0,
  reporter: [['list']],
  use: {
    baseURL: process.env.E2E_BASE_URL ?? 'http://localhost:3000',
    viewport: { width: 1600, height: 950 },
    locale: 'ru-RU',
    screenshot: 'only-on-failure',
    trace: 'retain-on-failure',
    actionTimeout: 30_000,
  },
  projects: [
    {
      name: 'chromium',
      // Разрешение задаётся после набора устройства: у Desktop Chrome своё,
      // и без этого порядка боковая панель и карта не помещаются рядом.
      use: { ...devices['Desktop Chrome'], viewport: { width: 1600, height: 950 } },
    },
  ],
})
