package me.bedwarshurts.leagueproximitychat.position;

import org.opencv.core.Core;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.Point;
import org.opencv.core.Rect;
import org.opencv.core.Scalar;
import org.opencv.core.Size;
import org.opencv.imgproc.Imgproc;

import java.util.ArrayList;
import java.util.List;

final class PathLineDetector {

    private static final Scalar WHITE_MIN = new Scalar(230, 230, 230);
    private static final Scalar WHITE_MAX = new Scalar(255, 255, 255);
    private static final double BOX_EDGE_RUN_FACTOR = 0.045;
    private static final double JOIN_PX = 6.0;
    private static final double START_MIN_TOLERANCE_PX = 3.0;
    private static final double START_TOLERANCE_PER_RADIUS = 0.4;
    private static final double LEAVES_ICON_MARGIN_PX = 4.0;
    private static final int LINE_WINDOW_MARGIN_PX = 8;

    record PathStart(Point point, IconCircle ring, double reach) {
    }

    record Segment(Point a, Point b) {
    }

    private PathLineDetector() {
    }

    static PathStart find(Mat minimap, List<IconCircle> allies) {
        if (allies.isEmpty()) return null;

        Mat white = new Mat();
        Mat boxEdges = new Mat();
        Mat vertical = new Mat();
        Mat lines = new Mat();
        try {
            Core.inRange(minimap, WHITE_MIN, WHITE_MAX, white);

            int run = Math.max(15, (int) Math.round(minimap.width() * BOX_EDGE_RUN_FACTOR));
            Mat horizontalKernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, new Size(run, 1));
            Mat verticalKernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, new Size(1, run));
            Imgproc.morphologyEx(white, boxEdges, Imgproc.MORPH_OPEN, horizontalKernel);
            Imgproc.morphologyEx(white, vertical, Imgproc.MORPH_OPEN, verticalKernel);
            horizontalKernel.release();
            verticalKernel.release();
            Core.bitwise_or(boxEdges, vertical, boxEdges);
            Core.subtract(white, boxEdges, white);

            Imgproc.HoughLinesP(white, lines, 1, Math.PI / 180, 6, 5, 2);
            List<Segment> segments = new ArrayList<>();
            for (int i = 0; i < lines.rows(); i++) {
                double[] l = lines.get(i, 0);
                segments.add(new Segment(new Point(l[0], l[1]), new Point(l[2], l[3])));
            }

