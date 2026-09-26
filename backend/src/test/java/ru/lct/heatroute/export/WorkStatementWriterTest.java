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
 * <p>
 * Разделы врезок и реконструкции из ведомости ушли вместе с объектами выгрузки.
 * Врезки в существующие камеры остались только в итогах, и количество со стоимостью
 * ведомость берёт из сводной записи варианта: отдельных объектов, по которым их можно
 * было бы пересчитать, больше нет.
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
        return statement(rows, Map.of());
    }

    private String statement(List<WorkStatementWriter.Row> rows,
                             Map<String, Object> summary) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writer.write(out, new WorkStatementWriter.Header(
                "dataset.geojson", "v2", 1, "сеть разделена на 3 части", 13.614,
                "18.09.2026 12:00", summary), rows);
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
    @DisplayName("Итог сходится с суммой строк и врезками из сводки")
    void totalsMatchRows() throws Exception {
        String text = statement(List.of(
                        segment("s1", 200, 100.0, 12_000_000, "base"),
                        segment("s2", 125, 50.0, 5_000_000, "special"),
                        new WorkStatementWriter.Row("heat_chamber", "ch1",
                                Map.of("diameter", 200, "degree", 3, "cost", 3_000_000.0))),
                // Одна врезка в существующую камеру: объекта под неё в выгрузке нет,
                // количество и стоимость приходят сводной записью варианта.
                Map.of("existing_chamber_tie_in_count", 1,
                        "existing_chamber_tie_in_cost", 5_000_000.0));

        assertThat(text).contains("Новые участки сети;2;150,0;17000000");
        assertThat(text).contains("Новые тепловые камеры;1;;3000000");
        assertThat(text).contains("Врезки в существующие камеры;1;;5000000");
        assertThat(text)
                .as("стоимость строительства — участки плюс камеры плюс врезки")
                .contains("Стоимость строительства;;150,0;25000000");
        assertThat(text).contains("ВСЕГО;;150,0;25000000");
    }

    @Test
    @DisplayName("Разделов врезок и реконструкции в ведомости нет")
    void noTieInAndReconstructionSections() throws Exception {
        String text = statement(List.of(
                        segment("s1", 200, 100.0, 12_000_000, "base"),
                        new WorkStatementWriter.Row("heat_chamber", "ch1",
                                Map.of("diameter", 200, "degree", 3, "cost", 3_000_000.0))),
                Map.of("existing_chamber_tie_in_count", 2,
                        "existing_chamber_tie_in_cost", 10_000_000.0));

        assertThat(text)
                .as("отдельного объекта врезки больше нет, перечислять нечего")
                .doesNotContain("ВРЕЗКИ В СУЩЕСТВУЮЩУЮ СЕТЬ");
        assertThat(text)
                .as("реконструкция выведена из расчётной модели")
                .doesNotContain("РЕКОНСТРУКЦИЯ");
        assertThat(text)
                .as("в итогах врезки остались строкой по данным сводки")
                .contains("Врезки в существующие камеры;2;;10000000");
    }

    @Test
    @DisplayName("Штраф за неподключённые точки виден отдельной строкой и входит в итог")
    void penaltyIsShownSeparately() throws Exception {
        String text = statement(List.of(segment("s1", 200, 100.0, 12_000_000, "base")),
                Map.of("unconnected_penalty", 104_000_000.0));

        assertThat(text).contains("Штраф за неподключённые точки;0;;104000000");
        assertThat(text).contains("Стоимость строительства;;100,0;12000000");
        assertThat(text).contains("ВСЕГО;;100,0;116000000");
    }

    @Test
    @DisplayName("Без штрафа строки штрафа в ведомости нет")
    void noPenaltyRowWhenNothingUnconnected() throws Exception {
        String text = statement(List.of(segment("s1", 200, 100.0, 12_000_000, "base")));

        assertThat(text).doesNotContain("Штраф за неподключённые точки");
        assertThat(text).contains("ВСЕГО;;100,0;12000000");
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
    @DisplayName("Пересечения по глубине попадают в ведомость с отметкой о норме")
    void depthCrossingsAreListed() throws Exception {
        // В выходной файл пересечения по глубине не идут: приложение их не предусматривает.
        // В ведомость идут — она адресована человеку, и просвет над существующей
        // коммуникацией он проверяет глазами.
        String text = statement(List.of(
                segment("s1", 200, 100.0, 12_000_000, "base"),
                new WorkStatementWriter.Row("depth_crossing", "dc1", Map.of(
                        "segment_id", "s1", "station", 42.0,
                        "utility_id", "121", "utility_type", "heat_network",
                        "passage", "above", "new_depth", 2.0, "utility_depth", 3.0,
                        "actual_clearance", 0.55, "required_clearance", 0.5)),
                new WorkStatementWriter.Row("depth_crossing", "dc2", Map.of(
                        "segment_id", "s1", "station", 88.0,
                        "utility_id", "122", "utility_type", "heat_network",
                        "passage", "above", "new_depth", 3.0, "utility_depth", 3.0,
                        "actual_clearance", -0.18, "required_clearance", 0.5))));

        assertThat(text).contains("ПЕРЕСЕЧЕНИЯ ПО ГЛУБИНЕ");
        assertThat(text).contains("сверху");
        assertThat(text)
                .as("выдержанный просвет отмечен как выдержанный")
                .contains("0,55;0,50;да");
        assertThat(text)
                .as("невыдержанный виден сразу, а не вычисляется читателем")
                .contains("-0,18;0,50;НЕТ");
    }

    @Test
    @DisplayName("В плоском расчёте раздела по глубине нет")
    void noDepthSectionWithoutCrossings() throws Exception {
        String text = statement(List.of(segment("s1", 200, 100.0, 12_000_000, "base")));

        assertThat(text).doesNotContain("ПЕРЕСЕЧЕНИЯ ПО ГЛУБИНЕ");
    }

    @Test
    @DisplayName("Точка с запятой внутри описания не ломает столбцы")
    void separatorInsideTextIsQuoted() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writer.write(out, new WorkStatementWriter.Header(
                        "dataset.geojson", "v2", 1,
                        "сеть разделена на 3 части; врезка в камеру 108", 13.614,
                        "18.09.2026 12:00", Map.of()),
                List.of(segment("s1", 200, 100.0, 12_000_000, "base")));
        String text = new String(out.toByteArray(), StandardCharsets.UTF_8);

        assertThat(text).contains("\"сеть разделена на 3 части; врезка в камеру 108\"");
    }
}
