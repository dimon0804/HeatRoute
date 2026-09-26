package ru.lct.heatroute.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.lct.heatroute.domain.reference.ReferenceCatalog;
import ru.lct.heatroute.domain.reference.ReferenceProperties;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Справочник технического приложения в том виде, в каком его применяет расчёт.
 * <p>
 * Ручка нужна не интерфейсу: она доказывает, что таблицы 1 и 2 вместе с разделом 3.2 —
 * это данные, а не константы в коде. Эксперт может открыть её и сверить значения
 * с приложением построчно, а при подмене справочника снаружи увидеть новые значения
 * без пересборки сервиса.
 */
@RestController
@RequestMapping("/api/v1/reference")
@Tag(name = "Справочник",
        description = "Значения технического приложения, применяемые расчётом")
public class ReferenceController {

    private final ReferenceCatalog catalog;

    public ReferenceController(ReferenceCatalog catalog) {
        this.catalog = catalog;
    }

    @GetMapping
    @Operation(summary = "Справочник целиком",
            description = "Условные диаметры с пропускной способностью, предельной длиной, "
                    + "стоимостью и габаритами; шкала стоимости камер; правила по типам "
                    + "пространственных ограничений; параметры стоимости и ранжирования.")
    public Map<String, Object> reference() {
        ReferenceProperties props = catalog.props();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("diameters", props.getDiameters());
        out.put("chamberCostScale", props.getChamberCostScale());
        out.put("tieInCost", props.getTieInCost());
        out.put("unconnectedPenaltyFixed", props.getUnconnectedPenaltyFixed());
        out.put("unconnectedPenaltyPerTph", props.getUnconnectedPenaltyPerTph());
        out.put("scoring", props.getScoring());
        out.put("chamberSnapRadius", props.getChamberSnapRadius());
        out.put("maxChamberDegree", props.getMaxChamberDegree());
        out.put("depth", props.getDepth());
        out.put("existingUtilities", props.getExistingUtilities());
        out.put("restrictions", props.getRestrictions());
        out.put("restrictionAliases", props.getRestrictionAliases());
        out.put("unknownRestriction", props.getUnknownRestriction());
        return out;
    }

    @GetMapping("/diameters")
    @Operation(summary = "Ряд условных диаметров (таблица 1)")
    public Object diameters() {
        return catalog.diameters();
    }

    @GetMapping("/restrictions")
    @Operation(summary = "Правила по типам пространственных ограничений (таблица 2)")
    public Map<String, Object> restrictions() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("rules", catalog.props().getRestrictions());
        out.put("aliases", catalog.props().getRestrictionAliases());
        out.put("unknownDefault", catalog.props().getUnknownRestriction());
        out.put("seenUnknownTypes", catalog.unknownTypesSeen());
        return out;
    }
}
