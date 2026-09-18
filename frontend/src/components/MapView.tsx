import { useEffect, useRef } from 'react'
import maplibregl, { Map as MapLibreMap, Popup } from 'maplibre-gl'
import 'maplibre-gl/dist/maplibre-gl.css'
import { BASE_STYLE, COLORS, OSM_SOURCE, SOURCE_IDS } from '../lib/mapStyle'
import { flow, meters, money, OBJECT_TYPE_LABELS, RESTRICTION_LABELS } from '../lib/format'

interface Props {
  scene: GeoJSON.FeatureCollection | null
  result: GeoJSON.FeatureCollection | null
  /** Показывать объекты только этого варианта; null — показывать все. */
  activeVariant: string | null
  showBasemap: boolean
  fitKey: string | null
  /** Идентификатор объекта, выбранного в таблице; карта подсвечивает и показывает его. */
  selectedFeatureId: string | null
}

const EMPTY: GeoJSON.FeatureCollection = { type: 'FeatureCollection', features: [] }

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
  sceneRef.current = scene
  resultRef.current = result
  variantRef.current = activeVariant

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
      addSceneLayers(instance)
      addResultLayers(instance)
      addHighlightLayer(instance)
      attachPopups(instance)
      applyVariantFilter(instance, variantRef.current)
      ready.current = true

      const bounds = sceneRef.current ? boundsOf(sceneRef.current) : null
      if (bounds) {
        instance.fitBounds(bounds, { padding: 60, duration: 0 })
      }
    })

    map.current = instance
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
  { id: 'recon-line', type: 'heat_network_reconstruction' },
  { id: 'proposed-casing', type: 'heat_network' },
  { id: 'proposed-line', type: 'heat_network' },
  { id: 'tie-in', type: 'tie_in' },
  { id: 'new-chamber', type: 'heat_chamber' },
  { id: 'chamber-recon', type: 'heat_chamber_reconstruction' },
  { id: 'technical-node', type: 'technical_node' },
  { id: 'depth-crossing', type: 'depth_crossing' },
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
        'railway', COLORS.tram,
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
  // Реконструкция рисуется под новой сетью: она идёт по существующей трассе,
  // и перекрывать ею новые участки нельзя.
  map.addLayer({
    id: 'recon-line',
    type: 'line',
    source: SOURCE_IDS.result,
    filter: ['==', ['get', 'object_type'], 'heat_network_reconstruction'],
    layout: { 'line-cap': 'round' },
    paint: {
      'line-color': COLORS.reconstruction,
      'line-width': 7,
      'line-opacity': 0.75,
      'line-dasharray': [1.5, 1],
    },
  })

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

  map.addLayer({
    id: 'tie-in',
    type: 'circle',
    source: SOURCE_IDS.result,
    filter: ['==', ['get', 'object_type'], 'tie_in'],
    paint: {
      'circle-radius': 7,
      'circle-color': COLORS.tieIn,
      'circle-stroke-color': '#ffffff',
      'circle-stroke-width': 2,
    },
  })

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
    id: 'chamber-recon',
    type: 'circle',
    source: SOURCE_IDS.result,
    filter: ['==', ['get', 'object_type'], 'heat_chamber_reconstruction'],
    paint: {
      'circle-radius': 9,
      'circle-color': 'rgba(0,0,0,0)',
      'circle-stroke-color': COLORS.chamberReconstruction,
      'circle-stroke-width': 2.5,
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

  // Пересечения по глубине: дополнительная задача. Ромбовидная обводка отличает их
  // от узлов сети — это не сооружение, а место, где трасса меняет глубину.
  map.addLayer({
    id: 'depth-crossing',
    type: 'circle',
    source: SOURCE_IDS.result,
    filter: ['==', ['get', 'object_type'], 'depth_crossing'],
    paint: {
      'circle-radius': 6,
      'circle-color': 'rgba(0,0,0,0)',
      'circle-stroke-color': COLORS.depthCrossing,
      'circle-stroke-width': 2,
    },
  })
}

/**
 * Слой подсветки поверх остальных: выбранный в таблице объект обводится белым.
 * Отдельный слой, а не изменение краски существующего, — иначе пришлось бы
 * пересобирать выражения цвета при каждом выборе строки.
 */
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
function attachPopups(map: MapLibreMap) {
  const clickable = [
    'proposed-line',
    'recon-line',
    'tie-in',
    'new-chamber',
    'chamber-recon',
    'technical-node',
    'depth-crossing',
    'existing-network',
    'existing-chamber',
    'oks-point',
    'heat-source',
    'restrictions-fill',
  ]

  const popup = new Popup({ closeButton: true, maxWidth: '340px', className: 'hr-popup' })

  clickable.forEach((layer) => {
    map.on('click', layer, (event) => {
      const feature = event.features?.[0]
      if (!feature) return
      popup.setLngLat(event.lngLat).setHTML(describe(feature.properties ?? {})).addTo(map)
    })
    map.on('mouseenter', layer, () => {
      map.getCanvas().style.cursor = 'pointer'
    })
    map.on('mouseleave', layer, () => {
      map.getCanvas().style.cursor = ''
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
  if (props.length != null) add('Длина', meters(Number(props.length)))
  if (props.laying_method) {
    add('Способ прокладки',
      props.laying_method === 'special' ? 'Специальный проход' : 'Обычная прокладка')
  }
  if (props.existing_diameter != null) add('Существующий ДУ', `${props.existing_diameter} мм`)
  if (props.required_diameter != null) add('Требуемый ДУ', `${props.required_diameter} мм`)
  if (props.existing_flow_tph != null) add('Существующий расход', flow(Number(props.existing_flow_tph)))
  if (props.added_flow_tph != null) add('Дополнительный расход', flow(Number(props.added_flow_tph)))
  if (props.calculated_flow_tph != null) add('Итоговый расход', flow(Number(props.calculated_flow_tph)))
  if (props.existing_object_id) add('Существующий объект', String(props.existing_object_id))
  if (props.start_node_id) add('Начальный узел', String(props.start_node_id))
  if (props.end_node_id) add('Конечный узел', String(props.end_node_id))
  if (props.depth_start != null) add('Глубина в начале', `${props.depth_start} м`)
  if (props.depth_end != null) add('Глубина в конце', `${props.depth_end} м`)
  if (props.utility_type) {
    add('Пересекаемая коммуникация', RESTRICTION_LABELS[String(props.utility_type)]
      ?? String(props.utility_type))
  }
  if (props.passage) {
    add('Прохождение', props.passage === 'above' ? 'сверху' : 'снизу')
  }
  if (props.new_depth != null) add('Глубина новой сети', `${props.new_depth} м`)
  if (props.utility_depth != null) add('Глубина коммуникации', `${props.utility_depth} м`)
  if (props.actual_clearance != null) {
    add('Вертикальный просвет',
      `${props.actual_clearance} м при норме ${props.required_clearance} м`)
  }
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
