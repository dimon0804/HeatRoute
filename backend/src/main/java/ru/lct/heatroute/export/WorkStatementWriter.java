package ru.lct.heatroute.export;

import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Ведомость объёмов работ по варианту — таблица, которую открывают в Excel.
 * <p>
 * Выгрузка GeoJSON адресована ГИС, а смету по ней никто считать не будет. Сметчику
 * нужно другое: перечень участков с диаметром, длиной и стоимостью, свод по условным
 * диаметрам и итог. Этот же документ становится приложением к проектному решению,
 * поэтому в шапке записано, по какому набору и какому варианту он построен.
 * <p>
 * Формат — CSV с точкой с запятой и BOM. Оба решения продиктованы Excel в русской
 * локали: по запятой он не делит строку на столбцы, а без BOM показывает кириллицу
 * иероглифами. Числа пишутся с запятой в качестве десятичного разделителя — иначе
 * Excel примет их за текст и не даст просуммировать.
 */
@Component
public class WorkStatementWriter {

    private static final char SEPARATOR = ';';
    private static final String BOM = "﻿";

    /**
     * Строка ведомости: объект результата с уже разобранными атрибутами.
     * Разбирать JSON здесь незачем — это делает вызывающий, он же знает источник.
     */
    public static final class Row {
        private final String objectType;
        private final String id;
        private final Map<String, Object> properties;

        public Row(String objectType, String id, Map<String, Object> properties) {
            this.objectType = objectType;
            this.id = id;
            this.properties = properties == null ? Map.of() : properties;
        }

        private double number(String key) {
            Object value = properties.get(key);
            return value instanceof Number ? ((Number) value).doubleValue() : 0;
        }

        private String text(String key) {
            Object value = properties.get(key);
            return value == null ? "" : String.valueOf(value);
        }
    }

    /** Шапка ведомости: что за расчёт и какой вариант. */
    public static final class Header {
        private final String datasetName;
        private final String variantCode;
        private final int rank;
        private final String description;
        private final double score;
        private final String generatedAt;

        public Header(String datasetName, String variantCode, int rank, String description,
                      double score, String generatedAt) {
            this.datasetName = datasetName;
            this.variantCode = variantCode;
            this.rank = rank;
            this.description = description;
            this.score = score;
            this.generatedAt = generatedAt;
        }
    }

    public void write(OutputStream out, Header header, List<Row> rows) throws IOException {
        Writer writer = new OutputStreamWriter(out, StandardCharsets.UTF_8);
        writer.write(BOM);

        writeHeader(writer, header);
        writeNewSegments(writer, rows);
        writeChambers(writer, rows);
        writeTieIns(writer, rows);
        writeReconstruction(writer, rows);
        writeDiameterSummary(writer, rows);
        writeTotals(writer, rows);

        writer.flush();
    }

    // =================================================================================

    private void writeHeader(Writer w, Header header) throws IOException {
        line(w, "ВЕДОМОСТЬ ОБЪЁМОВ РАБОТ");
        line(w, "Исходный набор", header.datasetName);
        line(w, "Вариант", header.variantCode + " (место " + header.rank + ")");
        line(w, "Описание", header.description);
        line(w, "Показатель ранжирования S", decimal(header.score, 3));
        line(w, "Документ построен", header.generatedAt);
        line(w, "");
    }

    private void writeNewSegments(Writer w, List<Row> rows) throws IOException {
        List<Row> segments = of(rows, "heat_network");
        if (segments.isEmpty()) {
            return;
        }
        line(w, "НОВОЕ СТРОИТЕЛЬСТВО");
        line(w, "№", "Участок", "От узла", "До узла", "Расход, т/ч", "ДУ, мм",
                "Длина, м", "Способ прокладки", "Глубина, м", "Стоимость, руб.");

        int index = 0;
        for (Row row : segments) {
            line(w,
                    String.valueOf(++index),
                    row.id,
                    row.text("start_node_id"),
                    row.text("end_node_id"),
                    decimal(row.number("flow_tph"), 2),
                    String.valueOf((long) row.number("diameter")),
                    decimal(row.number("length"), 1),
                    layingLabel(row.text("laying_method")),
                    depthLabel(row),
                    decimal(row.number("cost"), 0));
        }
        line(w, "");
    }

