package ru.lct.heatroute.api;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import ru.lct.heatroute.ingest.GeoJsonFormatException;
import ru.lct.heatroute.service.DatasetService;

import javax.servlet.http.HttpServletRequest;
import java.io.UncheckedIOException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Единый формат ошибок.
 * <p>
 * Сообщения — на русском и по существу: сервис показывают экспертам, и ответ вида
 * «500 Internal Server Error» вместо «в файле нет поля features» стоит дороже,
 * чем время на этот класс.
 */
@Slf4j
@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(DatasetService.NotFoundException.class)
    public ResponseEntity<ApiError> notFound(DatasetService.NotFoundException e,
                                             HttpServletRequest request) {
        return build(HttpStatus.NOT_FOUND, e.getMessage(), request, null);
    }

    @ExceptionHandler(GeoJsonFormatException.class)
    public ResponseEntity<ApiError> badGeoJson(GeoJsonFormatException e,
                                               HttpServletRequest request) {
        return build(HttpStatus.BAD_REQUEST,
                "Файл не является корректным GeoJSON: " + e.getMessage(), request, null);
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiError> tooLarge(MaxUploadSizeExceededException e,
                                             HttpServletRequest request) {
        return build(HttpStatus.PAYLOAD_TOO_LARGE,
                "Файл превышает допустимый размер загрузки", request, null);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> invalid(MethodArgumentNotValidException e,
                                            HttpServletRequest request) {
        List<String> details = e.getBindingResult().getFieldErrors().stream()
                .map(f -> f.getField() + ": " + f.getDefaultMessage())
                .collect(Collectors.toList());
        return build(HttpStatus.BAD_REQUEST, "Некорректный запрос", request, details);
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ApiError> conflict(IllegalStateException e,
                                             HttpServletRequest request) {
        return build(HttpStatus.CONFLICT, e.getMessage(), request, null);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiError> badRequest(IllegalArgumentException e,
                                               HttpServletRequest request) {
        return build(HttpStatus.BAD_REQUEST, e.getMessage(), request, null);
    }

    @ExceptionHandler(UncheckedIOException.class)
    public ResponseEntity<ApiError> io(UncheckedIOException e, HttpServletRequest request) {
        log.error("Ошибка ввода-вывода на {}", request.getRequestURI(), e);
        return build(HttpStatus.INTERNAL_SERVER_ERROR,
                "Ошибка чтения или записи файла: " + e.getMessage(), request, null);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> unexpected(Exception e, HttpServletRequest request) {
        log.error("Необработанная ошибка на {}", request.getRequestURI(), e);
        return build(HttpStatus.INTERNAL_SERVER_ERROR,
                "Внутренняя ошибка сервиса: " + e.getMessage(), request, null);
    }

    private ResponseEntity<ApiError> build(HttpStatus status, String message,
                                           HttpServletRequest request, List<String> details) {
        return ResponseEntity.status(status).body(ApiError.builder()
                .status(status.value())
                .error(status.name())
                .message(message)
                .path(request.getRequestURI())
                .timestamp(OffsetDateTime.now())
                .details(details)
                .build());
    }
}
