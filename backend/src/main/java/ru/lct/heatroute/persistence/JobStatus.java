package ru.lct.heatroute.persistence;

/** Состояние расчёта. */
public enum JobStatus {

    /** Поставлен в очередь, свободного потока пока нет. */
    QUEUED,

    /** Считается. */
    RUNNING,

    /** Завершён, варианты доступны. */
    COMPLETED,

    /** Прерван ошибкой; причина в поле error_message. */
    FAILED,

    /** Отменён пользователем. */
    CANCELLED
}
