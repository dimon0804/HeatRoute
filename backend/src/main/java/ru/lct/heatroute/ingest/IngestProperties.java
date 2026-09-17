package ru.lct.heatroute.ingest;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Поведение сервиса там, где входные данные молчат.
 * <p>
 * Каждое допущение вынесено в параметр и попадает в диагностику: эксперт должен видеть
 * не только результат, но и на каком основании он получен, а проверочный набор может
 * потребовать другого решения без пересборки.
 */
@Data
@ConfigurationProperties(prefix = "heatroute.ingest")
public class IngestProperties {

    /** Что делать, если у существующего участка нет атрибута {@code flow_tph}. */
    private ExistingFlowMode existingFlowMode = ExistingFlowMode.ZERO;

    /**
     * Доля пропускной способности, принимаемая за существующий расход
     * в режиме {@link ExistingFlowMode#CAPACITY_FRACTION}.
     */
    private double existingFlowCapacityFraction = 0.5;

    /**
     * Принимать ли расход перспективного ОКС с точки подключения, если полигона
     * {@code oks_future} в наборе нет. Конкурсный набор 2026 года устроен именно так.
     */
    private boolean allowFlowOnConnectionPoint = true;

    /**
     * Достраивать ли точку подключения для {@code oks_future}, у которого её нет:
     * берётся ближайшая к существующей сети точка контура ОКС.
     */
    private boolean deriveMissingConnectionPoint = true;

    /** Прерывать разбор при первой ошибке или собирать полный протокол. */
    private boolean failFast = false;

    public enum ExistingFlowMode {
        /**
         * Расход считается нулевым. Нейтральное допущение: сервис не приписывает
         * существующей сети нагрузки, которой в данных нет, и не завышает объём
         * реконструкции.
         */
        ZERO,
        /**
         * Расход принимается как доля пропускной способности существующего ДУ.
         * Консервативный режим для наборов, где загрузку сети нужно учесть,
         * а фактических значений нет.
         */
        CAPACITY_FRACTION
    }
}
