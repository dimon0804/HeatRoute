package ru.lct.heatroute.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.InputStreamResource;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import ru.lct.heatroute.api.dto.DatasetResponse;
import ru.lct.heatroute.api.dto.DiagnosticsEntryDto;
import ru.lct.heatroute.persistence.DatasetEntity;
import ru.lct.heatroute.service.DatasetService;
import ru.lct.heatroute.storage.FileStorageService;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

/** Загрузка и разбор конкурсных наборов. */
@Slf4j
@RestController
@RequestMapping("/api/v1/datasets")
@Profile("!nodb")
@Tag(name = "Наборы данных",
        description = "Загрузка конкурсного набора GeoJSON и протокол его разбора")
public class DatasetController {

    private final DatasetService datasets;
    private final FileStorageService storage;

    public DatasetController(DatasetService datasets, FileStorageService storage) {
        this.datasets = datasets;
        this.storage = storage;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "Загрузить конкурсный набор",
            description = "Файл сохраняется на диск и разбирается потоково, до 3 ГБ. "
                    + "В ответе — сводка по набору и протокол разбора: какие атрибуты "
                    + "восстановлены и какие допущения приняты.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Набор принят и разобран"),
            @ApiResponse(responseCode = "400", description = "Файл не является GeoJSON",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    public DatasetResponse upload(
            @Parameter(description = "Файл GeoJSON типа FeatureCollection")
            @RequestPart("file") MultipartFile file) {
        return datasets.upload(file);
    }

    @GetMapping
    @Operation(summary = "Список загруженных наборов, новые первыми")
    public List<DatasetResponse> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return datasets.list(PageRequest.of(page, Math.min(size, 200)));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Набор со сводкой и протоколом разбора")
    public DatasetResponse get(@PathVariable UUID id) {
        return datasets.get(id);
    }

    @GetMapping("/{id}/diagnostics")
    @Operation(summary = "Протокол разбора",
            description = "Что восстановлено и какие допущения приняты там, "
                    + "где входные данные молчали.")
    public List<DiagnosticsEntryDto> diagnostics(@PathVariable UUID id) {
        return datasets.get(id).getDiagnostics();
    }

    @GetMapping(value = "/{id}/source.geojson", produces = "application/geo+json")
    @Operation(summary = "Исходный файл набора",
            description = "Отдаётся ровно в том виде, в каком был загружен: "
                    + "интерфейс рисует по нему существующую обстановку на карте.")
    public ResponseEntity<InputStreamResource> source(@PathVariable UUID id) throws IOException {
        DatasetEntity entity = datasets.require(id);
        if (!storage.exists(entity.getStoredPath())) {
            throw new DatasetService.NotFoundException(
                    "Файл набора удалён из временного хранилища: " + id);
        }
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "inline; filename=\"" + entity.getOriginalName() + "\"")
                .contentLength(entity.getSizeBytes())
                .body(new InputStreamResource(storage.open(entity.getStoredPath())));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Удалить набор вместе с файлом и расчётами по нему")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        datasets.delete(id);
        return ResponseEntity.noContent().build();
    }
}