    private void writeChambers(Writer w, List<Row> rows) throws IOException {
        List<Row> chambers = of(rows, "heat_chamber");
        if (chambers.isEmpty()) {
            return;
        }
        line(w, "ТЕПЛОВЫЕ КАМЕРЫ");
        line(w, "№", "Камера", "ДУ, мм", "Примыканий", "Стоимость, руб.");

        int index = 0;
        for (Row row : chambers) {
            line(w,
                    String.valueOf(++index),
                    row.id,
                    String.valueOf((long) row.number("diameter")),
                    String.valueOf((long) row.number("degree")),
                    decimal(row.number("cost"), 0));
        }
        line(w, "");
    }

    private void writeTieIns(Writer w, List<Row> rows) throws IOException {
        List<Row> tieIns = of(rows, "tie_in");
        if (tieIns.isEmpty()) {
            return;
        }
        line(w, "ВРЕЗКИ В СУЩЕСТВУЮЩУЮ СЕТЬ");
        line(w, "№", "Врезка", "Объект врезки", "Тип объекта", "ДУ объекта, мм",
                "Требуемый ДУ, мм", "Доп. расход, т/ч", "Стоимость, руб.");

        int index = 0;
        for (Row row : tieIns) {
            line(w,
                    String.valueOf(++index),
                    row.id,
                    row.text("existing_object_id"),
                    objectLabel(row.text("existing_object_type")),
                    String.valueOf((long) row.number("existing_diameter")),
                    String.valueOf((long) row.number("required_diameter")),
                    decimal(row.number("added_flow_tph"), 2),
                    decimal(row.number("cost"), 0));
        }
        line(w, "");
    }

    private void writeReconstruction(Writer w, List<Row> rows) throws IOException {
        List<Row> segments = of(rows, "heat_network_reconstruction");
        List<Row> chambers = of(rows, "heat_chamber_reconstruction");
        if (segments.isEmpty() && chambers.isEmpty()) {
            return;
        }
        line(w, "РЕКОНСТРУКЦИЯ СУЩЕСТВУЮЩЕЙ СЕТИ");

        if (!segments.isEmpty()) {
            line(w, "№", "Участок", "Расход был, т/ч", "Расход стал, т/ч",
                    "ДУ был, мм", "ДУ стал, мм", "Длина, м", "Стоимость, руб.");
            int index = 0;
            for (Row row : segments) {
                line(w,
                        String.valueOf(++index),
                        row.id,
                        decimal(row.number("flow_before_tph"), 2),
                        decimal(row.number("flow_after_tph"), 2),
                        String.valueOf((long) row.number("diameter_before")),
                        String.valueOf((long) row.number("diameter_after")),
                        decimal(row.number("length"), 1),
                        decimal(row.number("cost"), 0));
            }
        }
        if (!chambers.isEmpty()) {
            line(w, "");
            line(w, "№", "Камера", "ДУ был, мм", "ДУ стал, мм", "Стоимость, руб.");
            int index = 0;
            for (Row row : chambers) {
                line(w,
                        String.valueOf(++index),
                        row.id,
                        String.valueOf((long) row.number("diameter_before")),
                        String.valueOf((long) row.number("diameter_after")),
                        decimal(row.number("cost"), 0));
            }
        }
        line(w, "");
    }

    /**
     * Свод по условным диаметрам — то, ради чего ведомость и открывают: объём труб
     * одного диаметра определяет закупку.
     */
    private void writeDiameterSummary(Writer w, List<Row> rows) throws IOException {
        Map<Long, double[]> byDiameter = new java.util.TreeMap<>();
        for (Row row : of(rows, "heat_network")) {
            double[] cell = byDiameter.computeIfAbsent((long) row.number("diameter"),
                    k -> new double[2]);
            cell[0] += row.number("length");
            cell[1] += row.number("cost");
        }
        if (byDiameter.isEmpty()) {
            return;
        }

        line(w, "СВОД ПО УСЛОВНЫМ ДИАМЕТРАМ (новое строительство)");
        line(w, "ДУ, мм", "Длина, м", "Стоимость, руб.");
        for (Map.Entry<Long, double[]> entry : byDiameter.entrySet()) {
            line(w, String.valueOf(entry.getKey()),
                    decimal(entry.getValue()[0], 1), decimal(entry.getValue()[1], 0));
        }
        line(w, "");
    }

