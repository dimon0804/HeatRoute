package ru.lct.heatroute.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import ru.lct.heatroute.api.dto.ComplianceReportDto;
import ru.lct.heatroute.compliance.ComplianceReport;
import ru.lct.heatroute.service.ComplianceService;

import java.io.IOException;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Проверка выгрузки на соответствие техническому приложению.
 * <p>
 * Ручка нужна не только нам. Департамент принимает такие расчёты от подрядчиков,
 * и проверять их сейчас приходится глазами: открыть выгрузку, пересчитать стоимость,
 * сверить диаметры с таблицей, обойти дерево и убедиться, что предельная длина
 * не превышена ни на одном пути. Здесь это делается за одну загрузку файлов, причём
 * правила берутся из подменяемого снаружи справочника, а не из кода.
 */
@Slf4j
@RestController
@Profile("!nodb")
@RequestMapping("/api/v1")
@Tag(name = "Соответствие приложению",
        description = "Проверка выгрузки по правилам технического приложения")
public class ComplianceController {

    private final ComplianceService compliance;

    public ComplianceController(ComplianceService compliance) {
        this.compliance = compliance;
    }

    @GetMapping("/jobs/{id}/compliance")
    @Operation(summary = "Проверить результат своего расчёта",
            description = "Берёт сохранённую выгрузку расчёта и входной набор, по которому "
                    + "он выполнен, и сверяет их с правилами приложения. Отчёт содержит "
                    + "число выполненных сверок и перечень нарушений с указанием объектов. "
                    + "Число сверок стоит рядом с числом нарушений намеренно: «нарушений "
                    + "нет» без него ничего не значит, проверок могло быть ноль.")
    public ComplianceReportDto ofJob(@PathVariable UUID id) {
        return toDto(compliance.checkJob(id));
    }

    @PostMapping(value = "/compliance", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "Проверить произвольную выгрузку",
            description = "Принимает выходной GeoJSON и входной набор, по которому он "
                    + "получен, и проверяет выгрузку по правилам приложения. Выгрузка может "
                    + "быть получена любым сервисом, не обязательно этим: проверка идёт "
                    + "по файлу и ничего не знает о том, как он построен.")
    public ComplianceReportDto ofFiles(
            @Parameter(description = "Выходной GeoJSON с вариантами подключения")
            @RequestPart("result") MultipartFile result,
            @Parameter(description = "Входной набор, по которому получен результат")
            @RequestPart("dataset") MultipartFile dataset) throws IOException {
        return toDto(compliance.checkFiles(result, dataset));
    }

    private ComplianceReportDto toDto(ComplianceReport report) {
        return ComplianceReportDto.builder()
                .compliant(report.isCompliant())
                .checks(report.getChecks())
                .violations(report.getFindings().size())
                .variantIds(report.getVariantIds())
                .objectCounts(report.getObjectCounts())
                .skipped(report.getSkipped())
                .findings(report.getFindings().stream()
                        .map(f -> ComplianceReportDto.Finding.builder()
                                .rule(f.getRule().name())
                                .title(f.getRule().title())
                                .requirement(f.getRule().source())
                                .variantId(f.getVariantId())
                                .objectIds(f.getObjectIds())
                                .detail(f.getDetail())
                                .build())
                        .collect(Collectors.toList()))
                .build();
    }
}
