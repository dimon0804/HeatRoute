package ru.lct.heatroute.storage;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Параметры временного дискового хранилища. */
@Data
@ConfigurationProperties(prefix = "heatroute.storage")
public class StorageProperties {

    /**
     * Корень хранилища. Требование ТЗ — входной файл до 3 ГБ и выгрузка до 500 МБ,
     * поэтому файлы живут на диске, а не в памяти и не в базе.
     */
    private String root = System.getProperty("java.io.tmpdir") + "/heatroute/storage";

    /** Через сколько часов загруженный файл считается устаревшим и удаляется. */
    private int retentionHours = 72;

    /** Размер буфера при потоковой записи и чтении, байт. */
    private int bufferSize = 1 << 20;
}
