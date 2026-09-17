-- =====================================================================================
--  Исходная схема сервиса моделирования трасс подключения к тепловым сетям.
--
--  Хранится ровно то, что нужно пережить перезапуск и показать на защите: загруженные
--  наборы с протоколом разбора, запуски расчёта с их состоянием, варианты и их объекты.
--  Сами входные файлы в базу не кладутся — они остаются во временном дисковом
--  хранилище: требование ТЗ о файлах до 3 ГБ несовместимо с их загрузкой в СУБД.
-- =====================================================================================

CREATE EXTENSION IF NOT EXISTS postgis;

-- -------------------------------------------------------------------------------------
--  Загруженный конкурсный набор
-- -------------------------------------------------------------------------------------
CREATE TABLE dataset
(
    id              UUID PRIMARY KEY,
    original_name   VARCHAR(512)             NOT NULL,
    stored_path     VARCHAR(1024)            NOT NULL,
    size_bytes      BIGINT                   NOT NULL,
    feature_count   BIGINT                   NOT NULL DEFAULT 0,
    uploaded_at     TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),

    -- Сводка разбора: сколько объектов какого типа, суммарный расход, габарит.
    summary_json    TEXT,
    -- Протокол допущений и предупреждений (IngestDiagnostics).
    diagnostics_json TEXT,

    status          VARCHAR(32)              NOT NULL,
    error_message   TEXT
);

COMMENT ON TABLE dataset IS 'Загруженный входной набор GeoJSON и результат его разбора';
COMMENT ON COLUMN dataset.diagnostics_json IS
    'Протокол разбора: какие атрибуты восстановлены и какие допущения приняты';

CREATE INDEX idx_dataset_uploaded_at ON dataset (uploaded_at DESC);

-- -------------------------------------------------------------------------------------
--  Запуск расчёта
-- -------------------------------------------------------------------------------------
CREATE TABLE calculation_job
(
    id             UUID PRIMARY KEY,
    dataset_id     UUID                     NOT NULL REFERENCES dataset (id) ON DELETE CASCADE,

    status         VARCHAR(32)              NOT NULL,
    -- Доля выполненного, 0..1; расчёт длится десятки секунд, и прогресс нужен интерфейсу.
    progress       DOUBLE PRECISION         NOT NULL DEFAULT 0,
    stage          VARCHAR(256),

    created_at     TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    started_at     TIMESTAMP WITH TIME ZONE,
    finished_at    TIMESTAMP WITH TIME ZONE,

    params_json    TEXT,
    -- Показатели прогона: размер графа, число кандидатов врезки, время.
    stats_json     TEXT,
    error_message  TEXT
);

COMMENT ON TABLE calculation_job IS 'Фоновый расчёт вариантов подключения по одному набору';

CREATE INDEX idx_job_dataset ON calculation_job (dataset_id);
CREATE INDEX idx_job_created_at ON calculation_job (created_at DESC);

-- -------------------------------------------------------------------------------------
--  Вариант подключения
-- -------------------------------------------------------------------------------------
CREATE TABLE variant
(
    id             UUID PRIMARY KEY,
    job_id         UUID          NOT NULL REFERENCES calculation_job (id) ON DELETE CASCADE,

    -- Идентификатор варианта в выгрузке (variant_id раздела 10 ТП).
    variant_code   VARCHAR(64)   NOT NULL,
    rank           INTEGER       NOT NULL,
    description    TEXT,

    -- Сводная запись раздела 10.7 целиком.
    summary_json   TEXT          NOT NULL,
    -- Отпечаток структуры: точки врезки и разбиение ОКС по частям сети.
    fingerprint    TEXT,

    -- Дублируются отдельными колонками для сортировки и сравнения без разбора JSON.
    score          DOUBLE PRECISION NOT NULL,
    calculated_cost DOUBLE PRECISION NOT NULL,
    total_length   DOUBLE PRECISION NOT NULL,

    UNIQUE (job_id, variant_code)
);

CREATE INDEX idx_variant_job_rank ON variant (job_id, rank);

-- -------------------------------------------------------------------------------------
--  Объекты варианта
-- -------------------------------------------------------------------------------------
CREATE TABLE variant_feature
(
    id              BIGSERIAL PRIMARY KEY,
    variant_id      UUID         NOT NULL REFERENCES variant (id) ON DELETE CASCADE,

    -- object_type раздела 10 ТП: heat_network, tie_in, heat_chamber и прочие.
    object_type     VARCHAR(64)  NOT NULL,
    feature_id      VARCHAR(128) NOT NULL,
    ordinal         INTEGER      NOT NULL,

    -- Геометрия в WGS 84 — ровно в том виде, в каком уходит в выгрузку и на карту.
    -- У сводной записи варианта геометрии нет (раздел 10.7 ТП).
    geom            geometry(Geometry, 4326),

    -- Собственный обязательный набор атрибутов типа, без чужих полей.
    properties_json TEXT         NOT NULL
);

COMMENT ON TABLE variant_feature IS
    'Объекты выходного GeoJSON по разделу 10 ТП, по одному на строку';

CREATE INDEX idx_feature_variant ON variant_feature (variant_id, ordinal);
CREATE INDEX idx_feature_type ON variant_feature (variant_id, object_type);
CREATE INDEX idx_feature_geom ON variant_feature USING GIST (geom);
