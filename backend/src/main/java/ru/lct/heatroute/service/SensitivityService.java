package ru.lct.heatroute.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.lct.heatroute.api.dto.SensitivityReportDto;
import ru.lct.heatroute.domain.model.FutureOks;
import ru.lct.heatroute.domain.model.InputScene;
import ru.lct.heatroute.domain.result.CalculationVariant;
import ru.lct.heatroute.domain.result.VariantSummary;
import ru.lct.heatroute.persistence.CalculationJobEntity;
import ru.lct.heatroute.persistence.CalculationJobRepository;
import ru.lct.heatroute.variant.VariantPlanner;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Что держит цену решения.
 * <p>
 * Итоговая стоимость — одно число, и по нему не видно, из чего она складывается. Вопрос,
 * который задаёт планировщик, звучит иначе: какой из объектов обходится дороже всего
 * и что будет, если его подключение отложить. Ответ получается перебором: задача решается
 * заново без каждой точки подключения по очереди, и разница показывает её вклад.
 * <p>
 * Это честный численный эксперимент, а не оценка по формуле. Вклад точки в сеть не равен
 * длине отвода к ней: убрав точку, эвристика может перестроить всё дерево, и выигрыш
 * окажется больше или меньше ожидаемого. Именно поэтому считается прогоном, а не
 * вычитанием.
 * <p>
 * Стоит это дорого: один прогон на конкурсном наборе идёт около двадцати секунд, и на
 * семнадцать точек уходят минуты. Поэтому анализ вынесен в отдельную ручку и никогда
 * не выполняется сам.
 */
@Slf4j
@Service
@Profile("!nodb")
public class SensitivityService {

    private final VariantPlanner planner;
    private final DatasetService datasets;
    private final CalculationJobRepository jobs;

    public SensitivityService(VariantPlanner planner,
                              DatasetService datasets,
                              CalculationJobRepository jobs) {
        this.planner = planner;
        this.datasets = datasets;
        this.jobs = jobs;
    }

    @Transactional(readOnly = true)
    public SensitivityReportDto analyse(UUID jobId, int limit) {
        CalculationJobEntity job = jobs.findById(jobId)
                .orElseThrow(() -> new IllegalArgumentException("Расчёт не найден: " + jobId));
        if (job.getDataset() == null) {
            throw new IllegalStateException("У расчёта нет входного набора");
        }

        long started = System.nanoTime();
        InputScene scene = datasets.parse(job.getDataset());

        VariantSummary base = bestOf(planner.plan(scene, (f, s) -> { },
                VariantPlanner.Options.builder().build()));
        if (base == null) {
            throw new IllegalStateException("Исходная задача не дала ни одного варианта");
        }

        // Самые тяжёлые точки вперёд: если анализ ограничен по числу прогонов, полезнее
        // узнать про крупную нагрузку, чем про мелкий отвод.
        List<FutureOks> order = scene.getFutureOks().stream()
                .sorted(Comparator.comparingDouble(FutureOks::getFlowTph).reversed())
                .collect(Collectors.toList());
        if (limit > 0 && order.size() > limit) {
            order = order.subList(0, limit);
        }

        List<SensitivityReportDto.Row> rows = new ArrayList<>();
        for (FutureOks oks : order) {
            InputScene without = withoutPoint(scene, oks);
            VariantSummary summary = bestOf(planner.plan(without, (f, s) -> { },
                    VariantPlanner.Options.builder().build()));
            if (summary == null) {
                log.warn("Без точки {} задача не дала вариантов", oks.getConnectionPointId());
                continue;
            }
            rows.add(SensitivityReportDto.Row.builder()
                    .change("точка подключения не подключается")
                    .objectId(oks.getConnectionPointId())
                    .score(summary.getScore())
                    .cost(summary.getCalculatedCost())
                    .length(summary.getNewNetworkLength())
                    .costContribution(round(base.getCalculatedCost() - summary.getCalculatedCost()))
                    .lengthContribution(round2(
                            base.getNewNetworkLength() - summary.getNewNetworkLength()))
                    .unconnected(summary.getUnconnectedOksIds())
                    .build());
        }
        rows.sort(Comparator.comparingDouble(SensitivityReportDto.Row::getCostContribution)
                .reversed());

        long millis = (System.nanoTime() - started) / 1_000_000;
        log.info("Анализ чувствительности расчёта {}: прогонов {}, {} мс",
                jobId, rows.size() + 1, millis);

        return SensitivityReportDto.builder()
                .baseScore(base.getScore())
                .baseCost(base.getCalculatedCost())
                .baseLength(base.getNewNetworkLength())
                .runs(rows.size() + 1)
                .millis(millis)
                .points(rows)
                .build();
    }

    /**
     * Та же обстановка без одной точки подключения. Полигон ОКС остаётся: здание стоит
     * на месте и препятствием быть не перестаёт, подключать его перестали. Иначе анализ
     * отвечал бы на другой вопрос — что будет, если дом снести.
     */
    private InputScene withoutPoint(InputScene scene, FutureOks removed) {
        List<FutureOks> kept = scene.getFutureOks().stream()
                .filter(oks -> !oks.getConnectionPointId()
                        .equals(removed.getConnectionPointId()))
                .collect(Collectors.toList());
        return scene.toBuilder().futureOks(kept).build();
    }

    private static VariantSummary bestOf(VariantPlanner.Plan plan) {
        return plan.getVariants().stream()
                .min(Comparator.comparingInt(v -> v.getSummary().getRank()))
                .map(CalculationVariant::getSummary)
                .orElse(null);
    }

    private static double round(double value) {
        return Math.round(value);
    }

    private static double round2(double value) {
        return Math.round(value * 100d) / 100d;
    }
}
