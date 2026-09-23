package me.bedwarshurts.leagueproximitychat.position;

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

    private int maxSeenCamW = 0;
    private int maxSeenCamH = 0;
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

                boolean touchesLeft = bounds.x <= edgeMarginX;
                boolean touchesRight = bounds.x + bounds.width >= minimap.width() - edgeMarginX;
                boolean touchesTop = bounds.y <= edgeMarginY;
                boolean touchesBottom = bounds.y + bounds.height >= minimap.height() - edgeMarginY;

                boolean isClipped = touchesLeft || touchesRight || touchesTop || touchesBottom;

                double trueCenterX = bounds.x + (bounds.width / 2.0);
                double trueCenterY = bounds.y + (bounds.height / 2.0);

                if (touchesLeft && !touchesRight) {
                    trueCenterX = (bounds.x + bounds.width) - (maxSeenCamW / 2.0);
                } else if (touchesRight && !touchesLeft) {
                    trueCenterX = bounds.x + (maxSeenCamW / 2.0);
                }

                if (touchesTop && !touchesBottom) {
                    trueCenterY = (bounds.y + bounds.height) - (maxSeenCamH / 2.0);
                } else if (touchesBottom && !touchesTop) {
                    trueCenterY = bounds.y + (maxSeenCamH / 2.0);
                }

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
                    Point topLeft = new Point(trueCenterX - (maxSeenCamW / 2.0), trueCenterY - (maxSeenCamH / 2.0));
                    Point bottomRight = new Point(trueCenterX + (maxSeenCamW / 2.0), trueCenterY + (maxSeenCamH / 2.0));

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
}
