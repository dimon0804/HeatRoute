package ru.lct.heatroute.explain;

import lombok.Value;
import lombok.extern.slf4j.Slf4j;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.LineString;
import org.springframework.stereotype.Component;
import ru.lct.heatroute.domain.model.InputScene;
import ru.lct.heatroute.domain.reference.ReferenceCatalog;
import ru.lct.heatroute.geo.GeoProperties;
import ru.lct.heatroute.routing.ObstacleField;
import ru.lct.heatroute.routing.RoutingProperties;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Объяснение, почему участок трассы прошёл именно здесь.
 * <p>
 * Главное недоверие к автоматической трассировке звучит так: «почему труба пошла тут,
 * а не прямее». Ответ у сервиса есть, но до сих пор он оставался внутри расчёта:
 * поле препятствий знает, какой объект какой отрезок отверг, а результат об этом молчал.
 * Здесь это знание восстанавливается по готовой трассе и превращается в перечень
 * ограничений, которые её зажали, с числами.
 * <p>
 * Восстанавливается, а не запоминается по ходу расчёта, намеренно. Запоминать пришлось бы
 * для миллиона отвергнутых отрезков графа видимости, из которых в решение попадут
 * единицы; а по готовой трассе тот же ответ считается за доли секунды и ровно там,
 * где его спросили.
 */
@Slf4j
@Component
public class RouteExplainer {

    /**
     * Насколько близко к своему минимальному расстоянию должен пройти участок, чтобы
     * ограничение считалось зажимающим, м. Полметра — цена одного шага упрощения
     * контура: ближе этого утверждать «труба идёт по клиренсу» было бы натяжкой.
     */
    private static final double BINDING_MARGIN_M = 0.5;

    /** Насколько далеко смотреть вокруг участка в поисках влияющих ограничений, м. */
    private static final double NEIGHBOURHOOD_M = 12.0;

    /**
     * Ближе этого расстояния участок считается примыкающим к объекту, а не проходящим
     * рядом с ним, м. Пять сантиметров — точность координат в выгрузке.
     */
    private static final double ATTACHMENT_TOLERANCE_M = 0.05;

    private final ReferenceCatalog catalog;
    private final GeoProperties geoProps;
    private final RoutingProperties routingProps;

    public RouteExplainer(ReferenceCatalog catalog, GeoProperties geoProps,
                          RoutingProperties routingProps) {
        this.catalog = catalog;
        this.geoProps = geoProps;
        this.routingProps = routingProps;
    }

    /** Ограничение рядом с участком и то, насколько оно его зажимает. */
    @Value
    public static class Nearby {
        String restrictionId;
        String type;
        /** Правило: обойти, специальный проход или место присоединения. */
        String rule;
        /** Фактическое расстояние от оси трассы до самого объекта, м. */
        double distanceM;
        /** Минимальное расстояние от оси, требуемое приложением для этого ДУ, м. */
        double requiredM;
        /**
         * Запас: сколько ещё можно было бы подвинуться к объекту, м.
         * <p>
         * Считается не вычитанием, а расстоянием до границы зоны отступа — той самой
         * геометрии, по которой маршрутизация и проверяла проходимость. Вычитание дало бы
         * отрицательные значения там, где их нет: контуры перед построением зоны
         * упрощаются с допуском в четверть метра, и расстояние до исходного контура
         * с расстоянием до упрощённого не совпадает. Ноль означает «ровно по пределу».
         */
        double marginM;
        /** Участок идёт вплотную к пределу: именно это ограничение задало его место. */
        boolean binding;
        /**
         * Участок примыкает к этому объекту: здесь он и должен его касаться, и говорить
         * о запасе бессмысленно. Так выглядит место присоединения к существующей сети.
         */
        boolean attachment;
    }

    /** Специальный проход, через который идёт участок. */
    @Value
    public static class Crossing {
        String restrictionId;
        String type;
        double kSpecial;
        /** Доля длины участка, занятая этим специальным проходом. */
        double share;
    }

    /** Разбор одного участка. */
    @Value
    public static class SegmentExplanation {
        String segmentId;
        int diameter;
        double lengthM;
        /**
         * Прямое расстояние между концами участка, м. Сравнение с длиной показывает,
         * насколько трасса вынуждена была отклониться от прямой.
         */
        double straightM;
        /** Насколько длиннее прямой линии, доля. Ноль — участок и есть прямая. */
        double detourShare;
        /** Наименьший запас до ограничения по всему участку, м. */
        double tightestMarginM;
        List<Nearby> nearby;
        List<Crossing> crossings;
        /** Человеческий вывод: чем участок зажат и насколько свободно он лежит. */
        String verdict;
    }

    /**
     * @param scene    входная обстановка, по которой считался результат
     * @param segments участки варианта: идентификатор, геометрия в рабочей проекции, ДУ
     */
    public List<SegmentExplanation> explain(InputScene scene, List<Segment> segments) {
        ObstacleField field = new ObstacleField(scene, catalog, geoProps, routingProps);
        List<SegmentExplanation> out = new ArrayList<>(segments.size());
        for (Segment segment : segments) {
            out.add(explainOne(field, segment));
        }
        return out;
    }

    /** Вход для разбора: минимум, который нужен от участка выгрузки. */
    @Value
    public static class Segment {
        String id;
        int diameter;
        LineString geometry;
        /** Собственный контур ОКС, к которому идёт участок, или {@code null}. */
        String exemptOksId;
    }

