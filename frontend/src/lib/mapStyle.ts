import type { StyleSpecification } from 'maplibre-gl'

/**
 * Цвета слоёв. Один источник истины для карты и легенды — иначе они разъезжаются
 * при первой же правке.
 */
export const COLORS = {
  background: '#0b1118',
  restriction: '#1d2836',
  restrictionLine: '#2b3a4d',
  water: '#16313f',
  tram: '#3a2f1d',
  existingNetwork: '#7d8fa3',
  source: '#ffffff',
  existingChamber: '#5b6f86',
  oksPoint: '#3ddc97',
  proposed: '#ff8a3d',
  proposedSpecial: '#ff5ec4',
  reconstruction: '#ffd23f',
  tieIn: '#ff4d6d',
  newChamber: '#4da3ff',
  chamberReconstruction: '#ffd23f',
  technicalNode: '#c9d6e4',
} as const

/**
 * Базовый стиль карты — сплошная подложка без внешних тайлов.
 * <p>
 * Выбрано сознательно: демонстрация не должна зависеть от интернета в зале.
 * Вся содержательная информация кейса и так приходит из набора — застройка,
 * дороги, водные объекты; подложка ничего к ней не добавляет. Растровые тайлы
 * OpenStreetMap включаются переключателем, когда сеть есть.
 */
export const BASE_STYLE: StyleSpecification = {
  version: 8,
  name: 'HeatRoute',
  sources: {},
  layers: [
    {
      id: 'background',
      type: 'background',
      paint: { 'background-color': COLORS.background },
    },
  ],
  // Ключ glyphs не задаётся вовсе: MapLibre проверяет стиль и на значении undefined
  // выдаёт ошибку. Подписей на карте нет, шрифты не нужны.
}

/** Источник растровой подложки OpenStreetMap — подключается по требованию. */
export const OSM_SOURCE = {
  type: 'raster' as const,
  tiles: ['https://tile.openstreetmap.org/{z}/{x}/{y}.png'],
  tileSize: 256,
  attribution: '© OpenStreetMap contributors',
  maxzoom: 19,
}

/** Идентификаторы источников данных карты. */
export const SOURCE_IDS = {
  scene: 'scene',
  result: 'result',
  osm: 'osm',
} as const

/** Легенда: что каким цветом показано. */
export interface LegendItem {
  color: string
  label: string
  shape: 'line' | 'dashed' | 'circle' | 'area'
  group: 'Исходные данные' | 'Новая сеть'
}

export const LEGEND: LegendItem[] = [
  { color: COLORS.restriction, label: 'Существующая застройка и ограничения', shape: 'area', group: 'Исходные данные' },
  { color: COLORS.water, label: 'Водные объекты', shape: 'area', group: 'Исходные данные' },
  { color: COLORS.existingNetwork, label: 'Существующая тепловая сеть', shape: 'line', group: 'Исходные данные' },
  { color: COLORS.existingChamber, label: 'Существующая тепловая камера', shape: 'circle', group: 'Исходные данные' },
  { color: COLORS.source, label: 'Источник теплоснабжения', shape: 'circle', group: 'Исходные данные' },
  { color: COLORS.oksPoint, label: 'Точка подключения перспективного ОКС', shape: 'circle', group: 'Исходные данные' },

  { color: COLORS.proposed, label: 'Новый участок, обычная прокладка', shape: 'line', group: 'Новая сеть' },
  { color: COLORS.proposedSpecial, label: 'Новый участок, специальный проход', shape: 'line', group: 'Новая сеть' },
  { color: COLORS.reconstruction, label: 'Реконструкция существующего участка', shape: 'dashed', group: 'Новая сеть' },
  { color: COLORS.tieIn, label: 'Точка врезки', shape: 'circle', group: 'Новая сеть' },
  { color: COLORS.newChamber, label: 'Новая тепловая камера', shape: 'circle', group: 'Новая сеть' },
  { color: COLORS.technicalNode, label: 'Технический узел', shape: 'circle', group: 'Новая сеть' },
]
