package me.bedwarshurts.leagueproximitychat.position;

import org.opencv.core.Core;
import org.opencv.core.Mat;
import org.opencv.core.Rect;
import org.opencv.core.Size;
import org.opencv.imgproc.Imgproc;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class ScoreboardLocator {

    private static final double REGION_LEFT = 0.15;
    private static final double REGION_TOP = 0.12;
    private static final double REGION_WIDTH = 0.70;
    private static final double REGION_HEIGHT = 0.68;
    private static final double MIN_RADIUS_PER_HEIGHT = 0.012;
    private static final double MAX_RADIUS_PER_HEIGHT = 0.045;
    private static final double MIN_DIST_PER_HEIGHT = 0.03;
    private static final double HOUGH_EDGE_THRESHOLD = 120;
    private static final double HOUGH_VOTES = 40;
    private static final double RADIUS_TOLERANCE = 0.25;
    private static final double SAME_COLUMN_PER_RADIUS = 0.5;
    private static final double SAME_ROW_PER_RADIUS = 0.4;
    private static final double MIN_PITCH_PER_RADIUS = 2.2;
    private static final double MAX_PITCH_PER_RADIUS = 3.8;
    private static final double PITCH_SLACK = 0.15;
    private static final double MIN_COLUMN_GAP_PER_RADIUS = 12;
    private static final double MAX_COLUMN_GAP_PER_RADIUS = 30;
    private static final double PORTRAIT_CROP_PER_RADIUS = 1.4;
    private static final int MATCH_SIZE = 48;
    private static final double[] ICON_CROPS = {0.70, 0.80, 0.90};
    private static final double ANCHOR_MARGIN_PER_RADIUS = 1.3;
    private static final int TRACK_DOWNSCALE = 2;
    private static final double GAP_SCAN_FROM = 0.8;
    private static final double GAP_SCAN_TO = 3.6;
    private static final double GAP_SCAN_HALF_HEIGHT = 0.9;
    private static final double GAP_MIN_WIDTH = 1.0;
    private static final double DEFAULT_GAP_PER_RADIUS = 1.97;
    private static final int GAP_BUSY_LEVEL = 80;
    private static final double LOOK_LEFT_PER_RADIUS = 3.15;
    private static final double LOOK_RIGHT_PER_RADIUS = 1.1;
    private static final double LOOK_HALF_HEIGHT_PER_RADIUS = 1.05;
    private static final double LOOK_SLACK_PER_RADIUS = 0.2;
    private static final double LOOK_SPLIT_PER_RADIUS = 1.0;
    private static final double LOOK_SAME = 0.6;
    private static final double LOOK_MOVE_GAIN = 0.25;
    private static final int LOOK_MAX_ROWS = 8;
    private static final double AREA_LEFT_PER_RADIUS = 3.6;
    private static final double AREA_SIDE_PER_RADIUS = 1.6;

    public record Portrait(double x, double y, double radius) {
    }

    public record Layout(List<Portrait> allies, List<Portrait> enemies, double radius) {
    }

    public record Anchor(Mat template, Rect rect) {
        public void release() {
            template.release();
        }
    }

    public record Fix(int x, int y, double score, double stayScore) {
    }

    private record Circle(double x, double y, double r) {
    }

    private record Column(double x, List<Circle> circles) {
    }

    private ScoreboardLocator() {
    }

    public static Layout find(Mat frame) {
        int w = frame.cols();
        int h = frame.rows();
        Rect region = new Rect((int) (w * REGION_LEFT), (int) (h * REGION_TOP), (int) (w * REGION_WIDTH), (int) (h * REGION_HEIGHT));
        Mat regionView = new Mat(frame, region);
        List<Circle> circles = houghCircles(regionView, h);
        regionView.release();
        for (int i = 0; i < circles.size(); i++) {
            Circle c = circles.get(i);
            circles.set(i, new Circle(c.x() + region.x, c.y() + region.y, c.r()));
        }
        if (circles.size() < 2) return null;

        double radius = median(circles.stream().map(Circle::r).toList());
        List<Circle> sized = circles.stream()
                .filter(c -> Math.abs(c.r() - radius) <= radius * RADIUS_TOLERANCE)
                .toList();
        List<Column> columns = columns(sized, radius);
        if (columns.isEmpty()) return null;

        Column left = null;
        Column right = null;
        double bestScore = -1;
        for (Column a : columns) {
            for (Column b : columns) {
                double gap = b.x() - a.x();
                if (gap < radius * MIN_COLUMN_GAP_PER_RADIUS || gap > radius * MAX_COLUMN_GAP_PER_RADIUS) continue;
                double score = alignedRows(a, b, radius);
                if (score > bestScore) {
                    bestScore = score;
                    left = a;
                    right = b;
                }
            }
        }
        if (left == null) {
            Column only = columns.stream().max(Comparator.comparingInt(c -> c.circles().size())).orElseThrow();
            if (only.x() < w / 2.0) left = only; else right = only;
        }

        List<Double> rows = rowCenters(left, right, radius);
        List<Portrait> allies = new ArrayList<>();
        List<Portrait> enemies = new ArrayList<>();
        for (double y : rows) {
            if (left != null) allies.add(new Portrait(left.x(), y, radius));
            if (right != null) enemies.add(new Portrait(right.x(), y, radius));
        }
        return new Layout(allies, enemies, radius);
    }

    public static Anchor anchor(Mat frame, Layout layout) {
        List<Portrait> column = layout.allies().isEmpty() ? layout.enemies() : layout.allies();
        if (column.isEmpty()) return null;
        double margin = layout.radius() * ANCHOR_MARGIN_PER_RADIUS;
        int x0 = (int) Math.max(0, Math.round(column.getFirst().x() - margin));
        int y0 = (int) Math.max(0, Math.round(column.getFirst().y() - margin));
        int x1 = (int) Math.min(frame.cols(), Math.round(column.getFirst().x() + margin));
        int y1 = (int) Math.min(frame.rows(), Math.round(column.getLast().y() + margin));
        if (x1 - x0 < TRACK_DOWNSCALE * 4 || y1 - y0 < TRACK_DOWNSCALE * 4) return null;
        Rect rect = new Rect(x0, y0, x1 - x0, y1 - y0);
        Mat view = new Mat(frame, rect);
        Mat template = shrinkGray(view);
        view.release();
        return new Anchor(template, rect);
    }

    public static Fix track(Mat region, Rect regionRect, Anchor anchor, int expectedX, int expectedY) {
        Mat gray = shrinkGray(region);
        Mat result = new Mat();
        try {
            if (gray.cols() < anchor.template().cols() || gray.rows() < anchor.template().rows()) return null;
            Imgproc.matchTemplate(gray, anchor.template(), result, Imgproc.TM_CCOEFF_NORMED);
            Core.MinMaxLocResult best = Core.minMaxLoc(result);
            int stayX = (int) Math.round((expectedX - regionRect.x) / (double) TRACK_DOWNSCALE);
            int stayY = (int) Math.round((expectedY - regionRect.y) / (double) TRACK_DOWNSCALE);
            double stay = stayX >= 0 && stayY >= 0 && stayX < result.cols() && stayY < result.rows()
                    ? result.get(stayY, stayX)[0] : -1;
            return new Fix(regionRect.x + (int) Math.round(best.maxLoc.x * TRACK_DOWNSCALE),
                    regionRect.y + (int) Math.round(best.maxLoc.y * TRACK_DOWNSCALE), best.maxVal, stay);
        } finally {
            gray.release();
            result.release();
        }
    }

    public static double gapOffset(Mat frame, List<Portrait> column, double radius) {
        List<Double> offsets = new ArrayList<>();
        for (Portrait p : column) {
            int x0 = (int) Math.round(p.x() + GAP_SCAN_FROM * radius);
            int x1 = (int) Math.round(p.x() + GAP_SCAN_TO * radius);
            int y0 = (int) Math.round(p.y() - GAP_SCAN_HALF_HEIGHT * radius);
            int y1 = (int) Math.round(p.y() + GAP_SCAN_HALF_HEIGHT * radius);
            if (x0 < 0 || y0 < 0 || x1 > frame.cols() || y1 > frame.rows() || x1 <= x0 || y1 <= y0) continue;
            Mat view = new Mat(frame, new Rect(x0, y0, x1 - x0, y1 - y0));
            Mat strip = view.clone();
            view.release();
            int w = strip.cols();
            int h = strip.rows();
            int channels = strip.channels();
            byte[] pixels = new byte[w * h * channels];
            strip.get(0, 0, pixels);
            strip.release();

            int bestStart = -1;
            int bestLength = 0;
            int runStart = -1;
            for (int x = 0; x < w; x++) {
                if (columnBusy(pixels, w, h, channels, x)) {
                    runStart = -1;
                    continue;
                }
                if (runStart < 0) runStart = x;
                if (x - runStart + 1 > bestLength) {
                    bestLength = x - runStart + 1;
                    bestStart = runStart;
                }
            }
            boolean bounded = bestStart > 0 && bestStart + bestLength < w;
            if (bounded && bestLength >= GAP_MIN_WIDTH * radius) offsets.add(x0 + bestStart + (bestLength - 1) / 2.0 - p.x());
        }
        return offsets.isEmpty() ? DEFAULT_GAP_PER_RADIUS * radius : median(offsets);
    }

    private static boolean columnBusy(byte[] pixels, int w, int h, int channels, int x) {
        for (int y = 0; y < h; y++) {
            int i = (y * w + x) * channels;
            for (int c = 0; c < Math.min(3, channels); c++) {
                if ((pixels[i + c] & 0xFF) > GAP_BUSY_LEVEL) return true;
            }
        }
        return false;
    }

    private static Mat shrinkGray(Mat image) {
        Mat gray = new Mat();
        Imgproc.cvtColor(image, gray, Imgproc.COLOR_BGR2GRAY);
        Imgproc.resize(gray, gray, new Size(gray.cols() / (double) TRACK_DOWNSCALE, gray.rows() / (double) TRACK_DOWNSCALE),
                0, 0, Imgproc.INTER_AREA);
        return gray;
    }

    public static double match(Mat frame, Portrait portrait, Mat icon) {
        int side = (int) Math.round(portrait.radius() * PORTRAIT_CROP_PER_RADIUS);
        int x = (int) Math.round(portrait.x() - side / 2.0);
        int y = (int) Math.round(portrait.y() - side / 2.0);
        if (side < 8 || x < 0 || y < 0 || x + side > frame.cols() || y + side > frame.rows() || icon == null || icon.empty()) return -1;

        Mat portraitView = new Mat(frame, new Rect(x, y, side, side));
        Mat crop = new Mat();
        Mat result = new Mat();
        Mat cropped = new Mat();
        try {
            Imgproc.resize(portraitView, crop, new Size(MATCH_SIZE, MATCH_SIZE), 0, 0, Imgproc.INTER_AREA);
            double best = -1;
            for (double fraction : ICON_CROPS) {
                int iconSide = (int) Math.round(Math.min(icon.cols(), icon.rows()) * fraction);
                int ix = (icon.cols() - iconSide) / 2;
                int iy = (icon.rows() - iconSide) / 2;
                Mat iconView = new Mat(icon, new Rect(ix, iy, iconSide, iconSide));
                Imgproc.resize(iconView, cropped, new Size(MATCH_SIZE, MATCH_SIZE), 0, 0, Imgproc.INTER_AREA);
                iconView.release();
                Imgproc.matchTemplate(crop, cropped, result, Imgproc.TM_CCOEFF_NORMED);
                best = Math.max(best, Core.minMaxLoc(result).maxVal);
            }
            return best;
        } finally {
            portraitView.release();
            crop.release();
            result.release();
            cropped.release();
        }
    }

    public static Rect area(List<Portrait> portraits, double radius) {
        double minX = portraits.stream().mapToDouble(Portrait::x).min().orElse(0);
        double maxX = portraits.stream().mapToDouble(Portrait::x).max().orElse(0);
        double minY = portraits.stream().mapToDouble(Portrait::y).min().orElse(0);
        double maxY = portraits.stream().mapToDouble(Portrait::y).max().orElse(0);
        int x0 = (int) Math.floor(minX - AREA_LEFT_PER_RADIUS * radius);
        int y0 = (int) Math.floor(minY - AREA_SIDE_PER_RADIUS * radius);
        int x1 = (int) Math.ceil(maxX + AREA_SIDE_PER_RADIUS * radius);
        int y1 = (int) Math.ceil(maxY + AREA_SIDE_PER_RADIUS * radius);
        return new Rect(x0, y0, x1 - x0, y1 - y0);
    }

    public static Mat look(Mat frame, Portrait portrait) {
        Rect rect = lookRect(portrait);
        if (rect.x < 0 || rect.y < 0 || rect.x + rect.width > frame.cols() || rect.y + rect.height > frame.rows()) return null;
        Mat view = new Mat(frame, rect);
        Mat look = view.clone();
        view.release();
        return look;
    }

    public static int[] reorder(Mat frame, List<Portrait> rows, List<Mat> looks) {
        int n = rows.size();
        if (n < 2 || n > LOOK_MAX_ROWS || looks.size() != n) return null;
        double[][] same = new double[n][n];
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) same[i][j] = sameLook(frame, rows.get(i), looks.get(j));
        }
        int[] best = bestOrder(same);
        int[] from = new int[n];
        for (int i = 0; i < n; i++) from[i] = i;
        boolean[] seen = new boolean[n];
        boolean moved = false;
        for (int start = 0; start < n; start++) {
            if (seen[start] || best[start] == start) continue;
            List<Integer> cycle = new ArrayList<>();
            double gain = 0;
            boolean sure = true;
            for (int i = start; !seen[i]; i = best[i]) {
                seen[i] = true;
                cycle.add(i);
                gain += same[i][best[i]] - same[i][i];
                if (same[i][best[i]] < LOOK_SAME) sure = false;
            }
            if (!sure || gain < LOOK_MOVE_GAIN) continue;
            for (int i : cycle) from[i] = best[i];
            moved = true;
        }
        return moved ? from : null;
    }

    private static double sameLook(Mat frame, Portrait portrait, Mat look) {
        if (look == null || look.empty()) return -1;
        int grow = (int) Math.round(LOOK_SLACK_PER_RADIUS * portrait.radius());
        int split = (int) Math.round((LOOK_LEFT_PER_RADIUS - LOOK_SPLIT_PER_RADIUS) * portrait.radius());
        Rect at = lookRect(portrait);
        Rect search = new Rect(at.x - grow, at.y - grow, look.cols() + grow * 2, look.rows() + grow * 2);
        if (search.x < 0 || search.y < 0 || search.x + search.width > frame.cols() || search.y + search.height > frame.rows()
                || split <= 0 || split >= look.cols()) return -1;
        Mat view = new Mat(frame, search);
        Mat side = new Mat();
        Mat portraitPart = new Mat();
        try {
            matchPart(view.colRange(0, split + grow * 2), look.colRange(0, split), side);
            matchPart(view.colRange(split, view.cols()), look.colRange(split, look.cols()), portraitPart);
            Core.min(side, portraitPart, side);
            return Core.minMaxLoc(side).maxVal;
        } finally {
            view.release();
            side.release();
            portraitPart.release();
        }
    }

    private static void matchPart(Mat region, Mat part, Mat result) {
        Imgproc.matchTemplate(region, part, result, Imgproc.TM_CCOEFF_NORMED);
        region.release();
        part.release();
    }

    private static Rect lookRect(Portrait portrait) {
        double r = portrait.radius();
        int x0 = (int) Math.round(portrait.x() - LOOK_LEFT_PER_RADIUS * r);
        int y0 = (int) Math.round(portrait.y() - LOOK_HALF_HEIGHT_PER_RADIUS * r);
        int x1 = (int) Math.round(portrait.x() + LOOK_RIGHT_PER_RADIUS * r);
        int y1 = (int) Math.round(portrait.y() + LOOK_HALF_HEIGHT_PER_RADIUS * r);
        return new Rect(x0, y0, x1 - x0, y1 - y0);
    }

    private static int[] bestOrder(double[][] same) {
        int n = same.length;
        int[] best = new int[n];
        for (int i = 0; i < n; i++) best[i] = i;
        double[] bestSum = {sum(same, best)};
        permute(same, new int[n], new boolean[n], 0, 0, best, bestSum);
        return best;
    }

    private static void permute(double[][] same, int[] order, boolean[] used, int row, double total, int[] best, double[] bestSum) {
        if (row == order.length) {
            if (total > bestSum[0]) {
                bestSum[0] = total;
                System.arraycopy(order, 0, best, 0, order.length);
            }
            return;
        }
        for (int j = 0; j < order.length; j++) {
            if (used[j]) continue;
            used[j] = true;
            order[row] = j;
            permute(same, order, used, row + 1, total + same[row][j], best, bestSum);
            used[j] = false;
        }
    }

    private static double sum(double[][] same, int[] order) {
        double total = 0;
        for (int i = 0; i < order.length; i++) total += same[i][order[i]];
        return total;
    }

    private static List<Circle> houghCircles(Mat region, int frameHeight) {
        Mat gray = new Mat();
        Mat found = new Mat();
        List<Circle> circles = new ArrayList<>();
        try {
            Imgproc.cvtColor(region, gray, Imgproc.COLOR_BGR2GRAY);
            Imgproc.GaussianBlur(gray, gray, new Size(5, 5), 1.5);
            Imgproc.HoughCircles(gray, found, Imgproc.HOUGH_GRADIENT, 1.2, frameHeight * MIN_DIST_PER_HEIGHT,
                    HOUGH_EDGE_THRESHOLD, HOUGH_VOTES,
                    (int) Math.round(frameHeight * MIN_RADIUS_PER_HEIGHT), (int) Math.round(frameHeight * MAX_RADIUS_PER_HEIGHT));
            for (int i = 0; i < found.cols(); i++) {
                double[] c = found.get(0, i);
                circles.add(new Circle(c[0], c[1], c[2]));
            }
            return circles;
        } finally {
            gray.release();
            found.release();
        }
    }

    private static List<Column> columns(List<Circle> circles, double radius) {
        List<Circle> byX = new ArrayList<>(circles);
        byX.sort(Comparator.comparingDouble(Circle::x));
        List<List<Circle>> groups = new ArrayList<>();
        for (Circle c : byX) {
            List<Circle> last = groups.isEmpty() ? null : groups.getLast();
            if (last != null && c.x() - last.getLast().x() <= radius * SAME_COLUMN_PER_RADIUS) {
                last.add(c);
            } else {
                List<Circle> group = new ArrayList<>();
                group.add(c);
                groups.add(group);
            }
        }

        List<Column> columns = new ArrayList<>();
        for (List<Circle> group : groups) {
            if (group.size() < 2) continue;
            group.sort(Comparator.comparingDouble(Circle::y));
            List<Double> gaps = new ArrayList<>();
            for (int i = 1; i < group.size(); i++) gaps.add(group.get(i).y() - group.get(i - 1).y());
            double pitch = gaps.stream().mapToDouble(Double::doubleValue).min().orElse(0);
            if (pitch < radius * MIN_PITCH_PER_RADIUS || pitch > radius * MAX_PITCH_PER_RADIUS) continue;
            boolean regular = gaps.stream().allMatch(g -> {
                double steps = g / pitch;
                return Math.abs(steps - Math.round(steps)) <= PITCH_SLACK;
            });
            if (!regular) continue;
            columns.add(new Column(group.stream().mapToDouble(Circle::x).average().orElse(0), group));
        }
        return columns;
    }

    private static double alignedRows(Column a, Column b, double radius) {
        int aligned = 0;
        for (Circle ca : a.circles()) {
            for (Circle cb : b.circles()) {
                if (Math.abs(ca.y() - cb.y()) <= radius * SAME_ROW_PER_RADIUS) {
                    aligned++;
                    break;
                }
            }
        }
        return aligned + 0.01 * (a.circles().size() + b.circles().size());
    }

    private static List<Double> rowCenters(Column left, Column right, double radius) {
        List<Circle> all = new ArrayList<>();
        if (left != null) all.addAll(left.circles());
        if (right != null) all.addAll(right.circles());
        all.sort(Comparator.comparingDouble(Circle::y));

        List<Double> rows = new ArrayList<>();
        List<Double> current = new ArrayList<>();
        for (Circle c : all) {
            if (!current.isEmpty() && c.y() - current.getLast() > radius * SAME_ROW_PER_RADIUS) {
                rows.add(current.stream().mapToDouble(Double::doubleValue).average().orElse(0));
                current.clear();
            }
            current.add(c.y());
        }
        if (!current.isEmpty()) rows.add(current.stream().mapToDouble(Double::doubleValue).average().orElse(0));
        return rows;
    }

    private static double median(List<Double> values) {
        List<Double> sorted = new ArrayList<>(values);
        sorted.sort(Double::compare);
        int mid = sorted.size() / 2;
        return sorted.size() % 2 == 1 ? sorted.get(mid) : (sorted.get(mid - 1) + sorted.get(mid)) / 2.0;
    }
}
