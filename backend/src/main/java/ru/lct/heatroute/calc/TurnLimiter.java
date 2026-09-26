package ru.lct.heatroute.calc;

import lombok.extern.slf4j.Slf4j;
import org.locationtech.jts.geom.Coordinate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Приведение трассы к предельному углу поворота.
 * <p>
 * Раздел 2.1 ТП в редакции от 18.09: «Допускается произвольный угол поворота до 90°
 * включительно». В первой редакции такого предела не было, и это новое геометрическое
 * требование, а не вопрос стоимости — отдельного удорожания поворота приложение
 * не вводит.
 * <p>
 * Граф видимости огибает препятствие по касательным и на остром выступе способен дать
 * поворот круче 90°: путь разворачивается вокруг вершины препятствия. Такую вершину
 * нельзя ни срезать, ни сгладить внутрь — там препятствие. Поэтому поворот разбивается
 * наружу: вершина заменяется короткой дугой того же радиуса вокруг неё, и один крутой
 * поворот становится несколькими мелкими. Точки дуги лежат дальше от препятствия, чем
 * исходная вершина, поэтому клиренс от него только растёт; но рядом может оказаться
 * другое препятствие, поэтому каждое новое звено проверяется на проходимость, а радиус
 * при неудаче уменьшается.
 * <p>
 * Если сгладить не удалось, вершина остаётся как была и попадает в счётчик нарушений:
 * тихо отдать трассу с недопустимым поворотом хуже, чем показать, что здесь предел
 * не выдержан.
 */
@Slf4j
@Component
public class TurnLimiter {

    /** Предел из раздела 2.1 ТП, град. Включительно, поэтому сравнение с допуском. */
    public static final double MAX_TURN_DEG = 90.0;

    private static final double TOLERANCE_DEG = 1e-6;

    /** Наибольший радиус сглаживания, м. Дальше дуга перестаёт быть местной правкой. */
    private static final double MAX_RADIUS_M = 2.0;

    /** Доля короткого из двух звеньев, которую можно занять дугой. */
    private static final double RADIUS_SHARE = 0.25;

    /** Сколько раз уменьшать радиус вдвое, прежде чем признать неудачу. */
    private static final int RADIUS_ATTEMPTS = 4;

    /**
     * Наименьший радиус сглаживания, м. Ниже него звенья дуги становятся короче
     * полуметра, и трасса приобретает ровно тот вид, который приложение запрещает
     * отдельной фразой про «необоснованные мелкие изломы». Лучше честно сказать,
     * что поворот привести не удалось, чем заменить одно нарушение другим.
     */
    private static final double MIN_RADIUS_M = 0.5;

    /**
     * Проходимо ли прямое звено. Отдельным интерфейсом, а не полем препятствий:
     * приведение угла — задача геометрическая, и проверять её надо на геометрии,
     * без поднятия обстановки целиком.
     */
    @FunctionalInterface
    public interface Passability {
        boolean test(Coordinate from, Coordinate to);
    }

    /** Результат: приведённая линия и сколько вершин осталось за пределом. */
    public static final class Result {
        private final List<Coordinate> path;
        private final int unresolved;

        Result(List<Coordinate> path, int unresolved) {
            this.path = path;
            this.unresolved = unresolved;
        }

        public List<Coordinate> getPath() {
            return path;
        }

        public int getUnresolved() {
            return unresolved;
        }
    }

    /**
     * @param path     вершины нитки в рабочей проекции, от начала к концу
     * @param passable проверка проходимости прямого звена по клиренсу нитки
     */
    public Result limit(List<Coordinate> path, Passability passable) {
        if (path.size() < 3) {
            return new Result(path, 0);
        }

        List<Coordinate> current = new ArrayList<>(path);
        int unresolved = 0;

        // Индекс вершины, с которой продолжается поиск: вставленные точки сами поворотов
        // круче предела не дают, поэтому проверять их заново не нужно, но соседей —
        // нужно, ведь у них изменилось одно из звеньев.
        int index = 1;
        while (index < current.size() - 1) {
            double turn = turnDeg(current.get(index - 1), current.get(index),
                    current.get(index + 1));
            if (turn <= MAX_TURN_DEG + TOLERANCE_DEG) {
                index++;
                continue;
            }

            List<Coordinate> fan = smooth(current, index, turn, passable);
            if (fan == null) {
                log.debug("Поворот {}° не удалось привести к пределу {}°: вершина оставлена",
                        Math.round(turn), Math.round(MAX_TURN_DEG));
                unresolved++;
                index++;
                continue;
            }

            List<Coordinate> replaced = new ArrayList<>(current.size() + fan.size());
            replaced.addAll(current.subList(0, index));
            replaced.addAll(fan);
            replaced.addAll(current.subList(index + 1, current.size()));
            current = replaced;
            // Возвращаемся на звено назад: у предыдущей вершины сменился выходной отрезок.
            index = Math.max(1, index - 1);
        }

        return new Result(current, unresolved);
    }

