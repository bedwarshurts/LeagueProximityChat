package me.bedwarshurts.leagueproximitychat;

import com.sun.net.httpserver.HttpServer;
import lombok.Getter;
import lombok.Setter;
import me.bedwarshurts.leagueproximitychat.data.LeagueGame;
import me.bedwarshurts.leagueproximitychat.data.LeaguePlayer;
import me.bedwarshurts.leagueproximitychat.discord.DiscordRPCManager;
import me.bedwarshurts.leagueproximitychat.livekit.LivekitRoom;
import me.bedwarshurts.leagueproximitychat.position.ScreenPositionTracker;
import me.bedwarshurts.leagueproximitychat.position.TemplateLoader;
import me.bedwarshurts.leagueproximitychat.managers.DebugManager;
import me.bedwarshurts.leagueproximitychat.managers.OverlayManager;
import me.bedwarshurts.leagueproximitychat.utils.RitoApiUtils;
import me.bedwarshurts.leagueproximitychat.utils.WindowUtils;
import me.bedwarshurts.leagueproximitychat.websocket.CoordinateServer;
import nu.pattern.OpenCV;
import org.json.JSONArray;
import org.json.JSONObject;
import org.opencv.core.Mat;

import java.awt.*;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.BindException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.security.NoSuchAlgorithmException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public class LeagueProximityChat {

    private static boolean wasPaused = false;
    private static boolean isAwaitingBrowser = true;
    @Getter @Setter private static boolean hasConnectedToLiveKit = false;
    private static boolean isTrackerReady = false;

    @Getter @Setter private static LivekitRoom activeRoom = null;
    private static volatile boolean hasSentRoster = false;
    private static final AtomicBoolean rosterBuildInFlight = new AtomicBoolean(false);
    private static final AtomicInteger rosterGeneration = new AtomicInteger(0);
    private static final AtomicBoolean gameOverFlag = new AtomicBoolean(false);

    @Getter private static String roomLeaderRiotId = null;

    @Setter private static String detectedChampion = null;

    private static ScreenPositionTracker tracker = null;
    private static CoordinateServer server = null;
    private static OverlayManager overlay = null;

    private static int gameEndFailureStreak = 0;
    private static final long GAME_END_CHECK_INTERVAL_MS = 2000;
    private static final int GAME_END_FAILURE_THRESHOLD = 3;

    private static final double GAME_START_MIN_TIME = 1.0;

    public static LeaguePlayer findLocalPlayer(LeagueGame gameData, String localSummonerName) {
        for (LeaguePlayer p : gameData.players()) {
            if ((p.getRiotId() != null && p.getRiotId().equalsIgnoreCase(localSummonerName))) {
                return p;
            }
        }
        return null;
    }

    private static void pollGameEnd() {
        if (!hasSentRoster) {
            gameEndFailureStreak = 0;
            gameOverFlag.set(false);
            return;
        }

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
                    .put("skinId", skinId)
                    .put("profileIconId", profileIconId)
                    .put("profileIconData", iconData));
        }

        return new JSONObject()
                .put("type", "MATCH_ROSTER")
                .put("players", players)
                .put("localIdentity", localPlayer.getRiotId())
                .put("roomLeader", roomLeader == null ? JSONObject.NULL : roomLeader)
                .toString();
    }

    private static void resetForNextGame() {
        if (server != null) {
            server.sendToActive("{\"type\":\"GAME_ENDED\"}");
            server.setUserRequestedConnection(false);
        }

        activeRoom = null;
        if (tracker != null) {
            tracker.release();
            tracker = null;
        }

        hasSentRoster = false;
        hasConnectedToLiveKit = false;
        isTrackerReady = false;
        wasPaused = false;
        detectedChampion = null;
        roomLeaderRiotId = null;

        RitoApiUtils.clearCache();

        gameOverFlag.set(false);
        rosterGeneration.incrementAndGet();
    }

    public static void trackingLoop() throws InterruptedException, NoSuchAlgorithmException {
        if (!server.hasActiveConnection()) {
            if (!isAwaitingBrowser) {
                System.out.println("Browser disconnected.");
                isAwaitingBrowser = true;

                hasConnectedToLiveKit = false;
                hasSentRoster = false;
                rosterGeneration.incrementAndGet();
                isTrackerReady = false;
                server.setUserRequestedConnection(false);
                activeRoom = null;
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

        if (!hasSentRoster || (server.isUserRequestedConnection() && !hasConnectedToLiveKit)) {

            String currentLeader = RitoApiUtils.getLobbyLeader();
            if (currentLeader != null && !currentLeader.equals(roomLeaderRiotId)) {
                roomLeaderRiotId = currentLeader;
            }

            gameData = RitoApiUtils.getLivePlayerList();
            localSummonerName = RitoApiUtils.getLocalSummonerName();
            isInGame = (gameData != null && localSummonerName != null);
        }

        if (isInGame && !hasSentRoster) {
            LeaguePlayer localPlayer = findLocalPlayer(gameData, localSummonerName);

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
            CompletableFuture.runAsync(DiscordRPCManager::updatePresenceIdle);
            Thread.sleep(1000);
            return;
        }

        if (isInGame && !hasConnectedToLiveKit && !hasGameStarted()) {
            CompletableFuture.runAsync(DiscordRPCManager::updatePresenceIdle);
            Thread.sleep(1000);
            return;
        }

        if (isInGame && !hasConnectedToLiveKit) {
            LeaguePlayer localPlayer = findLocalPlayer(gameData, localSummonerName);

            if (localPlayer != null) {
                String roomName = gameData.createRoomHash();
                String identity = localPlayer.getRiotId();
                String name = localPlayer.getRiotId() + " (" + localPlayer.getChampionName() + ")";

                activeRoom = new LivekitRoom(roomName, roomLeaderRiotId);

                String token = activeRoom.generateRoomToken(name, identity);
                String payload = new JSONObject()
                        .put("type", "CONNECT_LIVEKIT")
                        .put("token", token)
                        .toString();
                server.sendToActive(payload);

                hasConnectedToLiveKit = true;
            }
        } else if (!isInGame && !hasConnectedToLiveKit) {
            CompletableFuture.runAsync(DiscordRPCManager::updatePresenceIdle);
            Thread.sleep(1000);
            return;
        }

        if (!isTrackerReady) {
            System.out.println("Loading champion template.");
            Mat championTemplate = TemplateLoader.autoLoadChampionTemplate();

            if (championTemplate == null) {
                System.err.println("Failed to load champion template. Retrying in 2 seconds!");
                Thread.sleep(2000);
                return;
            }

            tracker = new ScreenPositionTracker(championTemplate);
            isTrackerReady = true;
            System.out.println("Starting position tracking.");
        }

        if (!WindowUtils.isWindowFocused("League of Legends (TM) Client")) {
            if (!wasPaused) System.out.println("League of Legends lost focus. Pausing tracking.");
            wasPaused = true;
            Thread.sleep(1000);
            return;
        }

        if (wasPaused) {
            System.out.println("League of Legends focused. Resuming tracking.");
            wasPaused = false;
        }

        long startTime = System.currentTimeMillis();

        ScreenPositionTracker.TrackResult pos = tracker.trackPlayerPosition();
        CompletableFuture.runAsync(() -> DiscordRPCManager.updatePresenceActive(pos, detectedChampion));
        server.broadcastCoordinates(pos.x(), pos.y(), pos.isDead());

        long elapsedTime = System.currentTimeMillis() - startTime;
        long sleepTime = Math.max(10, 60 - elapsedTime);

        Thread.sleep(sleepTime);
    }

    public static void startHttpServer() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(8000), 0);

        server.createContext("/", exchange -> {
            try (InputStream is = LeagueProximityChat.class.getResourceAsStream("/index.html")) {
                if (is == null) {
                    throw new Exception("Could not find index.html.");
                }

                byte[] htmlBytes = is.readAllBytes();

                exchange.getResponseHeaders().set("Content-Type", "text/html");
                exchange.sendResponseHeaders(200, htmlBytes.length);

                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(htmlBytes);
                }
            } catch (Exception e) {
                String error = "Error: " + e.getMessage();
                exchange.sendResponseHeaders(404, error.length());
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(error.getBytes());
                }
            }
        });

        server.createContext("/livekit-client.umd.min.js", exchange -> {
            try (InputStream is = LeagueProximityChat.class.getResourceAsStream("/livekit-client.umd.min.js")) {
                if (is == null) {
                    exchange.sendResponseHeaders(404, -1);
                    return;
                }

                byte[] bytes = is.readAllBytes();
                exchange.getResponseHeaders().set("Content-Type", "application/javascript; charset=utf-8");
                exchange.getResponseHeaders().set("Cache-Control", "max-age=86400");
                exchange.sendResponseHeaders(200, bytes.length);

                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(bytes);
                }
            } catch (Exception e) {
                exchange.sendResponseHeaders(404, -1);
            }
        });

        server.createContext("/profile-icon/", exchange -> {
            byte[] image = null;
            try {
                String idPart = exchange.getRequestURI().getPath()
                        .substring("/profile-icon/".length()).replaceAll("[^0-9]", "");
                if (!idPart.isEmpty()) {
                    image = RitoApiUtils.getProfileIconImage(Integer.parseInt(idPart));
                }
            } catch (Exception ignored) {
            }

            if (image == null || image.length == 0) {
                exchange.sendResponseHeaders(404, -1);
                return;
            }

            exchange.getResponseHeaders().set("Content-Type", "image/jpeg");
            exchange.getResponseHeaders().set("Cache-Control", "max-age=86400");
            exchange.sendResponseHeaders(200, image.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(image);
            }
        });

        server.setExecutor(Executors.newFixedThreadPool(4, r -> {
            Thread t = new Thread(r, "http-server");
            t.setDaemon(true);
            return t;
        }));

        server.start();
        System.out.println("Local Web Server running on port 8000!");

        try {
            overlay = new OverlayManager(
                    () -> {
                        if (LeagueProximityChat.server != null) {
                            LeagueProximityChat.server.sendToActive("{\"type\":\"TOGGLE_MUTE\"}");
                        }
                    },
                    () -> {
                        if (LeagueProximityChat.server != null) {
                            LeagueProximityChat.server.sendToActive("{\"type\":\"TOGGLE_DEAFEN\"}");
                        }
                    });

            if (!overlay.launch()) {
                if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                    Desktop.getDesktop().browse(new URI("http://localhost:8000"));
                } else {
                    System.out.println("Please manually go to: http://localhost:8000");
                }
            }
        } catch (Exception e) {
            System.err.println("Failed to open browser: " + e.getMessage());
            System.out.println("Please manually go to: http://localhost:8000");
        }
    }

    public static void main(String[] args) throws InterruptedException {
        OpenCV.loadLocally();
        System.out.println("OpenCV loaded successfully.");

        if (DebugManager.isENABLED()) {
            System.out.println("[Debug] LPC_DEBUG is set — debug images and verbose tracking logs enabled.");
        }

        try {
            startHttpServer();
        } catch (BindException e) {
            System.err.println("The application is already running!");
            System.exit(0);
        } catch (IOException e) {
            System.err.println("Failed to start local web server: " + e.getMessage());
        }

        server = new CoordinateServer(new InetSocketAddress("127.0.0.1", 8887));
        server.start();

        ScheduledExecutorService gameEndPoller = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "game-end-poller");
            t.setDaemon(true);
            return t;
        });
        gameEndPoller.scheduleWithFixedDelay(LeagueProximityChat::pollGameEnd,
                GAME_END_CHECK_INTERVAL_MS, GAME_END_CHECK_INTERVAL_MS, TimeUnit.MILLISECONDS);


        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            if (server != null && server.hasActiveConnection()) {
                server.sendToActive("{\"type\":\"SHUTDOWN\"}");
                try {
                    Thread.sleep(200);
                } catch (InterruptedException ignored) {
                }
            }
            DiscordRPCManager.stop();
        }));

        roomLeaderRiotId = RitoApiUtils.getLobbyLeader();
        if (roomLeaderRiotId == null) {
            System.err.println("Please launch this app while waiting in the game lobby!");
            //Thread.sleep(10000);
            //System.exit(0);
        }

        DiscordRPCManager.start();
        while (true) {
            try {
                trackingLoop();
            } catch (InterruptedException | NoSuchAlgorithmException e) {
                System.err.println("Tracking loop interrupted: " + e.getMessage());
            }
        }
    }
}