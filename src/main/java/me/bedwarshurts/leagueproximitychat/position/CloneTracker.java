package me.bedwarshurts.leagueproximitychat.position;

import me.bedwarshurts.leagueproximitychat.managers.DebugManager;
import org.opencv.core.Mat;
import org.opencv.core.Point;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Predicate;

final class CloneTracker {

    private static final Map<String, Long> CLONE_LIFETIME_MS = Map.of(
            "shaco", 20_000L,
            "leblanc", 10_000L,
            "monkeyking", 6_000L,
            "wukong", 6_000L,
            "neeko", 6_000L);

    private static final double LOOKALIKE_MIN_SCORE = 0.55;
    private static final double COVERED_LOOKALIKE_MIN_SCORE = 0.40;
    private static final double STRONG_LOOKALIKE_SCORE = 0.70;
    private static final double GATE_DASH_PER_MAP = 0.03;
    private static final double GATE_SPEED_PER_MAP_PER_SECOND = 0.04;
    private static final double GATE_MAX_PER_MAP = 0.12;
    private static final double MAX_SPEED_PER_MAP_PER_SECOND = 0.05;
    private static final double VELOCITY_SMOOTHING = 0.5;
    private static final long VANISH_MS = 1200;
    private static final long COAST_MS = 1000;
    private static final double HIDDEN_MARGIN_PX = 2.0;
    private static final double HEALTH_BAR_MAX_PERCENT = 5.0;
    private static final double HEALTH_BAR_MARGIN_PERCENT = 3.0;
    private static final int CAMERA_CONFIRM_FRAMES = 3;
    private static final double CAMERA_INNER_HALF_WIDTH = 0.35;
    private static final double CAMERA_INNER_TOP = 0.30;
    private static final double CAMERA_INNER_BOTTOM = 0.20;

    record Frame(Mat minimap, int mapSize, List<IconCircle> allies, List<IconCircle> enemies,
                 List<IconMatcher.RingScore> scores, PathLineDetector.PathStart path,
                 boolean healthBarOnScreen, boolean healthBarProjected, float hpX, float hpY, CameraBox camera) {
    }

    private static final class Track {
        Point pos;
        Point seenPos;
        int radius;
        double vx;
        double vy;
        long seenMs;
        IconCircle ring;

        Track(IconCircle ring, long now) {
            this.ring = ring;
            this.pos = ring.center();
            this.seenPos = ring.center();
            this.radius = ring.radius();
            this.seenMs = now;
        }
    }

    private final String championName;
    private final long lifetimeMs;
    private Track first;
    private Track second;
    private Track you;
    private String reason = "";
    private long startedMs;
    private long lastUpdateMs;
    private Track cameraSuspect;
    private int cameraStreak;
    private Point endedAt;

    private CloneTracker(String championName, long lifetimeMs) {
        this.championName = championName;
        this.lifetimeMs = lifetimeMs;
    }

    static CloneTracker forChampion(String championName) {
        if (championName == null) return null;
        Long lifetime = CLONE_LIFETIME_MS.get(championName.toLowerCase(Locale.ROOT));
        return lifetime == null ? null : new CloneTracker(championName, lifetime);
    }

    boolean active() {
        return first != null;
    }

    boolean youKnown() {
        return you != null;
    }

    Point yourPoint() {
        return you == null ? null : you.pos;
    }

    Point takeEndPoint() {
        Point point = endedAt;
        endedAt = null;
        return point;
    }

    void reset() {
        first = null;
        second = null;
        you = null;
        cameraSuspect = null;
        cameraStreak = 0;
    }

    boolean tryStart(Frame frame, Point lastYou, double stepPx, Predicate<Point> anotherChampion, long now) {
        IconMatcher.RingScore continuation = null;
        double continuationDist = Double.MAX_VALUE;
        for (IconMatcher.RingScore s : frame.scores()) {
            if (!looksLikeYou(s)) continue;
            double d = distance(s.ring().center(), lastYou);
            if (d > stepPx || anotherChampion.test(s.ring().center())) continue;
            if (d < continuationDist) {
                continuationDist = d;
                continuation = s;
            }
        }
        if (continuation == null) return false;

        IconMatcher.RingScore other = null;
        for (IconMatcher.RingScore s : frame.scores()) {
            if (s == continuation || s.rawScore() < STRONG_LOOKALIKE_SCORE) continue;
            if (other == null || s.rawScore() > other.rawScore()) other = s;
        }
        if (other == null) return false;

        reset();
        endedAt = null;
        first = new Track(continuation.ring(), now);
        second = new Track(other.ring(), now);
        startedMs = now;
        lastUpdateMs = now;
        if (DebugManager.isENABLED()) System.out.printf("[clone] A second %s icon appeared - following both icons until it is clear which one is you.%n",
                championName);

        weighEvidence(frame, now);
        if (active()) debugImage(frame);
        return true;
    }

