import { useEffect, useRef } from 'react'
import maplibregl, { Map as MapLibreMap, Popup } from 'maplibre-gl'
import 'maplibre-gl/dist/maplibre-gl.css'
import { BASE_STYLE, COLORS, OSM_SOURCE, SOURCE_IDS } from '../lib/mapStyle'
import { flow, meters, money, OBJECT_TYPE_LABELS, RESTRICTION_LABELS } from '../lib/format'
import type { ForbiddenZone } from '../api/types'

interface Props {
  scene: GeoJSON.FeatureCollection | null
  result: GeoJSON.FeatureCollection | null
  /** Показывать объекты только этого варианта; null — показывать все. */
  activeVariant: string | null
  showBasemap: boolean
  fitKey: string | null
  /** Идентификатор объекта, выбранного в таблице; карта подсвечивает и показывает его. */
  selectedFeatureId: string | null
  /** Запретные зоны: круги, через которые трасса не пройдёт. */
  zones: ForbiddenZone[]
  /** Включён режим постановки зон: щелчок по карте ставит зону, а не открывает объект. */
  placingZone: boolean
  /** Поставить зону в точке щелчка. */
  onPlaceZone: (lon: number, lat: number) => void
}

const EMPTY: GeoJSON.FeatureCollection = { type: 'FeatureCollection', features: [] }

/**
 * Круг на местности в виде полигона.
 * <p>
 * Радиус задаётся в метрах, а координаты — в градусах, поэтому по долготе он делится
 * на косинус широты: на широте Москвы градус долготы почти вдвое короче градуса широты.
 * Шестьдесят четыре вершины — на глаз уже окружность при любом масштабе карты.
 */
function circle(lon: number, lat: number, radiusM: number): GeoJSON.Polygon {
  const points: GeoJSON.Position[] = []
  const latDegrees = radiusM / 111_320
  const lonDegrees = latDegrees / Math.max(Math.cos((lat * Math.PI) / 180), 1e-6)
  for (let i = 0; i <= 64; i++) {
    const angle = (i / 64) * 2 * Math.PI
    points.push([lon + lonDegrees * Math.cos(angle), lat + latDegrees * Math.sin(angle)])
  }
  return { type: 'Polygon', coordinates: [points] }
}

function zoneCollection(zones: ForbiddenZone[]): GeoJSON.FeatureCollection {
  return {
    type: 'FeatureCollection',
    features: zones.map((zone, index) => ({
      type: 'Feature',
      geometry: circle(zone.lon, zone.lat, zone.radiusM),
      properties: { index: index + 1, radius_m: zone.radiusM },
    })),
  }
}

/** Коллекция, пригодная для источника карты: без features MapLibre падает. */
function safe(collection: GeoJSON.FeatureCollection | null): GeoJSON.FeatureCollection {
  return collection?.features ? collection : EMPTY
}

/**
 * Карта: исходная обстановка и построенная сеть.
 * <p>
 * Слои создаются один раз, дальше меняются только данные источников и фильтры.
 * Пересоздавать слои на каждое обновление нельзя — при переключении вариантов карта
 * заметно мигала бы, а на демонстрации это выглядит как сбой.
 * <p>
 * Подписей на карте нет намеренно: без внешнего сервера шрифтов MapLibre не умеет
 * рисовать текст, а зависеть от интернета в зале защиты не стоит. Все сведения
 * об объекте показываются по щелчку.
 */