            PathStart best = bestStart(segments, allies);
            if (DebugImages.enabled()) DebugImages.pathLine(minimap, white, segments, best);
            return best;
        } finally {
            white.release();
            boxEdges.release();
            vertical.release();
            lines.release();
        }
    }

    static boolean[] linePixels(Mat minimap, Point center, int radius, Rect crop) {
        boolean[] result = new boolean[crop.width * crop.height];
        int half = radius + LINE_WINDOW_MARGIN_PX;
        int x0 = Math.max(0, (int) Math.round(center.x) - half);
        int y0 = Math.max(0, (int) Math.round(center.y) - half);
        int x1 = Math.min(minimap.width(), (int) Math.round(center.x) + half + 1);
        int y1 = Math.min(minimap.height(), (int) Math.round(center.y) + half + 1);
        if (x1 - x0 < 3 || y1 - y0 < 3) return result;

        Mat window = new Mat(minimap, new Rect(x0, y0, x1 - x0, y1 - y0));
        Mat white = new Mat();
        Mat labels = new Mat();
        Mat lines = new Mat();
        Mat kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, new Size(3, 3));
        try {
            Core.inRange(window, WHITE_MIN, WHITE_MAX, white);
            int count = Imgproc.connectedComponents(white, labels, 8, CvType.CV_32S);
            if (count <= 1) return result;

            int w = labels.cols();
            int h = labels.rows();
            int[] label = new int[w * h];
            labels.get(0, 0, label);
            boolean[] leavesIcon = new boolean[count];
            double outside = radius + LEAVES_ICON_MARGIN_PX;
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    int l = label[y * w + x];
                    if (l > 0 && Math.hypot(x0 + x - center.x, y0 + y - center.y) > outside) leavesIcon[l] = true;
                }
            }

            byte[] mask = new byte[w * h];
            boolean any = false;
            for (int i = 0; i < mask.length; i++) {
                if (label[i] > 0 && leavesIcon[label[i]]) {
                    mask[i] = (byte) 255;
                    any = true;
                }
            }
            if (!any) return result;

            lines.create(h, w, CvType.CV_8UC1);
            lines.put(0, 0, mask);
            Imgproc.dilate(lines, lines, kernel);
            lines.get(0, 0, mask);
            for (int y = 0; y < crop.height; y++) {
                for (int x = 0; x < crop.width; x++) {
                    int wx = crop.x + x - x0;
                    int wy = crop.y + y - y0;
                    if (wx >= 0 && wy >= 0 && wx < w && wy < h && mask[wy * w + wx] != 0) result[y * crop.width + x] = true;
                }
            }
            return result;
        } finally {
            window.release();
            white.release();
            labels.release();
            lines.release();
            kernel.release();
        }
    }

    private static PathStart bestStart(List<Segment> segments, List<IconCircle> allies) {
        if (segments.isEmpty()) return null;

        int[] parent = new int[segments.size()];
        for (int i = 0; i < parent.length; i++) parent[i] = i;
        for (int i = 0; i < segments.size(); i++) {
            for (int j = i + 1; j < segments.size(); j++) {
                if (touches(segments.get(i), segments.get(j))) union(parent, i, j);
            }
        }

        PathStart best = null;
        double bestOffset = Double.MAX_VALUE;
        for (int i = 0; i < segments.size(); i++) {
            for (Point end : new Point[]{segments.get(i).a(), segments.get(i).b()}) {
                for (IconCircle ring : allies) {
                    double offset = distance(end, ring.center());
                    double tolerance = Math.max(START_MIN_TOLERANCE_PX, ring.radius() * START_TOLERANCE_PER_RADIUS);
                    if (offset > tolerance) continue;

                    double reach = 0;
                    int root = find(parent, i);
                    for (int k = 0; k < segments.size(); k++) {
                        if (find(parent, k) != root) continue;
                        reach = Math.max(reach, Math.max(distance(ring.center(), segments.get(k).a()),
                                distance(ring.center(), segments.get(k).b())));
                    }
                    if (reach < ring.radius() + LEAVES_ICON_MARGIN_PX) continue;

                    double normalized = offset / ring.radius();
                    if (normalized < bestOffset) {
                        bestOffset = normalized;
                        best = new PathStart(end, ring, reach);
                    }
                }
            }
        }
        return best;
    }

    private static boolean touches(Segment s, Segment t) {
        return pointToSegment(s.a(), t) <= JOIN_PX || pointToSegment(s.b(), t) <= JOIN_PX
                || pointToSegment(t.a(), s) <= JOIN_PX || pointToSegment(t.b(), s) <= JOIN_PX;
    }

    private static double pointToSegment(Point p, Segment s) {
        double dx = s.b().x - s.a().x;
        double dy = s.b().y - s.a().y;
        double lengthSq = dx * dx + dy * dy;
        double t = lengthSq == 0 ? 0 : Math.clamp(((p.x - s.a().x) * dx + (p.y - s.a().y) * dy) / lengthSq, 0, 1);
        return Math.hypot(p.x - (s.a().x + t * dx), p.y - (s.a().y + t * dy));
    }

    private static double distance(Point a, Point b) {
        return Math.hypot(a.x - b.x, a.y - b.y);
    }

    private static int find(int[] parent, int i) {
        while (parent[i] != i) {
            parent[i] = parent[parent[i]];
            i = parent[i];
        }
        return i;
    }

    private static void union(int[] parent, int a, int b) {
        parent[find(parent, a)] = find(parent, b);
    }
}
