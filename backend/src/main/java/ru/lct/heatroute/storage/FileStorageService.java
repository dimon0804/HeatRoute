package ru.lct.heatroute.storage;

import lombok.Value;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * Временное дисковое хранилище входных файлов и выгрузок.
 * <p>
 * Раздел 3.2 ТЗ требует принимать файл до 3 ГБ и отдавать выгрузку до 500 МБ, обрабатывая
 * их потоково и не загружая целиком в оперативную память. Отсюда устройство: приходящий
 * поток сразу переписывается на диск буфером, дальше с файлом работают только потоково,
 * а в базе остаётся лишь путь.
 */
@Slf4j
@Service
public class FileStorageService {

    /** Допустимое расширение сохраняемого файла; всё остальное заменяется на .geojson. */
    private static final java.util.regex.Pattern EXTENSION =
            java.util.regex.Pattern.compile("\\.[a-z0-9]{1,10}");

    private final StorageProperties props;
    private Path root;

    public FileStorageService(StorageProperties props) {
        this.props = props;
    }

    @PostConstruct
    void init() throws IOException {
        root = Paths.get(props.getRoot()).toAbsolutePath().normalize();
        Files.createDirectories(root);
        log.info("Дисковое хранилище: {} (срок хранения {} ч)", root, props.getRetentionHours());
    }

    /** Результат сохранения: относительный путь внутри хранилища и размер. */
    @Value
    public static class StoredFile {
        String relativePath;
        long sizeBytes;
    }

    /**
     * Переписывает поток в новый файл хранилища. Имя генерируется, исходное имя
     * в путь не попадает: во входящем имени может оказаться что угодно, вплоть
     * до попытки выйти за пределы каталога.
     */
    public StoredFile store(InputStream in, String suffix) throws IOException {
        String name = UUID.randomUUID() + normalizeSuffix(suffix);
        Path target = root.resolve(name);
        long size;
        try (OutputStream out = Files.newOutputStream(target)) {
            size = copy(in, out);
        }
        log.debug("Сохранён файл {} ({} байт)", name, size);
        return new StoredFile(name, size);
    }

    public Path resolve(String relativePath) {
        Path candidate = root.resolve(relativePath).normalize();
        if (!candidate.startsWith(root)) {
            throw new IllegalArgumentException("Путь выходит за пределы хранилища: " + relativePath);
        }
        return candidate;
    }

    public InputStream open(String relativePath) throws IOException {
        return Files.newInputStream(resolve(relativePath));
    }

    public boolean exists(String relativePath) {
        return Files.isRegularFile(resolve(relativePath));
    }

    public void delete(String relativePath) {
        try {
            Files.deleteIfExists(resolve(relativePath));
        } catch (IOException e) {
            log.warn("Не удалось удалить файл {}: {}", relativePath, e.getMessage());
        }
    }

    /** Удаление файлов старше срока хранения. Возвращает число удалённых. */
    public int purgeExpired() {
        Instant threshold = Instant.now().minus(props.getRetentionHours(), ChronoUnit.HOURS);
        int removed = 0;
        try (Stream<Path> files = Files.list(root)) {
            for (Path file : (Iterable<Path>) files::iterator) {
                if (Files.isRegularFile(file)
                        && Files.getLastModifiedTime(file).toInstant().isBefore(threshold)) {
                    Files.deleteIfExists(file);
                    removed++;
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        if (removed > 0) {
            log.info("Удалено устаревших файлов хранилища: {}", removed);
        }
        return removed;
    }

    private long copy(InputStream in, OutputStream out) throws IOException {
        byte[] buffer = new byte[props.getBufferSize()];
        long total = 0;
        int read;
        while ((read = in.read(buffer)) > 0) {
            out.write(buffer, 0, read);
            total += read;
        }
        return total;
    }

    private String normalizeSuffix(String original) {
        if (original == null) {
            return ".geojson";
        }
        int dot = original.lastIndexOf('.');
        if (dot < 0 || dot == original.length() - 1) {
            return ".geojson";
        }
        String ext = original.substring(dot).toLowerCase(java.util.Locale.ROOT);
        return EXTENSION.matcher(ext).matches() ? ext : ".geojson";
    }
}