export function MapView({
  scene, result, activeVariant, showBasemap, fitKey, selectedFeatureId,
  zones, placingZone, onPlaceZone,
}: Props) {
  const container = useRef<HTMLDivElement>(null)
  const map = useRef<MapLibreMap | null>(null)
  const ready = useRef(false)

  // Последние данные держим в ссылках, а не только в состоянии: карта может быть
  // пересоздана (в режиме разработки React монтирует компонент дважды), и тогда
  // применить данные надо заново, хотя сами данные не менялись.
  const sceneRef = useRef(scene)
  const resultRef = useRef(result)
  const variantRef = useRef(activeVariant)
  const zonesRef = useRef(zones)
  const placingRef = useRef(placingZone)
  const placeHandlerRef = useRef(onPlaceZone)
  sceneRef.current = scene
  resultRef.current = result
  variantRef.current = activeVariant
  zonesRef.current = zones
  placingRef.current = placingZone
  placeHandlerRef.current = onPlaceZone

  // --- создание карты -----------------------------------------------------------------
  useEffect(() => {
    if (!container.current || map.current) return

    const instance = new maplibregl.Map({
      container: container.current,
      style: BASE_STYLE,
      center: [37.64, 55.7],
      zoom: 13.5,
      attributionControl: false,
    })
    instance.addControl(new maplibregl.NavigationControl({ showCompass: false }), 'top-right')
    instance.addControl(new maplibregl.ScaleControl({ maxWidth: 140, unit: 'metric' }), 'bottom-left')

    instance.on('load', () => {
      instance.addSource(SOURCE_IDS.scene, { type: 'geojson', data: safe(sceneRef.current) })
      instance.addSource(SOURCE_IDS.result, { type: 'geojson', data: safe(resultRef.current) })
      instance.addSource(SOURCE_IDS.zones, {
        type: 'geojson', data: zoneCollection(zonesRef.current),
      })
      addSceneLayers(instance)
      addResultLayers(instance)
      addZoneLayers(instance)
      addHighlightLayer(instance)
      attachPopups(instance, placingRef)
      attachZonePlacement(instance, placingRef, placeHandlerRef)
      applyVariantFilter(instance, variantRef.current)
      ready.current = true

      const bounds = sceneRef.current ? boundsOf(sceneRef.current) : null
      if (bounds) {
        instance.fitBounds(bounds, { padding: 60, duration: 0 })
      }
    })

    map.current = instance
    // Карта доступна из консоли браузера и из сквозных тестов: тест должен уметь
    // ткнуть в заданную географическую точку, а пересчитывать проекцию у себя —
    // значит проверять свою арифметику вместо поведения сервиса. В работе сервиса
    // эта ссылка ни на что не влияет.
    ;(window as unknown as { heatrouteMap?: MapLibreMap }).heatrouteMap = instance
    return () => {
      instance.remove()
      map.current = null
      ready.current = false
    }
  }, [])

  // --- данные исходной обстановки -------------------------------------------------------
  useEffect(() => {
    const instance = map.current
    if (!instance || !ready.current) return
    const source = instance.getSource(SOURCE_IDS.scene) as maplibregl.GeoJSONSource | undefined
    source?.setData(safe(scene))
  }, [scene])

  // --- данные результата ------------------------------------------------------------------
  useEffect(() => {
    const instance = map.current
    if (!instance || !ready.current) return
    const source = instance.getSource(SOURCE_IDS.result) as maplibregl.GeoJSONSource | undefined
    source?.setData(safe(result))
  }, [result])

  // --- запретные зоны -----------------------------------------------------------------------
  useEffect(() => {
    const instance = map.current
    if (!instance || !ready.current) return
    const source = instance.getSource(SOURCE_IDS.zones) as maplibregl.GeoJSONSource | undefined
    source?.setData(zoneCollection(zones))
  }, [zones])

  // Курсор — единственный признак того, что щелчок сейчас поставит зону, а не откроет
  // карточку объекта. Без него режим незаметен, и на демонстрации это сбивает.
  useEffect(() => {
    const instance = map.current
    if (!instance || !ready.current) return
    instance.getCanvas().style.cursor = placingZone ? 'crosshair' : ''
  }, [placingZone])

  // --- фильтр по варианту -------------------------------------------------------------------
  useEffect(() => {
    const instance = map.current
    if (!instance || !ready.current) return
    applyVariantFilter(instance, activeVariant)
  }, [activeVariant, result])

  // --- подсветка выбранного объекта -------------------------------------------------------
  useEffect(() => {
    const instance = map.current
    if (!instance || !ready.current) return

    const filter: maplibregl.FilterSpecification = selectedFeatureId
      ? ['==', ['get', 'id'], selectedFeatureId]
      : ['==', ['get', 'id'], '\u0000']   // заведомо пустой фильтр
    if (instance.getLayer('highlight-line')) instance.setFilter('highlight-line', filter)
    if (instance.getLayer('highlight-point')) instance.setFilter('highlight-point', filter)

    if (!selectedFeatureId || !result?.features) return
    const feature = result.features.find((f) => f.properties?.id === selectedFeatureId)
    if (!feature?.geometry || !('coordinates' in feature.geometry)) return

    // Показываем объект целиком, не меняя масштаб резко: на демонстрации важно,
    // чтобы зритель не потерял контекст.
    const bounds = boundsOfGeometry(feature.geometry)
    if (bounds) {
      instance.fitBounds(bounds, { padding: 220, duration: 700, maxZoom: 17.5 })
    }
  }, [selectedFeatureId, result])

  // --- подложка ------------------------------------------------------------------------------
  useEffect(() => {
    const instance = map.current
    if (!instance || !ready.current) return
    const has = Boolean(instance.getSource(SOURCE_IDS.osm))
    if (showBasemap && !has) {
      instance.addSource(SOURCE_IDS.osm, OSM_SOURCE)
      instance.addLayer(
        { id: 'osm', type: 'raster', source: SOURCE_IDS.osm, paint: { 'raster-opacity': 0.45 } },
        'restrictions-fill',
      )
    } else if (!showBasemap && has) {
      if (instance.getLayer('osm')) instance.removeLayer('osm')
      instance.removeSource(SOURCE_IDS.osm)
    }
  }, [showBasemap])

  // --- подгонка вида ---------------------------------------------------------------------------
  useEffect(() => {
    const instance = map.current
    if (!instance || !fitKey || !scene) return
    const bounds = boundsOf(scene)
    if (bounds) {
      instance.fitBounds(bounds, { padding: 60, duration: 600 })
    }
  }, [fitKey, scene])

  return <div ref={container} className="h-full w-full" />
}

