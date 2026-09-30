package me.bedwarshurts.leagueproximitychat.position;

import org.opencv.core.Core;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.MatOfPoint;
import org.opencv.core.Point;
import org.opencv.core.Rect;
import org.opencv.core.Scalar;
import org.opencv.imgproc.Imgproc;

import java.util.ArrayList;
import java.util.List;

final class CameraBoxLocator {

    private static final double EDGE_STRENGTH = 0.6;
    private static final int EDGE_MIN_RUN_PX = 10;
    private static final double EDGE_PAIR_TOLERANCE = 0.25;

    private record Edges(int start, int end, boolean both) {
    }

    private int maxSeenCamW = 0;
    private int maxSeenCamH = 0;
    private int measuredCamW = 0;
    private int measuredCamH = 0;
    private int lastMinimapWidth = 0;

    CameraBox locate(Mat minimap, int screenWidth, int screenHeight) {
        Mat gray = new Mat();
        Mat thresholded = new Mat();
        Mat hierarchy = new Mat();
        List<MatOfPoint> contours = new ArrayList<>();

        Point center = new Point(minimap.width() / 2.0, minimap.height() / 2.0);

        try {
            if (minimap.width() != lastMinimapWidth) {
                float exactAspectRatio = (float) screenWidth / screenHeight;
                maxSeenCamH = (int) (minimap.height() * 0.14);
                maxSeenCamW = (int) (maxSeenCamH * exactAspectRatio);
                measuredCamW = maxSeenCamW;
                measuredCamH = maxSeenCamH;
                lastMinimapWidth = minimap.width();
                System.out.printf("[CameraBox] Initialized exact bounds for %dx%d monitor. Box: %dx%d%n",
                        screenWidth, screenHeight, maxSeenCamW, maxSeenCamH);
            }

            int edgeMarginX = (int) (minimap.width() * 0.08);
            int edgeMarginY = (int) (minimap.height() * 0.08);

            Imgproc.cvtColor(minimap, gray, Imgproc.COLOR_BGR2GRAY);
            Imgproc.threshold(gray, thresholded, 240, 255, Imgproc.THRESH_BINARY);
            Imgproc.findContours(thresholded, contours, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE);

            MatOfPoint cameraContour = null;
            double maxBoundingArea = 0;

            for (MatOfPoint contour : contours) {
                Rect rect = Imgproc.boundingRect(contour);
                double area = (double) rect.width * rect.height;

                if (rect.width > 20 && rect.height > 20 && area > maxBoundingArea) {
                    if (maxSeenCamW > 0 && maxSeenCamH > 0) {
                        if (rect.width < maxSeenCamW * 0.35 || rect.height < maxSeenCamH * 0.35) continue;
                    }
                    maxBoundingArea = area;
                    cameraContour = contour;
                }
            }

            if (cameraContour != null) {
                Rect bounds = Imgproc.boundingRect(cameraContour);
                int[] rowCounts = new int[bounds.height];
                int[] colCounts = new int[bounds.width];
                whiteProfiles(thresholded, bounds, rowCounts, colCounts);

                Edges xs = findEdges(colCounts, bounds.x, measuredCamW);
                Edges ys = findEdges(rowCounts, bounds.y, measuredCamH);
                if (xs != null && xs.both()) measuredCamW = xs.end() - xs.start() + 1;
                if (ys != null && ys.both()) measuredCamH = ys.end() - ys.start() + 1;

                double trueCenterX = boxCenter(xs, bounds.x, bounds.width, measuredCamW, minimap.width(), edgeMarginX);
                double trueCenterY = boxCenter(ys, bounds.y, bounds.height, measuredCamH, minimap.height(), edgeMarginY);
                boolean isClipped = (xs != null && !xs.both()) || (ys != null && !ys.both());

                center.x = trueCenterX;
                center.y = trueCenterY;

                if (DebugImages.enabled()) {
                    Mat debugMask = Mat.zeros(thresholded.size(), CvType.CV_8UC1);
                    Imgproc.drawContours(debugMask, List.of(cameraContour), -1, new Scalar(255), 1);
                    DebugImages.write("debug_camera_mask.png", debugMask);
                    debugMask.release();

                    Mat debugReconstructed = minimap.clone();
                    Imgproc.drawContours(debugReconstructed, List.of(cameraContour), -1, new Scalar(0, 0, 255), 1);

                    Scalar boxColor = isClipped ? new Scalar(255, 255, 0) : new Scalar(0, 255, 0);
                    Point topLeft = new Point(trueCenterX - (measuredCamW / 2.0), trueCenterY - (measuredCamH / 2.0));
                    Point bottomRight = new Point(trueCenterX + (measuredCamW / 2.0), trueCenterY + (measuredCamH / 2.0));

                    Imgproc.rectangle(debugReconstructed, topLeft, bottomRight, boxColor, 2);
                    Imgproc.circle(debugReconstructed, center, 2, boxColor, -1);

                    DebugImages.write("debug_camera_reconstructed.png", debugReconstructed);
                    debugReconstructed.release();
                }

                return new CameraBox(center, Math.max(0, maxSeenCamW), Math.max(0, maxSeenCamH));
            } else {
                if (DebugImages.enabled()) {
                    DebugImages.write("debug_camera_mask.png", thresholded);
                }
            }

        } finally {
            gray.release();
            thresholded.release();
            hierarchy.release();
            for (MatOfPoint contour : contours) contour.release();
        }

        return null;
    }

