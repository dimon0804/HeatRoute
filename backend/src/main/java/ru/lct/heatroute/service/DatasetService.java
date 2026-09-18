package ru.lct.heatroute.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import ru.lct.heatroute.api.dto.DatasetResponse;
import ru.lct.heatroute.api.dto.DiagnosticsEntryDto;
import ru.lct.heatroute.api.dto.SceneSummaryDto;
import ru.lct.heatroute.domain.model.InputScene;
import ru.lct.heatroute.ingest.GeoJsonFormatException;
import ru.lct.heatroute.ingest.GeoJsonStreamParser;
import ru.lct.heatroute.ingest.SceneAssembler;
import ru.lct.heatroute.persistence.DatasetEntity;
import ru.lct.heatroute.persistence.DatasetRepository;
import ru.lct.heatroute.persistence.DatasetStatus;
import ru.lct.heatroute.storage.FileStorageService;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Приём конкурсного набора и его разбор.
 * <p>
 * Профиль {@code nodb} выключает слой хранения целиком: расчётное ядро тестируется
 * без базы данных, чтобы проверка алгоритма не зависела от поднятого PostgreSQL.
 * <p>
 * Файл сразу переписывается на диск и дальше читается только потоково: требование
 * раздела 3.2 ТЗ — принимать до 3 ГБ, не загружая в память. Разбор выполняется
 * сразу при загрузке, чтобы пользователь увидел протокол допущений до запуска расчёта,
 * а не после.
 */
@Slf4j
@Service
@Profile("!nodb")
public class DatasetService {

    private final FileStorageService storage;
    private final GeoJsonStreamParser parser;
    private final SceneAssembler assembler;
    private final SceneMapper mapper;
    private final DatasetRepository repository;
    private final ObjectMapper json;

    public DatasetService(FileStorageService storage,
                          GeoJsonStreamParser parser,
                          SceneAssembler assembler,
                          SceneMapper mapper,
                          DatasetRepository repository,
                          ObjectMapper json) {
        this.storage = storage;
        this.parser = parser;
        this.assembler = assembler;
        this.mapper = mapper;
        this.repository = repository;
        this.json = json;
    }

    @Transactional
    public DatasetResponse upload(MultipartFile file) {
        UUID id = UUID.randomUUID();
        DatasetEntity entity = new DatasetEntity();
        entity.setId(id);
        entity.setOriginalName(originalName(file));
        entity.setUploadedAt(OffsetDateTime.now());
        entity.setStatus(DatasetStatus.PARSING);

        FileStorageService.StoredFile stored;
        try (InputStream in = file.getInputStream()) {
            stored = storage.store(in, entity.getOriginalName());
        } catch (IOException e) {
            throw new UncheckedIOException("Не удалось сохранить загруженный файл", e);
        }
        entity.setStoredPath(stored.getRelativePath());
        entity.setSizeBytes(stored.getSizeBytes());

        try {
            InputScene scene = parse(entity);
            entity.setFeatureCount(countFeatures(scene));
            entity.setSummaryJson(write(mapper.summarize(scene)));
            entity.setDiagnosticsJson(write(mapper.diagnostics(scene.getDiagnostics())));
            entity.setStatus(scene.getDiagnostics().hasErrors()
                    ? DatasetStatus.INVALID : DatasetStatus.READY);
        } catch (GeoJsonFormatException e) {
            entity.setStatus(DatasetStatus.FAILED);
            entity.setErrorMessage("Файл не является корректным GeoJSON: " + e.getMessage());
            log.warn("Разбор набора {} не удался: {}", id, e.getMessage());
        } catch (RuntimeException e) {
            entity.setStatus(DatasetStatus.FAILED);
            entity.setErrorMessage("Ошибка разбора: " + e.getMessage());
            log.error("Разбор набора {} не удался", id, e);
        }

        repository.save(entity);
        log.info("Набор {} загружен: {} ({} байт), состояние {}",
                id, entity.getOriginalName(), entity.getSizeBytes(), entity.getStatus());
        return toResponse(entity);
    }