// =====================================================================================

/** Слои результата: идентификатор слоя и тип объекта, который он показывает. */
const RESULT_LAYERS: { id: string; type: string }[] = [
  { id: 'proposed-casing', type: 'heat_network' },
  { id: 'proposed-line', type: 'heat_network' },
  { id: 'new-chamber', type: 'heat_chamber' },
  { id: 'technical-node', type: 'technical_node' },
]

/** Показывать объекты только выбранного варианта; {@code null} — все сразу. */
function applyVariantFilter(map: MapLibreMap, variant: string | null) {
  RESULT_LAYERS.forEach(({ id, type }) => {
    if (!map.getLayer(id)) return
    const base: maplibregl.FilterSpecification = ['==', ['get', 'object_type'], type]
    map.setFilter(
      id,
      variant
        ? (['all', base, ['==', ['get', 'variant_id'], variant]] as maplibregl.FilterSpecification)
        : base,
    )
  })
}

function addSceneLayers(map: MapLibreMap) {
  // Пространственные ограничения: заливка по типу, чтобы вода и пути читались отдельно.
  map.addLayer({
    id: 'restrictions-fill',
    type: 'fill',
    source: SOURCE_IDS.scene,
    filter: ['==', ['get', 'object_type'], 'restriction'],
    paint: {
      'fill-color': [
        'match',
        ['get', 'restriction_type'],
        'water', COLORS.water,
        // Железная дорога выделена отдельно: её пересечение запрещено,
        // а трамвайные пути проходятся специальным проходом.
        'railway', COLORS.railway,
        'tram_tracks', COLORS.tram,
        'road', COLORS.tram,
        COLORS.restriction,
      ],
      'fill-opacity': 0.85,
    },
  })
  map.addLayer({
    id: 'restrictions-outline',
    type: 'line',
    source: SOURCE_IDS.scene,
    filter: ['==', ['get', 'object_type'], 'restriction'],
    paint: { 'line-color': COLORS.restrictionLine, 'line-width': 0.8 },
  })

  map.addLayer({
    id: 'existing-network',
    type: 'line',
    source: SOURCE_IDS.scene,
    filter: ['==', ['get', 'object_type'], 'heat_network'],
    layout: { 'line-cap': 'round', 'line-join': 'round' },
    paint: {
      'line-color': COLORS.existingNetwork,
      // Толщина по условному диаметру: магистраль видно сразу, без подписей.
      'line-width': ['interpolate', ['linear'], ['coalesce', ['get', 'diameter'], 100], 50, 1.5, 500, 4.5],
    },
  })

  map.addLayer({
    id: 'existing-chamber',
    type: 'circle',
    source: SOURCE_IDS.scene,
    filter: ['==', ['get', 'object_type'], 'heat_chamber'],
    paint: {
      'circle-radius': 4,
      'circle-color': COLORS.existingChamber,
      'circle-stroke-color': '#0b1118',
      'circle-stroke-width': 1,
    },
  })

  map.addLayer({
    id: 'oks-point',
    type: 'circle',
    source: SOURCE_IDS.scene,
    filter: ['==', ['get', 'object_type'], 'oks_connection_point'],
    paint: {
      'circle-radius': ['interpolate', ['linear'], ['coalesce', ['get', 'flow_tph'], 10], 4, 4, 80, 9],
      'circle-color': COLORS.oksPoint,
      'circle-stroke-color': '#0b1118',
      'circle-stroke-width': 1.5,
    },
  })

  map.addLayer({
    id: 'heat-source',
    type: 'circle',
    source: SOURCE_IDS.scene,
    filter: ['==', ['get', 'object_type'], 'source'],
    paint: {
      'circle-radius': 8,
      'circle-color': COLORS.source,
      'circle-stroke-color': COLORS.proposed,
      'circle-stroke-width': 3,
    },
  })
}

