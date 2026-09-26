package ru.lct.heatroute.compliance;

import lombok.Value;

import java.util.List;
import java.util.Map;

/**
 * Итог проверки выгрузки на соответствие техническому приложению.
 * <p>
 * Число выполненных проверок в отчёте стоит рядом с числом нарушений намеренно:
 * «нарушений нет» без второй цифры ничего не значит — проверок могло быть ноль.
 */
@Value
public class ComplianceReport {

    /** Сколько отдельных сверок выполнено. */
    int checks;

    /** Найденные нарушения; пусто — выгрузка правилам соответствует. */
    List<ComplianceFinding> findings;

    /** Сколько объектов каждого типа найдено в выгрузке. */
    Map<String, Long> objectCounts;

    /** Идентификаторы вариантов, найденных в выгрузке. */
    List<String> variantIds;

    /**
     * Правила, которые проверить не удалось, и почему. Пустой список честнее молчания:
     * без входного набора часть правил проверить нечем, и «нарушений нет» в этом случае
     * означает меньше, чем кажется.
     */
    List<String> skipped;

    public boolean isCompliant() {
        return findings.isEmpty();
    }
}
