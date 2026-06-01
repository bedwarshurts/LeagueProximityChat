package me.bedwarshurts.leagueproximitychat.position;

import me.bedwarshurts.leagueproximitychat.utils.LeagueConfigReader;
import me.bedwarshurts.leagueproximitychat.utils.RitoApiUtils;
import me.bedwarshurts.leagueproximitychat.utils.WindowUtils;
import org.opencv.core.*;
import org.opencv.core.Point;
import org.opencv.imgcodecs.Imgcodecs;
import org.opencv.imgproc.Imgproc;
import org.opencv.imgproc.Moments;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import java.util.ArrayList;
import java.util.List;

public class ScreenPositionTracker {

    private Robot robot;
    private float userMinimapScale;

    private Mat championTemplate;

    private Mat lockedCoreTemplate = null;
    private boolean isScaleLocked = false;

    private float lastKnownX = 50f;
    private float lastKnownY = 50f;

    private float healthBarCalibrateX = 0.0f;
    private float healthBarCalibrateY = 0.0f;
    private int calibrationFrames = 0;
    private static final int MAX_CALIBRATION_FRAMES = 30;

    private Rect cachedGameCrop = null;
    private int cachedResolutionWidth = -1;

    public static class TrackResult {
        public float x;
        public float y;
        public boolean isDead;

        public TrackResult(float x, float y, boolean isDead) {
            this.x = x;
            this.y = y;
            this.isDead = isDead;
        }
    }

    public static class TemplateMatch {
        public Point center;
        public double score;

        public TemplateMatch(Point center, double score) {
            this.center = center;
            this.score = score;
        }
    }

    public static class CameraBox {
        public Point center;
        public int width;
        public int height;

        public CameraBox(Point center, int width, int height) {
            this.center = center;
            this.width = width;
            this.height = height;
        }
    }

    public ScreenPositionTracker(Mat championTemplate) {
        try {
            this.robot = new Robot();
            LeagueConfigReader.LeagueSettings settings = LeagueConfigReader.loadSettings();
            this.userMinimapScale = settings.minimapScale;
            this.championTemplate = championTemplate;
        } catch (AWTException e) {
            System.err.println("[MINIMAP TRACKER] Failed to initialize Java Robot API");
            System.err.println("Stacktrace: " + e.getMessage());
        }
    }

