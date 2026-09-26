package ru.lct.heatroute.calc;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Предел угла поворота из раздела 2.1 ТП проверяется на чистой геометрии, без набора
 * данных: правило касается только формы линии, и мешать его проверку с маршрутизацией
 * незачем. Препятствий в этих проверках нет вовсе, поэтому сглаживание ограничено
 * лишь самой геометрией дуги.
 */
class TurnLimiterTest {

    /** Свободное поле: любое звено проходимо. */
    private static final TurnLimiter.Passability FREE = (from, to) -> true;

    private final TurnLimiter limiter = new TurnLimiter();

    @Test
    @DisplayName("Прямая и мягкий поворот остаются как есть")
    void keepsGentleTurns() {
        List<Coordinate> straight = List.of(
                new Coordinate(0, 0), new Coordinate(100, 0), new Coordinate(200, 0));
        assertThat(limiter.limit(straight, FREE).getPath())
                .as("прямую линию менять не за что")
                .isEqualTo(straight);

        List<Coordinate> right = List.of(
                new Coordinate(0, 0), new Coordinate(100, 0), new Coordinate(100, 100));
        TurnLimiter.Result result = limiter.limit(right, FREE);
        assertThat(TurnLimiter.turnDeg(right.get(0), right.get(1), right.get(2)))
                .as("прямой угол — ровно предел, он допустим")
                .isCloseTo(90.0, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(result.getPath()).isEqualTo(right);
        assertThat(result.getUnresolved()).isZero();
    }

    @Test
    @DisplayName("Поворот круче предела разбивается на несколько мелких")
    void splitsSharpTurn() {
        // Отклонение от прямой 135°: трасса разворачивается назад-влево.
        List<Coordinate> hairpin = List.of(
                new Coordinate(-10, 0), new Coordinate(0, 0), new Coordinate(-5, 5));
        assertThat(TurnLimiter.turnDeg(hairpin.get(0), hairpin.get(1), hairpin.get(2)))
                .isCloseTo(135.0, org.assertj.core.data.Offset.offset(1e-6));

        TurnLimiter.Result result = limiter.limit(hairpin, FREE);

        assertThat(result.getUnresolved())
                .as("на свободном поле сгладить обязано получиться")
                .isZero();
        assertThat(result.getPath().size())
                .as("вершина заменяется дугой, точек становится больше")
                .isGreaterThan(hairpin.size());
        assertNoSharpTurns(result.getPath());
    }

    @Test
    @DisplayName("Дуга обходит вершину снаружи поворота, а не срезает угол")
    void arcGoesOutside() {
        // Препятствие стоит с внутренней стороны поворота, поэтому внутрь уходить нельзя.
        // Признак внешнего обхода: дуга уводит линию за вершину, и суммарная длина
        // растёт, а не падает, как было бы при срезании угла.
        List<Coordinate> hairpin = List.of(
                new Coordinate(-10, 0), new Coordinate(0, 0), new Coordinate(-5, 5));
        List<Coordinate> smoothed = limiter.limit(hairpin, FREE).getPath();

        assertThat(length(smoothed))
                .as("внешний обход длиннее исходной ломаной")
                .isGreaterThan(length(hairpin));

        // Внутренний сектор поворота — между направлениями на предыдущую и следующую
        // точку, отсчитанный коротким путём. Точки дуги в него попадать не должны.
        double toBefore = Math.toDegrees(Math.atan2(0 - 0, -10 - 0));
        double toAfter = Math.toDegrees(Math.atan2(5 - 0, -5 - 0));
        for (Coordinate c : smoothed) {
            double distance = Math.hypot(c.x, c.y);
            if (distance < 1e-9 || distance > 3.0) {
                continue;           // концы исходных звеньев, они вне дуги
            }
            double angle = Math.toDegrees(Math.atan2(c.y, c.x));
            assertThat(insideShortSector(angle, toAfter, toBefore))
                    .as("точка дуги под углом %.1f° попала во внутренний сектор", angle)
                    .isFalse();
        }
    }

    @Test
    @DisplayName("Несколько крутых поворотов подряд приводятся все")
    void handlesSeveralSharpTurns() {
        List<Coordinate> zigzag = List.of(
                new Coordinate(0, 0),
                new Coordinate(20, 0),
                new Coordinate(12, 8),
                new Coordinate(22, 14),
                new Coordinate(14, 24));

        TurnLimiter.Result result = limiter.limit(zigzag, FREE);

        assertThat(result.getUnresolved()).isZero();
        assertNoSharpTurns(result.getPath());
        assertThat(result.getPath().get(0))
                .as("начало нитки сдвигать нельзя: это узел")
                .isEqualTo(zigzag.get(0));
        assertThat(result.getPath().get(result.getPath().size() - 1))
                .as("конец нитки тоже узел")
                .isEqualTo(zigzag.get(zigzag.size() - 1));
    }

    // =================================================================================

    private static void assertNoSharpTurns(List<Coordinate> path) {
        for (int i = 1; i + 1 < path.size(); i++) {
            double turn = TurnLimiter.turnDeg(path.get(i - 1), path.get(i), path.get(i + 1));
            assertThat(turn)
                    .as("поворот в вершине %d линии из %d точек", i, path.size())
                    .isLessThanOrEqualTo(TurnLimiter.MAX_TURN_DEG + 1e-6);
        }
    }

    private static double length(List<Coordinate> path) {
        double sum = 0;
        for (int i = 0; i + 1 < path.size(); i++) {
            sum += path.get(i).distance(path.get(i + 1));
        }
        return sum;
    }

    /** Лежит ли угол внутри сектора, отсчитанного коротким путём от {@code from} к {@code to}. */
    private static boolean insideShortSector(double angle, double from, double to) {
        double span = normalize(to - from);
        if (span > 180) {
            double swap = from;
            from = to;
            to = swap;
            span = normalize(to - from);
        }
        double offset = normalize(angle - from);
        return offset > 1e-6 && offset < span - 1e-6;
    }

    private static double normalize(double degrees) {
        double value = degrees % 360;
        return value < 0 ? value + 360 : value;
    }
}