    // =================================================================================

    private SegmentExplanation explainOne(ObstacleField field, Segment segment) {
        LineString line = segment.getGeometry();
        double length = line.getLength();
        double straight = line.getStartPoint().distance(line.getEndPoint());
        double detour = straight > 1e-6 ? (length - straight) / straight : 0;

        List<Nearby> nearby = new ArrayList<>();
        double tightest = Double.MAX_VALUE;

        for (ObstacleField.Obstacle obstacle : field.forbidden(segment.getDiameter())) {
            Nearby found = measure(obstacle, segment, line, "обойти");
            if (found != null) {
                nearby.add(found);
                if (!found.isAttachment()) {
                    tightest = Math.min(tightest, found.getMarginM());
                }
            }
        }
        for (ObstacleField.Obstacle obstacle : field.special(segment.getDiameter())) {
            Nearby found = measure(obstacle, segment, line, "специальный проход");
            if (found != null) {
                nearby.add(found);
                // Внутри разрешённого пересечения расстояние не проверяется, и запас
                // по нему считать бессмысленно: в этом месте его просто нет.
                if (!found.isAttachment() && !obstacle.getPreparedSource().intersects(line)) {
                    tightest = Math.min(tightest, found.getMarginM());
                }
            }
        }
        nearby.sort(Comparator.comparingDouble(Nearby::getMarginM));

        List<Crossing> crossings = new ArrayList<>();
        for (ObstacleField.SpecialInterval interval : field.specialIntervals(
                line.getCoordinateN(0), line.getCoordinateN(line.getNumPoints() - 1),
                segment.getDiameter())) {
            crossings.add(new Crossing(interval.getRestrictionId(), interval.getCrossingType(),
                    interval.getKSpecial(),
                    round(interval.getToFraction() - interval.getFromFraction())));
        }

        double margin = tightest == Double.MAX_VALUE ? Double.NaN : round(tightest);
        return new SegmentExplanation(segment.getId(), segment.getDiameter(),
                round(length), round(straight), round(detour),
                margin, nearby, crossings, verdict(nearby, crossings, detour, margin));
    }

    private Nearby measure(ObstacleField.Obstacle obstacle, Segment segment,
                           LineString line, String rule) {
        if (obstacle.isOwnedBy(segment.getExemptOksId())) {
            return null;            // собственный контур ОКС трассе к своему ИТП не мешает
        }
        Geometry source = obstacle.getSource();
        if (source == null || source.isEmpty()) {
            return null;
        }
        double distance = source.distance(line);
        double required = catalog.minHorizontalDistance(obstacle.getRule(),
                segment.getDiameter()) + catalog.pairWidth(segment.getDiameter()) / 2;
        if (distance > required + NEIGHBOURHOOD_M) {
            return null;
        }

        // Участок, начинающийся или кончающийся на объекте, к нему примыкает. Так выглядит
        // место присоединения к существующей сети: там трасса обязана её коснуться,
        // и разговор о запасе теряет смысл.
        boolean attachment = distance <= ATTACHMENT_TOLERANCE_M;
        double margin = attachment ? 0 : obstacle.getBuffered().distance(line);

        return new Nearby(obstacle.getRestrictionId(), obstacle.getCanonicalType(),
                attachment ? "место присоединения" : rule,
                round(distance), round(required), round(margin),
                !attachment && margin <= BINDING_MARGIN_M, attachment);
    }

    /**
     * Короткий вывод для человека. Смысл в том, чтобы не заставлять читателя складывать
     * числа самому: если участок зажат, надо сказать чем, а если свободен — сказать это
     * прямо, иначе останется подозрение, что трасса кривая без причины.
     */
    private String verdict(List<Nearby> nearby, List<Crossing> crossings,
                           double detour, double margin) {
        List<String> binding = new ArrayList<>();
        List<String> attached = new ArrayList<>();
        for (Nearby item : nearby) {
            if (item.isAttachment()) {
                attached.add(item.getType() + " " + item.getRestrictionId());
            } else if (item.isBinding()) {
                binding.add(item.getType() + " " + item.getRestrictionId());
            }
        }

        StringBuilder text = new StringBuilder();
        if (!attached.isEmpty()) {
            text.append("Участок примыкает к ").append(String.join(", ", attached))
                    .append(". ");
        }
        if (!binding.isEmpty()) {
            text.append("Место участка задано ограничениями: ")
                    .append(String.join(", ", binding))
                    .append(". Ближе к ним трассу подвинуть нельзя");
        } else if (!Double.isNaN(margin)) {
            text.append(String.format("Участок лежит свободно: до ближайшего ограничения "
                    + "ещё %.2f м запаса", margin));
        } else {
            text.append("Ограничений рядом нет");
        }

        if (!crossings.isEmpty()) {
            List<String> parts = new ArrayList<>();
            for (Crossing crossing : crossings) {
                parts.add(String.format("%s (Kспец %.2f)", crossing.getType(),
                        crossing.getKSpecial()));
            }
            text.append(". Проходит специальным проходом через ")
                    .append(String.join(", ", parts));
        }

        if (detour > 0.02) {
            text.append(String.format(". Длиннее прямой на %.0f %%: обход препятствий",
                    detour * 100));
        } else {
            text.append(". Идёт практически по прямой");
        }
        return text.toString();
    }

    private static double round(double value) {
        return Math.round(value * 100d) / 100d;
    }
}
