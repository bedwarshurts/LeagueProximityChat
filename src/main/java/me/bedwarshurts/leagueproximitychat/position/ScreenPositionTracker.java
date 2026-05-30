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

public class ScreenPositionTracker {

    private Robot robot;
    private float userMinimapScale;

    private Mat championTemplate;

    private Mat lockedCoreTemplate = null;
    private boolean isScaleLocked = false;

    private static final float SCREEN_TO_MAP_RATIO = 0.05f;

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

    public ScreenPositionTracker(Mat championTemplate) {
        try {
            this.robot = new Robot();

            LeagueConfigReader.LeagueSettings settings = LeagueConfigReader.loadSettings();
            this.userMinimapScale = settings.minimapScale;

            this.championTemplate = championTemplate;

        } catch (AWTException e) {
            System.err.println("[TRACKER] Failed to initialize Java Robot API");
            e.printStackTrace();
        }
    }

    public TrackResult trackPlayerPosition() {
        boolean isDead = checkDeathState();

        Rectangle gameBounds = WindowUtils.getGameWindowBounds("League of Legends (TM) Client");

        if (gameBounds == null) {
            return new TrackResult(50f, 50f, isDead);
        }

        float normalizedScale = userMinimapScale;
        if (normalizedScale > 5.0f) {
            normalizedScale = normalizedScale / 100.0f;
        }

        double MIN_MAP_PERCENT = 0.205;
        double MAX_MAP_PERCENT = 0.268;

        double clampedScale = Math.clamp(normalizedScale, 0.0, 3.0);
        double currentMapPercent = MIN_MAP_PERCENT + ((MAX_MAP_PERCENT - MIN_MAP_PERCENT) * clampedScale);

        int perfectMapSize = (int) (gameBounds.height * currentMapPercent);

        Rectangle minimapBounds = new Rectangle(
                gameBounds.x + gameBounds.width - perfectMapSize,
                gameBounds.y + gameBounds.height - perfectMapSize,
                perfectMapSize,
                perfectMapSize
        );

        Mat fullScreenMat = captureScreen(gameBounds);
        Mat minimapMat = captureScreen(minimapBounds);

        if (WindowUtils.isWindowFocused("League of Legends")) {
            Imgcodecs.imwrite("debug_screen.png", fullScreenMat);
            Imgcodecs.imwrite("debug_minimap.png", minimapMat);
        }

        Point healthBarCenter = locateSelfHealthBar(fullScreenMat);

        if (healthBarCenter != null) {
            float offsetX = (float) (healthBarCenter.x - (gameBounds.width / 2.0));
            float offsetY = (float) (healthBarCenter.y - (gameBounds.height / 2.0));

            Point cameraMapCenter = locateMinimapCameraBox(minimapMat);

            float finalX = (float) cameraMapCenter.x + (offsetX * SCREEN_TO_MAP_RATIO);
            float finalY = (float) cameraMapCenter.y + (offsetY * SCREEN_TO_MAP_RATIO);

            float invertedY = 100f - ((finalY / minimapBounds.height) * 100f);

            return new TrackResult((finalX / minimapBounds.width) * 100f, invertedY, isDead);

        } else if (championTemplate != null) {
            Point champMapCenter = locateChampionViaTemplate(minimapMat);

            if (champMapCenter != null) {
                float finalX = (float) champMapCenter.x;
                float finalY = (float) champMapCenter.y;

                float invertedY = 100f - ((finalY / minimapBounds.height) * 100f);

                return new TrackResult((finalX / minimapBounds.width) * 100f, invertedY, isDead);
            }
        }

        return new TrackResult(50f, 50f, isDead);
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
        Imgproc.cvtColor(screen, hsv, Imgproc.COLOR_BGR2HSV);

        Scalar lowerYellow = new Scalar(22, 140, 200);
        Scalar upperYellow = new Scalar(26, 255, 255);

        Mat mask = new Mat();
        Core.inRange(hsv, lowerYellow, upperYellow, mask);

        int hudTopY = (int) (screen.height() * 0.75);
        Imgproc.rectangle(mask, new Point(0, hudTopY), new Point(screen.width(), screen.height()), new Scalar(0), -1);

        Imgproc.rectangle(mask, new Point(screen.width() * 0.85, 0), new Point(screen.width(), screen.height() * 0.10), new Scalar(0), -1);

        Mat openKernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, new org.opencv.core.Size(3, 3));
        Imgproc.morphologyEx(mask, mask, Imgproc.MORPH_OPEN, openKernel);
        openKernel.release();

