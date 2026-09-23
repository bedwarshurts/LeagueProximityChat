package me.bedwarshurts.leagueproximitychat;

import me.bedwarshurts.leagueproximitychat.app.AppConstants;
import me.bedwarshurts.leagueproximitychat.app.AppInfo;
import me.bedwarshurts.leagueproximitychat.data.LeagueGame;
import me.bedwarshurts.leagueproximitychat.data.LeaguePlayer;
import me.bedwarshurts.leagueproximitychat.discord.DiscordRPCManager;
import me.bedwarshurts.leagueproximitychat.livekit.LivekitRoom;
import me.bedwarshurts.leagueproximitychat.managers.ConfigManager;
import me.bedwarshurts.leagueproximitychat.managers.DebugManager;
import me.bedwarshurts.leagueproximitychat.managers.LogManager;
import me.bedwarshurts.leagueproximitychat.managers.OverlayManager;
import me.bedwarshurts.leagueproximitychat.managers.PauseDetector;
import me.bedwarshurts.leagueproximitychat.managers.PlayOfGameManager;
import me.bedwarshurts.leagueproximitychat.managers.UiWindowManager;
import me.bedwarshurts.leagueproximitychat.position.ScreenPositionTracker;
import me.bedwarshurts.leagueproximitychat.position.TemplateLoader;
import me.bedwarshurts.leagueproximitychat.utils.LeagueConfigReader;
import me.bedwarshurts.leagueproximitychat.utils.RitoApiUtils;
import me.bedwarshurts.leagueproximitychat.utils.WindowUtils;
import me.bedwarshurts.leagueproximitychat.web.LocalWebServer;
import me.bedwarshurts.leagueproximitychat.websocket.CoordinateServer;
import nu.pattern.OpenCV;
import org.json.JSONArray;
import org.json.JSONObject;

