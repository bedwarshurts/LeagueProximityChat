package me.bedwarshurts.leagueproximitychat.position;

import me.bedwarshurts.leagueproximitychat.managers.DebugManager;
import org.opencv.core.Mat;
import org.opencv.core.Point;
import org.opencv.core.Rect;
import org.opencv.core.Scalar;
import org.opencv.core.Size;
import org.opencv.imgcodecs.Imgcodecs;
import org.opencv.imgproc.Imgproc;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.List;

final class DebugImages {

    private DebugImages() {
    }

    static boolean enabled() {
        return DebugManager.isENABLED();
    }

    static void write(String fileName, Mat image) {
        Imgcodecs.imwrite(DebugManager.getDebugDir() + "/" + fileName, image);
    }

    static void enemyIndicators(Mat minimap) {
        Mat redMask = new Mat();
        Mat overlay = minimap.clone();
        try {
            MinimapRingDetector.enemyRedMask(minimap, redMask);
            overlay.setTo(new Scalar(0, 255, 0), redMask);
            write("debug_enemy_indicators.png", overlay);
        } finally {
            redMask.release();
            overlay.release();
        }
    }

    static void healthLocation(Mat minimap, float lastKnownX, float lastKnownY, Point champMapCenter, int mapSize) {
        Mat debugHealthLoc = minimap.clone();

        double mapPixelX = MapCoordinates.pixelX(lastKnownX, mapSize);
        double mapPixelY = MapCoordinates.pixelY(lastKnownY, mapSize);
        Point hpEstimatedPos = new Point(mapPixelX, mapPixelY);

        Imgproc.circle(debugHealthLoc, hpEstimatedPos, 6, new Scalar(255, 0, 255), 2);
        Imgproc.circle(debugHealthLoc, hpEstimatedPos, 1, new Scalar(255, 0, 255), -1);

        Imgproc.putText(debugHealthLoc, "HP", new Point(mapPixelX + 8, mapPixelY + 4),
                Imgproc.FONT_HERSHEY_SIMPLEX, 0.35, new Scalar(255, 0, 255), 1);

        if (champMapCenter != null) {
            Imgproc.rectangle(debugHealthLoc,
                    new Point(champMapCenter.x - 6, champMapCenter.y - 6),
                    new Point(champMapCenter.x + 6, champMapCenter.y + 6),
                    new Scalar(0, 255, 0), 1);
            Imgproc.line(debugHealthLoc, hpEstimatedPos, champMapCenter, new Scalar(0, 255, 255), 1);
            double matchX = MapCoordinates.percentXPrecise(champMapCenter.x, mapSize);
            double matchY = MapCoordinates.percentYPrecise(champMapCenter.y, mapSize);
            double gap = Math.hypot(matchX - lastKnownX, matchY - lastKnownY);
            Imgproc.putText(debugHealthLoc, String.format("gap=%.1f%%", gap),
                    new Point(4, 14), Imgproc.FONT_HERSHEY_SIMPLEX, 0.4, new Scalar(0, 255, 0), 1);
        }

        write("debug_health_location.png", debugHealthLoc);
        debugHealthLoc.release();
    }

    static void saveTemplate(String fileName, Mat core, double score) {
        if (!enabled() || core == null || core.empty()) return;
        Mat big = new Mat();
        try {
            Imgproc.resize(core, big, new Size(220, 220), 0, 0, Imgproc.INTER_NEAREST);
            drawCaption(big, score);
            write(fileName, big);
        } catch (Exception e) {
            DebugManager.logFailure("[Debug] Could not write " + fileName, e);
        } finally {
            big.release();
        }
    }

    static void saveLockContext(Mat minimap, IconCircle pick, double score) {
        if (!enabled()) return;
        Mat ctx = minimap.clone();
        try {
            Imgproc.circle(ctx, pick.center(), pick.radius(), new Scalar(0, 255, 0), 1);
            Imgproc.circle(ctx, pick.center(), 1, new Scalar(0, 0, 255), -1);
            drawCaption(ctx, score);
            write("debug_lock_minimap.png", ctx);
        } catch (Exception e) {
            DebugManager.logFailure("[Debug] Could not write debug_lock_minimap.png", e);
        } finally {
            ctx.release();
        }
    }

    static void croppedTemplates(List<Mat> crops) {
        if (crops.isEmpty()) return;

        if (enabled()) {
            int totalWidth = 0;
            int maxHeight = 0;
            for (Mat crop : crops) {
                totalWidth += crop.width();
                if (crop.height() > maxHeight) maxHeight = crop.height();
            }

            if (totalWidth > 0 && maxHeight > 0) {
                Mat spriteSheet = Mat.zeros(maxHeight, totalWidth, crops.getFirst().type());
                int currentX = 0;
                for (Mat crop : crops) {
                    Mat roi = new Mat(spriteSheet, new Rect(currentX, 0, crop.width(), crop.height()));
                    crop.copyTo(roi);
                    currentX += crop.width();
                    roi.release();
                }
                write("debug_cropped_templates.png", spriteSheet);
                spriteSheet.release();
            }
        }

        for (Mat crop : crops) crop.release();
        crops.clear();
    }

