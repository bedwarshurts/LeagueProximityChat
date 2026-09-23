package me.bedwarshurts.leagueproximitychat.position;

import me.bedwarshurts.leagueproximitychat.utils.MathUtils;
import org.opencv.core.Core;
import org.opencv.core.Mat;
import org.opencv.core.MatOfPoint;
import org.opencv.core.Point;
import org.opencv.core.Scalar;
import org.opencv.core.Size;
import org.opencv.imgproc.Imgproc;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

final class MinimapRingDetector {

    private static final double BLIP_RADIUS_LOCKED_MIN = 0.7;
    private static final double BLIP_RADIUS_LOCKED_MAX = 1.45;

    private static final double ALLY_RING_CLOSE_FACTOR = 0.01;
    private static final double ALLY_PEAK_DEDUP_FACTOR = 0.45;
    private static final double ALLY_MIN_RADIUS_FACTOR = 0.025;
    private static final double ALLY_MAX_RADIUS_FACTOR = 0.10;

    private static final double ENEMY_RING_MIN_RADIUS_RATIO = 0.85;
    private static final double EXPECTED_ICON_RADIUS_FACTOR = 0.042;
    private static final double RING_INTERIOR_FACTOR = 0.65;
    private static final double STRUCTURE_MIN_RED_FRACTION = 0.15;
    private static final double STRUCTURE_MAX_COLOUR_FRACTION = 0.10;

    private MinimapRingDetector() {
    }

    static List<IconCircle> findAllies(Mat minimap, int lockedBlipRadius) {
        List<IconCircle> centers;
        Mat hsv = new Mat();
        Mat mask = new Mat();

        try {
            Imgproc.cvtColor(minimap, hsv, Imgproc.COLOR_BGR2HSV);
            Core.inRange(hsv, new Scalar(80, 140, 200), new Scalar(115, 255, 255), mask);

            if (DebugImages.enabled()) {
                DebugImages.write("debug_ally_mask.png", mask);
            }

            centers = circlesFromRingMask(minimap, mask, lockedBlipRadius, "debug_ransac_raw.png");

            if (DebugImages.enabled()) {
                Mat debugDrawMap = minimap.clone();
                for (IconCircle c : centers) {
                    Imgproc.circle(debugDrawMap, c.center(), c.radius(), new Scalar(0, 255, 0), 2);
                    Imgproc.circle(debugDrawMap, c.center(), 2, new Scalar(0, 0, 255), -1);
                }
                DebugImages.write("debug_ally_centers.png", debugDrawMap);
                debugDrawMap.release();
            }

        } finally {
            hsv.release();
            mask.release();
        }

        return centers;
    }

    static List<IconCircle> findEnemies(Mat minimap, int lockedBlipRadius) {
        List<IconCircle> centers;
        Mat mask = new Mat();

        try {
            enemyRedMask(minimap, mask);
            centers = circlesFromRingMask(minimap, mask, lockedBlipRadius, null);

            if (DebugImages.enabled()) {
                List<IconCircle> champions = championRingsOnly(minimap, centers, lockedBlipRadius);
                Mat debugDrawMap = minimap.clone();
                for (IconCircle c : centers) {
                    if (champions.contains(c)) {
                        Imgproc.circle(debugDrawMap, c.center(), c.radius(), new Scalar(0, 0, 255), 2);
                    } else {
                        Imgproc.circle(debugDrawMap, c.center(), c.radius(), new Scalar(140, 140, 140), 1);
                    }
                }
                DebugImages.write("debug_enemy_circles.png", debugDrawMap);
                debugDrawMap.release();
            }
        } finally {
            mask.release();
        }

        return centers;
    }

    static List<IconCircle> championRingsOnly(Mat minimap, List<IconCircle> rings, int lockedBlipRadius) {
        List<IconCircle> champions = new ArrayList<>();
        double expectedRadius = lockedBlipRadius > 0 ? lockedBlipRadius : minimap.width() * EXPECTED_ICON_RADIUS_FACTOR;
        Mat hsv = new Mat();
        try {
            Imgproc.cvtColor(minimap, hsv, Imgproc.COLOR_BGR2HSV);
            for (IconCircle ring : rings) {
                if (ring.radius() < expectedRadius * ENEMY_RING_MIN_RADIUS_RATIO) continue;
                if (looksLikeStructure(hsv, ring)) continue;
                champions.add(ring);
            }
        } finally {
            hsv.release();
        }
        return champions;
    }

    static void enemyRedMask(Mat bgr, Mat out) {
        Mat hsv = new Mat();
        Mat lower = new Mat();
        Mat upper = new Mat();
        try {
            Imgproc.cvtColor(bgr, hsv, Imgproc.COLOR_BGR2HSV);
            Core.inRange(hsv, new Scalar(0, 110, 120), new Scalar(9, 255, 255), lower);
            Core.inRange(hsv, new Scalar(173, 110, 120), new Scalar(179, 255, 255), upper);
            Core.bitwise_or(lower, upper, out);
        } finally {
            hsv.release();
            lower.release();
            upper.release();
        }
    }