    private void writeTotals(Writer w, List<Row> rows) throws IOException {
        double newLength = sum(rows, "heat_network", "length");
        double reconLength = sum(rows, "heat_network_reconstruction", "length");

        line(w, "ИТОГО");
        line(w, "Раздел", "Количество", "Длина, м", "Стоимость, руб.");
        total(w, "Новые участки сети", of(rows, "heat_network").size(), newLength,
                sum(rows, "heat_network", "cost"));
        total(w, "Тепловые камеры", of(rows, "heat_chamber").size(), 0,
                sum(rows, "heat_chamber", "cost"));
        total(w, "Врезки", of(rows, "tie_in").size(), 0, sum(rows, "tie_in", "cost"));
        total(w, "Реконструкция участков", of(rows, "heat_network_reconstruction").size(),
                reconLength, sum(rows, "heat_network_reconstruction", "cost"));
        total(w, "Реконструкция камер", of(rows, "heat_chamber_reconstruction").size(), 0,
                sum(rows, "heat_chamber_reconstruction", "cost"));

        double cost = sum(rows, "heat_network", "cost")
                + sum(rows, "heat_chamber", "cost")
                + sum(rows, "tie_in", "cost")
                + sum(rows, "heat_network_reconstruction", "cost")
                + sum(rows, "heat_chamber_reconstruction", "cost");
        line(w, "ВСЕГО", "", decimal(newLength + reconLength, 1), decimal(cost, 0));
    }

    private void total(Writer w, String label, int count, double length, double cost)
            throws IOException {
        line(w, label, String.valueOf(count),
                length > 0 ? decimal(length, 1) : "", decimal(cost, 0));
    }

    // =================================================================================

    private static List<Row> of(List<Row> rows, String objectType) {
        List<Row> out = new ArrayList<>();
        for (Row row : rows) {
            if (objectType.equals(row.objectType)) {
                out.add(row);
            }
        }
        return out;
    }

    private static double sum(List<Row> rows, String objectType, String key) {
        double sum = 0;
        for (Row row : of(rows, objectType)) {
            sum += row.number(key);
        }
        return sum;
    }

    private static String layingLabel(String method) {
        if (method == null || method.isEmpty()) {
            return "";
        }
        // Коды те же, что в выгрузке (LayingMethod): ведомость читает человек,
        // и «base» ему ничего не говорит.
        Map<String, String> labels = new LinkedHashMap<>();
        labels.put("base", "подземная бесканальная");
        labels.put("special", "специальный проход");
        return labels.getOrDefault(method, method);
    }

    private static String objectLabel(String type) {
        if ("heat_chamber".equals(type)) {
            return "тепловая камера";
        }
        if ("heat_network".equals(type)) {
            return "участок сети";
        }
        return type == null ? "" : type;
    }

    /** Глубина участка: одно число, если она постоянна, иначе диапазон. */
    private static String depthLabel(Row row) {
        Object start = row.properties.get("depth_start");
        Object end = row.properties.get("depth_end");
        if (!(start instanceof Number) || !(end instanceof Number)) {
            return "";
        }
        double from = ((Number) start).doubleValue();
        double to = ((Number) end).doubleValue();
        return Math.abs(from - to) < 1e-6
                ? decimal(from, 1)
                : decimal(from, 1) + "…" + decimal(to, 1);
    }

    /**
     * Число для Excel в русской локали: запятая вместо точки и никаких разделителей
     * разрядов. Разделитель разрядов здесь не украшение, а способ сломать разбор:
     * пробел превращает число в текст, а запятая — ещё и в два столбца.
     * <p>
     * Локаль задана явно: иначе формат зависел бы от настроек машины, где запущен
     * сервис, и ведомость на сервере выглядела бы иначе, чем на машине разработчика.
     */
    private static String decimal(double value, int digits) {
        return String.format(Locale.ROOT, "%." + digits + "f", value).replace('.', ',');
    }

    private static void line(Writer w, String... cells) throws IOException {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < cells.length; i++) {
            if (i > 0) {
                sb.append(SEPARATOR);
            }
            sb.append(escape(cells[i]));
        }
        sb.append("\r\n");
        w.write(sb.toString());
    }

    /** Кавычки по правилам CSV: только там, где без них строка развалится. */
    private static String escape(String value) {
        if (value == null) {
            return "";
        }
        boolean needsQuotes = value.indexOf(SEPARATOR) >= 0
                || value.indexOf('"') >= 0
                || value.indexOf('\n') >= 0;
        if (!needsQuotes) {
            return value;
        }
        return '"' + value.replace("\"", "\"\"") + '"';
    }
}
