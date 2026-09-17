package ru.lct.heatroute.config;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * Пул фоновых расчётов.
 * <p>
 * ТЗ обещает поддержку пятидесяти пользователей на машине с 16 ГБ ОЗУ. Пятьдесят
 * одновременных расчётов такая машина не выдержит, и это не нужно: расчёт занимает
 * секунды, а не часы. Поэтому одновременных расчётов немного, остальные ждут в очереди,
 * а клиент видит состояние {@code QUEUED} — честно и предсказуемо.
 * <p>
 * Очередь ограничена: при переполнении задача выполняется в вызывающем потоке, то есть
 * нагрузка возвращается источнику, а не копится в памяти до отказа.
 */
@Slf4j
@Configuration
public class AsyncConfig {

    @Data
    @ConfigurationProperties(prefix = "heatroute.async")
    public static class AsyncProperties {
        /** Сколько расчётов идёт одновременно. */
        private int corePoolSize = 2;
        private int maxPoolSize = 4;
        /** Сколько расчётов может ждать в очереди. */
        private int queueCapacity = 50;
        private int keepAliveSeconds = 120;
    }

    @Bean("calculationExecutor")
    public Executor calculationExecutor(AsyncProperties props) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(props.getCorePoolSize());
        executor.setMaxPoolSize(props.getMaxPoolSize());
        executor.setQueueCapacity(props.getQueueCapacity());
        executor.setKeepAliveSeconds(props.getKeepAliveSeconds());
        executor.setThreadNamePrefix("calc-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(60);
        executor.initialize();
        log.info("Пул расчётов: {}..{} потоков, очередь {}",
                props.getCorePoolSize(), props.getMaxPoolSize(), props.getQueueCapacity());
        return executor;
    }
}
