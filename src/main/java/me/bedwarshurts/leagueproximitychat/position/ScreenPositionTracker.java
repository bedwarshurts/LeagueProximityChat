package me.bedwarshurts.leagueproximitychat.position;

import lombok.Getter;
import me.bedwarshurts.leagueproximitychat.app.AppConstants;
import me.bedwarshurts.leagueproximitychat.managers.ClipRecorder;
import me.bedwarshurts.leagueproximitychat.managers.DebugManager;
import me.bedwarshurts.leagueproximitychat.utils.LeagueConfigReader;
import me.bedwarshurts.leagueproximitychat.utils.RitoApiUtils;
import org.opencv.core.Core;
import org.opencv.core.Mat;
import org.opencv.core.Point;
import org.opencv.core.Rect;
import org.opencv.imgproc.Imgproc;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ScreenPositionTracker {

    private static final double MINIMAP_OVERRIDE_SCORE = 0.70;
    private static final double MINIMAP_OVERRIDE_DIST = 15.0;
    private static final double DRIFT_MIN_MATCH_SCORE = 0.65;

    private static final double FOUNTAIN_ORDER_X = 3.83, FOUNTAIN_ORDER_Y = 4.22;
    private static final double FOUNTAIN_CHAOS_X = 96.04, FOUNTAIN_CHAOS_Y = 96.09;
    private static final int FOUNTAIN_SAMPLE_WINDOW_FRAMES = 15;
    private static final double FOUNTAIN_MAX_CORRECTION = 4.0;
    private static final float FOUNTAIN_BLEND_ALPHA = 0.5f;
    private static final double FOUNTAIN_MIN_MATCH_SCORE = 0.75;

    private static final Pattern TEAM_PATTERN = Pattern.compile("\"team\":\"(ORDER|CHAOS)\"");

    public record TrackResult(float x, float y, boolean isDead, boolean detected, DeadView deadView) {
    }

    public record DeadView(float listenX, float listenY, List<float[]> visibleEnemies) {
    }

    private record CapturedFrame(Mat screen, Mat minimap, Rect minimapRoi, int mapSize) {
        void release() {
            screen.release();
            minimap.release();
        }
    }

    private final ScreenCapture screenCapture = new ScreenCapture();
    private final float userMinimapScale;
    private final boolean isColorblind;
    @Getter private final LeagueConfigReader.Warning configWarning;
    private final MinimapLocator minimapLocator;

    private final HealthBarDetector healthBars;
    private final CameraBoxLocator cameraBoxes = new CameraBoxLocator();
    private final IconMatcher iconMatcher;
    private final HealthBarCalibration calibration = new HealthBarCalibration();

    private Rect cachedGameCrop = null;
    private int cachedResolutionWidth = -1;

    private float lastKnownX = 0f;
    private float lastKnownY = 0f;
    private boolean positionDetected = false;
    private boolean minimapFailsafeActive = false;
    private float deadListenX = Float.NaN;
    private float deadListenY = Float.NaN;

    private String localTeam = null;
    private boolean wasDeadLastFrame = false;
    private int fountainSampleFramesLeft = 0;
    private boolean fountainAnchored = false;
    private float anchorOffsetX = 0f;
    private float anchorOffsetY = 0f;

    public ScreenPositionTracker(Mat championTemplate) {
        LeagueConfigReader.LeagueSettings settings = LeagueConfigReader.loadSettings();
        this.userMinimapScale = settings.getMinimapScale();
        this.isColorblind = settings.isColorblind();
        this.configWarning = settings.getWarning();
        this.healthBars = new HealthBarDetector(isColorblind);
        this.iconMatcher = new IconMatcher(championTemplate);
        this.minimapLocator = MinimapLocator.create();
        if (DebugManager.isENABLED()) System.out.println("[constructor] Tracker initialized. Target Health Bar Color: "
                + (this.isColorblind ? "YELLOW" : "GREEN"));
    }

    public TrackResult trackPlayerPosition() {
        boolean isDead = checkDeathState();

        if (wasDeadLastFrame && !isDead) {
            fountainSampleFramesLeft = FOUNTAIN_SAMPLE_WINDOW_FRAMES;
        }
        wasDeadLastFrame = isDead;

        if (isDead) {
            return trackWhileDead();
        }
        deadListenX = Float.NaN;
        deadListenY = Float.NaN;

        CapturedFrame frame = captureFrame();
        if (frame == null) {
            return anchored(lastKnownX, lastKnownY, false);
        }

        Mat fullScreenMat = frame.screen();
        Mat minimapMat = frame.minimap();
        int mapSize = frame.mapSize();

        ClipRecorder.record(fullScreenMat);

        if (DebugImages.enabled()) {
            DebugImages.write("debug_screen.png", fullScreenMat);
            DebugImages.write("debug_minimap.png", minimapMat);
            DebugImages.enemyIndicators(minimapMat);
        }

        Point healthBarCenter = healthBars.locate(fullScreenMat, frame.minimapRoi());
        CameraBox cameraBox = (healthBarCenter != null)
                ? cameraBoxes.locate(minimapMat, fullScreenMat.width(), fullScreenMat.height())
                : null;

        List<IconCircle> allyCircles = MinimapRingDetector.findAllies(minimapMat, iconMatcher.lockedBlipRadius());
        List<IconCircle> enemyCircles = MinimapRingDetector.findEnemies(minimapMat, iconMatcher.lockedBlipRadius());

        boolean hasProjection = healthBarCenter != null && cameraBox != null;
        float rawHpX = hasProjection
                ? MapCoordinates.projectHealthBarX(healthBarCenter.x, cameraBox, mapSize, fullScreenMat.width()) : 0f;
        float rawHpY = hasProjection
                ? MapCoordinates.projectHealthBarY(healthBarCenter.y, cameraBox, mapSize, fullScreenMat.height()) : 0f;

        if (!iconMatcher.isBootstrapped() && hasProjection && !allyCircles.isEmpty()) {
            iconMatcher.bootstrapFromHealthBar(minimapMat, allyCircles,
                    rawHpX + calibration.offsetX(), rawHpY + calibration.offsetY());
        }

        float anchorX = hasProjection ? rawHpX + calibration.offsetX() : lastKnownX;
        float anchorY = hasProjection ? rawHpY + calibration.offsetY() : lastKnownY;

        IconMatcher.TemplateMatch champMatch = iconMatcher.locate(minimapMat, allyCircles, enemyCircles, anchorX, anchorY);

        Point champMapCenter = (champMatch != null) ? champMatch.center() : null;
        double champScore = (champMatch != null) ? champMatch.score() : 0.0;
        double champRawScore = (champMatch != null) ? champMatch.rawScore() : 0.0;

        if (fountainSampleFramesLeft > 0) {
            updateFountainAnchor(champMapCenter, champRawScore, mapSize);
        }

        if (hasProjection) {
            iconMatcher.checkLock(champMatch, rawHpX + calibration.offsetX(), rawHpY + calibration.offsetY(),
                    lastKnownX, lastKnownY, mapSize);
        }

        if (hasProjection && champMapCenter != null && !calibration.isConverged()) {
            calibration.update(rawHpX, rawHpY, champMapCenter, (float) champScore, mapSize,
                    iconMatcher.lastStrongMatchCount());
        }

        if (calibration.isConverged() && hasProjection && champMapCenter != null && champScore > DRIFT_MIN_MATCH_SCORE) {
            calibration.monitorDrift(rawHpX, rawHpY, champMapCenter, mapSize);
        }

        TrackResult result;

        if (hasProjection) {
            float hpX = rawHpX + calibration.offsetX();
            float hpY = rawHpY + calibration.offsetY();

            boolean minimapOverride = false;
            if (champMapCenter != null && champRawScore > MINIMAP_OVERRIDE_SCORE) {
                float matchX = MapCoordinates.percentX(champMapCenter.x, mapSize);
                float matchY = MapCoordinates.percentY(champMapCenter.y, mapSize);
                double disagreement = Math.hypot(hpX - matchX, hpY - matchY);
                if (disagreement > MINIMAP_OVERRIDE_DIST) {
                    this.lastKnownX = matchX;
                    this.lastKnownY = matchY;
                    minimapOverride = true;
                    if (!minimapFailsafeActive) {
                        minimapFailsafeActive = true;
                        if (DebugManager.isENABLED())
                            System.out.printf("[trackPlayerPosition] Health-bar projection (%.1f, %.1f) is %.1f%% away from a confident minimap match (%.1f, %.1f, raw=%.2f) - overriding with the minimap icon %n",
                                hpX, hpY, disagreement, matchX, matchY, champRawScore);
                    }
                }
            }

            if (!minimapOverride) {
                this.lastKnownX = hpX;
                this.lastKnownY = hpY;
                if (minimapFailsafeActive) {
                    minimapFailsafeActive = false;
                    if (DebugManager.isENABLED()) System.out.println("[trackPlayerPosition] Health bar re-agrees with the minimap - resuming normal health-bar tracking.");
                }
                if (DebugManager.isENABLED()) System.out.printf("[trackPlayerPosition] HEALTHBAR -> X: %.2f%% | Y: %.2f%%%n", lastKnownX, lastKnownY);
            }

            if (DebugImages.enabled()) {
                DebugImages.healthLocation(minimapMat, lastKnownX, lastKnownY, champMapCenter, mapSize);
            }

            positionDetected = true;
            result = anchored(lastKnownX, lastKnownY, false);

        } else if (champMapCenter != null) {
            minimapFailsafeActive = false;
            this.lastKnownX = MapCoordinates.percentX(champMapCenter.x, mapSize);
            this.lastKnownY = MapCoordinates.percentY(champMapCenter.y, mapSize);

            if (DebugManager.isENABLED()) System.out.printf("[trackPlayerPosition] MINIMAP TEMPLATE -> X: %.2f%% | Y: %.2f%%%n", lastKnownX, lastKnownY);
            positionDetected = true;
            result = anchored(lastKnownX, lastKnownY, false);

        } else {
            minimapFailsafeActive = false;
            if (DebugManager.isENABLED()) System.out.printf("[trackPlayerPosition] No detection - returning last known -> X: %.2f%% | Y: %.2f%%%n",
                    lastKnownX, lastKnownY);
            result = anchored(lastKnownX, lastKnownY, false);
        }

        frame.release();
        return result;
    }

    private TrackResult trackWhileDead() {
        TrackResult body = anchored(lastKnownX, lastKnownY, true);
        if (Float.isNaN(deadListenX)) {
            deadListenX = body.x();
            deadListenY = body.y();
        }

        List<float[]> visibleEnemies = new ArrayList<>();
        CapturedFrame frame = captureFrame();
        if (frame != null) {
            try {
                CameraBox camera = cameraBoxes.locate(frame.minimap(), frame.screen().width(), frame.screen().height());
                if (camera != null) {
                    deadListenX = (float) MapCoordinates.percentXPrecise(camera.center().x, frame.mapSize()) + anchorOffsetX;
                    deadListenY = (float) MapCoordinates.percentYPrecise(camera.center().y, frame.mapSize()) + anchorOffsetY;
                }
                int lockedBlipRadius = iconMatcher.lockedBlipRadius();
                List<IconCircle> champions = MinimapRingDetector.championRingsOnly(frame.minimap(),
                        MinimapRingDetector.findEnemies(frame.minimap(), lockedBlipRadius), lockedBlipRadius);
                for (IconCircle ring : champions) {
                    visibleEnemies.add(new float[]{
                            (float) MapCoordinates.percentXPrecise(ring.center().x, frame.mapSize()) + anchorOffsetX,
                            (float) MapCoordinates.percentYPrecise(ring.center().y, frame.mapSize()) + anchorOffsetY});
                }
            } finally {
                frame.release();
            }
        }

        return new TrackResult(body.x(), body.y(), true, body.detected(),
                new DeadView(deadListenX, deadListenY, visibleEnemies));
    }

    private TrackResult anchored(float x, float y, boolean isDead) {
        return new TrackResult(x + anchorOffsetX, y + anchorOffsetY, isDead, positionDetected, null);
    }

    private CapturedFrame captureFrame() {
        Mat fullScreenMat = screenCapture.captureWindowClient(AppConstants.GAME_WINDOW_TITLE);

        if (fullScreenMat == null) {
            return null;
        }

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
        int estimatedMapSize = (int) (fullScreenMat.height() * currentMapPercent);

        MinimapLocator.MinimapRect mapBounds =
                (minimapLocator != null) ? minimapLocator.update(fullScreenMat, estimatedMapSize) : null;

        int perfectMapSize;
        Rect minimapRoi;
        if (mapBounds != null
                && mapBounds.x() >= 0 && mapBounds.y() >= 0
                && mapBounds.x() + mapBounds.size() <= fullScreenMat.width()
                && mapBounds.y() + mapBounds.size() <= fullScreenMat.height()) {
            perfectMapSize = mapBounds.size();
            minimapRoi = new Rect(mapBounds.x(), mapBounds.y(), mapBounds.size(), mapBounds.size());
        } else {
            perfectMapSize = estimatedMapSize;
            minimapRoi = new Rect(
                    fullScreenMat.width() - perfectMapSize,
                    fullScreenMat.height() - perfectMapSize,
                    perfectMapSize,
                    perfectMapSize
            );
        }
        Mat minimapMat = new Mat(fullScreenMat, minimapRoi).clone();

        return new CapturedFrame(fullScreenMat, minimapMat, minimapRoi, perfectMapSize);
    }

    private void updateFountainAnchor(Point champMapCenter, double champRawScore, int mapSize) {
        fountainSampleFramesLeft--;
        if (champMapCenter == null || !(champRawScore > FOUNTAIN_MIN_MATCH_SCORE) || localTeam == null) return;

        float measuredX = MapCoordinates.percentX(champMapCenter.x, mapSize);
        float measuredY = MapCoordinates.percentY(champMapCenter.y, mapSize);
        boolean chaos = "CHAOS".equalsIgnoreCase(localTeam);
        double canonX = chaos ? FOUNTAIN_CHAOS_X : FOUNTAIN_ORDER_X;
        double canonY = chaos ? FOUNTAIN_CHAOS_Y : FOUNTAIN_ORDER_Y;
        double dx = canonX - measuredX;
        double dy = canonY - measuredY;

        if (Math.abs(dx) <= FOUNTAIN_MAX_CORRECTION && Math.abs(dy) <= FOUNTAIN_MAX_CORRECTION) {
            if (!fountainAnchored) {
                anchorOffsetX = (float) dx;
                anchorOffsetY = (float) dy;
                fountainAnchored = true;
            } else {
                anchorOffsetX += (float) (dx - anchorOffsetX) * FOUNTAIN_BLEND_ALPHA;
                anchorOffsetY += (float) (dy - anchorOffsetY) * FOUNTAIN_BLEND_ALPHA;
            }
            fountainSampleFramesLeft = 0;
            System.out.printf("[fountain] Respawn anchor: measured (%.1f, %.1f) vs canonical (%.1f, %.1f) -> frame offset now (%.2f, %.2f).%n",
                    measuredX, measuredY, canonX, canonY, anchorOffsetX, anchorOffsetY);
        }
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

        if (localTeam == null) {
            Matcher teamMatcher = TEAM_PATTERN.matcher(playerBlock);
            if (teamMatcher.find()) {
                localTeam = teamMatcher.group(1);
            }
        }

        return playerBlock.contains("\"isDead\":true");
    }

    public void release() {
        iconMatcher.release();
        if (minimapLocator != null) minimapLocator.release();
    }
}