function addResultLayers(map: MapLibreMap) {
  // Тёмная обводка под трассой: на плотной застройке она отделяет линию от фона.
  map.addLayer({
    id: 'proposed-casing',
    type: 'line',
    source: SOURCE_IDS.result,
    filter: ['==', ['get', 'object_type'], 'heat_network'],
    layout: { 'line-cap': 'round', 'line-join': 'round' },
    paint: { 'line-color': '#0b1118', 'line-width': 7 },
  })

  map.addLayer({
    id: 'proposed-line',
    type: 'line',
    source: SOURCE_IDS.result,
    filter: ['==', ['get', 'object_type'], 'heat_network'],
    layout: { 'line-cap': 'round', 'line-join': 'round' },
    paint: {
      // Специальный проход выделен цветом: он дороже и требует объяснения на защите.
      'line-color': [
        'match',
        ['get', 'laying_method'],
        'special', COLORS.proposedSpecial,
        COLORS.proposed,
      ],
      'line-width': ['interpolate', ['linear'], ['coalesce', ['get', 'diameter'], 100], 50, 2, 500, 5.5],
    },
  })

  // Отдельного объекта места присоединения в выгрузке нет: новый участок либо
  // заканчивается в существующей камере, либо в новой, поставленной в точке
  // присоединения. Врезки учтены количеством и стоимостью в сводке варианта.
  map.addLayer({
    id: 'new-chamber',
    type: 'circle',
    source: SOURCE_IDS.result,
    filter: ['==', ['get', 'object_type'], 'heat_chamber'],
    paint: {
      'circle-radius': 5,
      'circle-color': COLORS.newChamber,
      'circle-stroke-color': '#0b1118',
      'circle-stroke-width': 1.5,
    },
  })

  map.addLayer({
    id: 'technical-node',
    type: 'circle',
    source: SOURCE_IDS.result,
    filter: ['==', ['get', 'object_type'], 'technical_node'],
    paint: {
      'circle-radius': 3,
      'circle-color': COLORS.technicalNode,
      'circle-stroke-color': '#0b1118',
      'circle-stroke-width': 1,
    },
  })
}

