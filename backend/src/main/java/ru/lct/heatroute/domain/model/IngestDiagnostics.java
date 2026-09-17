package ru.lct.heatroute.domain.model;

import lombok.Getter;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Протокол разбора входного файла: что пришло, чего не хватило, что восстановлено
 * и какие допущения приняты.
 * <p>
 * Это не логирование «на всякий случай»: критерий «диагностика данных и запуск
 * на дополнительном наборе без изменения алгоритма» проверяется экспертами напрямую,
 * а на защите по этому протоколу объясняется, почему сервис принял именно такие
 * значения там, где входные данные молчали.
 */
@Getter
public class IngestDiagnostics {

    public enum Severity {
        /** Данные приняты как есть. */
        INFO,
        /** Значение восстановлено или принято по умолчанию; результат остаётся корректным. */
        ASSUMPTION,
        /** Данные противоречивы; результат может отличаться от ожидаемого. */
        WARNING,
        /** Расчёт по этим данным невозможен. */
        ERROR
    }

    /** Одна запись протокола. */
    @lombok.Value
    public static class Entry {
        Severity severity;
        /** Код для машинной обработки и тестов, например {@code upstream.inferred}. */
        String code;
        /** Человекочитаемое сообщение — попадает в API и на экран. */
        String message;
        /** ID объектов, которых касается запись; пустой список, если запись общая. */
        List<String> objectIds;
    }

    private final List<Entry> entries = new ArrayList<>();
    private final Map<String, Integer> counters = new LinkedHashMap<>();

    public void add(Severity severity, String code, String message, List<String> objectIds) {
        entries.add(new Entry(severity, code, message,
                objectIds == null ? List.of() : List.copyOf(objectIds)));
        counters.merge(code, 1, Integer::sum);
    }

    public void info(String code, String message) {
        add(Severity.INFO, code, message, null);
    }

    public void assumption(String code, String message, List<String> ids) {
        add(Severity.ASSUMPTION, code, message, ids);
    }

    public void warning(String code, String message, List<String> ids) {
        add(Severity.WARNING, code, message, ids);
    }

    public void error(String code, String message, List<String> ids) {
        add(Severity.ERROR, code, message, ids);
    }

    public boolean hasErrors() {
        return entries.stream().anyMatch(e -> e.getSeverity() == Severity.ERROR);
    }

    public long count(Severity severity) {
        return entries.stream().filter(e -> e.getSeverity() == severity).count();
    }
}
