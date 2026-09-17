package ru.lct.heatroute.config;

import lombok.extern.slf4j.Slf4j;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.orm.jpa.EntityManagerFactoryDependsOnPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import javax.sql.DataSource;

/**
 * Миграции схемы под управлением приложения, а не автоконфигурации.
 * <p>
 * ТЗ фиксирует Spring Boot 2.6.3 (раздел 3.2), а его автоконфигурация Flyway рассчитана
 * на Flyway 8.x и не запускается с 9.x. При этом Flyway 8.x не знает PostgreSQL новее
 * четырнадцатой версии, а ТЗ разрешает СУБД вплоть до восемнадцатой. Поэтому выбран
 * третий путь: версия Spring Boot остаётся требуемой, Flyway берётся девятый, а его
 * автоконфигурация отключается и заменяется этими двадцатью строками.
 * <p>
 * Порядок важен: Hibernate проверяет схему при старте, поэтому фабрика сущностей
 * обязана создаваться после миграции. Это задаёт {@link EntityManagerFactoryDependsOnPostProcessor}.
 */
@Slf4j
@Configuration(proxyBeanMethods = false)
@Profile("!nodb")
public class FlywayConfig {

    @Bean(initMethod = "migrate")
    public Flyway flyway(DataSource dataSource) {
        Flyway flyway = Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .baselineOnMigrate(true)
                .baselineVersion("0")
                .validateOnMigrate(true)
                .load();
        log.info("Flyway настроен вручную: автоконфигурация Spring Boot 2.6.3 "
                + "несовместима с Flyway 9");
        return flyway;
    }

    /** Миграция обязана пройти до того, как Hibernate начнёт проверять схему. */
    @Configuration(proxyBeanMethods = false)
    static class FlywayBeforeJpa extends EntityManagerFactoryDependsOnPostProcessor {
        FlywayBeforeJpa() {
            super("flyway");
        }
    }

    /** Текущая версия схемы в журнал при старте — первое, что спрашивают при разборе. */
    @Bean
    public ApplicationRunner schemaVersionLogger(Flyway flyway) {
        return args -> {
            MigrationInfo current = flyway.info().current();
            log.info("Схема базы данных: версия {}, применено миграций {}",
                    current == null ? "отсутствует" : current.getVersion(),
                    flyway.info().applied().length);
        };
    }
}
