/** @type {import('tailwindcss').Config} */
export default {
  content: ['./index.html', './src/**/*.{ts,tsx}'],
  theme: {
    extend: {
      colors: {
        // Палитра карты: цвета слоёв заданы здесь и в lib/mapStyle.ts одними значениями,
        // чтобы легенда и карта не разъезжались.
        ink: '#0f1720',
        panel: '#151d28',
        edge: '#233040',
        muted: '#8ea0b5',
        accent: '#4da3ff',
        existing: '#6b7d91',
        proposed: '#ff8a3d',
        warn: '#ffd23f',
        alert: '#ff4d6d',
        chamber: '#4da3ff',
        oks: '#3ddc97',
      },
      fontFamily: {
        sans: ['Inter', 'Segoe UI', 'system-ui', 'sans-serif'],
        mono: ['JetBrains Mono', 'Consolas', 'monospace'],
      },
    },
  },
  plugins: [],
}