/**
 * Слой подсветки поверх остальных: выбранный в таблице объект обводится белым.
 * Отдельный слой, а не изменение краски существующего, — иначе пришлось бы
 * пересобирать выражения цвета при каждом выборе строки.
 */
/**
 * Слои запретных зон.
 * <p>
 * Рисуются поверх исходной обстановки, но под новой сетью: зона — это ограничение,
 * а не результат, и заслонять построенную трассу она не должна. Штриховая граница
 * отличает её от водных объектов и застройки, которые приходят из данных.
 */
function addZoneLayers(map: MapLibreMap) {
  map.addLayer({
    id: 'zone-fill',
    type: 'fill',
    source: SOURCE_IDS.zones,
    paint: {
      'fill-color': COLORS.forbiddenZone,
      'fill-opacity': 0.14,
    },
  })
  map.addLayer({
    id: 'zone-outline',
    type: 'line',
    source: SOURCE_IDS.zones,
    paint: {
      'line-color': COLORS.forbiddenZone,
      'line-width': 1.6,
      'line-dasharray': [3, 2],
      'line-opacity': 0.85,
    },
  })
}

/**
 * Постановка зоны щелчком по карте.
 * <p>
 * Обработчик ставится один раз и читает режим из ссылки, а не из замыкания: иначе
 * при каждом переключении режима пришлось бы снимать и вешать слушатель заново,
 * а MapLibre в этот момент уже обрабатывает щелчок.
 */
function attachZonePlacement(
  map: MapLibreMap,
  placing: { current: boolean },
  handler: { current: (lon: number, lat: number) => void },
) {
  map.on('click', (event) => {
    if (!placing.current) return
    handler.current(event.lngLat.lng, event.lngLat.lat)
  })
}

function addHighlightLayer(map: MapLibreMap) {
  map.addLayer({
    id: 'highlight-line',
    type: 'line',
    source: SOURCE_IDS.result,
    filter: ['==', ['get', 'id'], '\u0000'],
    layout: { 'line-cap': 'round', 'line-join': 'round' },
    paint: {
      'line-color': COLORS.highlight,
      'line-width': 3,
      'line-opacity': 0.9,
      'line-dasharray': [2, 1.5],
    },
  })
  map.addLayer({
    id: 'highlight-point',
    type: 'circle',
    source: SOURCE_IDS.result,
    filter: ['==', ['get', 'id'], '\u0000'],
    paint: {
      'circle-radius': 12,
      'circle-color': 'rgba(0,0,0,0)',
      'circle-stroke-color': COLORS.highlight,
      'circle-stroke-width': 2,
    },
  })
}

/** Всплывающая карточка по щелчку: все атрибуты объекта так, как они уйдут в выгрузку. */
function attachPopups(map: MapLibreMap, placing: { current: boolean }) {
  const clickable = [
    'proposed-line',
    'new-chamber',
    'technical-node',
    'existing-network',
    'existing-chamber',
    'oks-point',
    'heat-source',
    'restrictions-fill',
  ]

  const popup = new Popup({ closeButton: true, maxWidth: '340px', className: 'hr-popup' })

  clickable.forEach((layer) => {
    map.on('click', layer, (event) => {
      if (placing.current) return
      const feature = event.features?.[0]
      if (!feature) return
      popup.setLngLat(event.lngLat).setHTML(describe(feature.properties ?? {})).addTo(map)
    })
    map.on('mouseenter', layer, () => {
      map.getCanvas().style.cursor = placing.current ? 'crosshair' : 'pointer'
    })
    map.on('mouseleave', layer, () => {
      map.getCanvas().style.cursor = placing.current ? 'crosshair' : ''
    })
  })
}

