package me.bedwarshurts.leagueproximitychat.position;

import me.bedwarshurts.leagueproximitychat.managers.DebugManager;
import me.bedwarshurts.leagueproximitychat.utils.ImageUtils;
import me.bedwarshurts.leagueproximitychat.utils.LeagueConfigReader;
import me.bedwarshurts.leagueproximitychat.utils.MathUtils;
import me.bedwarshurts.leagueproximitychat.utils.RitoApiUtils;
import me.bedwarshurts.leagueproximitychat.utils.WindowUtils;
import org.opencv.core.*;
import org.opencv.core.Point;
import org.opencv.imgcodecs.Imgcodecs;
import org.opencv.imgproc.Imgproc;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import java.util.*;
import java.util.List;

public class ScreenPositionTracker {

    private Robot robot;
    private float userMinimapScale;
    private Mat championTemplate;
    private Mat lockedCoreTemplate = null;
    private Mat lockedCoreTemplateEnhanced = null;
    private static final double LOW_CONTRAST_STDDEV_THRESHOLD = 28.0;
    private boolean isScaleLocked = false;
    private boolean isBootstrapped = false;
    private boolean isColorblind = false;

    private int maxSeenCamW = 0;
    private int maxSeenCamH = 0;
    private int lastMinimapWidth = 0;

    private float lastKnownX = 50f;
    private float lastKnownY = 50f;

    private float healthBarCalibrateX = 0.0f;
    private float healthBarCalibrateY = 0.0f;
    private int calibrationFrames = 0;
    private boolean calibrationConverged = false;

    private static final int MIN_CALIBRATION_FRAMES = 15;

    private static final int MAX_CALIBRATION_FRAMES = 120;

    private static final float CONVERGENCE_THRESHOLD = 0.04f;

    private int consecutiveOutliers = 0;
    private static final int MAX_OUTLIER_STREAK = 25;
    private static final float OUTLIER_THRESHOLD = 2.5f;

    private static final int ROLLING_WINDOW_SIZE = 14;

    private final Deque<float[]> offsetWindow = new ArrayDeque<>();

    private static final float DRIFT_CONFIRM_THRESHOLD = 2f;
    private static final int DRIFT_SAMPLE_WINDOW = 10;
    private int driftConfirmStreak = 0;
    private static final int DRIFT_CONFIRM_REQUIRED = 2;

    private int driftSampleCount = 0;
    private double accumulatedDriftMagnitude = 0f;

    private int bootstrapConfidence = 0;
    private Point bootstrapLastPick = null;
    private static final int BOOTSTRAP_CONFIRM_FRAMES = 3;
    private static final double BOOTSTRAP_MAX_DIST = 12.0;
    private static final double BOOTSTRAP_MIN_CHAMPION_MATCH = 0.30;
    private static final double BOOTSTRAP_MATCH_MARGIN = 0.10;
    private static final double ALLY_ISOLATION_FACTOR = 1.1;

    private int lockedMatchFailures = 0;
    private static final int MAX_LOCKED_MATCH_FAILURES = 25;

    private int lockedBlipRadius = 0;
    private static final double BLIP_RADIUS_LOCKED_MIN = 0.7;
    private static final double BLIP_RADIUS_LOCKED_MAX = 1.45;

    private static final int MATCH_BLUR_KERNEL = 3;

    private static final double PROXIMITY_BOOST_MAX = 0.50;
    private static final double PROXIMITY_BOOST_RADIUS = 25.0;
    private static final double CLONE_DETECT_THRESHOLD = 0.78;
    private static final double MAX_HEALTHBAR_MATCH_DIST = 15.0;

    private int lastStrongMatchCount = 0;
    private int wrongLockStreak = 0;
    private static final int MAX_WRONG_LOCK_STREAK = 60;

    private static final double BLIP_RADIUS_PER_MINIMAP_PX = 16.0 / 280.0;

    private static final double ALLY_RING_CLOSE_FACTOR = 0.01;
    private static final double ALLY_PEAK_DEDUP_FACTOR = 0.45;
    private static final double ALLY_MIN_RADIUS_FACTOR = 0.025;
    private static final double ALLY_MAX_RADIUS_FACTOR = 0.10;

    private static final double OCCLUSION_MIN_VISIBLE_FRACTION = 0.25;
    private static final int OCCLUSION_JITTER_PX = 3;

    private static final double ICON_CORE_CROP = 0.65;
    private static final double ICON_CORE_MARGIN = (1.0 - ICON_CORE_CROP) / 2.0;

    private Rect cachedGameCrop = null;
    private int cachedResolutionWidth = -1;

    public record TrackResult(float x, float y, boolean isDead) {
    }

    public record TemplateMatch(Point center, double score) {
    }

    public record CameraBox(Point center, int width, int height) {
    }

    public record CandidateMatch(Point center, int width, int height, double score, double rawScore) {
    }

    private record EvalResult(Point center, double score) {
    }

    private record AllyCircle(Point center, int radius) {
    }

    public ScreenPositionTracker(Mat championTemplate) {
        try {
            this.robot = new Robot();
            LeagueConfigReader.LeagueSettings settings = LeagueConfigReader.loadSettings();
            this.userMinimapScale = settings.getMinimapScale();
            this.isColorblind = settings.isColorblind();
            this.championTemplate = championTemplate;
            System.out.println("[constructor] Tracker initialized. Target Health Bar Color: "
                    + (this.isColorblind ? "YELLOW" : "GREEN"));
        } catch (AWTException e) {
            System.err.println("[constructor] Failed to initialize Java Robot API");
            System.err.println("[constructor] Stacktrace: " + e.getMessage());
        }
    }