    void update(Frame frame, long now) {
        if (!active()) return;
        double dt = Math.clamp((now - lastUpdateMs) / 1000.0, 0.05, 2.0);
        lastUpdateMs = now;
        if (now - startedMs > lifetimeMs) {
            end("it lasted longer than a clone can", frame.mapSize());
            return;
        }

        List<IconCircle> candidates = new ArrayList<>();
        for (IconMatcher.RingScore s : frame.scores()) {
            if (looksLikeYou(s)) candidates.add(s.ring());
        }

        int mapSize = frame.mapSize();
        double gateFirst = gate(first, dt, now, mapSize);
        double gateSecond = gate(second, dt, now, mapSize);
        Point predFirst = predict(first, dt);
        Point predSecond = predict(second, dt);
        IconCircle bestFirst = null;
        IconCircle bestSecond = null;
        double bestCost = gateFirst + gateSecond;
        for (int i = -1; i < candidates.size(); i++) {
            IconCircle a = i < 0 ? null : candidates.get(i);
            double costA = a == null ? gateFirst : distance(predFirst, a.center());
            if (costA > gateFirst) continue;
            for (int j = -1; j < candidates.size(); j++) {
                if (j >= 0 && j == i) continue;
                IconCircle b = j < 0 ? null : candidates.get(j);
                double costB = b == null ? gateSecond : distance(predSecond, b.center());
                if (costB > gateSecond) continue;
                if (costA + costB < bestCost) {
                    bestCost = costA + costB;
                    bestFirst = a;
                    bestSecond = b;
                }
            }
        }
        follow(first, bestFirst, dt, now, mapSize);
        follow(second, bestSecond, dt, now, mapSize);

        weighEvidence(frame, now);
        if (active()) debugImage(frame);
    }

    private void weighEvidence(Frame frame, long now) {
        int mapSize = frame.mapSize();

        if (frame.healthBarProjected()) {
            double dFirst = percentDistance(first.pos, frame.hpX(), frame.hpY(), mapSize);
            double dSecond = percentDistance(second.pos, frame.hpX(), frame.hpY(), mapSize);
            double near = Math.min(dFirst, dSecond);
            if (near <= HEALTH_BAR_MAX_PERCENT && Math.abs(dFirst - dSecond) >= HEALTH_BAR_MARGIN_PERCENT) {
                decide(dFirst <= dSecond ? first : second, "your health bar is there", mapSize);
            }
        }

        cameraEvidence(frame, mapSize);

        if (first.ring != null && second.ring != null && pairIsAlone(frame)) {
            int top = MinimapRingDetector.drawnOnTop(frame.minimap(), first.ring, second.ring);
            if (top > 0) decide(first, "it is drawn on top of the clone", mapSize);
            else if (top < 0) decide(second, "it is drawn on top of the clone", mapSize);
        }

        if (frame.path() != null) {
            if (first.ring != null && first.ring == frame.path().ring()) decide(first, "your path line starts there", mapSize);
            else if (second.ring != null && second.ring == frame.path().ring()) decide(second, "your path line starts there", mapSize);
        }

        for (Track t : List.of(first, second)) {
            if (t.ring != null || now - t.seenMs <= VANISH_MS || hidden(t, frame)) continue;
            Track remaining = t == first ? second : first;
            if (you != remaining) {
                you = remaining;
                reason = "the other icon disappeared";
            }
            end("the clone disappeared", mapSize);
            return;
        }
    }

    private void cameraEvidence(Frame frame, int mapSize) {
        Track inside = null;
        if (!frame.healthBarOnScreen() && frame.camera() != null) {
            boolean firstIn = first.ring != null && onScreen(first.pos, frame.camera());
            boolean secondIn = second.ring != null && onScreen(second.pos, frame.camera());
            if (firstIn != secondIn) inside = firstIn ? first : second;
        }
        if (inside == null) {
            cameraSuspect = null;
            cameraStreak = 0;
            return;
        }
        cameraStreak = inside == cameraSuspect ? cameraStreak + 1 : 1;
        cameraSuspect = inside;
        if (cameraStreak >= CAMERA_CONFIRM_FRAMES) {
            decide(inside == first ? second : first, "the clone is on your screen without your health bar", mapSize);
        }
    }