function describe(props: Record<string, unknown>): string {
  const type = String(props.object_type ?? '')
  const title = OBJECT_TYPE_LABELS[type] ?? type
  const rows: string[] = []

  const add = (label: string, value: string | undefined | null) => {
    if (value == null || value === '') return
    rows.push(
      `<div class="hr-row"><span class="hr-label">${label}</span><span class="hr-value">${value}</span></div>`,
    )
  }

  add('Идентификатор', String(props.id ?? ''))
  if (props.variant_id) add('Вариант', String(props.variant_id))
  if (props.restriction_type) {
    const key = String(props.restriction_type)
    add('Тип ограничения', RESTRICTION_LABELS[key] ?? key)
  }
  if (props.address) add('Адрес', String(props.address))
  if (props.flow_tph != null) add('Расчётный расход', flow(Number(props.flow_tph)))
  if (props.diameter != null) add('Условный диаметр', `${props.diameter} мм`)
  // Число примыкающих участков: по приложению к камере их не больше четырёх.
  if (props.degree != null) add('Примыканий', `${props.degree} из 4`)
  if (props.length != null) add('Длина', meters(Number(props.length)))
  if (props.laying_method) {
    add('Способ прокладки',
      props.laying_method === 'special' ? 'Специальный проход' : 'Обычная прокладка')
  }
  if (props.start_node_id) add('Начальный узел', String(props.start_node_id))
  if (props.end_node_id) add('Конечный узел', String(props.end_node_id))
  // Вертикальное положение задано только глубинами концов участка: Z-координат
  // в геометрии нет, в двумерном режиме глубины приходят пустыми.
  if (props.depth_start != null) add('Глубина в начале', `${props.depth_start} м`)
  if (props.depth_end != null) add('Глубина в конце', `${props.depth_end} м`)
  if (props.cost != null) add('Стоимость', money(Number(props.cost)))

  return `<div class="hr-popup-body"><div class="hr-title">${title}</div>${rows.join('')}</div>`
}

/** Границы одной геометрии. */
function boundsOfGeometry(geometry: GeoJSON.Geometry): maplibregl.LngLatBoundsLike | null {
  return boundsOf({
    type: 'FeatureCollection',
    features: [{ type: 'Feature', geometry, properties: {} }],
  })
}

/** Границы коллекции для подгонки вида. */
function boundsOf(collection: GeoJSON.FeatureCollection): maplibregl.LngLatBoundsLike | null {
  // Коллекция может прийти без features: во входном файле бывает что угодно,
  // а сервис отдаёт его как есть, чтобы карта показывала именно загруженные данные.
  if (!collection?.features?.length) {
    return null
  }
  let minLon = Infinity
  let minLat = Infinity
  let maxLon = -Infinity
  let maxLat = -Infinity

  const walk = (coords: unknown): void => {
    if (Array.isArray(coords) && typeof coords[0] === 'number' && typeof coords[1] === 'number') {
      const [lon, lat] = coords as [number, number]
      minLon = Math.min(minLon, lon)
      minLat = Math.min(minLat, lat)
      maxLon = Math.max(maxLon, lon)
      maxLat = Math.max(maxLat, lat)
      return
    }
    if (Array.isArray(coords)) coords.forEach(walk)
  }

  collection.features.forEach((feature) => {
    if (feature.geometry && 'coordinates' in feature.geometry) {
      walk(feature.geometry.coordinates)
    }
  })

  if (!Number.isFinite(minLon)) return null
  return [
    [minLon, minLat],
    [maxLon, maxLat],
  ]
}
