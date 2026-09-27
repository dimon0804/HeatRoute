package ru.lct.heatroute.ingest;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.context.ActiveProfiles;
import ru.lct.heatroute.api.ApiError;
import ru.lct.heatroute.api.ApiExceptionHandler;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Присланный файл не является JSON — это ошибка запроса, а не сбой сервиса.
 * <p>
 * Разбор — единственная точка, через которую файл попадает и в загрузку набора,
 * и в проверку чужой выгрузки. Поэтому о синтаксической ошибке он обязан сообщать
 * тем же исключением, что и о структурной: иначе каждый вход должен ловить Jackson
 * сам, и забытый вход отвечает пятисоткой на файл, в котором виноват отправитель.
 */
@SpringBootTest
@ActiveProfiles("nodb")
class MalformedGeoJsonTest {

    private static final String NOT_JSON = "not a geojson\n";

    @Autowired
    GeoJsonStreamParser parser;

    private void parse(String content) throws Exception {
        parser.parse(new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8)),
                feature -> { });
    }

    @Test
    @DisplayName("Синтаксическая ошибка в файле объявляется ошибкой формата GeoJSON")
    void syntaxErrorIsReportedAsFormatError() {
        assertThatThrownBy(() -> parse(NOT_JSON))
                .isInstanceOf(GeoJsonFormatException.class);
    }

    @Test
    @DisplayName("Сообщение об ошибке формата называет место в файле")
    void formatErrorNamesThePlaceInFile() {
        assertThatThrownBy(() -> parse("{\"type\":\"FeatureCollection\",\"features\":["))
                .isInstanceOf(GeoJsonFormatException.class)
                .hasMessageContaining("строка")
                .hasMessageContaining("позиция");
    }

    @Test
    @DisplayName("Обрыв в середине объекта тоже ошибка формата, а не потеря объектов молча")
    void truncatedFeatureIsReportedNotSwallowed() {
        assertThatThrownBy(() -> parse(
                "{\"type\":\"FeatureCollection\",\"features\":[{\"type\":\"Feature\","
                        + "\"properties\":{\"id\":1,\"object_type\":\"source\"}"))
                .isInstanceOf(GeoJsonFormatException.class);
    }

    @Test
    @DisplayName("Разбор файла с диска даёт ту же ошибку формата, что и разбор потока")
    void fileAndStreamFailTheSameWay(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("broken.geojson");
        Files.write(file, NOT_JSON.getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> parser.parse(file, feature -> { }))
                .as("загрузка набора читает сохранённый файл, проверка выгрузки — поток; "
                        + "оба входа обязаны отвечать одинаково")
                .isInstanceOf(GeoJsonFormatException.class);
    }

    @Test
    @DisplayName("Ошибка формата уходит клиенту как 400 с внятным сообщением")
    void formatErrorAnswersBadRequest() {
        GeoJsonFormatException raised = catchFormatError();
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRequestURI("/api/v1/compliance");

        ResponseEntity<ApiError> response =
                new ApiExceptionHandler().badGeoJson(raised, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getMessage())
                .as("проверяющий должен понять, что виноват его файл")
                .contains("не является корректным GeoJSON")
                .contains("JSON");
    }

    private GeoJsonFormatException catchFormatError() {
        try {
            parse(NOT_JSON);
        } catch (GeoJsonFormatException e) {
            return e;
        } catch (Exception e) {
            throw new AssertionError("ожидалась ошибка формата, получено " + e, e);
        }
        throw new AssertionError("битый файл разобрался без ошибки");
    }
}