    private static boolean looksLikeStructure(Mat hsv, IconCircle ring) {
        int inner = (int) Math.round(ring.radius() * RING_INTERIOR_FACTOR);
        int cx = (int) Math.round(ring.center().x);
        int cy = (int) Math.round(ring.center().y);
        int total = 0;
        int red = 0;
        int colour = 0;
        byte[] px = new byte[3];
        for (int dy = -inner; dy <= inner; dy++) {
            for (int dx = -inner; dx <= inner; dx++) {
                if (dx * dx + dy * dy > inner * inner) continue;
                int x = cx + dx;
                int y = cy + dy;
                if (x < 0 || y < 0 || x >= hsv.cols() || y >= hsv.rows()) continue;
                hsv.get(y, x, px);
                int h = px[0] & 0xFF;
                int s = px[1] & 0xFF;
                int v = px[2] & 0xFF;
                total++;
                if ((h <= 9 || h >= 173) && s >= 110 && v >= 120) {
                    red++;
                } else if (s > 60 && v > 60 && h > 12 && h < 170) {
                    colour++;
                }
            }
        }
        if (total == 0) return false;
        return red >= total * STRUCTURE_MIN_RED_FRACTION && colour <= total * STRUCTURE_MAX_COLOUR_FRACTION;
    }

    private static List<IconCircle> circlesFromRingMask(Mat minimap, Mat mask, int lockedBlipRadius, String rawFitsDebugFile) {
        List<IconCircle> centers = new ArrayList<>();
        Mat closed = new Mat();
        Mat hierarchy = new Mat();
        List<MatOfPoint> contours = new ArrayList<>();

        try {
            int closeK = Math.max(3, (int) Math.round(minimap.width() * ALLY_RING_CLOSE_FACTOR));
            Mat closeKernel = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, new Size(closeK, closeK));
            Imgproc.morphologyEx(mask, closed, Imgproc.MORPH_CLOSE, closeKernel);
            closeKernel.release();

            Imgproc.findContours(closed, contours, hierarchy, Imgproc.RETR_LIST, Imgproc.CHAIN_APPROX_NONE);

            double minR = minimap.width() * ALLY_MIN_RADIUS_FACTOR;
            double maxR = minimap.width() * ALLY_MAX_RADIUS_FACTOR;

            if (lockedBlipRadius > 0) {
                minR = Math.max(minR, lockedBlipRadius * BLIP_RADIUS_LOCKED_MIN);
                maxR = Math.min(maxR, lockedBlipRadius * BLIP_RADIUS_LOCKED_MAX);
            }
            double inlierTol = Math.max(2.5, minimap.width() * 0.005);
            List<MathUtils.CircleFit> rawFits = new ArrayList<>();
            for (MatOfPoint c : contours) {
                rawFits.addAll(MathUtils.extractCircles(c.toArray(), minR, maxR, inlierTol));
            }

            if (rawFitsDebugFile != null && DebugImages.enabled()) {
                Mat raw = minimap.clone();
                for (MathUtils.CircleFit f : rawFits) {
                    Imgproc.circle(raw, new Point(f.cx(), f.cy()), (int) Math.round(f.radius()),
                            new Scalar(0, 255, 255), 1);
                }
                DebugImages.write(rawFitsDebugFile, raw);
                raw.release();
            }

            rawFits.sort(Comparator.comparingDouble(MathUtils.CircleFit::residual));

            for (MathUtils.CircleFit fit : rawFits) {
                int cr = (int) Math.round(fit.radius());
                if (cr < minR || cr > maxR) continue;
                if (fit.residual() > fit.radius() * 0.25) continue;
                int cx = (int) Math.round(fit.cx());
                int cy = (int) Math.round(fit.cy());
                if (cx < 0 || cy < 0 || cx >= minimap.width() || cy >= minimap.height()) continue;
                Point ctr = new Point(cx, cy);
                if (isCovered(ctr, cr, centers)) continue;
                if (swallowsMultipleCenters(ctr, cr, centers)) continue;
                centers.add(new IconCircle(ctr, cr));
            }
        } finally {
            closed.release();
            hierarchy.release();
            for (MatOfPoint c : contours) c.release();
        }

        return centers;
    }

    private static boolean isCovered(Point center, int radius, List<IconCircle> kept) {
        for (IconCircle k : kept) {
            double sep = Math.hypot(center.x - k.center().x, center.y - k.center().y);
            if (sep < Math.min(k.radius(), radius) * ALLY_PEAK_DEDUP_FACTOR) return true;
        }
        return false;
    }

    private static boolean swallowsMultipleCenters(Point center, int radius, List<IconCircle> kept) {
        int contained = 0;
        for (IconCircle k : kept) {
            double sep = Math.hypot(center.x - k.center().x, center.y - k.center().y);
            if (sep < radius * 0.8) contained++;
        }
        return contained >= 2;
    }
}