    static void top10(Mat minimap, List<IconMatcher.CandidateMatch> candidates) {
        if (!enabled() || candidates.isEmpty()) return;

        Mat top10Map = minimap.clone();
        candidates.sort((c1, c2) -> Double.compare(c2.score(), c1.score()));
        int limit = Math.min(10, candidates.size());

        for (int i = 0; i < limit; i++) {
            IconMatcher.CandidateMatch c = candidates.get(i);
            Point tl = new Point(c.center().x - (c.width() / 2.0), c.center().y - (c.height() / 2.0));
            Point br = new Point(tl.x + c.width(), tl.y + c.height());

            Scalar boxColor = (i == 0) ? new Scalar(0, 255, 0) : new Scalar(0, 0, 255);
            Imgproc.rectangle(top10Map, tl, br, boxColor, 1);

            String text = String.format("Top%d | %.0f%%", i + 1, c.score() * 100);
            double textY = Math.max(10, tl.y - 4);

            Imgproc.putText(top10Map, text, new Point(tl.x + 1, textY + 1),
                    Imgproc.FONT_HERSHEY_SIMPLEX, 0.35, new Scalar(0, 0, 0), 1);
            Imgproc.putText(top10Map, text, new Point(tl.x, textY),
                    Imgproc.FONT_HERSHEY_SIMPLEX, 0.35, boxColor, 1);
        }

        write("debug_template_top10_match.png", top10Map);
        top10Map.release();
    }

    private static final double[] COMPARED_TINT = {144, 238, 144};  // light green (BGR)
    private static final double[] COVERED_TINT = {128, 128, 128};   // gray
    private static final double COMPARED_ALPHA = 0.45;
    private static final double COVERED_ALPHA = 0.75;

    static void comparedPixels(Mat minimap, List<IconMatcher.ComparedWindow> windows, Point picked, int width, int height) {
        if (!enabled() || windows == null || windows.isEmpty()) return;

        Mat image = minimap.clone();
        int channels = image.channels();
        byte[] original = new byte[(int) image.total() * channels];
        image.get(0, 0, original);
        byte[] tinted = original.clone();

        List<IconMatcher.ComparedWindow> ordered = new ArrayList<>(windows);
        ordered.sort(Comparator.comparing(w -> w.center().equals(picked)));
        for (IconMatcher.ComparedWindow window : ordered) {
            int x0 = (int) Math.round(window.center().x - width / 2.0);
            int y0 = (int) Math.round(window.center().y - height / 2.0);
            for (int y = Math.max(0, y0); y < Math.min(image.rows(), y0 + height); y++) {
                for (int x = Math.max(0, x0); x < Math.min(image.cols(), x0 + width); x++) {
                    boolean covered = IconMatcher.isCovered(x + 0.5, y + 0.5, window.coveringIcons());
                    double[] tint = covered ? COVERED_TINT : COMPARED_TINT;
                    double alpha = covered ? COVERED_ALPHA : COMPARED_ALPHA;
                    int i = (y * image.cols() + x) * channels;
                    for (int c = 0; c < 3; c++) {
                        tinted[i + c] = (byte) Math.round((original[i + c] & 0xFF) * (1 - alpha) + tint[c] * alpha);
                    }
                }
            }
        }

        image.put(0, 0, tinted);
        write("debug_template_covered.png", image);
        image.release();
    }

    static void matchBox(Mat minimap, double centerX, double centerY, int width, int height, Scalar color) {
        if (!enabled()) return;

        Mat debugMap = minimap.clone();
        Point topLeft = new Point(centerX - (width / 2.0), centerY - (height / 2.0));
        Point bottomRight = new Point(topLeft.x + width, topLeft.y + height);
        Imgproc.rectangle(debugMap, topLeft, bottomRight, color, 2);
        write("debug_template_match.png", debugMap);
        debugMap.release();
    }

    private static void drawCaption(Mat img, double score) {
        String caption = new SimpleDateFormat("HH:mm:ss").format(new Date())
                + String.format("  s=%.2f", score);
        int[] baseline = new int[1];
        Size txt = Imgproc.getTextSize(caption, Imgproc.FONT_HERSHEY_SIMPLEX, 0.4, 1, baseline);
        Point org = new Point(Math.max(2, img.width() - txt.width - 4), img.height() - 6);
        Imgproc.putText(img, caption, new Point(org.x + 1, org.y + 1),
                Imgproc.FONT_HERSHEY_SIMPLEX, 0.4, new Scalar(0, 0, 0), 2);
        Imgproc.putText(img, caption, org,
                Imgproc.FONT_HERSHEY_SIMPLEX, 0.4, new Scalar(255, 255, 255), 1);
    }
}