    /**
     * Дуга вокруг вершины вместо самой вершины. Возвращает {@code null}, если ни при одном
     * радиусе все новые звенья не проходят по клиренсу.
     */
    private List<Coordinate> smooth(List<Coordinate> path, int index, double turn,
                                    Passability passable) {
        Coordinate before = path.get(index - 1);
        Coordinate vertex = path.get(index);
        Coordinate after = path.get(index + 1);

        double inLength = before.distance(vertex);
        double outLength = vertex.distance(after);
        if (inLength < 1e-6 || outLength < 1e-6) {
            return null;
        }

        // Дуга идёт снаружи поворота: от точки на входном звене, вокруг вершины,
        // к точке на выходном. Внешний обход длиннее внутреннего ровно настолько,
        // насколько поворот круче прямого угла, и именно он уводит от препятствия.
        double sweep = 180.0 + turn;
        // Делим не ровно на предел, а с запасом: при делении ровно на 90° углы дуги
        // ложатся точно на предел, и любая погрешность выносит их за него.
        int steps = (int) Math.ceil(sweep / (MAX_TURN_DEG - 5.0));
        if (steps < 2) {
            return null;
        }

        double startAngle = Math.atan2(before.y - vertex.y, before.x - vertex.x);
        double endAngle = Math.atan2(after.y - vertex.y, after.x - vertex.x);
        double direction = outwardDirection(before, vertex, after);
        double delta = normalized(endAngle - startAngle, direction);

        // Углы поворотов внутри веера от радиуса не зависят: это чистая геометрия дуги.
        // Поэтому пригодность разбиения проверяется один раз, а радиус уменьшается
        // только ради клиренса.
        double radius = Math.min(MAX_RADIUS_M, RADIUS_SHARE * Math.min(inLength, outLength));
        if (!withinLimit(before, fanAt(vertex, radius, startAngle, delta, steps), after)) {
            return null;
        }
        for (int attempt = 0; attempt < RADIUS_ATTEMPTS && radius >= MIN_RADIUS_M; attempt++) {
            List<Coordinate> fan = fanAt(vertex, radius, startAngle, delta, steps);
            if (allPassable(before, fan, after, passable)) {
                return fan;
            }
            radius /= 2;
        }
        return null;
    }

    /** Точки дуги радиуса {@code radius} вокруг вершины от {@code startAngle} на {@code delta}. */
    private static List<Coordinate> fanAt(Coordinate vertex, double radius,
                                          double startAngle, double delta, int steps) {
        List<Coordinate> fan = new ArrayList<>(steps + 1);
        for (int i = 0; i <= steps; i++) {
            double angle = startAngle + delta * i / steps;
            fan.add(new Coordinate(vertex.x + radius * Math.cos(angle),
                    vertex.y + radius * Math.sin(angle)));
        }
        return fan;
    }

    /**
     * В какую сторону обходить вершину по дуге.
     * <p>
     * Дуга строится от точки на входном звене к точке на выходном. Короткий путь между
     * ними срезает угол и идёт по внутренней стороне поворота — как раз туда, где стоит
     * препятствие, заставившее трассу повернуть. Нужен длинный путь, в обратную сторону
     * вращения: он сметает 180° + угол поворота и обходит вершину снаружи, оставляя
     * сектор между звеньями нетронутым.
     * <p>
     * Знак векторного произведения входного и выходного звеньев говорит, левый поворот
     * или правый. У левого поворота (произведение положительно) короткий путь идёт
     * по часовой стрелке, значит длинный — против; у правого наоборот.
     */
    private static double outwardDirection(Coordinate before, Coordinate vertex,
                                           Coordinate after) {
        double cross = (vertex.x - before.x) * (after.y - vertex.y)
                - (vertex.y - before.y) * (after.x - vertex.x);
        return cross >= 0 ? 1 : -1;
    }

    /** Приращение угла в заданную сторону обхода, радианы. */
    private static double normalized(double delta, double direction) {
        double twoPi = 2 * Math.PI;
        double value = delta % twoPi;
        if (direction > 0) {
            if (value <= 0) {
                value += twoPi;
            }
        } else if (value >= 0) {
            value -= twoPi;
        }
        return value;
    }

    private static boolean allPassable(Coordinate before, List<Coordinate> fan,
                                       Coordinate after, Passability passable) {
        Coordinate previous = before;
        for (Coordinate point : fan) {
            if (!passable.test(previous, point)) {
                return false;
            }
            previous = point;
        }
        return passable.test(previous, after);
    }

    /** Все повороты новой цепочки, включая стыки с исходными звеньями, в пределах нормы. */
    private static boolean withinLimit(Coordinate before, List<Coordinate> fan,
                                       Coordinate after) {
        List<Coordinate> chain = new ArrayList<>(fan.size() + 2);
        chain.add(before);
        chain.addAll(fan);
        chain.add(after);
        for (int i = 1; i + 1 < chain.size(); i++) {
            if (turnDeg(chain.get(i - 1), chain.get(i), chain.get(i + 1))
                    > MAX_TURN_DEG + TOLERANCE_DEG) {
                return false;
            }
        }
        return true;
    }

    /**
     * Угол поворота в вершине {@code b}: отклонение от продолжения предыдущего звена.
     * Ноль соответствует движению по прямой, как и определяет раздел 2.1 ТП.
     */
    public static double turnDeg(Coordinate a, Coordinate b, Coordinate c) {
        double inX = b.x - a.x;
        double inY = b.y - a.y;
        double outX = c.x - b.x;
        double outY = c.y - b.y;
        double inLength = Math.hypot(inX, inY);
        double outLength = Math.hypot(outX, outY);
        if (inLength < 1e-9 || outLength < 1e-9) {
            return 0;
        }
        double cos = (inX * outX + inY * outY) / (inLength * outLength);
        return Math.toDegrees(Math.acos(Math.max(-1, Math.min(1, cos))));
    }
}
