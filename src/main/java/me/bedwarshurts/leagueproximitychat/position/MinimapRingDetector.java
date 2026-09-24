package me.bedwarshurts.leagueproximitychat.position;

import me.bedwarshurts.leagueproximitychat.utils.MathUtils;
import org.opencv.core.Core;
import org.opencv.core.Mat;
import org.opencv.core.MatOfPoint;
import org.opencv.core.Point;
import org.opencv.core.Rect;
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

    private static final int RING_SAMPLES = 64;
    private static final double OVERLAP_MIN_CENTER_FACTOR = 0.25;
    private static final double OVERLAP_OWN_RING_BAND_PX = 2.5;
    private static final double OVERLAP_MIN_RING_FRACTION = 0.65;
    private static final double BROKEN_RING_MIN_COVERAGE = 0.65;
    private static final double BROKEN_RING_KNOWN_FRACTION = 0.8;
    private static final double BROKEN_RING_REFIT_BAND_PX = 3.0;
    private static final double[] RING_SAMPLE_COS = new double[RING_SAMPLES];
    private static final double[] RING_SAMPLE_SIN = new double[RING_SAMPLES];

    static {
        for (int s = 0; s < RING_SAMPLES; s++) {
            double angle = 2 * Math.PI * s / RING_SAMPLES;
            RING_SAMPLE_COS[s] = Math.cos(angle);
            RING_SAMPLE_SIN[s] = Math.sin(angle);
        }
    }

    private MinimapRingDetector() {
    }

    static List<IconCircle> findAllies(Mat minimap, int lockedBlipRadius) {
        List<IconCircle> centers;
        Mat mask = new Mat();

        try {
            allyRingMask(minimap, mask);

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
            mask.release();
        }

        return centers;
    }

    static void allyRingMask(Mat bgr, Mat out) {
        Mat hsv = new Mat();
        try {
            Imgproc.cvtColor(bgr, hsv, Imgproc.COLOR_BGR2HSV);
            Core.inRange(hsv, new Scalar(80, 140, 200), new Scalar(115, 255, 255), out);
        } finally {
            hsv.release();
        }
    }

    static boolean overlappedByAllyRing(Mat minimap, IconCircle target) {
        int r = target.radius();
        int reach = 3 * r + 2;
        int x0 = (int) Math.max(0, target.center().x - reach);
        int y0 = (int) Math.max(0, target.center().y - reach);
        int w = (int) Math.min(minimap.width(), target.center().x + reach) - x0;
        int h = (int) Math.min(minimap.height(), target.center().y + reach) - y0;
        if (w <= 0 || h <= 0) return false;

        byte[] ring = new byte[w * h];
        Mat roi = new Mat(minimap, new Rect(x0, y0, w, h));
        Mat mask = new Mat();
        Mat kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, new Size(3, 3));
        try {
            allyRingMask(roi, mask);
            Imgproc.dilate(mask, mask, kernel);
            mask.get(0, 0, ring);
        } finally {
            roi.release();
            mask.release();
            kernel.release();
        }

        double tx = target.center().x - x0;
        double ty = target.center().y - y0;
        double ownBandInner = Math.pow(Math.max(0, r - OVERLAP_OWN_RING_BAND_PX), 2);
        double ownBandOuter = Math.pow(r + OVERLAP_OWN_RING_BAND_PX, 2);
        double minCenterDist = Math.pow(r * OVERLAP_MIN_CENTER_FACTOR, 2);
        double maxCenterDist = Math.pow(2 * r, 2);

        for (int dy = -2 * r; dy <= 2 * r; dy++) {
            for (int dx = -2 * r; dx <= 2 * r; dx++) {
                int centerDist = dx * dx + dy * dy;
                if (centerDist < minCenterDist || centerDist >= maxCenterDist) continue;

                int hits = 0;
                int valid = 0;
                for (int s = 0; s < RING_SAMPLES; s++) {
                    double ox = dx + r * RING_SAMPLE_COS[s];
                    double oy = dy + r * RING_SAMPLE_SIN[s];
                    double fromTarget = ox * ox + oy * oy;
                    if (fromTarget >= ownBandInner && fromTarget <= ownBandOuter) continue;
                    int ix = (int) Math.round(tx + ox);
                    int iy = (int) Math.round(ty + oy);
                    if (ix < 0 || iy < 0 || ix >= w || iy >= h) continue;
                    valid++;
                    if (ring[iy * w + ix] != 0) hits++;
                }
                if (valid >= RING_SAMPLES / 4 && hits >= valid * OVERLAP_MIN_RING_FRACTION) return true;
            }
        }
        return false;
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

            addBrokenRings(mask, centers, minR, maxR, inlierTol);
        } finally {
            closed.release();
            hierarchy.release();
            for (MatOfPoint c : contours) c.release();
        }

        return centers;
    }

    private static void addBrokenRings(Mat mask, List<IconCircle> centers, double minR, double maxR, double inlierTol) {
        int w = mask.cols();
        int h = mask.rows();
        byte[] rawPixels = new byte[w * h];
        byte[] grownPixels = new byte[w * h];
        Mat grown = new Mat();
        Mat hierarchy = new Mat();
        Mat kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, new Size(3, 3));
        List<MatOfPoint> contours = new ArrayList<>();
        List<MathUtils.CircleFit> fits = new ArrayList<>();

        try {
            mask.get(0, 0, rawPixels);
            Imgproc.dilate(mask, grown, kernel);
            grown.get(0, 0, grownPixels);
            Imgproc.findContours(grown, contours, hierarchy, Imgproc.RETR_LIST, Imgproc.CHAIN_APPROX_NONE);

            for (MatOfPoint c : contours) {
                Rect box = Imgproc.boundingRect(c);
                if (Math.max(box.width, box.height) < minR * 1.5) continue;
                Point[] pts = c.toArray();
                if (mostlyOnKnownRings(pts, centers, inlierTol)) continue;
                fits.addAll(MathUtils.extractCircles(pts, minR, maxR, inlierTol));
            }
        } finally {
            grown.release();
            hierarchy.release();
            kernel.release();
            for (MatOfPoint c : contours) c.release();
        }

        fits.sort(Comparator.comparingDouble(MathUtils.CircleFit::residual));
        for (MathUtils.CircleFit fit : fits) {
            if (fit.residual() > fit.radius() * 0.25) continue;
            MathUtils.CircleFit ring = refitOnMaskPixels(rawPixels, w, h, fit);
            if (ring == null) continue;
            int cr = (int) Math.round(ring.radius());
            if (cr < minR || cr > maxR) continue;
            int cx = (int) Math.round(ring.cx());
            int cy = (int) Math.round(ring.cy());
            if (cx < 0 || cy < 0 || cx >= w || cy >= h) continue;
            Point ctr = new Point(cx, cy);
            if (isCovered(ctr, cr, centers)) continue;
            if (swallowsMultipleCenters(ctr, cr, centers)) continue;
            if (ringCoverage(grownPixels, w, h, cx, cy, cr) < BROKEN_RING_MIN_COVERAGE) continue;
            centers.add(new IconCircle(ctr, cr));
        }
    }

    private static boolean mostlyOnKnownRings(Point[] pts, List<IconCircle> centers, double inlierTol) {
        if (centers.isEmpty()) return false;
        int known = 0;
        for (Point p : pts) {
            for (IconCircle c : centers) {
                if (Math.abs(Math.hypot(p.x - c.center().x, p.y - c.center().y) - c.radius()) < inlierTol + 1) {
                    known++;
                    break;
                }
            }
        }
        return known >= pts.length * BROKEN_RING_KNOWN_FRACTION;
    }

    private static MathUtils.CircleFit refitOnMaskPixels(byte[] pixels, int w, int h, MathUtils.CircleFit fit) {
        List<Point> near = new ArrayList<>();
        int reach = (int) Math.ceil(fit.radius() + BROKEN_RING_REFIT_BAND_PX);
        int x0 = Math.max(0, (int) Math.floor(fit.cx()) - reach);
        int x1 = Math.min(w - 1, (int) Math.ceil(fit.cx()) + reach);
        int y0 = Math.max(0, (int) Math.floor(fit.cy()) - reach);
        int y1 = Math.min(h - 1, (int) Math.ceil(fit.cy()) + reach);
        for (int y = y0; y <= y1; y++) {
            for (int x = x0; x <= x1; x++) {
                if (pixels[y * w + x] == 0) continue;
                double off = Math.hypot(x - fit.cx(), y - fit.cy()) - fit.radius();
                if (Math.abs(off) <= BROKEN_RING_REFIT_BAND_PX) near.add(new Point(x, y));
            }
        }
        return MathUtils.fitCircle(near.toArray(new Point[0]));
    }

    private static double ringCoverage(byte[] pixels, int w, int h, double cx, double cy, int r) {
        int hits = 0;
        int valid = 0;
        for (int s = 0; s < RING_SAMPLES; s++) {
            int ix = (int) Math.round(cx + r * RING_SAMPLE_COS[s]);
            int iy = (int) Math.round(cy + r * RING_SAMPLE_SIN[s]);
            if (ix < 0 || iy < 0 || ix >= w || iy >= h) continue;
            valid++;
            if (pixels[iy * w + ix] != 0) hits++;
        }
        return valid < RING_SAMPLES / 4 ? 0.0 : hits / (double) valid;
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