    public TrackResult trackPlayerPosition() {
        boolean isDead = checkDeathState();
        Rectangle gameBounds = WindowUtils.getGameWindowBounds("League of Legends (TM) Client");

        if (gameBounds == null) {
            return new TrackResult(lastKnownX, lastKnownY, isDead);
        }

        Mat fullScreenMat = captureScreen(gameBounds);

        if (fullScreenMat.width() != cachedResolutionWidth) {
            cachedGameCrop = null;
            cachedResolutionWidth = fullScreenMat.width();
        }

        if (cachedGameCrop == null) {
            Mat gray = new Mat();
            Mat mask = new Mat();
            Mat nonZero = new Mat();
            try {
                Imgproc.cvtColor(fullScreenMat, gray, Imgproc.COLOR_BGR2GRAY);
                Imgproc.threshold(gray, mask, 10, 255, Imgproc.THRESH_BINARY);
                Core.findNonZero(mask, nonZero);

                if (nonZero.total() > 0) {
                    Rect trueGameRect = Imgproc.boundingRect(nonZero);
                    if (trueGameRect.width > fullScreenMat.width() * 0.5 && trueGameRect.height > fullScreenMat.height() * 0.5) {
                        cachedGameCrop = trueGameRect;
                    }
                }
            } finally {
                gray.release();
                mask.release();
                nonZero.release();
            }
        }

        if (cachedGameCrop != null) {
            Mat croppedScreen = new Mat(fullScreenMat, cachedGameCrop).clone();
            fullScreenMat.release();
            fullScreenMat = croppedScreen;
        }

        float normalizedScale = userMinimapScale;
        if (normalizedScale > 5.0f) {
            normalizedScale = normalizedScale / 100.0f;
        }

        double MIN_MAP_PERCENT = 0.205;
        double MAX_MAP_PERCENT = 0.268;

        double clampedScale = Math.clamp(normalizedScale, 0.0, 3.0);
        double currentMapPercent = MIN_MAP_PERCENT + ((MAX_MAP_PERCENT - MIN_MAP_PERCENT) * clampedScale);

        int perfectMapSize = (int) (fullScreenMat.height() * currentMapPercent);

        Rect minimapRoi = new Rect(
                fullScreenMat.width() - perfectMapSize,
                fullScreenMat.height() - perfectMapSize,
                perfectMapSize,
                perfectMapSize
        );
        Mat minimapMat = new Mat(fullScreenMat, minimapRoi).clone();

        if (WindowUtils.isWindowFocused("League of Legends (TM) Client")) {
            Imgcodecs.imwrite("debug/debug_screen.png", fullScreenMat);
            Imgcodecs.imwrite("debug/debug_minimap.png", minimapMat);
        }

        Point healthBarCenter = locateSelfHealthBar(fullScreenMat);

        TemplateMatch champMatch = null;
        if (healthBarCenter == null || calibrationFrames < MAX_CALIBRATION_FRAMES) {
            champMatch = (championTemplate != null) ? locateChampionViaTemplate(minimapMat) : null;
        }

        Point champMapCenter = (champMatch != null) ? champMatch.center : null;
        double champScore = (champMatch != null) ? champMatch.score : 0.0;

        CameraBox cameraBox = null;
        if (healthBarCenter != null) {
            cameraBox = locateMinimapCameraBox(minimapMat);
        }

        if (healthBarCenter != null && champMapCenter != null && calibrationFrames < MAX_CALIBRATION_FRAMES) {
            if (champScore > 0.70) {
                float rawHpX = calculateProjectedX(healthBarCenter.x, cameraBox, perfectMapSize, fullScreenMat.width());
                float rawHpY = calculateProjectedY(healthBarCenter.y, cameraBox, perfectMapSize, fullScreenMat.height());

                float trueX = ((float) champMapCenter.x / perfectMapSize) * 100f;
                float trueY = 100f - (((float) champMapCenter.y / perfectMapSize) * 100f);

                float targetOffsetX = trueX - rawHpX;
                float targetOffsetY = trueY - rawHpY;

                this.healthBarCalibrateX += (targetOffsetX - this.healthBarCalibrateX) * 0.15f;
                this.healthBarCalibrateY += (targetOffsetY - this.healthBarCalibrateY) * 0.15f;

                this.calibrationFrames++;

                if (this.calibrationFrames >= MAX_CALIBRATION_FRAMES) {
                    System.out.printf("[trackPlayerPosition] Calibration fully locked! Final Offsets -> X: %.2f, Y: %.2f\n", healthBarCalibrateX, healthBarCalibrateY);
                }
            } else {
                System.out.printf("[trackPlayerPosition] Calibration Paused - Waiting for clear icon visibility (Score: %.2f)\n", champScore);
            }
        }

        if (healthBarCenter != null) {
            float rawHpX = calculateProjectedX(healthBarCenter.x, cameraBox, perfectMapSize, fullScreenMat.width());
            float rawHpY = calculateProjectedY(healthBarCenter.y, cameraBox, perfectMapSize, fullScreenMat.height());

            this.lastKnownX = rawHpX + this.healthBarCalibrateX;
            this.lastKnownY = rawHpY + this.healthBarCalibrateY;

            if (calibrationFrames == 0) {
                this.lastKnownX -= 0.32f;
                this.lastKnownY -= 2.28f;
            }

            System.out.printf("[trackPlayerPosition] HEALTHBAR -> X: %.2f%% | Y: %.2f%%\n", lastKnownX, lastKnownY);

            fullScreenMat.release();
            minimapMat.release();
            return new TrackResult(lastKnownX, lastKnownY, isDead);

        } else if (champMapCenter != null) {
            this.lastKnownX = ((float) champMapCenter.x / perfectMapSize) * 100f;
            this.lastKnownY = 100f - (((float) champMapCenter.y / perfectMapSize) * 100f);

            System.out.printf("[trackPlayerPosition] MINIMAP TEMPLATE -> X: %.2f%% | Y: %.2f%%\n", lastKnownX, lastKnownY);

            fullScreenMat.release();
            minimapMat.release();
            return new TrackResult(lastKnownX, lastKnownY, isDead);
        }

        fullScreenMat.release();
        minimapMat.release();
        System.out.printf("[trackPlayerPosition] Couldn't find player returning previous data -> X: %.2f%% | Y: %.2f%%\n", lastKnownX, lastKnownY);
        return new TrackResult(lastKnownX, lastKnownY, isDead);
    }

