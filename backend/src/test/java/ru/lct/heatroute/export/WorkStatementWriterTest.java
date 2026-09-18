package ru.lct.heatroute.export;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Ведомость объёмов работ.
 * <p>
 * Проверяется то, из-за чего документ вообще может оказаться бесполезным: Excel в русской
 * локали должен разобрать его на столбцы и увидеть в числах числа, а суммы в итогах
 * должны сходиться со строками выше. Ведомость, где итог не равен сумме, хуже,
 * чем её отсутствие.
 */
class WorkStatementWriterTest {

    private final WorkStatementWriter writer = new WorkStatementWriter();

    private static WorkStatementWriter.Row segment(String id, int dn, double length,
                                                   double cost, String laying) {
        return new WorkStatementWriter.Row("heat_network", id, Map.of(
                "start_node_id", "n1",
                "end_node_id", "n2",
                "flow_tph", 42.5,
                "diameter", dn,
                "length", length,
                "laying_method", laying,
                "cost", cost));
    }

    private String statement(List<WorkStatementWriter.Row> rows) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writer.write(out, new WorkStatementWriter.Header(
                "dataset.geojson", "v2", 1, "сеть разделена на 3 части", 13.614,
                "18.09.2026 12:00"), rows);
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("Файл начинается с BOM и разделён точкой с запятой")
    void excelReadableFormat() throws Exception {
        String text = statement(List.of(segment("s1", 200, 100.0, 12_000_000, "base")));

        assertThat(text)
                .as("без BOM Excel показывает кириллицу иероглифами")
                .startsWith("﻿");
        assertThat(text)
                .as("разделитель — точка с запятой, иначе Excel не делит на столбцы")
                .contains("Исходный набор;dataset.geojson");
        assertThat(text.lines().anyMatch(l -> l.contains(",")))
                .as("десятичный разделитель — запятая")
                .isTrue();
    }

    @Test
    @DisplayName("В числах нет разделителей разрядов")
    void numbersHaveNoGrouping() throws Exception {
        String text = statement(List.of(segment("s1", 200, 1234.5, 123_456_789, "base")));

        assertThat(text).contains("123456789");
        assertThat(text)
                .as("пробел в числе превращает его для Excel в текст")
                .doesNotContain("123 456 789");
        assertThat(text).contains("1234,5");
    }

    @Test
    @DisplayName("Итог сходится с суммой строк")
    void totalsMatchRows() throws Exception {
        String text = statement(List.of(
                segment("s1", 200, 100.0, 12_000_000, "base"),
                segment("s2", 125, 50.0, 5_000_000, "special"),
                new WorkStatementWriter.Row("heat_chamber", "ch1",
                        Map.of("diameter", 200, "degree", 3, "cost", 3_000_000.0)),
                new WorkStatementWriter.Row("tie_in", "t1",
                        Map.of("existing_object_id", "121", "existing_object_type", "heat_network",
                                "existing_diameter", 300, "required_diameter", 400,
                                "added_flow_tph", 42.5, "cost", 5_000_000.0))));

        assertThat(text).contains("Новые участки сети;2;150,0;17000000");
        assertThat(text).contains("Тепловые камеры;1;;3000000");
        assertThat(text).contains("Врезки;1;;5000000");
        assertThat(text).contains("ВСЕГО;;150,0;25000000");
    }

    @Test
    @DisplayName("Свод по диаметрам складывает участки одного ДУ")
    void diameterSummaryGroups() throws Exception {
        String text = statement(List.of(
                segment("s1", 200, 100.0, 12_000_000, "base"),
                segment("s2", 200, 40.0, 4_800_000, "base"),
                segment("s3", 125, 50.0, 5_000_000, "base")));

        assertThat(text).contains("125;50,0;5000000");
        assertThat(text).contains("200;140,0;16800000");
    }

    @Test
    @DisplayName("Способ прокладки и глубина написаны по-русски")
    void humanReadableLabels() throws Exception {
        String text = statement(List.of(
                new WorkStatementWriter.Row("heat_network", "s1", Map.of(
                        "diameter", 200, "length", 10.0, "cost", 1_000_000.0,
                        "laying_method", "special",
                        "depth_start", 3.0, "depth_end", 2.0))));

        assertThat(text).contains("специальный проход");
        assertThat(text)
                .as("глубина по участку меняется — показывается диапазоном")
                .contains("3,0…2,0");
        assertThat(text).doesNotContain(";special;");
    }

    @Test
    @DisplayName("Точка с запятой внутри описания не ломает столбцы")
    void separatorInsideTextIsQuoted() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writer.write(out, new WorkStatementWriter.Header(
                        "dataset.geojson", "v2", 1,
                        "сеть разделена на 3 части; врезка в камеру 108", 13.614,
                        "18.09.2026 12:00"),
                List.of(segment("s1", 200, 100.0, 12_000_000, "base")));
        String text = new String(out.toByteArray(), StandardCharsets.UTF_8);

        assertThat(text).contains("\"сеть разделена на 3 части; врезка в камеру 108\"");
    }
}
