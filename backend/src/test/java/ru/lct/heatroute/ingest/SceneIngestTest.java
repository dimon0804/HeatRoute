package ru.lct.heatroute.ingest;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import ru.lct.heatroute.domain.model.ExistingChamber;
import ru.lct.heatroute.domain.model.ExistingSegment;
import ru.lct.heatroute.domain.model.ExistingTopology;
import ru.lct.heatroute.domain.model.FutureOks;
import ru.lct.heatroute.domain.model.IngestDiagnostics;
import ru.lct.heatroute.domain.model.InputScene;

import java.io.InputStream;
import java.util.List;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Разбор конкурсного набора ЛЦТ-2026 целиком.
 * <p>
 * Набор специально взят такой, какой он есть: без {@code upstream_object_id},
 * без {@code flow_tph} у существующей сети и без полигонов {@code oks_future}.
 * Тест закрепляет, что сервис на таких данных не падает и не теряет объекты,
 * а недостающее восстанавливает и записывает в протокол.
 */
@SpringBootTest
@ActiveProfiles("nodb")
class SceneIngestTest {

    private static final String SAMPLE = "/samples/dataset_lct2026.geojson";

    @Autowired
    GeoJsonStreamParser parser;
    @Autowired
    SceneAssembler assembler;

    private static InputScene scene;

    @BeforeAll
    static void guard() {
        assertThat(SceneIngestTest.class.getResource(SAMPLE))
                .as("конкурсный набор должен лежать в тестовых ресурсах").isNotNull();
    }

    private InputScene scene() throws Exception {
        if (scene == null) {
            SceneAssembler.Collector collector = new SceneAssembler.Collector();
            try (InputStream in = getClass().getResourceAsStream(SAMPLE)) {
                parser.parse(Objects.requireNonNull(in), collector::accept);
            }
            scene = assembler.assemble(collector);
        }
        return scene;
    }

    @Test
    @DisplayName("Все объекты набора разобраны и распределены по типам")
    void readsEveryFeature() throws Exception {
        InputScene s = scene();
        assertThat(s.getSegments()).hasSize(29);
        assertThat(s.getChambers()).hasSize(9);
        assertThat(s.getFutureOks()).hasSize(17);
        // 85 существующих зданий + 2 водных объекта + 1 железная дорога
        assertThat(s.getRestrictions()).hasSize(88);
        assertThat(s.getSource()).isNotNull();
    }

    @Test
    @DisplayName("Расходы перспективных ОКС взяты с точек подключения без потерь")
    void readsFlows() throws Exception {
        InputScene s = scene();
        assertThat(s.totalFutureFlowTph()).isCloseTo(488.72, org.assertj.core.data.Offset.offset(0.01));
        assertThat(s.getFutureOks())
                .allMatch(o -> o.getFlowSource() == FutureOks.FlowSource.CONNECTION_POINT);
        assertThat(s.getFutureOks()).allMatch(o -> o.getFlowTph() > 0);
    }

    @Test
    @DisplayName("Длины считаются в метрах рабочей проекции, а не в градусах")
    void projectsToMeters() throws Exception {
        InputScene s = scene();
        // Суммарная длина существующей сети по независимому расчёту — около 1460 м.
        assertThat(s.existingNetworkLength()).isBetween(1400.0, 1520.0);
        assertThat(s.getExtent().getWidth()).isBetween(1800.0, 2200.0);
        assertThat(s.getExtent().getHeight()).isBetween(1400.0, 1800.0);
    }

    @Test
    @DisplayName("Направление к источнику восстановлено для каждого участка сети")
    void restoresUpstreamChain() throws Exception {
        InputScene s = scene();
        ExistingTopology topology = s.getTopology();

        assertThat(topology.getSourceNodeIndex()).isGreaterThanOrEqualTo(0);
        assertThat(s.getSegments()).allMatch(seg -> seg.getUpstreamObjectId() != null);
        assertThat(s.getSegments()).allMatch(ExistingSegment::isUpstreamInferred);

        // Цепочка от каждого участка обязана заканчиваться источником: иначе
        // дополнительный расход некуда распространять и реконструкция не считается.
        String sourceId = s.getSource().getId();
        for (ExistingSegment seg : s.getSegments()) {
            List<String> chain = topology.chainToSource(seg.getId());
            assertThat(chain)
                    .as("цепочка к источнику для участка %s", seg.getId())
                    .isNotEmpty()
                    .last().isEqualTo(sourceId);
        }
    }