    /**
     * Повторный разбор набора из хранилища.
     * <p>
     * Разобранная обстановка сознательно не кешируется в памяти между запросами:
     * на файле в 3 ГБ такой кеш стал бы главным потребителем кучи. Повторный разбор
     * потоковый и стоит секунды.
     */
    public InputScene parse(DatasetEntity entity) {
        return parse(entity, null, null);
    }

    /**
     * Разбор с допущением о загрузке существующей сети, заданным на один расчёт.
     * {@code null} в обоих параметрах означает «как настроено в сервисе».
     */
    public InputScene parse(DatasetEntity entity,
                            ru.lct.heatroute.ingest.IngestProperties.ExistingFlowMode mode,
                            Double fraction) {
        SceneAssembler.Collector collector = new SceneAssembler.Collector();
        try (InputStream in = storage.open(entity.getStoredPath())) {
            parser.parse(in, collector::accept);
        } catch (IOException e) {
            throw new UncheckedIOException("Не удалось прочитать файл набора", e);
        }
        return assembler.assemble(collector, mode, fraction);
    }

    @Transactional(readOnly = true)
    public List<DatasetResponse> list(Pageable pageable) {
        return repository.findAllByOrderByUploadedAtDesc(pageable).getContent().stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public DatasetEntity require(UUID id) {
        return repository.findById(id).orElseThrow(() ->
                new NotFoundException("Набор не найден: " + id));
    }

    @Transactional(readOnly = true)
    public DatasetResponse get(UUID id) {
        return toResponse(require(id));
    }

    @Transactional
    public void delete(UUID id) {
        DatasetEntity entity = require(id);
        storage.delete(entity.getStoredPath());
        repository.delete(entity);
        log.info("Набор {} удалён", id);
    }

    public DatasetResponse toResponse(DatasetEntity entity) {
        return DatasetResponse.builder()
                .id(entity.getId())
                .originalName(entity.getOriginalName())
                .sizeBytes(entity.getSizeBytes())
                .featureCount(entity.getFeatureCount())
                .uploadedAt(entity.getUploadedAt())
                .status(entity.getStatus().name())
                .errorMessage(entity.getErrorMessage())
                .summary(read(entity.getSummaryJson(), SceneSummaryDto.class))
                .diagnostics(readList(entity.getDiagnosticsJson()))
                .build();
    }

    // =================================================================================

    /**
     * Число объектов во входном файле, а не в модели: сервис добавляет производные
     * ограничения (существующая сеть по таблице 5.1) и сливает точки подключения
     * с полигонами ОКС, поэтому счёт по модели ввёл бы в заблуждение.
     */
    private long countFeatures(InputScene scene) {
        return scene.getSourceFeatureCount();
    }

    private String originalName(MultipartFile file) {
        String name = file.getOriginalFilename();
        if (name == null || name.isBlank()) {
            return "dataset.geojson";
        }
        // В имени файла может приехать что угодно, вплоть до попытки выйти из каталога;
        // в путь оно не попадает, но и в базе хранить сырое значение незачем.
        return name.replaceAll("[\\\\/\\p{Cntrl}]", "_");
    }

    private String write(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("Не удалось сериализовать данные набора", e);
        }
    }

    private <T> T read(String value, Class<T> type) {
        if (value == null) {
            return null;
        }
        try {
            return json.readValue(value, type);
        } catch (Exception e) {
            log.warn("Не удалось прочитать сохранённые данные: {}", e.getMessage());
            return null;
        }
    }

    private List<DiagnosticsEntryDto> readList(String value) {
        if (value == null) {
            return List.of();
        }
        try {
            return json.readValue(value, json.getTypeFactory()
                    .constructCollectionType(List.class, DiagnosticsEntryDto.class));
        } catch (Exception e) {
            log.warn("Не удалось прочитать протокол разбора: {}", e.getMessage());
            return List.of();
        }
    }

    /** Запрошенного объекта нет. */
    public static class NotFoundException extends RuntimeException {
        public NotFoundException(String message) {
            super(message);
        }
    }
}