    private float calculateProjectedX(double healthBarX, CameraBox cameraBox, int perfectMapSize, int screenWidth) {
        float cameraCenterX = (float) cameraBox.center.x;
        float PERSPECTIVE_COMPENSATION_X = 0.68f;
        float dynamicRatioX = (cameraBox.width > 0) ? (((float) cameraBox.width * PERSPECTIVE_COMPENSATION_X) / screenWidth) : 0.021f;

        float offsetX = (float) healthBarX - (screenWidth / 2.0f);
        float finalX = cameraCenterX + (offsetX * dynamicRatioX);
        return (finalX / perfectMapSize) * 100f;
    }

    private float calculateProjectedY(double healthBarY, CameraBox cameraBox, int perfectMapSize, int screenHeight) {
        float feetY = (float) (healthBarY + (screenHeight * 0.074f));
        float cameraCenterY = (float) cameraBox.center.y;
        float PERSPECTIVE_COMPENSATION_Y = 0.75f;
        float dynamicRatioY = (cameraBox.height > 0) ? (((float) cameraBox.height * PERSPECTIVE_COMPENSATION_Y) / screenHeight) : 0.021f;

        float offsetY = feetY - (screenHeight / 2.0f);
        float finalY = cameraCenterY + (offsetY * dynamicRatioY);
        return 100f - ((finalY / perfectMapSize) * 100f);
    }

    private boolean checkDeathState() {
        String localSummonerName = RitoApiUtils.getLocalSummonerName();
        if (localSummonerName == null) return false;

        String playerListJson = RitoApiUtils.fetchAPI("https://127.0.0.1:2999/liveclientdata/playerlist");
        if (playerListJson == null) return false;

        int nameIdx = playerListJson.indexOf("\"" + localSummonerName + "\"");
        if (nameIdx == -1) return false;

        int blockStart = playerListJson.lastIndexOf("\"championName\":", nameIdx);
        if (blockStart == -1) return false;

        int blockEnd = playerListJson.indexOf("\"championName\":", blockStart + 15);
        if (blockEnd == -1) {
            blockEnd = playerListJson.length();
        }

        String playerBlock = playerListJson.substring(blockStart, blockEnd).replaceAll("\\s+", "");
        return playerBlock.contains("\"isDead\":true");
    }

    private Point locateSelfHealthBar(Mat screen) {
        Mat hsv = new Mat();
        Mat mask = new Mat();
        Mat hierarchy = new Mat();
        java.util.List<MatOfPoint> contours = new ArrayList<>();
        Point resultPoint = null;

        try {
            Imgproc.cvtColor(screen, hsv, Imgproc.COLOR_BGR2HSV);

            Scalar lowerYellow = new Scalar(22, 140, 200);
            Scalar upperYellow = new Scalar(26, 255, 255);
            Core.inRange(hsv, lowerYellow, upperYellow, mask);

            int hudTopY = (int) (screen.height() * 0.75);
            Imgproc.rectangle(mask, new Point(0, hudTopY), new Point(screen.width(), screen.height()), new Scalar(0), -1);
            Imgproc.rectangle(mask, new Point(screen.width() * 0.85, 0), new Point(screen.width(), screen.height() * 0.10), new Scalar(0), -1);

            Mat openKernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, new Size(3, 3));
            Imgproc.morphologyEx(mask, mask, Imgproc.MORPH_OPEN, openKernel);
            openKernel.release();

            Mat closeKernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, new Size(11, 1));
            Imgproc.morphologyEx(mask, mask, Imgproc.MORPH_CLOSE, closeKernel);
            closeKernel.release();

            if (WindowUtils.isWindowFocused("League of Legends (TM) Client")) {
                Imgcodecs.imwrite("debug/debug_health_mask.png", mask);
            }

