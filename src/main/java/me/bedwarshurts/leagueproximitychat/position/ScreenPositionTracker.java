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

    private static final double MINIMAP_ONLY_MIN_RAW_SCORE = 0.45;
    private static final double PATH_LINE_SCORE = 1.0;
    private static final long CONTINUITY_WINDOW_MS = 6000;
    private static final double CONTINUITY_STEP_BASE = 2.0;
    private static final double CONTINUITY_STEP_PER_SECOND = 3.0;
    private static final double CONTINUITY_STEP_MAX = 6.0;
    private static final double OTHER_RING_MATCH_PX = 6.0;
    private static final double OTHER_RING_DRIFT_PER_SECOND = 0.03;
    private static final double OWN_RING_MATCH_FACTOR = 0.6;
    private static final double MINIMAP_JUMP_BASE = 5.0;
    private static final double MINIMAP_JUMP_PER_SECOND = 6.0;
    private static final double MINIMAP_JUMP_MAX = 25.0;
    private static final double MINIMAP_JUMP_SAME_SPOT = 3.0;
    private static final int MINIMAP_JUMP_CONFIRM_FRAMES = 3;

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
    private long lastFixMs = 0;
    private long lastReliableFixMs = 0;
    private Point yourPointAtLastFix = null;
    private List<Point> otherRingsAtLastFix = List.of();
    private float pendingJumpX = Float.NaN;
    private float pendingJumpY = Float.NaN;
    private int pendingJumpFrames = 0;
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
        if (allyCircles.isEmpty() && DebugImages.enabled()) {
            DebugImages.incident("no-ally-circles", minimapMat, fullScreenMat);
        }

        PathLineDetector.PathStart path = PathLineDetector.find(minimapMat, allyCircles);
        float pathX = path != null ? MapCoordinates.percentX(path.point().x, mapSize) : Float.NaN;
        float pathY = path != null ? MapCoordinates.percentY(path.point().y, mapSize) : Float.NaN;

        boolean hasProjection = healthBarCenter != null && cameraBox != null;
        float rawHpX = hasProjection
                ? MapCoordinates.projectHealthBarX(healthBarCenter.x, cameraBox, mapSize, fullScreenMat.width()) : 0f;
        float rawHpY = hasProjection
                ? MapCoordinates.projectHealthBarY(healthBarCenter.y, cameraBox, mapSize, fullScreenMat.height()) : 0f;

        if (!iconMatcher.isBootstrapped() && !allyCircles.isEmpty()) {
            if (hasProjection) {
                iconMatcher.bootstrapFromHealthBar(minimapMat, allyCircles,
                        rawHpX + calibration.offsetX(), rawHpY + calibration.offsetY());
            } else if (path != null) {
                iconMatcher.bootstrapFromHealthBar(minimapMat, allyCircles, pathX, pathY);
            }
        }

        float anchorX = hasProjection ? rawHpX + calibration.offsetX() : path != null ? pathX : lastKnownX;
        float anchorY = hasProjection ? rawHpY + calibration.offsetY() : path != null ? pathY : lastKnownY;

        IconMatcher.TemplateMatch champMatch = iconMatcher.locate(minimapMat, allyCircles, enemyCircles, anchorX, anchorY,
                hasProjection || path != null);

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

        Point truePoint = path != null ? path.point() : champMapCenter;
        double truePointScore = path != null ? PATH_LINE_SCORE : champRawScore;
        if (hasProjection && truePoint != null && !calibration.isConverged()) {
            calibration.update(rawHpX, rawHpY, truePoint, (float) truePointScore, mapSize,
                    path != null ? 0 : iconMatcher.lastStrongMatchCount());
        }

        if (calibration.isConverged() && hasProjection && truePoint != null && truePointScore > DRIFT_MIN_MATCH_SCORE) {
            calibration.monitorDrift(rawHpX, rawHpY, truePoint, mapSize);
        }

        TrackResult result;

        if (hasProjection) {
            float hpX = rawHpX + calibration.offsetX();
            float hpY = rawHpY + calibration.offsetY();

            boolean minimapOverride = false;
            boolean overrideCandidate = false;
            float matchX = Float.NaN;
            float matchY = Float.NaN;
            double matchScore = 0;
            if (path != null) {
                matchX = pathX;
                matchY = pathY;
                matchScore = PATH_LINE_SCORE;
            } else if (champMapCenter != null && champRawScore > MINIMAP_OVERRIDE_SCORE) {
                matchX = MapCoordinates.percentX(champMapCenter.x, mapSize);
                matchY = MapCoordinates.percentY(champMapCenter.y, mapSize);
                matchScore = champRawScore;
            }
            if (!Float.isNaN(matchX)) {
                double disagreement = Math.hypot(hpX - matchX, hpY - matchY);
                overrideCandidate = disagreement > MINIMAP_OVERRIDE_DIST;
                if (overrideCandidate && acceptMinimapMatch(matchX, matchY, matchScore, path != null, minimapMat, fullScreenMat)) {
                    this.lastKnownX = matchX;
                    this.lastKnownY = matchY;
                    minimapOverride = true;
                    if (!minimapFailsafeActive) {
                        minimapFailsafeActive = true;
                        if (DebugManager.isENABLED())
                            System.out.printf("[trackPlayerPosition] Health-bar projection (%.1f, %.1f) is %.1f%% away from the %s (%.1f, %.1f, score=%.2f) - overriding with it %n",
                                hpX, hpY, disagreement, path != null ? "movement path line" : "confident minimap match", matchX, matchY, matchScore);
                    }
                }
            }

            if (!minimapOverride) {
                this.lastKnownX = hpX;
                this.lastKnownY = hpY;
                if (!overrideCandidate) resetPendingJump();
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
            lastFixMs = System.currentTimeMillis();
            lastReliableFixMs = lastFixMs;
            rememberOtherRings(allyCircles, mapSize);
            result = anchored(lastKnownX, lastKnownY, false);

        } else if (path != null && acceptMinimapMatch(pathX, pathY, PATH_LINE_SCORE, true, minimapMat, fullScreenMat)) {
            minimapFailsafeActive = false;
            this.lastKnownX = pathX;
            this.lastKnownY = pathY;
            lastFixMs = System.currentTimeMillis();
            lastReliableFixMs = lastFixMs;
            rememberOtherRings(allyCircles, mapSize);

            if (DebugManager.isENABLED()) System.out.printf("[trackPlayerPosition] PATH LINE -> X: %.2f%% | Y: %.2f%%%n", lastKnownX, lastKnownY);
            positionDetected = true;
            result = anchored(lastKnownX, lastKnownY, false);

        } else if (champMapCenter != null && champRawScore >= MINIMAP_ONLY_MIN_RAW_SCORE
                && acceptMinimapMatch(MapCoordinates.percentX(champMapCenter.x, mapSize),
                MapCoordinates.percentY(champMapCenter.y, mapSize), champRawScore, false, minimapMat, fullScreenMat)) {
            minimapFailsafeActive = false;
            this.lastKnownX = MapCoordinates.percentX(champMapCenter.x, mapSize);
            this.lastKnownY = MapCoordinates.percentY(champMapCenter.y, mapSize);
            lastFixMs = System.currentTimeMillis();
            lastReliableFixMs = lastFixMs;
            rememberOtherRings(allyCircles, mapSize);

            if (DebugManager.isENABLED()) System.out.printf("[trackPlayerPosition] MINIMAP TEMPLATE -> X: %.2f%% | Y: %.2f%%%n", lastKnownX, lastKnownY);
            positionDetected = true;
            result = anchored(lastKnownX, lastKnownY, false);

        } else if (continuityFix(minimapMat, mapSize)) {
            minimapFailsafeActive = false;
            if (DebugManager.isENABLED()) System.out.printf("[trackPlayerPosition] CONTINUITY (covered ring where you just were) -> X: %.2f%% | Y: %.2f%%%n",
                    lastKnownX, lastKnownY);
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

    private boolean continuityFix(Mat minimap, int mapSize) {
        IconMatcher.RingPick pick = iconMatcher.lastBestRing();
        long now = System.currentTimeMillis();
        double seconds = (now - lastFixMs) / 1000.0;
        double allowed = Math.min(CONTINUITY_STEP_MAX, CONTINUITY_STEP_BASE + CONTINUITY_STEP_PER_SECOND * seconds);

        String rejection = null;
        boolean stacked = false;
        float x = Float.NaN;
        float y = Float.NaN;
        if (pick == null) {
            rejection = "no ring to consider";
        } else {
            x = MapCoordinates.percentX(pick.center().x, mapSize);
            y = MapCoordinates.percentY(pick.center().y, mapSize);
            double step = Math.hypot(x - lastKnownX, y - lastKnownY);
            if (!positionDetected || now - lastReliableFixMs > CONTINUITY_WINDOW_MS) {
                rejection = "no reliable position in the last " + CONTINUITY_WINDOW_MS / 1000 + " s";
            } else if (step > allowed) {
                rejection = String.format("too far (%.1f%% > %.1f%%)", step, allowed);
            } else if (wasAnotherChampion(pick.center(), now, mapSize)) {
                rejection = "that ring was another champion a moment ago";
            } else if (!pick.overlapped()) {
                stacked = MinimapRingDetector.overlappedByAllyRing(minimap, pick.ring());
                if (!stacked) rejection = "not covered by another icon";
            }
        }

        if (DebugImages.enabled()) {
            DebugImages.continuity(minimap, mapSize, lastKnownX, lastKnownY, allowed, pick, stacked, rejection,
                    otherRingsAtLastFix);
        }
        if (rejection != null) return false;

        this.lastKnownX = x;
        this.lastKnownY = y;
        lastFixMs = now;
        return true;
    }

    private void rememberOtherRings(List<IconCircle> allies, int mapSize) {
        Point you = new Point(MapCoordinates.pixelX(lastKnownX, mapSize), MapCoordinates.pixelY(lastKnownY, mapSize));
        IconCircle yours = null;
        double nearest = Double.MAX_VALUE;
        for (IconCircle ring : allies) {
            double d = Math.hypot(ring.center().x - you.x, ring.center().y - you.y);
            if (d < nearest) {
                nearest = d;
                yours = ring;
            }
        }
        boolean identified = yours != null && nearest <= yours.radius() * OWN_RING_MATCH_FACTOR;

        List<Point> others = new ArrayList<>();
        for (IconCircle ring : allies) {
            boolean mine = identified
                    ? ring == yours
                    : Math.hypot(ring.center().x - you.x, ring.center().y - you.y) <= ring.radius();
            if (!mine) others.add(ring.center());
        }
        yourPointAtLastFix = you;
        otherRingsAtLastFix = others;
    }

    private boolean wasAnotherChampion(Point candidate, long now, int mapSize) {
        if (yourPointAtLastFix == null || otherRingsAtLastFix == null) return false;
        double drift = OTHER_RING_MATCH_PX + OTHER_RING_DRIFT_PER_SECOND * mapSize * (now - lastReliableFixMs) / 1000.0;
        double toYou = Math.hypot(candidate.x - yourPointAtLastFix.x, candidate.y - yourPointAtLastFix.y);
        for (Point other : otherRingsAtLastFix) {
            double toOther = Math.hypot(candidate.x - other.x, candidate.y - other.y);
            if (toOther <= drift && toOther < toYou) return true;
        }
        return false;
    }

    private boolean acceptMinimapMatch(float x, float y, double rawScore, boolean knownToBeYou, Mat minimap, Mat screen) {
        double seconds = (System.currentTimeMillis() - lastFixMs) / 1000.0;
        double allowed = Math.min(MINIMAP_JUMP_MAX, MINIMAP_JUMP_BASE + MINIMAP_JUMP_PER_SECOND * seconds);
        double jump = Math.hypot(x - lastKnownX, y - lastKnownY);
        if (!positionDetected || jump <= allowed) {
            resetPendingJump();
            return true;
        }

        boolean confident = rawScore > MINIMAP_OVERRIDE_SCORE && (knownToBeYou || iconMatcher.isBootstrapped());
        if (confident && pendingJumpFrames > 0
                && Math.hypot(x - pendingJumpX, y - pendingJumpY) <= MINIMAP_JUMP_SAME_SPOT) {
            pendingJumpFrames++;
        } else if (confident) {
            pendingJumpX = x;
            pendingJumpY = y;
            pendingJumpFrames = 1;
        } else {
            resetPendingJump();
        }
        if (pendingJumpFrames >= MINIMAP_JUMP_CONFIRM_FRAMES) {
            resetPendingJump();
            if (DebugManager.isENABLED()) System.out.printf("[trackPlayerPosition] Minimap match at (%.1f, %.1f) held for %d frames - accepting the %.1f%% jump.%n",
                    x, y, MINIMAP_JUMP_CONFIRM_FRAMES, jump);
            return true;
        }

        if (DebugManager.isENABLED()) {
            System.out.printf("[trackPlayerPosition] Ignoring minimap match at (%.1f, %.1f) raw=%.2f - %.1f%% from the last position (allowed %.1f%%).%n",
                    x, y, rawScore, jump, allowed);
            DebugImages.incident("ignored-jump", minimap, screen);
        }
        return false;
    }

    private void resetPendingJump() {
        pendingJumpFrames = 0;
        pendingJumpX = Float.NaN;
        pendingJumpY = Float.NaN;
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
