package ru.lct.heatroute.variant;

import lombok.extern.slf4j.Slf4j;
import org.locationtech.jts.geom.Coordinate;
import org.springframework.stereotype.Component;
import ru.lct.heatroute.domain.reference.ReferenceCatalog;
import ru.lct.heatroute.routing.RoutingGraph;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Перенос развилок в точку, которая минимизирует стоимость примыкающих труб.
 * <p>
 * Дерево собирается из путей в графе видимости, поэтому его развилки стоят там, где
 * оказался общий участок двух кратчайших путей, — как правило, на углу обогнутого
 * здания. Для связности это годится, для денег нет: в задаче Штейнера оптимальная
 * развилка трёх ветвей лежит в точке Ферма, где рёбра расходятся под 120°. Разница
 * между остовным деревом и деревом Штейнера на плоскости доходит до 13 %, и берётся
 * она в основном здесь.
 * <p>
 * Минимизируется не длина, а стоимость: {@code Σ wᵢ · |x − pᵢ|}, где {@code wᵢ} —
 * цена метра трубы соответствующего диаметра. Ствол толще отводов и тянет развилку
 * к себе сильнее — и правильно делает: метр ДУ 300 стоит вдвое дороже метра ДУ 65,
 * и сокращать нужно именно его.
 * <p>
 * Точка ищется алгоритмом Вейсфельда — это обычные итерации взвешенного среднего
 * с весами, обратными расстояниям. Ход принимается, только если каждый новый отрезок
 * проходим по клиренсу и выигрыш покрывает порог: развилка — это тепловая камера,
 * двигать её на сантиметры ради копеек незачем.
 * <p>
 * Стоимость самой камеры при переносе не меняется: она считается по наибольшему ДУ
 * примыкающих участков (раздел 8.2 ТП), а состав примыканий остаётся прежним.
 */
@Slf4j
@Component
public class JunctionRelocator {

    /**
     * Сколько проходов по всем развилкам делать максимум. Проход переносит одну
     * развилку, и перенос меняет оптимум соседних, поэтому проходов нужно больше,
     * чем развилок в дереве. Ограничение — страховка от зацикливания, а не режим
     * работы: на конкурсном наборе выигрыш исчерпывается за пять-шесть проходов.
     */
    private static final int MAX_PASSES = 16;

    /** Итерации Вейсфельда: дальше точка смещается меньше чем на миллиметр. */
    private static final int WEISZFELD_ITERATIONS = 64;

    /** Сошлось, если шаг меньше этого. */
    private static final double CONVERGENCE_M = 1e-3;

    /**
     * Смещение меньше этого не рассматривается.
     * <p>
     * Развилка — это тепловая камера, сооружение габаритом в метры; сдвиг на сантиметры
     * в проекте ничего не меняет, а расчёт замедляет. Порог заодно оказался полезен
     * для качества: мелкие ходы уводят поиск в худший локальный оптимум, и с метровым
     * порогом решение выходит дешевле, чем с двадцатисантиметровым (S 13,590 против
     * 13,593 на конкурсном наборе).
     */
    private static final double MIN_SHIFT_M = 1.0;

    /**
     * Выигрыш меньше этого не стоит хода.
     * <p>
     * Двадцать пять тысяч рублей — это около двадцати сантиметров трубы ДУ 200.
     * Ниже начинается шум сметы: экономия такого порядка не переживёт ни уточнения
     * расценок, ни привязки камеры на местности.
     */
    private static final double MIN_GAIN_RUB = 25_000;

    /** Ближе этого к соседу развилка не ставится — получился бы вырожденный участок. */
    private static final double MIN_NEIGHBOUR_DISTANCE_M = 1.0;