            Imgproc.findContours(mask, contours, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE);

            Rect bestBar = null;
            double bestDistance = Double.MAX_VALUE;

            double minHeight = screen.height() * 0.003;
            double maxHeight = screen.height() * 0.020;
            double minWidth = screen.width() * 0.008;

            for (MatOfPoint contour : contours) {
                Rect rect = Imgproc.boundingRect(contour);
                double pixelArea = Imgproc.contourArea(contour);

                if (rect.height >= minHeight && rect.height <= maxHeight && rect.width >= minWidth) {
                    double extent = pixelArea / (double) (rect.width * rect.height);
                    double aspectRatio = rect.width / (double) rect.height;

                    if (extent > 0.75 && aspectRatio > 2.5) {
                        double centerX = rect.x + (rect.width / 2.0);
                        double centerY = rect.y + (rect.height / 2.0);
                        double distToCenter = Math.pow(centerX - (screen.width() / 2.0), 2) + Math.pow(centerY - (screen.height() / 2.0), 2);

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
        } finally {
            hsv.release();
            mask.release();
            hierarchy.release();
            for (MatOfPoint contour : contours) {
                contour.release();
            }
        }

        return resultPoint;
    }

    private CameraBox locateMinimapCameraBox(Mat minimap) {
        Mat gray = new Mat();
        Mat thresholded = new Mat();
        Mat hierarchy = new Mat();
        java.util.List<MatOfPoint> contours = new ArrayList<>();

        Point center = new Point(minimap.width() / 2.0, minimap.height() / 2.0);
        int width = 0;
        int height = 0;

        try {
            Imgproc.cvtColor(minimap, gray, Imgproc.COLOR_BGR2GRAY);
            Imgproc.threshold(gray, thresholded, 240, 255, Imgproc.THRESH_BINARY);

            Imgproc.findContours(thresholded, contours, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE);

            MatOfPoint cameraContour = null;
            double maxBoundingArea = 0;

            for (MatOfPoint contour : contours) {
                Rect rect = Imgproc.boundingRect(contour);
                double area = rect.width * rect.height;

                if (rect.width > 30 && rect.height > 30) {
                    if (area > maxBoundingArea) {
                        maxBoundingArea = area;
                        cameraContour = contour;
                    }
                }
            }

            if (cameraContour != null) {
                Moments moments = Imgproc.moments(cameraContour);
                if (moments.get_m00() > 0) {
                    center.x = moments.get_m10() / moments.get_m00();
                    center.y = moments.get_m01() / moments.get_m00();
                } else {
                    Rect bounds = Imgproc.boundingRect(cameraContour);
                    center.x = bounds.x + (bounds.width / 2.0);
                    center.y = bounds.y + (bounds.height / 2.0);
                }

                Rect bounds = Imgproc.boundingRect(cameraContour);
                width = bounds.width;
                height = bounds.height;

                if (WindowUtils.isWindowFocused("League of Legends (TM) Client")) {
                    Mat debugMask = Mat.zeros(thresholded.size(), CvType.CV_8UC1);
                    Imgproc.drawContours(debugMask, List.of(cameraContour), -1, new Scalar(255), 1);
                    Imgcodecs.imwrite("debug/debug_camera_mask.png", debugMask);
                    debugMask.release();
                }
            } else {
                if (WindowUtils.isWindowFocused("League of Legends (TM) Client")) {
                    Imgcodecs.imwrite("debug/debug_camera_mask.png", thresholded);
                }
            }
        } finally {
            gray.release();
            thresholded.release();
            hierarchy.release();
            for (MatOfPoint contour : contours) {
                contour.release();
            }
        }

        return new CameraBox(center, width, height);
    }

    private TemplateMatch locateChampionViaTemplate(Mat minimap) {
        int borderMarginX = (int) (minimap.width() * 0.08);
        int borderMarginY = (int) (minimap.height() * 0.08);

        java.util.List<Point> allyCenters = findAllyLocations(minimap);

        if (allyCenters.isEmpty()) return null;

        if (isScaleLocked && lockedCoreTemplate != null) {
            double bestScore = -1.0;
            Point bestAllyCenter = null;

            int cw = lockedCoreTemplate.width();
            int ch = lockedCoreTemplate.height();

            for (Point ally : allyCenters) {
                int searchRadiusX = (int) (cw * 0.8);
                int searchRadiusY = (int) (ch * 0.8);
                int startX = (int) Math.max(0, ally.x - searchRadiusX);
                int startY = (int) Math.max(0, ally.y - searchRadiusY);
                int roiW = Math.min(minimap.width() - startX, searchRadiusX * 2);
                int roiH = Math.min(minimap.height() - startY, searchRadiusY * 2);

                if (roiW < cw || roiH < ch) continue;

                Mat localRoi = new Mat(minimap, new Rect(startX, startY, roiW, roiH));
                Mat result = new Mat();
                Imgproc.matchTemplate(localRoi, lockedCoreTemplate, result, Imgproc.TM_CCOEFF_NORMED);
                Core.MinMaxLocResult mmr = Core.minMaxLoc(result);

                if (mmr.maxVal > bestScore) {
                    bestScore = mmr.maxVal;
                    bestAllyCenter = new Point(startX + mmr.maxLoc.x + (cw / 2.0), startY + mmr.maxLoc.y + (ch / 2.0));
                }

                localRoi.release();
                result.release();
            }

            if (bestScore > 0.40 && bestAllyCenter != null) {
                if (bestAllyCenter.x > borderMarginX && bestAllyCenter.x < minimap.width() - borderMarginX &&
                        bestAllyCenter.y > borderMarginY && bestAllyCenter.y < minimap.height() - borderMarginY) {

                    drawDebugBox(minimap, bestAllyCenter.x, bestAllyCenter.y, lockedCoreTemplate.width(), lockedCoreTemplate.height(), new Scalar(0, 255, 0));
                    System.out.printf("[locateChampionViaTemplate] Found template after locked scale! Match: %.2f%%\n", (bestScore * 100));
                    return new TemplateMatch(bestAllyCenter, bestScore);
                }
            }

            return null;
        }

        double globalBestScore = 0;
        Point globalBestCenter = null;
        Mat globalBestTemplate = null;
        int globalBestSize = 0;

        for (int targetSize = 120; targetSize >= 12; targetSize--) {
            if (targetSize > minimap.width() || targetSize > minimap.height()) {
                continue;
            }

            Mat resizedTemplate = new Mat();
            Imgproc.resize(championTemplate, resizedTemplate, new Size(targetSize, targetSize), 0, 0, Imgproc.INTER_AREA);

            int cx = (int) (resizedTemplate.width() * 0.15);
            int cy = (int) (resizedTemplate.height() * 0.15);
            int cw = (int) (resizedTemplate.width() * 0.70);
            int ch = (int) (resizedTemplate.height() * 0.70);

            if (cw <= 0 || ch <= 0) {
                resizedTemplate.release();
                continue;
            }

            Mat coreTemplate = new Mat(resizedTemplate, new Rect(cx, cy, cw, ch));
            Mat result = new Mat();
            Imgproc.matchTemplate(minimap, coreTemplate, result, Imgproc.TM_CCOEFF_NORMED);
            Core.MinMaxLocResult mmr = Core.minMaxLoc(result);

            if (mmr.maxVal > globalBestScore) {
                double matchCenterX = mmr.maxLoc.x + (coreTemplate.width() / 2.0);
                double matchCenterY = mmr.maxLoc.y + (coreTemplate.height() / 2.0);

                boolean isNearAlly = false;
                for (Point ally : allyCenters) {
                    if (Math.hypot(ally.x - matchCenterX, ally.y - matchCenterY) < 30) {
                        isNearAlly = true;
                        break;
                    }
                }

                if (isNearAlly && matchCenterX > borderMarginX && matchCenterX < minimap.width() - borderMarginX &&
                        matchCenterY > borderMarginY && matchCenterY < minimap.height() - borderMarginY) {

                    globalBestScore = mmr.maxVal;
                    globalBestCenter = new Point(matchCenterX, matchCenterY);
                    if (globalBestTemplate != null) globalBestTemplate.release();
                    globalBestTemplate = coreTemplate.clone();
                    globalBestSize = targetSize;
                }
            }

            coreTemplate.release();
            resizedTemplate.release();
            result.release();
        }

        if (globalBestScore > 0.75) {
            System.out.printf("[locateChampionViaTemplate] EXACT SCALE LOCKED at %dpx! Match: %.2f%%\n", globalBestSize, (globalBestScore * 100));
            this.lockedCoreTemplate = globalBestTemplate;
            this.isScaleLocked = true;

            drawDebugBox(minimap, globalBestCenter.x, globalBestCenter.y, lockedCoreTemplate.width(), lockedCoreTemplate.height(), new Scalar(0, 165, 255));
            return new TemplateMatch(globalBestCenter, globalBestScore);
        }

        if (globalBestTemplate != null) globalBestTemplate.release();
        return null;
    }

    private void drawDebugBox(Mat minimap, double centerX, double centerY, int width, int height, Scalar color) {
        if (WindowUtils.isWindowFocused("League of Legends (TM) Client")) {
            Mat debugMap = minimap.clone();
            Point topLeft = new Point(centerX - (width / 2.0), centerY - (height / 2.0));
            Point bottomRight = new Point(topLeft.x + width, topLeft.y + height);
            Imgproc.rectangle(debugMap, topLeft, bottomRight, color, 2);
            Imgcodecs.imwrite("debug/debug_template_match.png", debugMap);
            debugMap.release();
        }
    }

    private java.util.List<Point> findAllyLocations(Mat minimap) {
        java.util.List<Point> centers = new java.util.ArrayList<>();
        Mat hsv = new Mat();
        Mat mask = new Mat();
        Mat hierarchy = new Mat();
        java.util.List<MatOfPoint> contours = new java.util.ArrayList<>();

        try {
            Imgproc.cvtColor(minimap, hsv, Imgproc.COLOR_BGR2HSV);

            Scalar lowerBlue = new Scalar(95, 100, 150);
            Scalar upperBlue = new Scalar(115, 255, 255);
            Core.inRange(hsv, lowerBlue, upperBlue, mask);

            Mat kernel = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, new Size(3, 3));
            Imgproc.morphologyEx(mask, mask, Imgproc.MORPH_DILATE, kernel);
            kernel.release();

            if (WindowUtils.isWindowFocused("League of Legends (TM) Client")) {
                Imgcodecs.imwrite("debug/debug_ally_mask.png", mask);
            }

            Imgproc.findContours(mask, contours, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE);

            Mat debugDrawMap = null;
            if (WindowUtils.isWindowFocused("League of Legends (TM) Client")) {
                debugDrawMap = minimap.clone();
            }

            for (MatOfPoint contour : contours) {
                float[] radius = new float[1];
                Point center = new Point();
                MatOfPoint2f contour2f = new MatOfPoint2f(contour.toArray());

                Imgproc.minEnclosingCircle(contour2f, center, radius);
                contour2f.release();

                if (radius[0] > 6 && radius[0] < 45) {
                    centers.add(center);
                    if (debugDrawMap != null) {
                        Imgproc.circle(debugDrawMap, center, (int)radius[0], new Scalar(0, 255, 255), 2);
                        Imgproc.circle(debugDrawMap, center, 2, new Scalar(0, 0, 255), -1);
                    }
                }
            }

            if (debugDrawMap != null) {
                Imgcodecs.imwrite("debug/debug_ally_centers.png", debugDrawMap);
                debugDrawMap.release();
            }
        } finally {
            hsv.release();
            mask.release();
            hierarchy.release();
            for (MatOfPoint contour : contours) {
                contour.release();
            }
        }

        return centers;
    }

    private Mat captureScreen(Rectangle bounds) {
        BufferedImage rawImg = robot.createScreenCapture(bounds);
        BufferedImage bgrImg = new BufferedImage(bounds.width, bounds.height, BufferedImage.TYPE_3BYTE_BGR);
        Graphics2D g = bgrImg.createGraphics();
        g.drawImage(rawImg, 0, 0, null);
        g.dispose();

        byte[] pixels = ((DataBufferByte) bgrImg.getRaster().getDataBuffer()).getData();

        Mat mat = new Mat(bounds.height, bounds.width, CvType.CV_8UC3);
        mat.put(0, 0, pixels);

        return mat;
    }
}