    private static void whiteProfiles(Mat thresholded, Rect bounds, int[] rowCounts, int[] colCounts) {
        Mat roi = new Mat(thresholded, bounds);
        Mat rowSums = new Mat();
        Mat colSums = new Mat();
        try {
            Core.reduce(roi, rowSums, 1, Core.REDUCE_SUM, CvType.CV_32S);
            Core.reduce(roi, colSums, 0, Core.REDUCE_SUM, CvType.CV_32S);
            rowSums.get(0, 0, rowCounts);
            colSums.get(0, 0, colCounts);
            for (int i = 0; i < rowCounts.length; i++) rowCounts[i] /= 255;
            for (int i = 0; i < colCounts.length; i++) colCounts[i] /= 255;
        } finally {
            roi.release();
            rowSums.release();
            colSums.release();
        }
    }

    private static Edges findEdges(int[] counts, int offset, int expectedSize) {
        int max = 0;
        for (int c : counts) max = Math.max(max, c);
        int threshold = Math.max(EDGE_MIN_RUN_PX, (int) (max * EDGE_STRENGTH));

        List<int[]> groups = new ArrayList<>();
        for (int i = 0; i < counts.length; i++) {
            if (counts[i] < threshold) continue;
            if (!groups.isEmpty() && groups.getLast()[1] == i - 1) {
                groups.getLast()[1] = i;
            } else {
                groups.add(new int[]{i, i});
            }
        }
        if (groups.isEmpty()) return null;

        int[] bestPair = null;
        double bestError = Double.MAX_VALUE;
        for (int a = 0; a < groups.size(); a++) {
            for (int b = a + 1; b < groups.size(); b++) {
                int span = groups.get(b)[1] - groups.get(a)[0] + 1;
                double error = Math.abs(span - expectedSize);
                if (error <= expectedSize * EDGE_PAIR_TOLERANCE && error < bestError) {
                    bestError = error;
                    bestPair = new int[]{groups.get(a)[0], groups.get(b)[1]};
                }
            }
        }
        if (bestPair != null) return new Edges(offset + bestPair[0], offset + bestPair[1], true);

        int[] strongest = groups.getFirst();
        int strongestCount = -1;
        for (int[] g : groups) {
            for (int i = g[0]; i <= g[1]; i++) {
                if (counts[i] > strongestCount) {
                    strongestCount = counts[i];
                    strongest = g;
                }
            }
        }
        return new Edges(offset + strongest[0], offset + strongest[1], false);
    }

    private static double boxCenter(Edges edges, int boundsStart, int boundsSize, int boxSize, int mapSize, int edgeMargin) {
        if (edges == null) return boundsStart + (boundsSize / 2.0);
        if (edges.both()) return (edges.start() + edges.end() + 1) / 2.0;
        if (edges.start() + boxSize >= mapSize - edgeMargin) return edges.start() + (boxSize / 2.0);
        if (edges.end() + 1 - boxSize <= edgeMargin) return edges.end() + 1 - (boxSize / 2.0);
        return boundsStart + (boundsSize / 2.0);
    }
}
