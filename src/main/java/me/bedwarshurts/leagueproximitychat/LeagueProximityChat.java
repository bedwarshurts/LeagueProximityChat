package me.bedwarshurts.leagueproximitychat;

import com.sun.net.httpserver.HttpServer;
import lombok.Getter;
import lombok.Setter;
import me.bedwarshurts.leagueproximitychat.data.LeagueGame;
import me.bedwarshurts.leagueproximitychat.data.LeaguePlayer;
import me.bedwarshurts.leagueproximitychat.livekit.LivekitRoom;
import me.bedwarshurts.leagueproximitychat.position.ScreenPositionTracker;
import me.bedwarshurts.leagueproximitychat.position.TemplateLoader;
import me.bedwarshurts.leagueproximitychat.utils.RitoApiUtils;
import me.bedwarshurts.leagueproximitychat.utils.WindowUtils;
import me.bedwarshurts.leagueproximitychat.websocket.CoordinateServer;
import nu.pattern.OpenCV;
import org.opencv.core.Mat;

import java.awt.*;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.BindException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.security.NoSuchAlgorithmException;

public class LeagueProximityChat {

    private static boolean wasPaused = false;
    private static boolean isAwaitingBrowser = true;
    @Getter @Setter private static boolean hasConnectedToLiveKit = false;
    private static boolean isTrackerReady = false;

    @Getter @Setter private static LivekitRoom activeRoom = null;
    private static boolean hasSentRoster = false;

    @Getter private static String roomLeaderRiotId = null;

    private static ScreenPositionTracker tracker = null;
    private static CoordinateServer server = null;

    public static LeaguePlayer findLocalPlayer(LeagueGame gameData, String localSummonerName) {
        for (LeaguePlayer p : gameData.players()) {
            if ((p.getRiotId() != null && p.getRiotId().equalsIgnoreCase(localSummonerName))) {
                return p;
            }
        }
        return null;
    }

    public static void trackingLoop() throws InterruptedException, NoSuchAlgorithmException {
        if (!server.hasActiveConnection()) {
            if (!isAwaitingBrowser) {
                System.out.println("Browser disconnected.");
                isAwaitingBrowser = true;

                hasConnectedToLiveKit = false;
                hasSentRoster = false;
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

        LeagueGame gameData = null;
        String localSummonerName = null;
        boolean isInGame = false;

        if (!hasSentRoster || (server.isUserRequestedConnection() && !hasConnectedToLiveKit)) {

            String currentLeader = RitoApiUtils.getLobbyLeader();
            if (currentLeader != null && !currentLeader.equals(roomLeaderRiotId)) {
                roomLeaderRiotId = currentLeader;
                System.out.println("Lobby switch detected. New leader: " + roomLeaderRiotId);
            }

            gameData = RitoApiUtils.getLivePlayerList();
            localSummonerName = RitoApiUtils.getLocalSummonerName();
            isInGame = (gameData != null && localSummonerName != null);
        }

        if (isInGame && !hasSentRoster) {
            LeaguePlayer localPlayer = findLocalPlayer(gameData, localSummonerName);

            if (localPlayer != null) {
                StringBuilder rosterArray = new StringBuilder("[");
                for (int i = 0; i < gameData.players().size(); i++) {
                    LeaguePlayer p = gameData.players().get(i);
                    String pId = p.getRiotId();
                    String pName = p.getRiotId() + " (" + p.getChampionName() + ")";

                    rosterArray.append(String.format("{\"identity\":\"%s\", \"name\":\"%s\"}", pId, pName));
                    if (i < gameData.players().size() - 1) rosterArray.append(",");
                }
                rosterArray.append("]");

                String rosterPayload = String.format("{\"type\":\"MATCH_ROSTER\", \"players\":%s, \"localIdentity\":\"%s\", \"roomLeader\":\"%s\"}",
                        rosterArray, localPlayer.getRiotId(), roomLeaderRiotId);
                server.sendToActive(rosterPayload);
                hasSentRoster = true;
                System.out.println("Match detected!");
            }
        }

        if (!server.isUserRequestedConnection()) {
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
                String payload = String.format("{\"type\":\"CONNECT_LIVEKIT\", \"token\":\"%s\"}", token);
                server.sendToActive(payload);

                hasConnectedToLiveKit = true;
            }
        } else if (!isInGame && !hasConnectedToLiveKit) {
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
        server.broadcastCoordinates(pos.x(), pos.y(), pos.isDead());

        long elapsedTime = System.currentTimeMillis() - startTime;
        long sleepTime = Math.max(10, 30 - elapsedTime);

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

        server.start();
        System.out.println("Local Web Server running on port 8000!");

        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(new URI("http://localhost:8000"));
            } else {
                System.out.println("Please manually go to: http://localhost:8000");
            }
        } catch (Exception e) {
            System.err.println("Failed to open browser: " + e.getMessage());
            System.out.println("Please manually go to: http://localhost:8000");
        }
    }

    public static void main(String[] args) {
        OpenCV.loadLocally();
        System.out.println("OpenCV loaded successfully.");

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


        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            if (server != null && server.hasActiveConnection()) {
                server.sendToActive("{\"type\":\"SHUTDOWN\"}");
                try {
                    Thread.sleep(200);
                } catch (InterruptedException ignored) {
                }
            }
        }));

        roomLeaderRiotId = RitoApiUtils.getLobbyLeader();
        System.out.println("Current lobby leader: " + roomLeaderRiotId);
        if (roomLeaderRiotId == null) {
            System.err.println("Please launch this app while waiting in the game lobby!");
            System.exit(0);
        }

        while (true) {
            try {
                trackingLoop();
            } catch (InterruptedException | NoSuchAlgorithmException e) {
                System.err.println("Tracking loop interrupted: " + e.getMessage());
            }
        }
    }
}