    @Test
    @DisplayName("Камеры привязаны к узлам сети, диаметр восстановлен по примыкающим участкам")
    void restoresChambers() throws Exception {
        InputScene s = scene();
        assertThat(s.getChambers()).allMatch(ExistingChamber::isDiameterInferred);
        assertThat(s.getChambers()).allMatch(ch -> ch.getDiameter() >= 300);
        assertThat(s.getChambers()).allMatch(ch -> ch.getExistingDegree() >= 1);
        assertThat(s.getChambers()).allMatch(ch -> ch.getUpstreamObjectId() != null);
    }

    @Test
    @DisplayName("Ограничение вне таблицы 5.1 разрешается через псевдоним, а не отбрасывается")
    void resolvesRestrictionAliases() throws Exception {
        InputScene s = scene();
        assertThat(s.getRestrictions())
                .filteredOn(r -> "oks".equals(r.getRawType()))
                .hasSize(85)
                .allMatch(r -> "oks_existing".equals(r.getCanonicalType()))
                .allMatch(r -> !r.isUnknownType());
        assertThat(s.getRestrictions())
                .filteredOn(r -> "railway".equals(r.getRawType()))
                .hasSize(1)
                .allMatch(r -> "tram_tracks".equals(r.getCanonicalType()));
    }

    @Test
    @DisplayName("Полигон вокруг точки подключения опознан как собственный контур ОКС")
    void linksOwnFootprints() throws Exception {
        InputScene s = scene();

        // Все 17 точек подключения лежат внутри полигонов-ограничений: это контуры
        // самих подключаемых зданий. Без опознания собственное здание закрыло бы
        // подход к своей же точке буфером в 5 м.
        assertThat(s.getFutureOks())
                .allMatch(o -> o.getFootprintSource() == FutureOks.FootprintSource.RESTRICTION_MATCH)
                .allMatch(o -> o.getFootprint() != null)
                .allMatch(o -> o.getFootprint().covers(o.getConnectionPoint()));

        // Обратная связь проставлена ровно у опознанных ограничений и ни у каких других.
        long owned = s.getRestrictions().stream()
                .filter(r -> !r.getOwnerOksIds().isEmpty())
                .count();
        // В наборе одно здание содержит две точки подключения, поэтому контуров 16, а не 17.
        assertThat(owned).isEqualTo(16);
        assertThat(s.getRestrictions())
                .filteredOn(r -> r.getOwnerOksIds().isEmpty())
                .hasSize(88 - 16);

        // Здание с двумя точками подключения должно числиться контуром обоих ОКС:
        // иначе его буфер перекроет подход к тому из них, что запомнился не последним.
        long twoOwners = s.getRestrictions().stream()
                .filter(r -> r.getOwnerOksIds().size() == 2)
                .count();
        assertThat(twoOwners).isEqualTo(1);
        assertThat(s.getRestrictions().stream()
                .mapToInt(r -> r.getOwnerOksIds().size()).sum()).isEqualTo(17);
    }

    @Test
    @DisplayName("Протокол разбора называет каждое принятое допущение")
    void reportsAssumptions() throws Exception {
        InputScene s = scene();
        IngestDiagnostics d = s.getDiagnostics();
        assertThat(d.hasErrors()).isFalse();

        List<String> codes = d.getEntries().stream()
                .map(IngestDiagnostics.Entry::getCode)
                .collect(java.util.stream.Collectors.toList());
        assertThat(codes).contains(
                "upstream.inferred",          // цепочка к источнику восстановлена
                "segment.noFlow",             // расход существующей сети не передан
                "chamber.diameterInferred",   // ДУ камер восстановлен
                "oks.flowFromConnectionPoint", // расход ОКС взят с точки подключения
                "oks.footprintFromRestriction" // контур ОКС опознан по ограничению
        );
    }
}