    private void decide(Track t, String why, int mapSize) {
        if (you == t) return;
        you = t;
        reason = why;
        if (DebugManager.isENABLED()) System.out.printf("[clone] You are the icon at (%.1f, %.1f) - %s.%n",
                MapCoordinates.percentX(t.pos.x, mapSize), MapCoordinates.percentY(t.pos.y, mapSize), why);
    }

    private void end(String why, int mapSize) {
        endedAt = you != null ? you.pos : null;
        if (DebugManager.isENABLED()) {
            String where = endedAt == null ? "" : String.format(" - you are at (%.1f, %.1f)",
                    MapCoordinates.percentX(endedAt.x, mapSize), MapCoordinates.percentY(endedAt.y, mapSize));
            System.out.printf("[clone] Stopped following the clone (%s)%s.%n", why, where);
        }
        reset();
    }

    private void follow(Track t, IconCircle ring, double dt, long now, int mapSize) {
        t.ring = ring;
        if (ring == null) {
            if (now - t.seenMs <= COAST_MS) {
                t.pos = predict(t, dt);
            } else {
                t.vx = 0;
                t.vy = 0;
            }
            return;
        }
        double seconds = Math.max(dt, (now - t.seenMs) / 1000.0);
        double vx = (ring.center().x - t.seenPos.x) / seconds;
        double vy = (ring.center().y - t.seenPos.y) / seconds;
        t.vx = VELOCITY_SMOOTHING * t.vx + (1 - VELOCITY_SMOOTHING) * vx;
        t.vy = VELOCITY_SMOOTHING * t.vy + (1 - VELOCITY_SMOOTHING) * vy;
        double max = MAX_SPEED_PER_MAP_PER_SECOND * mapSize;
        double speed = Math.hypot(t.vx, t.vy);
        if (speed > max) {
            t.vx *= max / speed;
            t.vy *= max / speed;
        }
        t.pos = ring.center();
        t.seenPos = ring.center();
        t.radius = ring.radius();
        t.seenMs = now;
    }

    private boolean pairIsAlone(Frame frame) {
        for (List<IconCircle> rings : List.of(frame.allies(), frame.enemies())) {
            for (IconCircle r : rings) {
                if (r == first.ring || r == second.ring) continue;
                if (overlaps(r, first.ring) || overlaps(r, second.ring)) return false;
            }
        }
        return true;
    }

    private static boolean hidden(Track t, Frame frame) {
        for (List<IconCircle> rings : List.of(frame.allies(), frame.enemies())) {
            for (IconCircle r : rings) {
                if (distance(r.center(), t.pos) <= r.radius() + HIDDEN_MARGIN_PX) return true;
            }
        }
        return false;
    }

    private static boolean onScreen(Point p, CameraBox camera) {
        double dx = p.x - camera.center().x;
        double dy = p.y - camera.center().y;
        return Math.abs(dx) <= camera.width() * CAMERA_INNER_HALF_WIDTH
                && dy >= -camera.height() * CAMERA_INNER_TOP
                && dy <= camera.height() * CAMERA_INNER_BOTTOM;
    }

    private static boolean looksLikeYou(IconMatcher.RingScore s) {
        return s.rawScore() >= (s.covered() ? COVERED_LOOKALIKE_MIN_SCORE : LOOKALIKE_MIN_SCORE);
    }

    private static double gate(Track t, double dt, long now, int mapSize) {
        double seconds = Math.max(dt, (now - t.seenMs) / 1000.0);
        double reach = t.radius + GATE_DASH_PER_MAP * mapSize + GATE_SPEED_PER_MAP_PER_SECOND * mapSize * seconds;
        return Math.min(reach, t.radius + GATE_MAX_PER_MAP * mapSize);
    }

    private static Point predict(Track t, double dt) {
        return new Point(t.pos.x + t.vx * dt, t.pos.y + t.vy * dt);
    }

    private static boolean overlaps(IconCircle a, IconCircle b) {
        return distance(a.center(), b.center()) < a.radius() + b.radius();
    }

    private static double percentDistance(Point p, float x, float y, int mapSize) {
        return Math.hypot(MapCoordinates.percentX(p.x, mapSize) - x, MapCoordinates.percentY(p.y, mapSize) - y);
    }

    private static double distance(Point a, Point b) {
        return Math.hypot(a.x - b.x, a.y - b.y);
    }

    private void debugImage(Frame frame) {
        if (!DebugImages.enabled()) return;
        DebugImages.cloneTracks(frame.minimap(), first.pos, first.radius, second.pos, second.radius,
                you == null ? 0 : you == first ? 1 : 2,
                you == null ? "clone: waiting for proof" : "clone: found",
                you == null ? "following both icons" : reason);
    }
}
