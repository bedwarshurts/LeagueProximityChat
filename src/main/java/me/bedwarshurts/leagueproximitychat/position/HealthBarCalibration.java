package me.bedwarshurts.leagueproximitychat.position;

import me.bedwarshurts.leagueproximitychat.managers.DebugManager;
import org.opencv.core.Point;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;

final class HealthBarCalibration {

    static final double MAX_HEALTHBAR_MATCH_DIST = 15.0;

    private static final float MIN_MATCH_SCORE = 0.65f;
    private static final int MIN_CALIBRATION_FRAMES = 15;
    private static final int MAX_CALIBRATION_FRAMES = 120;
    private static final float CONVERGENCE_THRESHOLD = 0.04f;

    private static final int MAX_OUTLIER_STREAK = 25;
    private static final float OUTLIER_THRESHOLD = 2.5f;
    private static final int ROLLING_WINDOW_SIZE = 14;

    private static final float DRIFT_CONFIRM_THRESHOLD = 2f;
    private static final int DRIFT_SAMPLE_WINDOW = 10;
    private static final int DRIFT_CONFIRM_REQUIRED = 2;

    private float offsetX = 0.0f;
    private float offsetY = 0.0f;
    private int calibrationFrames = 0;
    private boolean converged = false;

    private int consecutiveOutliers = 0;
    private final Deque<float[]> offsetWindow = new ArrayDeque<>();

    private int driftConfirmStreak = 0;
    private int driftSampleCount = 0;
    private double accumulatedDriftMagnitude = 0f;

    float offsetX() {
        return offsetX;
    }

    float offsetY() {
        return offsetY;
    }

    boolean isConverged() {
        return converged;
    }

    void update(float rawHpX, float rawHpY, Point champMapCenter, float champScore, int mapSize, int strongMatchCount) {
        if (champScore <= MIN_MATCH_SCORE) {
            if (DebugManager.isENABLED()) System.out.printf("[calibration] Paused - low template confidence (%.2f)%n", champScore);
            return;
        }

        float trueX = MapCoordinates.percentX(champMapCenter.x, mapSize);
        float trueY = MapCoordinates.percentY(champMapCenter.y, mapSize);

        float targetOffsetX = trueX - rawHpX;
        float targetOffsetY = trueY - rawHpY;

        float rawMatchDist = (float) Math.hypot(targetOffsetX, targetOffsetY);
        if (rawMatchDist > MAX_HEALTHBAR_MATCH_DIST) {
            if (strongMatchCount >= 2) {
                if (DebugManager.isENABLED()) System.out.printf("[calibration] Match %.1f%% from health-bar projection with %d strong matches - likely a clone, skipping frame.%n",
                        rawMatchDist, strongMatchCount);
            } else {
                if (DebugManager.isENABLED()) System.out.printf("[calibration] Match %.1f%% from health-bar projection with only %d strong match - wrong lock suspected, skipping frame.%n",
                        rawMatchDist, strongMatchCount);
            }
            return;
        }

        if (!tryAcceptSample(targetOffsetX, targetOffsetY)) {
            if (DebugManager.isENABLED()) System.out.printf("[calibration] Outlier rejected (%.2f, %.2f)%n", targetOffsetX, targetOffsetY);
            return;
        }

        float alpha = computeAlpha(champScore);
        this.offsetX += (targetOffsetX - this.offsetX) * alpha;
        this.offsetY += (targetOffsetY - this.offsetY) * alpha;
        this.calibrationFrames++;

        if (DebugManager.isENABLED()) System.out.printf("[calibration] Frame %d | alpha=%.3f | score=%.2f | Offsets -> X: %.3f, Y: %.3f%n",
                calibrationFrames, alpha, champScore, offsetX, offsetY);

        if (calibrationFrames >= MIN_CALIBRATION_FRAMES && hasConverged()) {
            this.converged = true;
            System.out.printf("[calibration] CONVERGED at frame %d. Final Offsets -> X: %.3f, Y: %.3f%n",
                    calibrationFrames, offsetX, offsetY);
        } else if (calibrationFrames >= MAX_CALIBRATION_FRAMES) {
            this.converged = true;
            System.out.printf("[calibration] Max frames reached - force-locking. Final Offsets -> X: %.3f, Y: %.3f%n",
                    offsetX, offsetY);
        }
    }

    void monitorDrift(float rawHpX, float rawHpY, Point champMapCenter, int mapSize) {
        float calibratedHpX = rawHpX + offsetX;
        float calibratedHpY = rawHpY + offsetY;

        float trueX = MapCoordinates.percentX(champMapCenter.x, mapSize);
        float trueY = MapCoordinates.percentY(champMapCenter.y, mapSize);

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
                    System.out.printf("[drift] Systematic drift detected - Variance: %.2f%%. Re-entering calibration.%n", avgDrift);
                    this.converged = false;
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

    private float computeAlpha(float score) {
        float scoreWeight = Math.clamp((score - 0.60f) / 0.40f, 0f, 1f);
        float progress = Math.min(1.0f, (float) calibrationFrames / MAX_CALIBRATION_FRAMES);
        float decayedBase = 0.30f * (1.0f - progress) + 0.05f * progress;
        return decayedBase * (0.5f + 0.5f * scoreWeight);
    }

    private boolean tryAcceptSample(float sampleX, float sampleY) {
        if (offsetWindow.size() < ROLLING_WINDOW_SIZE / 2) {
            offsetWindow.addLast(new float[]{sampleX, sampleY});
            return true;
        }

        float medianX = computeMedian(offsetWindow, 0);
        float medianY = computeMedian(offsetWindow, 1);

        if (Math.abs(sampleX - medianX) > OUTLIER_THRESHOLD
                || Math.abs(sampleY - medianY) > OUTLIER_THRESHOLD) {

            consecutiveOutliers++;
            if (consecutiveOutliers >= MAX_OUTLIER_STREAK) {
                System.out.printf("[calibration] %d consecutive outliers! Camera shift detected. Wiping previous median.%n", consecutiveOutliers);

                offsetWindow.clear();
                consecutiveOutliers = 0;

                this.calibrationFrames = 0;

                offsetWindow.addLast(new float[]{sampleX, sampleY});
                return true;
            }

            return false;
        }

        consecutiveOutliers = 0;

        if (offsetWindow.size() >= ROLLING_WINDOW_SIZE) {
            offsetWindow.pollFirst();
        }
        offsetWindow.addLast(new float[]{sampleX, sampleY});
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

    private static float computeMedian(Deque<float[]> window, int index) {
        float[] values = new float[window.size()];
        int i = 0;
        for (float[] sample : window) values[i++] = sample[index];
        Arrays.sort(values);
        int mid = values.length / 2;
        return (values.length % 2 == 0) ? (values[mid - 1] + values[mid]) / 2.0f : values[mid];
    }
}