        Mat closeKernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, new org.opencv.core.Size(11, 1));
        Imgproc.morphologyEx(mask, mask, Imgproc.MORPH_CLOSE, closeKernel);
        closeKernel.release();

        if (WindowUtils.isWindowFocused("League of Legends")) {
            Imgcodecs.imwrite("debug_health_mask.png", mask);
        }

        java.util.List<MatOfPoint> contours = new ArrayList<>();
        Mat hierarchy = new Mat();
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
            return new Point(bestBar.x + (bestBar.width / 2.0), bestBar.y + (bestBar.height / 2.0));
        }

        return null;
    }

    private Point locateMinimapCameraBox(Mat minimap) {
        Mat gray = new Mat();
        Imgproc.cvtColor(minimap, gray, Imgproc.COLOR_BGR2GRAY);

        Mat thresholded = new Mat();
        Imgproc.threshold(gray, thresholded, 240, 255, Imgproc.THRESH_BINARY);

        if (WindowUtils.isWindowFocused("League of Legends")) {
            Imgcodecs.imwrite("debug_camera_mask.png", thresholded);
        }

        Moments moments = Imgproc.moments(thresholded);
        if (moments.get_m00() > 0) {
            int x = (int) (moments.get_m10() / moments.get_m00());
            int y = (int) (moments.get_m01() / moments.get_m00());
            return new Point(x, y);
        }
        return new Point(minimap.width() / 2.0, minimap.height() / 2.0);
    }

    private Point locateChampionViaTemplate(Mat minimap) {
        int borderMarginX = (int) (minimap.width() * 0.08);
        int borderMarginY = (int) (minimap.height() * 0.08);

        if (isScaleLocked && lockedCoreTemplate != null) {
            Mat result = new Mat();
            Imgproc.matchTemplate(minimap, lockedCoreTemplate, result, Imgproc.TM_CCOEFF_NORMED);
            Core.MinMaxLocResult mmr = Core.minMaxLoc(result);

            if (mmr.maxVal > 0.32) {
                double centerX = mmr.maxLoc.x + (lockedCoreTemplate.width() / 2.0);
                double centerY = mmr.maxLoc.y + (lockedCoreTemplate.height() / 2.0);

                if (centerX > borderMarginX && centerX < minimap.width() - borderMarginX &&
                        centerY > borderMarginY && centerY < minimap.height() - borderMarginY) {

                    Rect matchRect = new Rect((int) mmr.maxLoc.x, (int) mmr.maxLoc.y, lockedCoreTemplate.width(), lockedCoreTemplate.height());
                    Mat matchPatch = new Mat(minimap, matchRect);
                    MatOfDouble stdDev = new MatOfDouble();
                    MatOfDouble mean = new MatOfDouble();
                    Core.meanStdDev(matchPatch, mean, stdDev);
                    double[] patchDev = stdDev.toArray();
                    matchPatch.release();

                    if (patchDev[0] > 15.0 || patchDev[1] > 15.0 || patchDev[2] > 15.0) {
                        result.release();
                        drawDebugBox(minimap, centerX, centerY, lockedCoreTemplate.width(), lockedCoreTemplate.height(), new Scalar(0, 255, 0)); // green Box
                        return new Point(centerX, centerY);
                    }
                }
            }

            result.release();
            System.out.println("[TRACKER] Target occluded or hidden. Waiting for reappearance...");
            return null;
        }

        System.out.println("[TRACKER] Calibrating HUD Scale... Please ensure your champion is visible.");

        double bestScore = 0;
        Point bestCenter = null;
        Mat bestTemplate = null;

        for (double scale = 0.20; scale <= 2.50; scale += 0.05) {
            int targetWidth = (int) (championTemplate.width() * scale);
            int targetHeight = (int) (championTemplate.height() * scale);

            if (targetWidth < 8 || targetHeight < 8 || targetWidth > minimap.width() || targetHeight > minimap.height()) {
                continue;
            }

            Mat resizedTemplate = new Mat();
            Imgproc.resize(championTemplate, resizedTemplate, new org.opencv.core.Size(targetWidth, targetHeight), 0, 0, Imgproc.INTER_LINEAR);

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

            if (mmr.maxVal > bestScore) {
                double centerX = mmr.maxLoc.x + (coreTemplate.width() / 2.0);
                double centerY = mmr.maxLoc.y + (coreTemplate.height() / 2.0);

                if (centerX > borderMarginX && centerX < minimap.width() - borderMarginX &&
                        centerY > borderMarginY && centerY < minimap.height() - borderMarginY) {

                    bestScore = mmr.maxVal;
                    bestCenter = new Point(centerX, centerY);
                    if (bestTemplate != null) bestTemplate.release();
                    bestTemplate = coreTemplate.clone();
                }
            }

            coreTemplate.release();
            resizedTemplate.release();
            result.release();
        }

        if (bestScore > 0.65) {
            System.out.printf("[TRACKER] EXACT SCALE LOCKED! Match: %.2f%%\n", (bestScore * 100));
            this.lockedCoreTemplate = bestTemplate;
            this.isScaleLocked = true;

            drawDebugBox(minimap, bestCenter.x, bestCenter.y, lockedCoreTemplate.width(), lockedCoreTemplate.height(), new Scalar(0, 165, 255)); // orange Box
            return bestCenter;
        }

        if (bestTemplate != null) bestTemplate.release();
        return null;
    }

    private void drawDebugBox(Mat minimap, double centerX, double centerY, int width, int height, Scalar color) {
        if (WindowUtils.isWindowFocused("League of Legends")) {
            Mat debugMap = minimap.clone();
            Point topLeft = new Point(centerX - (width / 2.0), centerY - (height / 2.0));
            Point bottomRight = new Point(topLeft.x + width, topLeft.y + height);
            Imgproc.rectangle(debugMap, topLeft, bottomRight, color, 2);
            Imgcodecs.imwrite("debug_template_match.png", debugMap);
            debugMap.release();
        }
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