    public TrackResult trackPlayerPosition() {
        boolean isDead = checkDeathState();

        if (isDead) {
            return new TrackResult(lastKnownX, lastKnownY, true);
        }

        Rectangle gameBounds = WindowUtils.getGameWindowBounds("League of Legends (TM) Client");

        if (gameBounds == null) {
            return new TrackResult(lastKnownX, lastKnownY, false);
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
                    if (trueGameRect.width > fullScreenMat.width() * 0.5
                            && trueGameRect.height > fullScreenMat.height() * 0.5) {
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
        if (normalizedScale > 5.0f) normalizedScale /= 100.0f;

        double clampedScale = Math.clamp(normalizedScale, 0.0, 3.0);
        double currentMapPercent = 0.205 + ((0.268 - 0.205) * clampedScale);
        int perfectMapSize = (int) (fullScreenMat.height() * currentMapPercent);

        Rect minimapRoi = new Rect(
                fullScreenMat.width() - perfectMapSize,
                fullScreenMat.height() - perfectMapSize,
                perfectMapSize,
                perfectMapSize
        );
        Mat minimapMat = new Mat(fullScreenMat, minimapRoi).clone();

        if (DebugManager.isENABLED()) {
            Imgcodecs.imwrite("debug/debug_screen.png", fullScreenMat);
            Imgcodecs.imwrite("debug/debug_minimap.png", minimapMat);
            debugEnemyIndicators(minimapMat);
        }

        Point healthBarCenter = locateSelfHealthBar(fullScreenMat);

        CameraBox cameraBox = (healthBarCenter != null) ? locateMinimapCameraBox(minimapMat, fullScreenMat.width(), fullScreenMat.height()) : null;

        List<AllyCircle> allyCircles = findAllyLocations(minimapMat);
        List<AllyCircle> enemyCircles = findEnemyCircles(minimapMat);

        if (!isBootstrapped && healthBarCenter != null && cameraBox != null && !allyCircles.isEmpty()) {
            bootstrapTemplateFromHealthBar(minimapMat, allyCircles, cameraBox, healthBarCenter,
                    perfectMapSize, fullScreenMat.width(), fullScreenMat.height(), minimapMat);
        }

        float anchorX, anchorY;
        if (healthBarCenter != null && cameraBox != null) {
            float aHpX = calculateProjectedX(healthBarCenter.x, cameraBox, perfectMapSize, fullScreenMat.width());
            float aHpY = calculateProjectedY(healthBarCenter.y, cameraBox, perfectMapSize, fullScreenMat.height());
            anchorX = aHpX + healthBarCalibrateX;
            anchorY = aHpY + healthBarCalibrateY;
        } else {
            anchorX = lastKnownX;
            anchorY = lastKnownY;
        }

        TemplateMatch champMatch = locateChampionViaTemplate(minimapMat, allyCircles, enemyCircles, anchorX, anchorY);

        Point champMapCenter = (champMatch != null) ? champMatch.center() : null;
        double champScore = (champMatch != null) ? champMatch.score() : 0.0;

        if ((isScaleLocked || isBootstrapped) && healthBarCenter != null && cameraBox != null) {
            float rawHpX = calculateProjectedX(healthBarCenter.x, cameraBox, perfectMapSize, fullScreenMat.width());
            float rawHpY = calculateProjectedY(healthBarCenter.y, cameraBox, perfectMapSize, fullScreenMat.height());
            float currentHpMapX = rawHpX + healthBarCalibrateX;
            float currentHpMapY = rawHpY + healthBarCalibrateY;

            if (champMatch == null) {
                double distanceMoved = Math.hypot(currentHpMapX - lastKnownX, currentHpMapY - lastKnownY);

                if (!(distanceMoved < 1.5)) {
                    lockedMatchFailures++;
                    if (lockedMatchFailures >= MAX_LOCKED_MATCH_FAILURES) {
                        System.out.println("[bootstrap] Locked template failing repeatedly while moving — resetting to re-learn.");
                        resetScaleLock();
                    }
                }
            } else {
                lockedMatchFailures = 0;

                float matchX = (float) (champMapCenter.x / (double) perfectMapSize) * 100f;
                float matchY = 100f - (float) ((champMapCenter.y / (double) perfectMapSize) * 100f);
                double matchToHpDist = Math.hypot(matchX - currentHpMapX, matchY - currentHpMapY);

                if (matchToHpDist > MAX_HEALTHBAR_MATCH_DIST && lastStrongMatchCount < 2) {
                    wrongLockStreak++;
                    System.out.printf("[bootstrap] Locked match %.1f%% from health bar with only %d strong match(es) — possible wrong lock (%d/%d).%n",
                            matchToHpDist, lastStrongMatchCount, wrongLockStreak, MAX_WRONG_LOCK_STREAK);
                    if (wrongLockStreak >= MAX_WRONG_LOCK_STREAK) {
                        System.out.println("[bootstrap] Wrong lock confirmed (lone far match, not a clone) — resetting to re-learn.");
                        resetScaleLock();
                    }
                } else {
                    wrongLockStreak = 0;
                }
            }
        }

        if (healthBarCenter != null && champMapCenter != null && cameraBox != null && !calibrationConverged) {
            runCalibrationUpdate(healthBarCenter, champMapCenter, cameraBox,
                    (float) champScore, perfectMapSize, fullScreenMat.width(), fullScreenMat.height());
        }

        if (calibrationConverged && healthBarCenter != null && champMapCenter != null && cameraBox != null && champScore > 0.65) {
            monitorDrift(healthBarCenter, champMapCenter, cameraBox,
                    perfectMapSize, fullScreenMat.width(), fullScreenMat.height());
        }

        TrackResult result;

        if (healthBarCenter != null && cameraBox != null) {
            float rawHpX = calculateProjectedX(healthBarCenter.x, cameraBox, perfectMapSize, fullScreenMat.width());
            float rawHpY = calculateProjectedY(healthBarCenter.y, cameraBox, perfectMapSize, fullScreenMat.height());

            this.lastKnownX = rawHpX + this.healthBarCalibrateX;
            this.lastKnownY = rawHpY + this.healthBarCalibrateY;

            if (DebugManager.isENABLED()) System.out.printf("[trackPlayerPosition] HEALTHBAR -> X: %.2f%% | Y: %.2f%%%n", lastKnownX, lastKnownY);

            if (DebugManager.isENABLED()) {
                Mat debugHealthLoc = minimapMat.clone();

                double mapPixelX = (this.lastKnownX / 100.0) * perfectMapSize;
                double mapPixelY = ((100.0 - this.lastKnownY) / 100.0) * perfectMapSize;
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
                    double matchX = (champMapCenter.x / (double) perfectMapSize) * 100.0;
                    double matchY = 100.0 - ((champMapCenter.y / (double) perfectMapSize) * 100.0);
                    double gap = Math.hypot(matchX - lastKnownX, matchY - lastKnownY);
                    Imgproc.putText(debugHealthLoc, String.format("gap=%.1f%%", gap),
                            new Point(4, 14), Imgproc.FONT_HERSHEY_SIMPLEX, 0.4, new Scalar(0, 255, 0), 1);
                }

                Imgcodecs.imwrite("debug/debug_health_location.png", debugHealthLoc);
                debugHealthLoc.release();
            }

            result = new TrackResult(lastKnownX, lastKnownY, false);

        } else if (champMapCenter != null) {
            this.lastKnownX = ((float) champMapCenter.x / perfectMapSize) * 100f;
            this.lastKnownY = 100f - (((float) champMapCenter.y / perfectMapSize) * 100f);

            if (DebugManager.isENABLED()) System.out.printf("[trackPlayerPosition] MINIMAP TEMPLATE -> X: %.2f%% | Y: %.2f%%%n", lastKnownX, lastKnownY);
            result = new TrackResult(lastKnownX, lastKnownY, false);

        } else {
            if (DebugManager.isENABLED()) System.out.printf("[trackPlayerPosition] No detection — returning last known -> X: %.2f%% | Y: %.2f%%%n",
                    lastKnownX, lastKnownY);
            result = new TrackResult(lastKnownX, lastKnownY, false);
        }

        fullScreenMat.release();
        minimapMat.release();
        return result;
    }

    private void runCalibrationUpdate(Point healthBarCenter, Point champMapCenter, CameraBox cameraBox,
                                      float champScore, int perfectMapSize, int screenWidth, int screenHeight) {
        if (champScore <= 0.65f) {
            System.out.printf("[calibration] Paused — low template confidence (%.2f)%n", champScore);
            return;
        }

        float rawHpX = calculateProjectedX(healthBarCenter.x, cameraBox, perfectMapSize, screenWidth);
        float rawHpY = calculateProjectedY(healthBarCenter.y, cameraBox, perfectMapSize, screenHeight);

        float trueX = ((float) champMapCenter.x / perfectMapSize) * 100f;
        float trueY = 100f - (((float) champMapCenter.y / perfectMapSize) * 100f);

        float targetOffsetX = trueX - rawHpX;
        float targetOffsetY = trueY - rawHpY;

        float rawMatchDist = (float) Math.hypot(targetOffsetX, targetOffsetY);
        if (rawMatchDist > MAX_HEALTHBAR_MATCH_DIST) {
            if (lastStrongMatchCount >= 2) {
                System.out.printf("[calibration] Match %.1f%% from health-bar projection with %d strong matches — likely a clone, skipping frame.%n",
                        rawMatchDist, lastStrongMatchCount);
            } else {
                System.out.printf("[calibration] Match %.1f%% from health-bar projection with only %d strong match — wrong lock suspected, skipping frame.%n",
                        rawMatchDist, lastStrongMatchCount);
            }
            return;
        }

        if (!tryAcceptCalibrationSample(targetOffsetX, targetOffsetY)) {
            System.out.printf("[calibration] Outlier rejected (%.2f, %.2f)%n", targetOffsetX, targetOffsetY);
            return;
        }

        float alpha = computeAlpha(champScore);
        this.healthBarCalibrateX += (targetOffsetX - this.healthBarCalibrateX) * alpha;
        this.healthBarCalibrateY += (targetOffsetY - this.healthBarCalibrateY) * alpha;
        this.calibrationFrames++;

        System.out.printf("[calibration] Frame %d | alpha=%.3f | score=%.2f | Offsets -> X: %.3f, Y: %.3f%n",
                calibrationFrames, alpha, champScore, healthBarCalibrateX, healthBarCalibrateY);

        if (calibrationFrames >= MIN_CALIBRATION_FRAMES && hasConverged()) {
            this.calibrationConverged = true;
            System.out.printf("[calibration] CONVERGED at frame %d. Final Offsets -> X: %.3f, Y: %.3f%n",
                    calibrationFrames, healthBarCalibrateX, healthBarCalibrateY);
        } else if (calibrationFrames >= MAX_CALIBRATION_FRAMES) {
            this.calibrationConverged = true;
            System.out.printf("[calibration] Max frames reached — force-locking. Final Offsets -> X: %.3f, Y: %.3f%n",
                    healthBarCalibrateX, healthBarCalibrateY);
        }
    }

    private float computeAlpha(float score) {
        float scoreWeight = Math.clamp((score - 0.60f) / 0.40f, 0f, 1f);
        float progress = Math.min(1.0f, (float) calibrationFrames / MAX_CALIBRATION_FRAMES);
        float decayedBase = 0.30f * (1.0f - progress) + 0.05f * progress;
        return decayedBase * (0.5f + 0.5f * scoreWeight);
    }

    private boolean tryAcceptCalibrationSample(float offsetX, float offsetY) {
        if (offsetWindow.size() < ROLLING_WINDOW_SIZE / 2) {
            offsetWindow.addLast(new float[]{offsetX, offsetY});
            return true;
        }

        float medianX = computeMedian(offsetWindow, 0);
        float medianY = computeMedian(offsetWindow, 1);

        if (Math.abs(offsetX - medianX) > OUTLIER_THRESHOLD
                || Math.abs(offsetY - medianY) > OUTLIER_THRESHOLD) {

            consecutiveOutliers++;
            if (consecutiveOutliers >= MAX_OUTLIER_STREAK) {
                System.out.printf("[calibration] %d consecutive outliers! Camera shift detected. Wiping previous median.%n", consecutiveOutliers);

                offsetWindow.clear();
                consecutiveOutliers = 0;

                this.calibrationFrames = 0;

                offsetWindow.addLast(new float[]{offsetX, offsetY});
                return true;
            }

            return false;
        }

        consecutiveOutliers = 0;

        if (offsetWindow.size() >= ROLLING_WINDOW_SIZE) {
            offsetWindow.pollFirst();
        }
        offsetWindow.addLast(new float[]{offsetX, offsetY});
        return true;
    }

    private boolean hasConverged() {
        if (offsetWindow.size() < 2) return false;

        float medianX = computeMedian(offsetWindow, 0);
        float medianY = computeMedian(offsetWindow, 1);
        float maxDevX = 0f;
        float maxDevY = 0f;

        for (float[] sample : offsetWindow) {
            maxDevX = Math.max(maxDevX, Math.abs(sample[0] - medianX));
            maxDevY = Math.max(maxDevY, Math.abs(sample[1] - medianY));
        }

        return maxDevX < CONVERGENCE_THRESHOLD && maxDevY < CONVERGENCE_THRESHOLD;
    }

    private float computeMedian(Deque<float[]> window, int index) {
        float[] values = new float[window.size()];
        int i = 0;
        for (float[] sample : window) values[i++] = sample[index];
        Arrays.sort(values);
        int mid = values.length / 2;
        return (values.length % 2 == 0) ? (values[mid - 1] + values[mid]) / 2.0f : values[mid];
    }

    private void monitorDrift(Point healthBarCenter, Point champMapCenter, CameraBox cameraBox,
                              int perfectMapSize, int screenWidth, int screenHeight) {

        float rawHpX = calculateProjectedX(healthBarCenter.x, cameraBox, perfectMapSize, screenWidth);
        float rawHpY = calculateProjectedY(healthBarCenter.y, cameraBox, perfectMapSize, screenHeight);

        float calibratedHpX = rawHpX + healthBarCalibrateX;
        float calibratedHpY = rawHpY + healthBarCalibrateY;

        float trueX = ((float) champMapCenter.x / perfectMapSize) * 100f;
        float trueY = 100f - (((float) champMapCenter.y / perfectMapSize) * 100f);

        double currentFrameDrift = Math.hypot(trueX - calibratedHpX, trueY - calibratedHpY);

        if (currentFrameDrift > MAX_HEALTHBAR_MATCH_DIST) {
            return;
        }

        accumulatedDriftMagnitude += currentFrameDrift;
        driftSampleCount++;

        if (driftSampleCount >= DRIFT_SAMPLE_WINDOW) {
            double avgDrift = accumulatedDriftMagnitude / driftSampleCount;

            if (avgDrift > DRIFT_CONFIRM_THRESHOLD) {
                driftConfirmStreak++;

                if (driftConfirmStreak >= DRIFT_CONFIRM_REQUIRED) {
                    System.out.printf("[drift] Systematic drift detected — Variance: %.2f%%. Re-entering calibration.%n", avgDrift);
                    this.calibrationConverged = false;
                    this.calibrationFrames = 0;
                    this.offsetWindow.clear();
                    driftConfirmStreak = 0;
                }
            } else {
                driftConfirmStreak = 0;
            }
            accumulatedDriftMagnitude = 0f;
            driftSampleCount = 0;
        }
    }

    private void bootstrapTemplateFromHealthBar(Mat minimap, List<AllyCircle> allies, CameraBox cameraBox,
                                                Point healthBarCenter, int perfectMapSize,
                                                int screenWidth, int screenHeight, Mat minimapMat) {
        float projX = calculateProjectedX(healthBarCenter.x, cameraBox, perfectMapSize, screenWidth) + healthBarCalibrateX;
        float projY = calculateProjectedY(healthBarCenter.y, cameraBox, perfectMapSize, screenHeight) + healthBarCalibrateY;

        AllyCircle nearest = null;
        double bestScore = -1.0;
        double secondScore = -1.0;

        for (AllyCircle a : allies) {
            double ax = (a.center().x / minimap.width()) * 100.0;
            double ay = 100.0 - ((a.center().y / minimap.height()) * 100.0);
            if (Math.hypot(ax - projX, ay - projY) > BOOTSTRAP_MAX_DIST) continue;

            Mat patch = extractIconTemplate(minimap, a.center(), a.radius());
            if (patch == null) continue;
            double score = championMatchScore(patch);
            saveTemplateDebug("debug/debug_extracted_icon_template_last.png", patch, score);
            patch.release();

            System.out.printf("[bootstrap] candidate @(%.0f,%.0f) championMatch=%.2f%n",
                    a.center().x, a.center().y, score);

            if (score > bestScore) {
                secondScore = bestScore;
                bestScore = score;
                nearest = a;
            } else if (score > secondScore) {
                secondScore = score;
            }
        }

        if (nearest == null || bestScore < BOOTSTRAP_MIN_CHAMPION_MATCH) {
            bootstrapConfidence = 0;
            bootstrapLastPick = null;
            return;
        }
        if (secondScore >= 0 && (bestScore - secondScore) < BOOTSTRAP_MATCH_MARGIN) {
            System.out.printf("[bootstrap] Ambiguous appearance match (%.2f vs %.2f) — waiting for separation.%n",
                    bestScore, secondScore);
            bootstrapConfidence = 0;
            bootstrapLastPick = null;
            return;
        }

        if (isRegionContaminatedByEnemy(minimapMat, nearest.center(), nearest.radius())) {
            System.out.println("[bootstrap] Target circle isolated, but contaminated by enemy indicators. Skipping frame.");
            bootstrapConfidence = 0;
            bootstrapLastPick = null;
            return;
        }

        if (!isAllyCircleIsolated(nearest, allies)) {
            System.out.println("[bootstrap] Target overlapped by another ally icon — waiting for a clean frame.");
            bootstrapConfidence = 0;
            bootstrapLastPick = null;
            return;
        }

        if (bootstrapLastPick != null
                && Math.hypot(nearest.center().x - bootstrapLastPick.x, nearest.center().y - bootstrapLastPick.y) < 5.0) {
            bootstrapConfidence++;
        } else {
            bootstrapConfidence = 1;
        }
        bootstrapLastPick = nearest.center();

        if (bootstrapConfidence < BOOTSTRAP_CONFIRM_FRAMES) return;

        Mat core = extractIconTemplate(minimap, nearest.center(), nearest.radius());
        if (core == null) return;

        double validationScore = championMatchScore(core);
        System.out.printf("[bootstrap] Learned-vs-DDragon validation score: %.2f%n", validationScore);
        saveTemplateDebug("debug/debug_extracted_icon_template_lock.png", core, validationScore);
        saveLockContext(minimap, nearest, validationScore);

        if (lockedCoreTemplate != null) lockedCoreTemplate.release();
        if (lockedCoreTemplateEnhanced != null) lockedCoreTemplateEnhanced.release();
        lockedCoreTemplate = core;
        lockedBlipRadius = nearest.radius();
        isScaleLocked = true;
        isBootstrapped = true;
        lockedMatchFailures = 0;

        double sigma = ImageUtils.getStdDev(lockedCoreTemplate);
        lockedCoreTemplateEnhanced = (sigma < LOW_CONTRAST_STDDEV_THRESHOLD)
                ? ImageUtils.applyEnhancement(lockedCoreTemplate)
                : null;

        System.out.printf("[bootstrap] Self-learned template from live minimap (σ=%.1f)%s — scale lock acquired.%n",
                sigma, lockedCoreTemplateEnhanced != null ? " [CLAHE]" : "");
    }

    private boolean isAllyCircleIsolated(AllyCircle target, List<AllyCircle> allies) {
        for (AllyCircle other : allies) {
            if (other == target) continue;
            double dist = Math.hypot(other.center().x - target.center().x,
                    other.center().y - target.center().y);
            double minSeparation = (target.radius() + other.radius()) * ALLY_ISOLATION_FACTOR;
            if (dist < minSeparation) return false;
        }
        return true;
    }

    private double championMatchScore(Mat learnedCore) {
        if (championTemplate == null || learnedCore.empty()) return -1.0;
        Mat ddragonCore = null;
        Mat ref = new Mat();
        Mat result = new Mat();
        try {
            int cx = (int) (championTemplate.width() * ICON_CORE_MARGIN);
            int cy = (int) (championTemplate.height() * ICON_CORE_MARGIN);
            int cw = (int) (championTemplate.width() * ICON_CORE_CROP);
            int ch = (int) (championTemplate.height() * ICON_CORE_CROP);
            if (cw < 4 || ch < 4) return -1.0;
            ddragonCore = new Mat(championTemplate, new Rect(cx, cy, cw, ch));

            int refW = Math.max(4, (int) (learnedCore.width() * 0.85));
            int refH = Math.max(4, (int) (learnedCore.height() * 0.85));
            if (refW > learnedCore.width() || refH > learnedCore.height()) return -1.0;
            Imgproc.resize(ddragonCore, ref, new Size(refW, refH), 0, 0, Imgproc.INTER_AREA);

            Imgproc.matchTemplate(learnedCore, ref, result, Imgproc.TM_CCOEFF_NORMED);
            return Core.minMaxLoc(result).maxVal;
        } catch (Exception e) {
            return -1.0;
        } finally {
            if (ddragonCore != null) ddragonCore.release();
            ref.release();
            result.release();
        }
    }

    private void saveTemplateDebug(String path, Mat core, double score) {
        if (!DebugManager.isENABLED() || core == null || core.empty()) return;
        Mat big = new Mat();
        try {
            Imgproc.resize(core, big, new Size(220, 220), 0, 0, Imgproc.INTER_NEAREST);
            drawCaption(big, score);
            Imgcodecs.imwrite(path, big);
        } catch (Exception ignored) {
        } finally {
            big.release();
        }
    }

    private void saveLockContext(Mat minimap, AllyCircle pick, double score) {
        if (!DebugManager.isENABLED()) return;
        Mat ctx = minimap.clone();
        try {
            Imgproc.circle(ctx, pick.center(), pick.radius(), new Scalar(0, 255, 0), 1);
            Imgproc.circle(ctx, pick.center(), 1, new Scalar(0, 0, 255), -1);
            drawCaption(ctx, score);
            Imgcodecs.imwrite("debug/debug_lock_minimap.png", ctx);
        } catch (Exception ignored) {
        } finally {
            ctx.release();
        }
    }

    private void drawCaption(Mat img, double score) {
        String caption = new java.text.SimpleDateFormat("HH:mm:ss").format(new java.util.Date())
                + String.format("  s=%.2f", score);
        int[] baseline = new int[1];
        Size txt = Imgproc.getTextSize(caption, Imgproc.FONT_HERSHEY_SIMPLEX, 0.4, 1, baseline);
        Point org = new Point(Math.max(2, img.width() - txt.width - 4), img.height() - 6);
        Imgproc.putText(img, caption, new Point(org.x + 1, org.y + 1),
                Imgproc.FONT_HERSHEY_SIMPLEX, 0.4, new Scalar(0, 0, 0), 2);
        Imgproc.putText(img, caption, org,
                Imgproc.FONT_HERSHEY_SIMPLEX, 0.4, new Scalar(255, 255, 255), 1);
    }

    private boolean isRegionContaminatedByEnemy(Mat minimap, Point center, int radius) {
        int expectedRadius = (int) Math.round(minimap.width() * BLIP_RADIUS_PER_MINIMAP_PX);
        int checkRadius = Math.max((int) (radius * 1.6), (int) (expectedRadius * 1.6));

        int x0 = (int) Math.max(0, center.x - checkRadius);
        int y0 = (int) Math.max(0, center.y - checkRadius);
        int x1 = (int) Math.min(minimap.width(), center.x + checkRadius);
        int y1 = (int) Math.min(minimap.height(), center.y + checkRadius);
        int w = x1 - x0;
        int h = y1 - y0;

        if (w <= 0 || h <= 0) return true;

        Mat roi = new Mat(minimap, new Rect(x0, y0, w, h));
        Mat redMask = new Mat();
        enemyRedMask(roi, redMask);

        Point roiCenter = new Point(center.x - x0, center.y - y0);
        int ignoreRadius = (int) (radius * 1.05);
        Imgproc.circle(redMask, roiCenter, ignoreRadius, new Scalar(0), -1);

        int redPixelCount = Core.countNonZero(redMask);

        double totalCheckedPixels = Math.max(1.0, (w * (double) h) - (Math.PI * ignoreRadius * (double) ignoreRadius));
        double redRatio = redPixelCount / totalCheckedPixels;

        if (DebugManager.isENABLED()) {
            Imgcodecs.imwrite("debug/debug_enemy_red_mask.png", redMask);
        }

        roi.release();
        redMask.release();

        return redRatio > 0.01;
    }

    private void enemyRedMask(Mat bgr, Mat out) {
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

    private void debugEnemyIndicators(Mat minimap) {
        Mat redMask = new Mat();
        Mat overlay = minimap.clone();
        try {
            enemyRedMask(minimap, redMask);
            overlay.setTo(new Scalar(0, 255, 0), redMask);
            Imgcodecs.imwrite("debug/debug_enemy_indicators.png", overlay);
        } finally {
            redMask.release();
            overlay.release();
        }
    }

    private Mat extractIconTemplate(Mat minimap, Point center, int radius) {
        int boxHalf = Math.max(4, radius);
        int x = (int) Math.max(0, center.x - boxHalf);
        int y = (int) Math.max(0, center.y - boxHalf);
        int w = Math.min(minimap.width() - x, boxHalf * 2);
        int h = Math.min(minimap.height() - y, boxHalf * 2);
        if (w < 6 || h < 6) return null;

        Mat region = new Mat(minimap, new Rect(x, y, w, h)).clone();

        int cx = (int) (region.width() * ICON_CORE_MARGIN);
        int cy = (int) (region.height() * ICON_CORE_MARGIN);
        int cw = (int) (region.width() * ICON_CORE_CROP);
        int ch = (int) (region.height() * ICON_CORE_CROP);
        if (cw < 4 || ch < 4) {
            region.release();
            return null;
        }

        Mat core = new Mat(region, new Rect(cx, cy, cw, ch)).clone();
        region.release();
        return core;
    }

    private void resetScaleLock() {
        if (lockedCoreTemplate != null) {
            lockedCoreTemplate.release();
            lockedCoreTemplate = null;
        }
        if (lockedCoreTemplateEnhanced != null) {
            lockedCoreTemplateEnhanced.release();
            lockedCoreTemplateEnhanced = null;
        }
        isScaleLocked = false;
        isBootstrapped = false;
        lockedMatchFailures = 0;
        wrongLockStreak = 0;
        lockedBlipRadius = 0;
        bootstrapConfidence = 0;
        bootstrapLastPick = null;
    }

    private float calculateProjectedX(double healthBarX, CameraBox cameraBox, int perfectMapSize, int screenWidth) {
        float cameraCenterX = (float) cameraBox.center().x;
        float PERSPECTIVE_COMPENSATION_X = 0.68f;
        float dynamicRatioX = (cameraBox.width() > 0)
                ? ((float) cameraBox.width() * PERSPECTIVE_COMPENSATION_X) / screenWidth
                : 0.021f;

        float offsetX = (float) healthBarX - (screenWidth / 2.0f);
        float finalX = cameraCenterX + (offsetX * dynamicRatioX);
        return (finalX / perfectMapSize) * 100f;
    }

    private float calculateProjectedY(double healthBarY, CameraBox cameraBox, int perfectMapSize, int screenHeight) {
        float feetY = (float) (healthBarY + (screenHeight * 0.074f));
        float cameraCenterY = (float) cameraBox.center().y;
        float PERSPECTIVE_COMPENSATION_Y = 0.75f;
        float dynamicRatioY = (cameraBox.height() > 0)
                ? ((float) cameraBox.height() * PERSPECTIVE_COMPENSATION_Y) / screenHeight
                : 0.021f;

        float offsetY = feetY - (screenHeight / 2.0f);
        float finalY = cameraCenterY + (offsetY * dynamicRatioY);
        return 100f - ((finalY / perfectMapSize) * 100f);
    }

    private boolean checkDeathState() {
        String localSummonerName = RitoApiUtils.getLocalSummonerName();
        if (localSummonerName == null) return false;

        String playerListJson = RitoApiUtils.fetchPlayerListRaw();
        if (playerListJson == null) return false;

        int nameIdx = playerListJson.indexOf("\"" + localSummonerName + "\"");
        if (nameIdx == -1) return false;

        int blockStart = playerListJson.lastIndexOf("\"championName\":", nameIdx);
        if (blockStart == -1) return false;

        int blockEnd = playerListJson.indexOf("\"championName\":", blockStart + 15);
        if (blockEnd == -1) blockEnd = playerListJson.length();

        String playerBlock = playerListJson.substring(blockStart, blockEnd).replaceAll("\\s+", "");
        return playerBlock.contains("\"isDead\":true");
    }

    private Point locateSelfHealthBar(Mat screen) {
        Mat hsv = new Mat();
        Mat mask = new Mat();
        Mat hierarchy = new Mat();
        List<MatOfPoint> contours = new ArrayList<>();
        Point resultPoint = null;

        try {
            Imgproc.cvtColor(screen, hsv, Imgproc.COLOR_BGR2HSV);

            Scalar lowerColor = this.isColorblind ? new Scalar(23, 140, 200) : new Scalar(45, 100, 100);
            Scalar upperColor = this.isColorblind ? new Scalar(26, 225, 255) : new Scalar(75, 255, 255);
            Core.inRange(hsv, lowerColor, upperColor, mask);

            int hudTopY = (int) (screen.height() * 0.75);
            Imgproc.rectangle(mask, new Point(0, hudTopY), new Point(screen.width(), screen.height()), new Scalar(0), -1);
            Imgproc.rectangle(mask, new Point(screen.width() * 0.85, 0), new Point(screen.width(), screen.height() * 0.10), new Scalar(0), -1);

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

            if (DebugManager.isENABLED()) {
                Mat debugHealthMap = new Mat();
                Imgproc.cvtColor(mask, debugHealthMap, Imgproc.COLOR_GRAY2BGR);
                if (bestBar != null) {
                    Imgproc.rectangle(debugHealthMap,
                            new Point(bestBar.x, bestBar.y),
                            new Point(bestBar.x + bestBar.width, bestBar.y + bestBar.height),
                            new Scalar(0, 0, 255), 2);
                }
                Imgcodecs.imwrite("debug/debug_health_mask.png", debugHealthMap);
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

    private CameraBox locateMinimapCameraBox(Mat minimap, int screenWidth, int screenHeight) {
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

                if (DebugManager.isENABLED()) {
                    Mat debugMask = Mat.zeros(thresholded.size(), CvType.CV_8UC1);
                    Imgproc.drawContours(debugMask, List.of(cameraContour), -1, new Scalar(255), 1);
                    Imgcodecs.imwrite("debug/debug_camera_mask.png", debugMask);
                    debugMask.release();

                    Mat debugReconstructed = minimap.clone();
                    Imgproc.drawContours(debugReconstructed, List.of(cameraContour), -1, new Scalar(0, 0, 255), 1);

                    Scalar boxColor = isClipped ? new Scalar(255, 255, 0) : new Scalar(0, 255, 0);
                    Point topLeft = new Point(trueCenterX - (maxSeenCamW / 2.0), trueCenterY - (maxSeenCamH / 2.0));
                    Point bottomRight = new Point(trueCenterX + (maxSeenCamW / 2.0), trueCenterY + (maxSeenCamH / 2.0));

                    Imgproc.rectangle(debugReconstructed, topLeft, bottomRight, boxColor, 2);
                    Imgproc.circle(debugReconstructed, center, 2, boxColor, -1);

                    Imgcodecs.imwrite("debug/debug_camera_reconstructed.png", debugReconstructed);
                    debugReconstructed.release();
                }

                return new CameraBox(center, Math.max(0, maxSeenCamW), Math.max(0, maxSeenCamH));
            } else {
                if (DebugManager.isENABLED()) {
                    Imgcodecs.imwrite("debug/debug_camera_mask.png", thresholded);
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

    private EvalResult evaluateTemplateAtAlly(Mat minimap, Point ally, Mat template, int padding) {
        return evaluateTemplateAtAlly(minimap, ally, template, null, padding);
    }

    private EvalResult evaluateTemplateAtAlly(Mat minimap, Point ally, Mat template,
                                              Mat enhancedTemplate, int padding) {
        int cw = (enhancedTemplate != null ? enhancedTemplate : template).width();
        int ch = (enhancedTemplate != null ? enhancedTemplate : template).height();

        int startX = (int) Math.max(0, ally.x - (cw / 2.0) - padding);
        int startY = (int) Math.max(0, ally.y - (ch / 2.0) - padding);
        int roiW = cw + (padding * 2);
        int roiH = ch + (padding * 2);

        if (startX + roiW > minimap.width()) roiW = minimap.width() - startX;
        if (startY + roiH > minimap.height()) roiH = minimap.height() - startY;
        if (roiW < cw || roiH < ch) return null;

        Mat localRoi = new Mat(minimap, new Rect(startX, startY, roiW, roiH));
        Mat result = new Mat();
        Mat matchRoi = localRoi;
        Mat enhancedRoi = null;
        Mat blurredTemplate = null;
        Mat blurredRoi = null;

        try {
            Mat matchTemplate = template;

            if (enhancedTemplate != null) {
                enhancedRoi = ImageUtils.applyEnhancement(localRoi);
                matchRoi = enhancedRoi;
                matchTemplate = enhancedTemplate;
            }

            if (MATCH_BLUR_KERNEL >= 3
                    && matchTemplate.width() >= MATCH_BLUR_KERNEL && matchTemplate.height() >= MATCH_BLUR_KERNEL
                    && matchRoi.width() >= MATCH_BLUR_KERNEL && matchRoi.height() >= MATCH_BLUR_KERNEL) {
                blurredTemplate = new Mat();
                blurredRoi = new Mat();
                Imgproc.GaussianBlur(matchTemplate, blurredTemplate, new Size(MATCH_BLUR_KERNEL, MATCH_BLUR_KERNEL), 0);
                Imgproc.GaussianBlur(matchRoi, blurredRoi, new Size(MATCH_BLUR_KERNEL, MATCH_BLUR_KERNEL), 0);
                matchTemplate = blurredTemplate;
                matchRoi = blurredRoi;
            }

            Imgproc.matchTemplate(matchRoi, matchTemplate, result, Imgproc.TM_CCOEFF_NORMED);
            Core.MinMaxLocResult mmr = Core.minMaxLoc(result);

            double matchCenterX = startX + mmr.maxLoc.x + (cw / 2.0);
            double matchCenterY = startY + mmr.maxLoc.y + (ch / 2.0);

            return new EvalResult(new Point(matchCenterX, matchCenterY), mmr.maxVal);
        } finally {
            localRoi.release();
            result.release();
            if (enhancedRoi != null) enhancedRoi.release();
            if (blurredTemplate != null) blurredTemplate.release();
            if (blurredRoi != null) blurredRoi.release();
        }
    }

    private List<AllyCircle> overlappingNeighbors(AllyCircle self, List<AllyCircle> allies, List<AllyCircle> enemies) {
        List<AllyCircle> neighbors = new ArrayList<>();
        for (AllyCircle other : allies) {
            if (other == self) continue;
            if (circlesOverlap(self, other)) neighbors.add(other);
        }
        for (AllyCircle other : enemies) {
            if (circlesOverlap(self, other)) neighbors.add(other);
        }
        return neighbors;
    }

    private static boolean circlesOverlap(AllyCircle a, AllyCircle b) {
        double sep = Math.hypot(a.center().x - b.center().x, a.center().y - b.center().y);
        return sep < a.radius() + b.radius();
    }

    private EvalResult evaluateOccludedAlly(Mat minimap, AllyCircle self, List<AllyCircle> occluders, Mat template) {
        int cw = template.width();
        int ch = template.height();
        int channels = template.channels();
        if (channels != minimap.channels()) return null;

        byte[] tpl = new byte[cw * ch * channels];
        template.get(0, 0, tpl);

        byte[] patch = new byte[cw * ch * channels];
        boolean[] visible = new boolean[cw * ch];
        int minVisible = (int) (cw * ch * OCCLUSION_MIN_VISIBLE_FRACTION);

        double bestScore = -1.0;
        Point bestCenter = null;

        for (int dy = -OCCLUSION_JITTER_PX; dy <= OCCLUSION_JITTER_PX; dy++) {
            for (int dx = -OCCLUSION_JITTER_PX; dx <= OCCLUSION_JITTER_PX; dx++) {
                int x0 = (int) Math.round(self.center().x + dx - cw / 2.0);
                int y0 = (int) Math.round(self.center().y + dy - ch / 2.0);
                if (x0 < 0 || y0 < 0 || x0 + cw > minimap.width() || y0 + ch > minimap.height()) continue;

                int visibleCount = 0;
                for (int y = 0; y < ch; y++) {
                    for (int x = 0; x < cw; x++) {
                        double gx = x0 + x + 0.5;
                        double gy = y0 + y + 0.5;
                        double dSelf = Math.hypot(gx - self.center().x, gy - self.center().y);
                        boolean v = true;
                        for (AllyCircle occ : occluders) {
                            double dOcc = Math.hypot(gx - occ.center().x, gy - occ.center().y);
                            if (dOcc < occ.radius() && dOcc < dSelf) {
                                v = false;
                                break;
                            }
                        }
                        visible[y * cw + x] = v;
                        if (v) visibleCount++;
                    }
                }
                if (visibleCount < minVisible) continue;

                Mat patchMat = new Mat(minimap, new Rect(x0, y0, cw, ch)).clone();
                patchMat.get(0, 0, patch);
                patchMat.release();

                double score = MathUtils.maskedZncc(tpl, patch, visible, channels);
                if (score > bestScore) {
                    bestScore = score;
                    bestCenter = new Point(x0 + (cw / 2.0), y0 + (ch / 2.0));
                }
            }
        }

        if (bestCenter == null || bestScore <= 0) return null;
        return new EvalResult(bestCenter, bestScore);
    }

    private TemplateMatch locateChampionViaTemplate(Mat minimap, List<AllyCircle> allyCircles,
                                                    List<AllyCircle> enemyCircles,
                                                    double anchorX, double anchorY) {
        int borderMarginX = (int) (minimap.width() * 0.03);
        int borderMarginY = (int) (minimap.height() * 0.03);
        lastStrongMatchCount = 0;

        if (allyCircles.isEmpty()) {
            if (DebugManager.isENABLED()) System.out.println("[locateChampionViaTemplate] FAILED: 0 blue ally circles found on the minimap.");
            return null;
        }

        if ((isScaleLocked || isBootstrapped) && lockedCoreTemplate != null) {
            double bestScore = -1.0;
            Point bestCenter = null;
            double rawScoreLog = 0.0;
            int cw = lockedCoreTemplate.width();
            int ch = lockedCoreTemplate.height();
            List<CandidateMatch> candidates = new ArrayList<>();

            int strongMatches = 0;
            for (AllyCircle ally : allyCircles) {
                int searchPad = (int) Math.clamp(ally.radius() - lockedCoreTemplate.width() / 2.0,
                        2.0, lockedCoreTemplate.width() * 3.0);
                EvalResult eval = evaluateTemplateAtAlly(minimap, ally.center(), lockedCoreTemplate, lockedCoreTemplateEnhanced, searchPad);
                if (eval == null) continue;

                double rawScore = eval.score();
                Point candidateCenter = eval.center();

                List<AllyCircle> occluders = overlappingNeighbors(ally, allyCircles, enemyCircles);
                if (!occluders.isEmpty()) {
                    EvalResult occluded = evaluateOccludedAlly(minimap, ally, occluders, lockedCoreTemplate);
                    if (occluded != null && occluded.score() > rawScore) {
                        rawScore = occluded.score();
                        candidateCenter = occluded.center();
                    }
                }

                if (rawScore > CLONE_DETECT_THRESHOLD) strongMatches++;

                double candidateX = (candidateCenter.x / minimap.width()) * 100.0;
                double candidateY = 100.0 - ((candidateCenter.y / minimap.height()) * 100.0);

                double dist = Math.hypot(candidateX - anchorX, candidateY - anchorY);
                double boost = PROXIMITY_BOOST_MAX * Math.max(0.0, 1.0 - (dist / PROXIMITY_BOOST_RADIUS));
                double score = rawScore + boost;

                candidates.add(new CandidateMatch(candidateCenter, cw, ch, score, rawScore));

                if (score > bestScore) {
                    bestScore = score;
                    bestCenter = candidateCenter;
                    rawScoreLog = rawScore;
                }
            }

            lastStrongMatchCount = strongMatches;

            if (DebugManager.isENABLED() && strongMatches >= 2) {
                System.out.printf("[locateChampionViaTemplate] %d strong icon matches — clone likely present; anchoring to (%.1f, %.1f).%n",
                        strongMatches, anchorX, anchorY);
            }

            drawTop10Debug(minimap, candidates);

            if (bestScore > 0.45 && bestCenter.x > borderMarginX && bestCenter.x < minimap.width() - borderMarginX && bestCenter.y > borderMarginY && bestCenter.y < minimap.height() - borderMarginY) {

                drawDebugBox(minimap, bestCenter.x, bestCenter.y,
                        lockedCoreTemplate.width(), lockedCoreTemplate.height(), new Scalar(0, 255, 0));
                if (DebugManager.isENABLED()) System.out.printf("[locateChampionViaTemplate] Locked Scale Match -> Raw: %.2f%% | Boosted: %.2f%%%n",
                        rawScoreLog * 100, bestScore * 100);
                return new TemplateMatch(bestCenter, bestScore);
            }

            return null;
        }

        if (championTemplate == null) {
            return null;
        }

        double globalBestScore = 0;
        Point globalBestCenter = null;
        Mat globalBestTemplate = null;
        int globalBestSize = 0;

        Map<Point, CandidateMatch> globalCandidatesMap = new HashMap<>();
        List<Mat> crops = new ArrayList<>();

        for (int targetSize = 120; targetSize >= 20; targetSize--) {
            if (targetSize > minimap.width() || targetSize > minimap.height()) continue;

            Mat resizedTemplate = new Mat();
            Imgproc.resize(championTemplate, resizedTemplate, new Size(targetSize, targetSize), 0, 0, Imgproc.INTER_AREA);

            int cx = (int) (resizedTemplate.width() * 0.125);
            int cy = (int) (resizedTemplate.height() * 0.125);
            int cw = (int) (resizedTemplate.width() * 0.75);
            int ch = (int) (resizedTemplate.height() * 0.75);

            if (cw <= 0 || ch <= 0) {
                resizedTemplate.release();
                continue;
            }

            Mat coreTemplate = new Mat(resizedTemplate, new Rect(cx, cy, cw, ch));
            Mat enhancedCore = (ImageUtils.getStdDev(coreTemplate) < LOW_CONTRAST_STDDEV_THRESHOLD)
                    ? ImageUtils.applyEnhancement(coreTemplate)
                    : null;

            if (DebugManager.isENABLED()) crops.add(coreTemplate.clone());

            for (AllyCircle ally : allyCircles) {
                EvalResult eval = evaluateTemplateAtAlly(minimap, ally.center(), coreTemplate, enhancedCore, 4);
                if (eval == null) continue;

                Point matchCenter = eval.center();
                double matchScore = eval.score();

                if (matchCenter.x > borderMarginX && matchCenter.x < minimap.width() - borderMarginX
                        && matchCenter.y > borderMarginY && matchCenter.y < minimap.height() - borderMarginY) {

                    if (matchScore > globalBestScore) {
                        globalBestScore = matchScore;
                        globalBestCenter = matchCenter;
                        if (globalBestTemplate != null) globalBestTemplate.release();
                        globalBestTemplate = coreTemplate.clone();
                        globalBestSize = targetSize;
                    }

                    CandidateMatch current = globalCandidatesMap.get(ally.center());
                    if (current == null || matchScore > current.score()) {
                        globalCandidatesMap.put(ally.center(), new CandidateMatch(matchCenter, cw, ch, matchScore, matchScore));
                    }
                }
            }

            if (enhancedCore != null) enhancedCore.release();
            coreTemplate.release();
            resizedTemplate.release();
        }

        drawCroppedTemplates(crops);
        drawTop10Debug(minimap, new ArrayList<>(globalCandidatesMap.values()));

        if (globalBestScore > 0.67) {
            System.out.printf("[locateChampionViaTemplate] Scale locked at %dpx! Match: %.2f%%%n",
                    globalBestSize, globalBestScore * 100);
            this.lockedCoreTemplate = globalBestTemplate;

            double sigma = ImageUtils.getStdDev(this.lockedCoreTemplate);
            if (sigma < LOW_CONTRAST_STDDEV_THRESHOLD) {
                this.lockedCoreTemplateEnhanced = ImageUtils.applyEnhancement(this.lockedCoreTemplate);
                System.out.printf("[locateChampionViaTemplate] Low-contrast template detected (σ=%.1f) — CLAHE enabled.%n", sigma);
            } else {
                this.lockedCoreTemplateEnhanced = null;
            }

            if (DebugManager.isENABLED()) Imgcodecs.imwrite("debug/debug_locked_template.png", lockedCoreTemplate);
            this.lockedBlipRadius = Math.max(1, globalBestSize / 2);
            this.isScaleLocked = true;
            this.lockedMatchFailures = 0;

            drawDebugBox(minimap, globalBestCenter.x, globalBestCenter.y,
                    lockedCoreTemplate.width(), lockedCoreTemplate.height(), new Scalar(0, 165, 255));
            return new TemplateMatch(globalBestCenter, globalBestScore);
        }

        if (DebugManager.isENABLED()) System.out.printf("[locateChampionViaTemplate] FAILED: Best match was only %.2f%%%n", globalBestScore * 100);
        if (globalBestTemplate != null) globalBestTemplate.release();
        return null;
    }

    private List<AllyCircle> findAllyLocations(Mat minimap) {
        List<AllyCircle> centers;
        Mat hsv = new Mat();
        Mat mask = new Mat();

        try {
            Imgproc.cvtColor(minimap, hsv, Imgproc.COLOR_BGR2HSV);
            Core.inRange(hsv, new Scalar(80, 140, 200), new Scalar(115, 255, 255), mask);

            if (DebugManager.isENABLED()) {
                Imgcodecs.imwrite("debug/debug_ally_mask.png", mask);
            }

            centers = circlesFromRingMask(minimap, mask, "debug/debug_ransac_raw.png");

            if (DebugManager.isENABLED()) {
                Mat debugDrawMap = minimap.clone();
                for (AllyCircle c : centers) {
                    Imgproc.circle(debugDrawMap, c.center(), c.radius(), new Scalar(0, 255, 0), 2);
                    Imgproc.circle(debugDrawMap, c.center(), 2, new Scalar(0, 0, 255), -1);
                }
                Imgcodecs.imwrite("debug/debug_ally_centers.png", debugDrawMap);
                debugDrawMap.release();
            }

        } finally {
            hsv.release();
            mask.release();
        }

        return centers;
    }

    private List<AllyCircle> findEnemyCircles(Mat minimap) {
        List<AllyCircle> centers;
        Mat mask = new Mat();

        try {
            enemyRedMask(minimap, mask);
            centers = circlesFromRingMask(minimap, mask, null);

            if (DebugManager.isENABLED()) {
                Mat debugDrawMap = minimap.clone();
                for (AllyCircle c : centers) {
                    Imgproc.circle(debugDrawMap, c.center(), c.radius(), new Scalar(0, 0, 255), 2);
                }
                Imgcodecs.imwrite("debug/debug_enemy_circles.png", debugDrawMap);
                debugDrawMap.release();
            }
        } finally {
            mask.release();
        }

        return centers;
    }

    private List<AllyCircle> circlesFromRingMask(Mat minimap, Mat mask, String rawFitsDebugPath) {
        List<AllyCircle> centers = new ArrayList<>();
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

            if (rawFitsDebugPath != null && DebugManager.isENABLED()) {
                Mat raw = minimap.clone();
                for (MathUtils.CircleFit f : rawFits) {
                    Imgproc.circle(raw, new Point(f.cx(), f.cy()), (int) Math.round(f.radius()),
                            new Scalar(0, 255, 255), 1);
                }
                Imgcodecs.imwrite(rawFitsDebugPath, raw);
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
                centers.add(new AllyCircle(ctr, cr));
            }
        } finally {
            closed.release();
            hierarchy.release();
            for (MatOfPoint c : contours) c.release();
        }

        return centers;
    }

    private boolean isCovered(Point center, int radius, List<AllyCircle> kept) {
        for (AllyCircle k : kept) {
            double sep = Math.hypot(center.x - k.center().x, center.y - k.center().y);
            if (sep < Math.min(k.radius(), radius) * ALLY_PEAK_DEDUP_FACTOR) return true;
        }
        return false;
    }

    private boolean swallowsMultipleCenters(Point center, int radius, List<AllyCircle> kept) {
        int contained = 0;
        for (AllyCircle k : kept) {
            double sep = Math.hypot(center.x - k.center().x, center.y - k.center().y);
            if (sep < radius * 0.8) contained++;
        }
        return contained >= 2;
    }

    private void drawCroppedTemplates(List<Mat> crops) {
        if (crops.isEmpty()) return;

        if (DebugManager.isENABLED()) {
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
                Imgcodecs.imwrite("debug/debug_cropped_templates.png", spriteSheet);
                spriteSheet.release();
            }
        }

        for (Mat crop : crops) crop.release();
        crops.clear();
    }

    private void drawTop10Debug(Mat minimap, List<CandidateMatch> candidates) {
        if (!DebugManager.isENABLED() || candidates.isEmpty()) return;

        Mat top10Map = minimap.clone();
        candidates.sort((c1, c2) -> Double.compare(c2.score(), c1.score()));
        int limit = Math.min(10, candidates.size());

        for (int i = 0; i < limit; i++) {
            CandidateMatch c = candidates.get(i);
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

        Imgcodecs.imwrite("debug/debug_template_top10_match.png", top10Map);
        top10Map.release();
    }

    private void drawDebugBox(Mat minimap, double centerX, double centerY, int width, int height, Scalar color) {
        if (!DebugManager.isENABLED()) return;

        Mat debugMap = minimap.clone();
        Point topLeft = new Point(centerX - (width / 2.0), centerY - (height / 2.0));
        Point bottomRight = new Point(topLeft.x + width, topLeft.y + height);
        Imgproc.rectangle(debugMap, topLeft, bottomRight, color, 2);
        Imgcodecs.imwrite("debug/debug_template_match.png", debugMap);
        debugMap.release();
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

    public void release() {
        if (championTemplate != null) {
            championTemplate.release();
            championTemplate = null;
        }
        if (lockedCoreTemplate != null) {
            lockedCoreTemplate.release();
            lockedCoreTemplate = null;
        }
        if (lockedCoreTemplateEnhanced != null) {
            lockedCoreTemplateEnhanced.release();
            lockedCoreTemplateEnhanced = null;
        }
    }
}