    /**
     * Доли пути до точки Ферма, которые пробуются по очереди.
     * <p>
     * Оптимум часто лежит внутри здания: развилка стоит на углу как раз потому, что
     * дальше идти нельзя. Отбрасывать такой ход — терять почти весь выигрыш, поэтому
     * пробуется частичный сдвиг. Движение идёт из заведомо допустимой точки в сторону
     * оптимума, и функция стоимости на этом отрезке выпуклая: любая доля пути дешевле
     * начала, а первая проходимая — лучшая из достижимых в этом направлении.
     * <p>
     * Шаг в десятую долю, а не крупнее: развилка часто стоит вплотную к зданию,
     * и грубая сетка проскакивает ту единственную долю пути, которая ещё проходима.
     */
    private static final double[] SHIFT_FRACTIONS = {1.0, 0.9, 0.8, 0.7, 0.6, 0.5, 0.4, 0.3, 0.2, 0.1};

    /** Расход, по которому берётся диаметр, если ветвь ничего не питает. */
    private static final double FALLBACK_FLOW_TPH = 1.0;

    private final ReferenceCatalog catalog;

    /** Диагностика последнего вызова: рассмотрено развилок и отвергнуто положений. */
    private int considered;
    private int blocked;

    public JunctionRelocator(ReferenceCatalog catalog) {
        this.catalog = catalog;
    }

    /**
     * @param passable проверка клиренса для каждого нового отрезка
     * @return суммарный выигрыш в рублях
     */
    public double relocate(RouteTree tree, RoutingGraph graph,
                           SteinerTreeBuilder.Passability passable) {
        double total = 0;
        int moved = 0;
        considered = 0;
        blocked = 0;

        for (int pass = 0; pass < MAX_PASSES; pass++) {
            double gain = relocateOnce(tree, graph, passable);
            if (gain <= 0) {
                break;
            }
            total += gain;
            moved++;
        }
        if (considered > 0) {
            log.debug("Развилки: рассмотрено {}, перенесено {}, положений отвергнуто "
                            + "препятствиями {}, дешевле на {} руб.",
                    considered, moved, blocked, Math.round(total));
        }
        return total;
    }

    private double relocateOnce(RouteTree tree, RoutingGraph graph,
                                SteinerTreeBuilder.Passability passable) {
        Map<Integer, Double> flows = tree.subtreeFlows();

        for (Integer node : new ArrayList<>(tree.nodes())) {
            if (!movable(tree, node)) {
                continue;
            }
            List<Neighbour> neighbours = neighboursOf(tree, graph, flows, node);
            if (neighbours.size() < 3) {
                // У проходного узла точка Ферма лежит на отрезке между соседями,
                // то есть узел просто исчезает. Этим занимается спрямление.
                continue;
            }

            Coordinate current = tree.locationOf(graph, node);
            Coordinate optimum = weightedFermatPoint(neighbours, current);
            if (optimum == null || current.distance(optimum) < MIN_SHIFT_M) {
                continue;
            }
            considered++;

            double currentCost = cost(neighbours, current);
            for (double fraction : SHIFT_FRACTIONS) {
                Coordinate target = along(current, optimum, fraction);
                if (current.distance(target) < MIN_SHIFT_M) {
                    break;
                }
                if (tooCloseToNeighbour(neighbours, target)) {
                    continue;
                }
                double gain = currentCost - cost(neighbours, target);
                if (gain < MIN_GAIN_RUB) {
                    break;   // дальше доли только меньше, выигрыш только меньше
                }
                boolean reachable = neighbours.stream()
                        .allMatch(n -> passable.check(target, n.location, n.exemptOks));
                if (!reachable) {
                    blocked++;
                    continue;
                }
                tree.moveTo(node, target);
                return gain;
            }
        }
        return 0;
    }

    /**
     * Корень — это точка врезки на существующей сети, её положение задано выбором
     * кандидата. Точка подключения ОКС — вход в здание, она тоже задана входными
     * данными. Двигать можно только развилки.
     */
    private boolean movable(RouteTree tree, int node) {
        return tree.contains(node)
                && !tree.getTerminalOks().containsKey(node)
                && tree.parentOf(node) != null;
    }

