package ru.lct.heatroute.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import io.swagger.v3.oas.annotations.Parameter;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import ru.lct.heatroute.api.dto.CreateJobRequest;
import ru.lct.heatroute.api.dto.JobResponse;
import ru.lct.heatroute.api.dto.VariantResponse;
import ru.lct.heatroute.service.CalculationService;
import ru.lct.heatroute.service.ResultExportService;

import javax.validation.Valid;
import java.util.List;
import java.util.UUID;

/** Запуск расчётов и выдача результата. */
@Slf4j
@RestController
@RequestMapping("/api/v1/jobs")
@Profile("!nodb")
@Tag(name = "Расчёты",
        description = "Построение вариантов подключения и выгрузка результата")
public class JobController {

    private final CalculationService calculations;
    private final ResultExportService export;

    public JobController(CalculationService calculations, ResultExportService export) {
        this.calculations = calculations;
        this.export = export;
    }

    @PostMapping
    @Operation(summary = "Запустить расчёт",
            description = "Возвращает идентификатор немедленно; расчёт идёт в фоне. "
                    + "Состояние и прогресс запрашиваются через GET по этому идентификатору.")
    @ApiResponses({
            @ApiResponse(responseCode = "202", description = "Расчёт поставлен в очередь"),
            @ApiResponse(responseCode = "404", description = "Набор не найден",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    public ResponseEntity<JobResponse> submit(@Valid @RequestBody CreateJobRequest request) {
        JobResponse job = calculations.submit(request);
        // Вызов через бин, а не напрямую: асинхронность в Spring работает через прокси.
        calculations.execute(job.getId(), request);
        return ResponseEntity.accepted().body(job);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Состояние расчёта и найденные варианты",
            description = "Пока расчёт идёт, в ответе состояние, прогресс и текущий этап. "
                    + "По завершении добавляются варианты с разбором стоимости.")
    public JobResponse get(@PathVariable UUID id) {
        return calculations.get(id);
    }

    @GetMapping
    @Operation(summary = "Список расчётов, новые первыми")
    public List<JobResponse> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return calculations.list(PageRequest.of(page, Math.min(size, 200)));
    }

    @GetMapping("/{id}/variants")
    @Operation(summary = "Варианты расчёта, лучший первым")
    public List<VariantResponse> variants(@PathVariable UUID id) {
        return calculations.get(id).getVariants();
    }

    @GetMapping(value = "/{id}/result.geojson", produces = "application/geo+json")
    @Operation(summary = "Выгрузка результата",
            description = "Один совмещённый файл GeoJSON в составе раздела 7.1 технического "
                    + "приложения — ровно четыре типа объектов: новые участки, новые "
                    + "тепловые камеры, технические узлы и сводная запись по каждому "
                    + "варианту. Объекты читаются из базы курсором и пишутся "
                    + "в ответ по одному, поэтому размер выгрузки не ограничен памятью.")
    public ResponseEntity<StreamingResponseBody> result(@PathVariable UUID id) {
        calculations.require(id);
        StreamingResponseBody body = out -> export.streamResult(id, out);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"heatroute_" + id + ".geojson\"")
                .body(body);
    }

    @GetMapping(value = "/{id}/statement.csv", produces = "text/csv; charset=UTF-8")
    @Operation(summary = "Ведомость объёмов работ",
            description = "Таблица для Excel: перечень новых участков с диаметром, длиной "
                    + "и стоимостью, новые тепловые камеры, врезки в существующие камеры, "
                    + "свод по условным диаметрам и итог. Выгрузка GeoJSON адресована ГИС, "
                    + "а смету считают "
                    + "по этому документу. Без параметра берётся вариант, занявший первое место.")
    public ResponseEntity<StreamingResponseBody> statement(
            @PathVariable UUID id,
            @RequestParam(required = false)
            @Parameter(description = "Код варианта, например v2; по умолчанию — лучший")
            String variantCode) {
        calculations.require(id);
        StreamingResponseBody body = out -> export.streamStatement(id, variantCode, out);
        String suffix = variantCode == null || variantCode.isEmpty() ? "best" : variantCode;
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"heatroute_" + id + "_" + suffix + ".csv\"")
                .body(body);
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Удалить расчёт вместе с его вариантами")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        calculations.delete(id);
        return ResponseEntity.noContent().build();
    }
}
