package me.bedwarshurts.leagueproximitychat.utils;

import org.opencv.core.Point;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

/**
 * Pure geometry helpers for recovering champion circles from minimap blip contours:
 * least-squares circle fitting plus RANSAC multi-circle extraction.
 */
public final class MathUtils {

    private MathUtils() {
    }

    public record CircleFit(double cx, double cy, double radius, double residual) {
    }

    // RANSAC circle-extraction tuning (knobs for separating/validating overlapping arcs).
    private static final int RANSAC_MAX_CIRCLES = 4;      // most circles to extract from a single contour
    private static final int RANSAC_TRIALS = 80;          // random 3-point samples per circle
    private static final int RANSAC_MIN_INLIERS = 10;     // min points on a circle to accept it
    private static final double RANSAC_MIN_ARC_DEG = 120;  // inliers must span at least this arc (rejects spurious fits)
    private static final double RANSAC_MIN_ARC_DENSITY = 0.45; // min inliers per pixel of covered arc length

    /**
     * RANSAC multi-circle extraction: repeatedly find the circle (from random 3-point samples) with the
     * most inliers that also form a wide CONTIGUOUS arc, refine it on those points, remove them, and look
     * again. This pulls overlapping arcs out of one merged contour as separate circles while rejecting
     * spurious fits stitched through unrelated points. Deterministic seed → stable frame-to-frame.
     */
    public static List<CircleFit> extractCircles(Point[] pts, double minR, double maxR, double inlierTol) {
        List<CircleFit> found = new ArrayList<>();
        if (pts.length < RANSAC_MIN_INLIERS) return found;

        List<Point> remaining = new ArrayList<>(Arrays.asList(pts));
        Random rng = new Random(7);

        for (int circ = 0; circ < RANSAC_MAX_CIRCLES && remaining.size() >= RANSAC_MIN_INLIERS; circ++) {
            List<Point> bestInliers = null;
            for (int trial = 0; trial < RANSAC_TRIALS; trial++) {
                Point a = remaining.get(rng.nextInt(remaining.size()));
                Point b = remaining.get(rng.nextInt(remaining.size()));
                Point c = remaining.get(rng.nextInt(remaining.size()));
                double[] cc = circumcircle(a, b, c);
                if (cc == null || cc[2] < minR || cc[2] > maxR) continue;

                List<Point> inliers = new ArrayList<>();
                for (Point p : remaining) {
                    if (Math.abs(Math.hypot(p.x - cc[0], p.y - cc[1]) - cc[2]) < inlierTol) inliers.add(p);
                }
                if (inliers.size() < RANSAC_MIN_INLIERS) continue;
                // Reject circles whose inliers don't form a wide contiguous arc - the signature of a
                // spurious fit through unrelated parts of a messy multi-ring contour.
                double arcDeg = arcCoverageDeg(inliers, cc[0], cc[1]);
                if (arcDeg < RANSAC_MIN_ARC_DEG) continue;
                // A real ring boundary is a contiguous pixel chain (~0.8 points per pixel of arc);
                // a circle stitched through scattered glyph/dot pixels covers its arc sparsely.
                if (inliers.size() < Math.toRadians(arcDeg) * cc[2] * RANSAC_MIN_ARC_DENSITY) continue;
                if (bestInliers == null || inliers.size() > bestInliers.size()) bestInliers = inliers;
            }

            if (bestInliers == null || bestInliers.size() < RANSAC_MIN_INLIERS) break;
            CircleFit refined = fitCircle(bestInliers.toArray(new Point[0]));
            if (refined != null) found.add(refined);
            remaining.removeAll(bestInliers);
        }
        return found;
    }

