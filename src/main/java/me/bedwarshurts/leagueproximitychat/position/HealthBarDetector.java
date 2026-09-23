package me.bedwarshurts.leagueproximitychat.position;

import org.opencv.core.Core;
import org.opencv.core.Mat;
import org.opencv.core.MatOfPoint;
import org.opencv.core.Point;
import org.opencv.core.Rect;
import org.opencv.core.Scalar;
import org.opencv.core.Size;
import org.opencv.imgproc.Imgproc;

import java.util.ArrayList;
import java.util.List;

final class HealthBarDetector {

    private static final double TEAM_FRAMES_HEIGHT_FACTOR = 0.16;
    private static final double TEAM_FRAMES_WIDTH_FACTOR = 0.25;
    private static final double HEALTH_BAR_FRAME_MAX_RATIO = 0.45;

    private final boolean colorblind;

    HealthBarDetector(boolean colorblind) {
        this.colorblind = colorblind;
    }

    Point locate(Mat screen, Rect minimapRoi) {
        Mat hsv = new Mat();
        Mat mask = new Mat();
        Mat hierarchy = new Mat();
        List<MatOfPoint> contours = new ArrayList<>();
        Point resultPoint = null;

        try {
            Imgproc.cvtColor(screen, hsv, Imgproc.COLOR_BGR2HSV);

            Scalar lowerColor = this.colorblind ? new Scalar(23, 140, 200) : new Scalar(45, 100, 100);
            Scalar upperColor = this.colorblind ? new Scalar(26, 225, 255) : new Scalar(75, 255, 255);
            Core.inRange(hsv, lowerColor, upperColor, mask);

            int hudTopY = (int) (screen.height() * 0.75);
            Imgproc.rectangle(mask, new Point(0, hudTopY), new Point(screen.width(), screen.height()), new Scalar(0), -1);
            Imgproc.rectangle(mask, new Point(screen.width() * 0.85, 0), new Point(screen.width(), screen.height() * 0.10), new Scalar(0), -1);
            Rect teamFrames = teamFramesZone(screen, minimapRoi);
            Imgproc.rectangle(mask, teamFrames.tl(), teamFrames.br(), new Scalar(0), -1);

            int openSize = Math.max(1, (int) (screen.height() * 0.002));
            int closeWidth = Math.max(3, (int) (screen.width() * 0.005));

            Mat openKernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, new Size(openSize, openSize));
            Imgproc.morphologyEx(mask, mask, Imgproc.MORPH_OPEN, openKernel);
            openKernel.release();

            Mat closeKernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, new Size(closeWidth, 1));
            Imgproc.morphologyEx(mask, mask, Imgproc.MORPH_CLOSE, closeKernel);
            closeKernel.release();

            Imgproc.findContours(mask, contours, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE);

            Rect bestBar = null;
            double bestDistance = Double.MAX_VALUE;
            List<Rect> rejectedSmallBars = new ArrayList<>();

            double minHeight = screen.height() * 0.003;
            double maxHeight = screen.height() * 0.020;
            double minWidth = screen.width() * 0.001;

            for (MatOfPoint contour : contours) {
                Rect rect = Imgproc.boundingRect(contour);
                double pixelArea = Imgproc.contourArea(contour);

                if (rect.height >= minHeight && rect.height <= maxHeight && rect.width >= minWidth) {
                    double extent = pixelArea / (double) (rect.width * rect.height);
                    double aspectRatio = rect.width / (double) rect.height;

                    if (extent > 0.55 && (aspectRatio > 2.5 || rect.width < 30)) {
                        if (rect.width < 30 && !hasDarkFrameAbove(hsv, rect)) {
                            rejectedSmallBars.add(rect);
                            continue;
                        }
                        double centerX = rect.x + (rect.width / 2.0);
                        double centerY = rect.y + (rect.height / 2.0);
                        double distToCenter = Math.pow(centerX - (screen.width() / 2.0), 2)
                                + Math.pow(centerY - (screen.height() / 2.0), 2);

                        if (distToCenter < bestDistance) {
                            bestDistance = distToCenter;
                            bestBar = rect;
                        }
                    }
                }
            }

            if (bestBar != null) {
                resultPoint = new Point(bestBar.x + (bestBar.width / 2.0), bestBar.y + (bestBar.height / 2.0));
            }

            if (DebugImages.enabled()) {
                Mat debugHealthMap = new Mat();
                Imgproc.cvtColor(mask, debugHealthMap, Imgproc.COLOR_GRAY2BGR);
                Imgproc.rectangle(debugHealthMap, teamFrames.tl(), teamFrames.br(), new Scalar(90, 90, 90), 2);
                for (Rect rejected : rejectedSmallBars) {
                    Imgproc.rectangle(debugHealthMap, rejected.tl(), rejected.br(), new Scalar(0, 200, 255), 1);
                }
                if (bestBar != null) {
                    Imgproc.rectangle(debugHealthMap,
                            new Point(bestBar.x, bestBar.y),
                            new Point(bestBar.x + bestBar.width, bestBar.y + bestBar.height),
                            new Scalar(0, 0, 255), 2);
                }
                DebugImages.write("debug_health_mask.png", debugHealthMap);
                debugHealthMap.release();
            }

        } finally {
            hsv.release();
            mask.release();
            hierarchy.release();
            for (MatOfPoint contour : contours) contour.release();
        }

        return resultPoint;
    }

    private static Rect teamFramesZone(Mat screen, Rect minimapRoi) {
        int width = screen.width();
        int height = screen.height();
        int top = Math.max(0, (int) (minimapRoi.y - height * TEAM_FRAMES_HEIGHT_FACTOR));
        int left;
        int right;
        if (minimapRoi.x + minimapRoi.width / 2.0 > width / 2.0) {
            left = (int) Math.clamp(minimapRoi.x - width * 0.01, 0, width * (1.0 - TEAM_FRAMES_WIDTH_FACTOR));
            right = width;
        } else {
            left = 0;
            right = (int) Math.clamp(minimapRoi.x + minimapRoi.width + width * 0.01, width * TEAM_FRAMES_WIDTH_FACTOR, width);
        }
        return new Rect(left, top, right - left, height - top);
    }

    private static boolean hasDarkFrameAbove(Mat hsv, Rect bar) {
        if (bar.y <= 0) return true;
        Mat inside = new Mat(hsv, bar);
        double insideV = Core.mean(inside).val[2];
        inside.release();
        if (insideV <= 0) return false;

        int rows = Math.max(3, (int) Math.round(hsv.rows() * 0.0025));
        double darkestRowV = Double.MAX_VALUE;
        for (int dy = 1; dy <= rows && bar.y - dy >= 0; dy++) {
            Mat row = new Mat(hsv, new Rect(bar.x, bar.y - dy, bar.width, 1));
            darkestRowV = Math.min(darkestRowV, Core.mean(row).val[2]);
            row.release();
        }
        return darkestRowV / insideV < HEALTH_BAR_FRAME_MAX_RATIO;
    }
}