    private List<Neighbour> neighboursOf(RouteTree tree, RoutingGraph graph,
                                         Map<Integer, Double> flows, int node) {
        List<Neighbour> out = new ArrayList<>(4);
        Integer parent = tree.parentOf(node);
        if (parent != null) {
            // Ребро к родителю несёт расход всего поддерева этого узла.
            out.add(new Neighbour(tree.locationOf(graph, parent),
                    costPerMeter(flows, node), null));
        }
        for (Integer child : tree.childrenOf(node)) {
            out.add(new Neighbour(tree.locationOf(graph, child), costPerMeter(flows, child),
                    tree.getTerminalOks().get(child)));
        }
        return out;
    }

    private double costPerMeter(Map<Integer, Double> flows, int node) {
        double flow = Math.max(flows.getOrDefault(node, FALLBACK_FLOW_TPH), FALLBACK_FLOW_TPH);
        int dn = catalog.selectForFlow(flow).map(row -> row.getDn()).orElse(catalog.largest().getDn());
        return catalog.newCostPerM(dn);
    }

    private static double cost(List<Neighbour> neighbours, Coordinate point) {
        double sum = 0;
        for (Neighbour n : neighbours) {
            sum += n.costPerMeter * point.distance(n.location);
        }
        return sum;
    }

    /**
     * Точка, минимизирующая {@code Σ wᵢ · |x − pᵢ|}, методом Вейсфельда.
     * <p>
     * Итерация — взвешенное среднее соседей с весами {@code wᵢ / |x − pᵢ|}: чем ближе
     * сосед, тем сильнее он тянет. Особенность метода в том, что он ломается, попав
     * ровно в одного из соседей: расстояние обращается в ноль. Проверяется явно —
     * в этом случае оптимум и есть этот сосед, дальше двигаться некуда.
     */
    private static Coordinate weightedFermatPoint(List<Neighbour> neighbours, Coordinate start) {
        double x = 0;
        double y = 0;
        double weight = 0;
        for (Neighbour n : neighbours) {
            x += n.location.x * n.costPerMeter;
            y += n.location.y * n.costPerMeter;
            weight += n.costPerMeter;
        }
        if (weight <= 0) {
            return null;
        }
        Coordinate point = new Coordinate(x / weight, y / weight);

        for (int i = 0; i < WEISZFELD_ITERATIONS; i++) {
            double sx = 0;
            double sy = 0;
            double sw = 0;
            for (Neighbour n : neighbours) {
                double distance = point.distance(n.location);
                if (distance < 1e-9) {
                    return start.distance(n.location) < 1e-9 ? null : n.location;
                }
                double w = n.costPerMeter / distance;
                sx += n.location.x * w;
                sy += n.location.y * w;
                sw += w;
            }
            if (sw <= 0) {
                return null;
            }
            Coordinate next = new Coordinate(sx / sw, sy / sw);
            double step = next.distance(point);
            point = next;
            if (step < CONVERGENCE_M) {
                break;
            }
        }
        return point;
    }

    private static boolean tooCloseToNeighbour(List<Neighbour> neighbours, Coordinate point) {
        for (Neighbour n : neighbours) {
            if (point.distance(n.location) < MIN_NEIGHBOUR_DISTANCE_M) {
                return true;
            }
        }
        return false;
    }

    /** Точка на доле {@code fraction} пути от {@code from} к {@code to}. */
    private static Coordinate along(Coordinate from, Coordinate to, double fraction) {
        return new Coordinate(from.x + (to.x - from.x) * fraction,
                from.y + (to.y - from.y) * fraction);
    }

    /** Сосед развилки: куда идёт участок, сколько стоит его метр и чей контур не мешает. */
    private static final class Neighbour {
        private final Coordinate location;
        private final double costPerMeter;
        private final String exemptOks;

        private Neighbour(Coordinate location, double costPerMeter, String exemptOks) {
            this.location = location;
            this.costPerMeter = costPerMeter;
            this.exemptOks = exemptOks;
        }
    }
}