    /**
     * Least-squares circle fit (Kåsa method): solves for the circle the points LIE ON, so a partial arc
     * reconstructs its full circle. Returns null if degenerate. residual = RMS distance of the points
     * from the fitted circle (low = a clean arc; high = points span multiple circles / noise).
     */
    public static CircleFit fitCircle(Point[] pts) {
        int n = pts.length;
        if (n < 5) return null;

        double Sx = 0, Sy = 0, Sxx = 0, Syy = 0, Sxy = 0, Sxz = 0, Syz = 0, Sz = 0;
        for (Point p : pts) {
            double x = p.x, y = p.y, z = x * x + y * y;
            Sx += x;
            Sy += y;
            Sxx += x * x;
            Syy += y * y;
            Sxy += x * y;
            Sxz += x * z;
            Syz += y * z;
            Sz += z;
        }

        // Solve [Sxx Sxy Sx; Sxy Syy Sy; Sx Sy n][D E F]^T = [-Sxz -Syz -Sz]^T (Cramer's rule).
        double det = det3(Sxx, Sxy, Sx, Sxy, Syy, Sy, Sx, Sy, n);
        if (Math.abs(det) < 1e-6) return null;
        double cD = det3(-Sxz, Sxy, Sx, -Syz, Syy, Sy, -Sz, Sy, n) / det;
        double cE = det3(Sxx, -Sxz, Sx, Sxy, -Syz, Sy, Sx, -Sz, n) / det;
        double cF = det3(Sxx, Sxy, -Sxz, Sxy, Syy, -Syz, Sx, Sy, -Sz) / det;

        double cx = -cD / 2.0, cy = -cE / 2.0;
        double r2 = (cD * cD + cE * cE) / 4.0 - cF;
        if (r2 <= 0) return null;
        double r = Math.sqrt(r2);

        double sumSq = 0;
        for (Point p : pts) {
            double e = Math.hypot(p.x - cx, p.y - cy) - r;
            sumSq += e * e;
        }
        return new CircleFit(cx, cy, r, Math.sqrt(sumSq / n));
    }

    /**
     * Angular span (degrees) the points cover around (cx, cy): 360 minus the largest gap between
     * consecutive angles. A real arc covers a wide contiguous span; scattered points leave a big gap.
     */
    private static double arcCoverageDeg(List<Point> pts, double cx, double cy) {
        int n = pts.size();
        if (n < 2) return 0;
        double[] ang = new double[n];
        for (int i = 0; i < n; i++) {
            ang[i] = Math.toDegrees(Math.atan2(pts.get(i).y - cy, pts.get(i).x - cx));
        }
        Arrays.sort(ang);
        double maxGap = (ang[0] + 360.0) - ang[n - 1];
        for (int i = 1; i < n; i++) maxGap = Math.max(maxGap, ang[i] - ang[i - 1]);
        return 360.0 - maxGap;
    }

    /**
     * Exact circle through three points (circumcircle); null if they're collinear.
     */
    private static double[] circumcircle(Point a, Point b, Point c) {
        double d = 2 * (a.x * (b.y - c.y) + b.x * (c.y - a.y) + c.x * (a.y - b.y));
        if (Math.abs(d) < 1e-6) return null;
        double a2 = a.x * a.x + a.y * a.y, b2 = b.x * b.x + b.y * b.y, c2 = c.x * c.x + c.y * c.y;
        double ux = (a2 * (b.y - c.y) + b2 * (c.y - a.y) + c2 * (a.y - b.y)) / d;
        double uy = (a2 * (c.x - b.x) + b2 * (a.x - c.x) + c2 * (b.x - a.x)) / d;
        return new double[]{ux, uy, Math.hypot(a.x - ux, a.y - uy)};
    }

    /**
     * 3x3 determinant.
     */
    private static double det3(double a, double b, double c,
                               double d, double e, double f,
                               double g, double h, double i) {
        return a * (e * i - f * h) - b * (d * i - f * g) + c * (d * h - e * g);
    }

    /**
     * Zero-mean normalized cross-correlation between two interleaved pixel buffers, computed only over
     * the pixels marked visible. Both buffers must be the same length (visible.length * channels).
     * Returns a score in [-1, 1] comparable to TM_CCOEFF_NORMED, or -1 if degenerate (no visible
     * pixels / zero variance).
     */
    public static double maskedZncc(byte[] a, byte[] b, boolean[] visible, int channels) {
        long count = 0;
        double sumA = 0, sumB = 0;
        for (int p = 0; p < visible.length; p++) {
            if (!visible[p]) continue;
            int base = p * channels;
            for (int c = 0; c < channels; c++) {
                sumA += (a[base + c] & 0xFF);
                sumB += (b[base + c] & 0xFF);
            }
            count++;
        }
        if (count == 0) return -1.0;

        double n = (double) count * channels;
        double meanA = sumA / n;
        double meanB = sumB / n;

        double num = 0, varA = 0, varB = 0;
        for (int p = 0; p < visible.length; p++) {
            if (!visible[p]) continue;
            int base = p * channels;
            for (int c = 0; c < channels; c++) {
                double va = (a[base + c] & 0xFF) - meanA;
                double vb = (b[base + c] & 0xFF) - meanB;
                num += va * vb;
                varA += va * va;
                varB += vb * vb;
            }
        }
        if (varA < 1e-9 || varB < 1e-9) return -1.0;
        return num / Math.sqrt(varA * varB);
    }
}