import java.awt.Desktop;
import java.io.IOException;
import java.net.BindException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public class LeagueProximityChat {

    private static boolean wasPaused = false;
    private static boolean isAwaitingBrowser = true;
    private static boolean isTrackerReady = false;

    private static final SessionState session = new SessionState();
    private static volatile boolean hasSentRoster = false;
    private static final AtomicBoolean rosterBuildInFlight = new AtomicBoolean(false);
    private static final AtomicInteger rosterGeneration = new AtomicInteger(0);
    private static final AtomicBoolean gameOverFlag = new AtomicBoolean(false);

    private static String roomLeaderRiotId = null;
    private static String detectedChampion = null;

    private static ScreenPositionTracker tracker = null;
    private static CoordinateServer server = null;
    private static OverlayManager overlay = null;
    private static UiWindowManager uiWindow = null;

    private static int gameEndFailureStreak = 0;
    private static final long GAME_END_CHECK_INTERVAL_MS = 2000;
    private static final long PAUSE_CHECK_INTERVAL_MS = 500;
    private static final int GAME_END_FAILURE_THRESHOLD = 3;

    private static final double GAME_START_MIN_TIME = 1.0;

    private static void pollGameEnd() {
        if (!hasSentRoster) {
            gameEndFailureStreak = 0;
            gameOverFlag.set(false);
            return;
        }

        PlayOfGameManager.poll(server);

        String phase = RitoApiUtils.getGameflowPhase();
        if (phase != null) {
            if (phase.equalsIgnoreCase("InProgress")
                    || phase.equalsIgnoreCase("Reconnect")
                    || phase.equalsIgnoreCase("GameStart")) {
                gameEndFailureStreak = 0;
                return;
            }
            gameOverFlag.set(true);
            return;
        }

        String playerList = RitoApiUtils.fetchPlayerListRaw();
        if (playerList != null && !playerList.isEmpty()) {
            gameEndFailureStreak = 0;
            return;
        }

        gameEndFailureStreak++;
        if (gameEndFailureStreak >= GAME_END_FAILURE_THRESHOLD) {
            gameOverFlag.set(true);
        }
    }

    private static boolean hasGameStarted() {
        return RitoApiUtils.getGameTime() > GAME_START_MIN_TIME;
    }

    private static String buildRosterPayload(LeagueGame gameData, LeaguePlayer localPlayer, String roomLeader) {
        JSONArray players = new JSONArray();
        int iconLookupFailStreak = 0;
        for (LeaguePlayer p : gameData.players()) {
            String pId = p.getRiotId();
            String pName = p.getRiotId() + " (" + p.getChampionName() + ")";

            int profileIconId = (p == localPlayer) ? RitoApiUtils.getLocalProfileIconId() : -1;

            if (profileIconId <= 0 && !p.isBot() && pId != null && !pId.isEmpty() && iconLookupFailStreak < 2) {
                profileIconId = RitoApiUtils.getProfileIconId(p.getRiotIdGameName(), p.getRiotIdTagLine(), pId);
                iconLookupFailStreak = (profileIconId <= 0) ? iconLookupFailStreak + 1 : 0;
            }

            int skinId = p.getEffectiveSkinId();

            String iconData = (profileIconId > 0) ? RitoApiUtils.getProfileIconDataUri(profileIconId) : "";

            if (DebugManager.isENABLED()) System.out.println("[Roster] " + pId + " champion=" + p.getChampionName()
                    + " skinId=" + skinId + " (api=" + p.getSkinID() + ", raw=" + p.getRawSkinName() + ")"
                    + " profileIcon=" + profileIconId + " iconBytes=" + iconData.length());

            players.put(new JSONObject()
                    .put("identity", pId == null ? "" : pId)
                    .put("name", pName)
                    .put("champion", p.getChampionName())
                    .put("team", p.getTeam() == null ? "" : p.getTeam())
                    .put("skinId", skinId)
                    .put("profileIconId", profileIconId)
                    .put("profileIconData", iconData));
        }

        return new JSONObject()
                .put("type", "MATCH_ROSTER")
                .put("players", players)
                .put("localIdentity", localPlayer.getRiotId())
                .put("roomLeader", roomLeader == null ? JSONObject.NULL : roomLeader)
                .put("debug", DebugManager.isENABLED())
                .toString();
    }

    private static void resetForNextGame() {
        if (server != null) {
            server.sendToActive("{\"type\":\"GAME_ENDED\"}");
            server.setUserRequestedConnection(false);
        }

        session.setActiveRoom(null);
        if (tracker != null) {
            tracker.release();
            tracker = null;
        }

        hasSentRoster = false;
        session.setConnectedToLiveKit(false);
        isTrackerReady = false;
        session.setConfigWarning(null);
        wasPaused = false;
        detectedChampion = null;
        roomLeaderRiotId = null;

        RitoApiUtils.clearCache();

        gameOverFlag.set(false);
        rosterGeneration.incrementAndGet();
    }

    public static void trackingLoop() throws InterruptedException {
        if (!server.hasActiveConnection()) {
            if (!isAwaitingBrowser) {
                System.out.println("Browser disconnected.");
                isAwaitingBrowser = true;

                session.setConnectedToLiveKit(false);
                hasSentRoster = false;
                rosterGeneration.incrementAndGet();
                isTrackerReady = false;
                server.setUserRequestedConnection(false);
                session.setActiveRoom(null);
            }
            Thread.sleep(1000);
            return;
        }

        if (isAwaitingBrowser) {
            System.out.println("Browser WebSocket connected!");
            isAwaitingBrowser = false;
        }

        if (hasSentRoster && gameOverFlag.get()) {
            System.out.println("Game ended. Resetting to wait for the next match.");
            resetForNextGame();
            Thread.sleep(1000);
            return;
        }

        LeagueGame gameData = null;
        String localSummonerName = null;
        boolean isInGame = false;

        if (!hasSentRoster || (server.isUserRequestedConnection() && !session.isConnectedToLiveKit())) {

            String currentLeader = RitoApiUtils.getLobbyLeader();
            if (currentLeader != null && !currentLeader.equals(roomLeaderRiotId)) {
                roomLeaderRiotId = currentLeader;
            }

            gameData = RitoApiUtils.getLivePlayerList();
            localSummonerName = RitoApiUtils.getLocalSummonerName();
            isInGame = (gameData != null && localSummonerName != null);
        }

        if (isInGame && !hasSentRoster) {
            LeaguePlayer localPlayer = gameData.findPlayer(localSummonerName);

            if (localPlayer != null && rosterBuildInFlight.compareAndSet(false, true)) {
                LeagueGame rosterGame = gameData;
                String leader = roomLeaderRiotId;
                int generation = rosterGeneration.get();

                CompletableFuture.runAsync(() -> {
                    try {
                        String payload = buildRosterPayload(rosterGame, localPlayer, leader);
                        if (rosterGeneration.get() == generation && server.hasActiveConnection()) {
                            server.sendToActive(payload);
                            hasSentRoster = true;
                            System.out.println("Match detected!");
                        }
                    } finally {
                        rosterBuildInFlight.set(false);
                    }
                });
            }
        }

        if (!server.isUserRequestedConnection()) {
            DiscordRPCManager.requestIdleUpdate();
            Thread.sleep(1000);
            return;
        }

        if (isInGame && !session.isConnectedToLiveKit() && !hasGameStarted()) {
            DiscordRPCManager.requestIdleUpdate();
            Thread.sleep(1000);
            return;
        }

        if (isInGame && !session.isConnectedToLiveKit()) {
            if (!ConfigManager.isConfigured()) {
                Thread.sleep(1000);
                return;
            }

            LeaguePlayer localPlayer = gameData.findPlayer(localSummonerName);

            if (localPlayer != null) {
                String roomName = gameData.createRoomHash();
                String identity = localPlayer.getRiotId();
                String name = localPlayer.getRiotId() + " (" + localPlayer.getChampionName() + ")";

                LivekitRoom room = new LivekitRoom(roomName, roomLeaderRiotId);
                session.setActiveRoom(room);

                String token = room.generateRoomToken(name, identity);
                String payload = new JSONObject()
                        .put("type", "CONNECT_LIVEKIT")
                        .put("token", token)
                        .toString();
                server.sendToActive(payload);

                session.setConnectedToLiveKit(true);
            }
        } else if (!isInGame && !session.isConnectedToLiveKit()) {
            DiscordRPCManager.requestIdleUpdate();
            Thread.sleep(1000);
            return;
        }

        if (!isTrackerReady) {
            System.out.println("Loading champion template.");
            TemplateLoader.ChampionTemplate championTemplate = TemplateLoader.autoLoadChampionTemplate();

            if (championTemplate == null) {
                System.err.println("Failed to load champion template. Retrying in 2 seconds!");
                Thread.sleep(2000);
                return;
            }

            detectedChampion = championTemplate.championName();
            tracker = new ScreenPositionTracker(championTemplate.icon());
            isTrackerReady = true;
            LeagueConfigReader.Warning configWarning = tracker.getConfigWarning();
            session.setConfigWarning(configWarning);
            if (configWarning != null) {
                System.err.println("[Config] " + configWarning.toLogLine());
                server.sendConfigWarning();
            }
            System.out.println("Starting position tracking.");
        }

        if (!WindowUtils.isWindowFocused(AppConstants.GAME_WINDOW_TITLE)) {
            if (!wasPaused) System.out.println("League of Legends lost focus. Pausing tracking.");
            wasPaused = true;
            Thread.sleep(1000);
            return;
        }

        if (wasPaused) {
            System.out.println("League of Legends focused. Resuming tracking.");
            wasPaused = false;
        }

        if (PauseDetector.isPaused()) {
            Thread.sleep(250);
            return;
        }

        long startTime = System.currentTimeMillis();

        ScreenPositionTracker.TrackResult pos = tracker.trackPlayerPosition();
        DiscordRPCManager.requestActiveUpdate(pos, detectedChampion);
        server.broadcastCoordinates(pos.x(), pos.y(), pos.isDead(), pos.detected(), pos.deadView());

        long elapsedTime = System.currentTimeMillis() - startTime;
        long sleepTime = Math.max(1, 16 - elapsedTime);

        Thread.sleep(sleepTime);
    }

    private static void launchUi() {
        try {
            overlay = new OverlayManager(
                    () -> {
                        if (server != null) {
                            server.sendToActive("{\"type\":\"TOGGLE_MUTE\"}");
                        }
                    },
                    () -> {
                        if (server != null) {
                            server.sendToActive("{\"type\":\"TOGGLE_DEAFEN\"}");
                        }
                    });

            uiWindow = new UiWindowManager();
            if (uiWindow.launch()) {
                overlay.launchWithNativeWindow(uiWindow);
            } else {
                uiWindow = null;
                if (!overlay.launch()) {
                    if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                        Desktop.getDesktop().browse(new URI(AppConstants.APP_URL));
                    } else {
                        System.out.println("Please manually go to: " + AppConstants.APP_URL);
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("Failed to open browser: " + e.getMessage());
            System.out.println("Please manually go to: " + AppConstants.APP_URL);
        }
    }

    public static void main(String[] args) {
        LogManager.install();
        System.out.println("LeagueProximityChat " + AppInfo.fullVersion());

        OpenCV.loadLocally();
        System.out.println("OpenCV loaded successfully.");

        if (DebugManager.isENABLED()) {
            System.out.println("[Debug] Debug mode active - debug images and verbose tracking logs enabled. This reduces performance.");
        }

        try {
            LocalWebServer.start(AppConstants.WEB_PORT);
            launchUi();
        } catch (BindException e) {
            System.err.println("The application is already running!");
            System.exit(0);
        } catch (IOException e) {
            System.err.println("Failed to start local web server: " + e.getMessage());
        }

        server = new CoordinateServer(new InetSocketAddress("127.0.0.1", AppConstants.WEBSOCKET_PORT), session);
        server.start();

        ScheduledExecutorService gameEndPoller = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "game-end-poller");
            t.setDaemon(true);
            return t;
        });
        gameEndPoller.scheduleWithFixedDelay(LeagueProximityChat::pollGameEnd,
                GAME_END_CHECK_INTERVAL_MS, GAME_END_CHECK_INTERVAL_MS, TimeUnit.MILLISECONDS);

        ScheduledExecutorService pausePoller = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "pause-poller");
            t.setDaemon(true);
            return t;
        });
        pausePoller.scheduleWithFixedDelay(() -> {
            try {
                PauseDetector.poll(server);
            } catch (Exception e) {
                DebugManager.logFailure("[Pause] Pause check failed", e);
            }
        }, PAUSE_CHECK_INTERVAL_MS, PAUSE_CHECK_INTERVAL_MS, TimeUnit.MILLISECONDS);


        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            if (server != null && server.hasActiveConnection()) {
                server.sendToActive("{\"type\":\"SHUTDOWN\"}");
                try {
                    Thread.sleep(200);
                } catch (InterruptedException ignored) {
                }
            }
            DiscordRPCManager.stop();
            if (uiWindow != null) {
                uiWindow.shutdown();
            }
        }));

        roomLeaderRiotId = RitoApiUtils.getLobbyLeader();
        if (roomLeaderRiotId == null) {
            System.err.println("Please launch this app while waiting in the game lobby!");
        }

        DiscordRPCManager.start();
        while (true) {
            try {
                trackingLoop();
            } catch (InterruptedException e) {
                System.err.println("Tracking loop interrupted: " + e.getMessage());
            }
        }
    }
}
