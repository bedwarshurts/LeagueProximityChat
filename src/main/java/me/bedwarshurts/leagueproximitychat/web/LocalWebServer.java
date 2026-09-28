package me.bedwarshurts.leagueproximitychat.web;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import me.bedwarshurts.leagueproximitychat.app.AppConstants;
import me.bedwarshurts.leagueproximitychat.app.AppInfo;
import me.bedwarshurts.leagueproximitychat.managers.ConfigManager;
import me.bedwarshurts.leagueproximitychat.managers.DebugManager;
import me.bedwarshurts.leagueproximitychat.managers.LogManager;
import me.bedwarshurts.leagueproximitychat.managers.MatchHistoryManager;
import me.bedwarshurts.leagueproximitychat.managers.PlayOfGameManager;
import me.bedwarshurts.leagueproximitychat.managers.VoiceArchiveManager;
import me.bedwarshurts.leagueproximitychat.utils.ReplayApiConfig;
import me.bedwarshurts.leagueproximitychat.utils.RitoApiUtils;
import org.json.JSONObject;

import java.awt.Desktop;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;

public final class LocalWebServer {

    private static final String WEB_ROOT = "/web/";
    private static final String JSON = "application/json; charset=utf-8";
    private static final String NO_STORE = "no-store";
    private static final String ONE_DAY = "max-age=86400";
    private static final int MAX_VOICE_UPLOAD_BYTES = 8 * 1024 * 1024;

    private static final Map<String, String> STATIC_TYPES = Map.of(
            "html", "text/html",
            "css", "text/css; charset=utf-8",
            "js", "application/javascript; charset=utf-8");

    private LocalWebServer() {
    }

    public static void start(int port) throws IOException {
        HttpServer httpServer = HttpServer.create(new InetSocketAddress(port), 0);

        httpServer.createContext("/", LocalWebServer::handleStatic);
        httpServer.createContext("/settings", LocalWebServer::handleSettings);
        httpServer.createContext("/logs", LocalWebServer::handleLogs);
        httpServer.createContext("/open-log-viewer", LocalWebServer::handleOpenLogViewer);
        httpServer.createContext("/potg/", LocalWebServer::handlePlayOfTheGame);
        httpServer.createContext("/profile-icon/", LocalWebServer::handleProfileIcon);
        httpServer.createContext("/matches", LocalWebServer::handleMatches);
        httpServer.createContext("/replay-api/enable", LocalWebServer::handleEnableReplayApi);

        httpServer.setExecutor(Executors.newFixedThreadPool(4, r -> {
            Thread t = new Thread(r, "http-server");
            t.setDaemon(true);
            return t;
        }));

        httpServer.start();
        System.out.println("Local Web Server running on port " + port + "!");
    }

    private static void handleStatic(HttpExchange exchange) throws IOException {
        String file = staticFileFor(exchange.getRequestURI().getPath());
        byte[] body = readResource(WEB_ROOT + file);
        if (body == null && !file.equals("index.html")) {
            file = "index.html";
            body = readResource(WEB_ROOT + file);
        }
        if (body == null) {
            send(exchange, 404, null, null, "Error: Could not find index.html.".getBytes(StandardCharsets.UTF_8));
            return;
        }

        String extension = file.substring(file.lastIndexOf('.') + 1);
        String cacheControl = file.equals("livekit-client.umd.min.js") ? ONE_DAY : null;
        send(exchange, 200, STATIC_TYPES.get(extension), cacheControl, body);
    }

    private static String staticFileFor(String path) {
        String file = path.startsWith("/") ? path.substring(1) : path;
        int dot = file.lastIndexOf('.');
        boolean knownType = dot > 0 && STATIC_TYPES.containsKey(file.substring(dot + 1));
        boolean safe = !file.contains("..") && !file.contains("\\") && !file.startsWith("/");
        return (knownType && safe) ? file : "index.html";
    }

    private static byte[] readResource(String name) throws IOException {
        try (InputStream is = LocalWebServer.class.getResourceAsStream(name)) {
            return is == null ? null : is.readAllBytes();
        }
    }

    private static void handleSettings(HttpExchange exchange) throws IOException {
        try {
            if ("GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                byte[] body = new JSONObject()
                        .put("configured", ConfigManager.isConfigured())
                        .put("url", ConfigManager.getLivekitUrl())
                        .put("apiKey", ConfigManager.getApiKey())
                        .put("apiSecret", ConfigManager.getApiSecret())
                        .put("lowPerformanceMode", ConfigManager.isLowPerformanceMode())
                        .put("debugMode", ConfigManager.isDebugMode())
                        .put("saveAllHighlights", ConfigManager.isSaveAllHighlights())
                        .put("version", AppInfo.version())
                        .put("build", AppInfo.buildLabel())
                        .toString().getBytes(StandardCharsets.UTF_8);
                send(exchange, 200, JSON, NO_STORE, body);
                return;
            }

            if ("POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                String raw;
                try (InputStream is = exchange.getRequestBody()) {
                    raw = new String(is.readAllBytes(), StandardCharsets.UTF_8);
                }
                JSONObject json = new JSONObject(raw);
                boolean ok = ConfigManager.save(
                        json.optString("url", ""),
                        json.optString("apiKey", ""),
                        json.optString("apiSecret", ""),
                        json.optBoolean("lowPerformanceMode", false),
                        json.optBoolean("debugMode", false),
                        json.optBoolean("saveAllHighlights", false));

                byte[] body = new JSONObject().put("ok", ok).toString().getBytes(StandardCharsets.UTF_8);
                send(exchange, ok ? 200 : 400, JSON, null, body);
                return;
            }

            exchange.sendResponseHeaders(405, -1);
        } catch (Exception e) {
            DebugManager.logFailure("[Settings] Request failed", e);
            try {
                exchange.sendResponseHeaders(500, -1);
            } catch (IOException ignored) {
            }
        }
    }

    private static void handleLogs(HttpExchange exchange) throws IOException {
        byte[] body = LogManager.snapshot().getBytes(StandardCharsets.UTF_8);
        send(exchange, 200, "text/plain; charset=utf-8", NO_STORE, body);
    }

    private static void handleOpenLogViewer(HttpExchange exchange) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(405, -1);
            return;
        }
        boolean opened = false;
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(new URI(AppConstants.APP_URL + "/logs"));
                opened = true;
            }
        } catch (Exception e) {
            DebugManager.logFailure("[Logs] Could not open the log viewer", e);
        }
        exchange.sendResponseHeaders(opened ? 204 : 500, -1);
    }

    private static void handlePlayOfTheGame(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();

        if (path.equals("/potg/meta")) {
            send(exchange, 200, JSON, NO_STORE, PlayOfGameManager.metaJson().getBytes(StandardCharsets.UTF_8));
            return;
        }

        if (path.equals("/potg/stats")) {
            send(exchange, 200, JSON, NO_STORE, PlayOfGameManager.statsJson().getBytes(StandardCharsets.UTF_8));
            return;
        }

        if (path.equals("/potg/save") && "POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            saveHighlight(exchange);
            return;
        }

        if (path.startsWith("/potg/frame/")) {
            byte[] img = null;
            try {
                String rest = path.substring("/potg/frame/".length());
                int slash = rest.indexOf('/');
                img = PlayOfGameManager.frameBytes(
                        Integer.parseInt(rest.substring(0, slash)),
                        Integer.parseInt(rest.substring(slash + 1)));
            } catch (Exception e) {
                DebugManager.logFailure("[PotG] Bad frame request " + path, e);
            }
            if (img == null) {
                exchange.sendResponseHeaders(404, -1);
                return;
            }
            send(exchange, 200, "image/jpeg", null, img);
            return;
        }

        exchange.sendResponseHeaders(404, -1);
    }

    private static void saveHighlight(HttpExchange exchange) throws IOException {
        JSONObject result = new JSONObject();
        int status = 200;
        try {
            byte[] webm = exchange.getRequestBody().readAllBytes();
            if (webm.length < 1024) throw new IOException("empty recording");

            Map<String, String> query = queryParams(exchange);
            String name = query.getOrDefault("name", "Play of the Game");
            name = name.replaceAll("[^A-Za-z0-9 _.-]", "").trim();
            if (name.isEmpty()) name = "Play of the Game";

            Path dir = Paths.get(System.getProperty("user.home"), "Videos", "LeagueProximityChat");
            Files.createDirectories(dir);
            String stamp = new SimpleDateFormat("yyyy-MM-dd HH.mm.ss").format(new Date());
            Path file = dir.resolve(name + " " + stamp + ".webm");
            Files.write(file, webm);

            result.put("path", file.toString());
            System.out.println("[PotG] Highlight saved to " + file);
            MatchHistoryManager.linkSavedHighlight(query.get("hid"), query.get("headline"), query.get("clock"), file);
        } catch (Exception ex) {
            status = 500;
            result.put("error", String.valueOf(ex.getMessage()));
            System.out.println("[PotG] Highlight save failed: " + ex.getMessage());
        }
        send(exchange, status, JSON, null, result.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static void handleMatches(HttpExchange exchange) throws IOException {
        String[] parts = exchange.getRequestURI().getPath().split("/");
        String method = exchange.getRequestMethod().toUpperCase();
        try {
            if (parts.length == 2 && method.equals("GET")) {
                send(exchange, 200, JSON, NO_STORE, MatchHistoryManager.listJson().getBytes(StandardCharsets.UTF_8));
                return;
            }
            String id = parts.length > 2 ? parts[2] : null;
            if (!MatchHistoryManager.isValidId(id)) {
                exchange.sendResponseHeaders(404, -1);
                return;
            }
            String action = parts.length > 3 ? parts[3] : "";

            if (action.isEmpty() && method.equals("GET")) {
                String match = MatchHistoryManager.matchJson(id);
                if (match == null) {
                    exchange.sendResponseHeaders(404, -1);
                    return;
                }
                send(exchange, 200, JSON, NO_STORE, match.getBytes(StandardCharsets.UTF_8));
                return;
            }

            if (action.equals("highlights") && parts.length == 4 && method.equals("POST")) {
                Map<String, String> query = queryParams(exchange);
                byte[] webm = exchange.getRequestBody().readAllBytes();
                if (webm.length < 1024) {
                    exchange.sendResponseHeaders(400, -1);
                    return;
                }
                int score = 0;
                try {
                    score = Integer.parseInt(query.getOrDefault("score", "0"));
                } catch (NumberFormatException ignored) {
                }
                MatchHistoryManager.addHighlight(id, query.get("hid"), query.get("headline"), query.get("clock"), score, webm);
                exchange.sendResponseHeaders(204, -1);
                return;
            }

            if (action.equals("highlights") && parts.length == 5 && method.equals("GET")) {
                Path file = MatchHistoryManager.highlightFile(id, Integer.parseInt(parts[4]));
                if (file == null || !Files.exists(file)) {
                    exchange.sendResponseHeaders(404, -1);
                    return;
                }
                send(exchange, 200, "video/webm", NO_STORE, Files.readAllBytes(file));
                return;
            }

            if (action.equals("voice") && parts.length == 4 && method.equals("POST")) {
                byte[] body = readLimited(exchange, MAX_VOICE_UPLOAD_BYTES);
                boolean stored = body != null && VoiceArchiveManager.store(id, body);
                exchange.sendResponseHeaders(body == null ? 413 : stored ? 204 : 409, -1);
                return;
            }

            if (action.equals("voice") && parts.length == 4 && method.equals("GET")) {
                String voice = VoiceArchiveManager.voiceJson(id);
                if (voice == null) {
                    exchange.sendResponseHeaders(404, -1);
                    return;
                }
                send(exchange, 200, JSON, NO_STORE, voice.getBytes(StandardCharsets.UTF_8));
                return;
            }

            if (action.equals("voice") && parts.length == 5 && parts[4].equals("data") && method.equals("GET")) {
                Path file = VoiceArchiveManager.dataFile(id);
                if (file == null) {
                    exchange.sendResponseHeaders(404, -1);
                    return;
                }
                send(exchange, 200, "application/octet-stream", NO_STORE, Files.readAllBytes(file));
                return;
            }

            if (action.equals("replay") && parts.length == 4 && method.equals("GET")) {
                String replay = MatchHistoryManager.replayJson(id);
                if (replay == null) {
                    exchange.sendResponseHeaders(404, -1);
                    return;
                }
                send(exchange, 200, JSON, NO_STORE, replay.getBytes(StandardCharsets.UTF_8));
                return;
            }

            if (action.equals("replay") && parts.length == 5 && method.equals("POST")) {
                boolean ok = switch (parts[4]) {
                    case "download" -> MatchHistoryManager.downloadReplay(id);
                    case "watch" -> ReplayApiConfig.isEnabled() && MatchHistoryManager.watchReplay(id);
                    default -> false;
                };
                send(exchange, ok ? 200 : 409, JSON, null, new JSONObject().put("ok", ok).toString().getBytes(StandardCharsets.UTF_8));
                return;
            }

            exchange.sendResponseHeaders(404, -1);
        } catch (Exception e) {
            DebugManager.logFailure("[History] Request failed: " + exchange.getRequestURI(), e);
            try {
                exchange.sendResponseHeaders(500, -1);
            } catch (IOException ignored) {
            }
        }
    }

    private static void handleEnableReplayApi(HttpExchange exchange) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(405, -1);
            return;
        }
        JSONObject result = new JSONObject().put("path", ReplayApiConfig.gameCfgPath().toString());
        try {
            ReplayApiConfig.enable();
            result.put("ok", true);
        } catch (Exception e) {
            System.err.println("[Replay] Could not enable the Replay API: " + e.getMessage());
            result.put("ok", false).put("error", String.valueOf(e.getMessage()));
        }
        send(exchange, result.getBoolean("ok") ? 200 : 500, JSON, null, result.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] readLimited(HttpExchange exchange, int maxBytes) throws IOException {
        try (InputStream in = exchange.getRequestBody()) {
            byte[] body = in.readNBytes(maxBytes + 1);
            return body.length > maxBytes ? null : body;
        }
    }

    private static Map<String, String> queryParams(HttpExchange exchange) {
        Map<String, String> params = new HashMap<>();
        String query = exchange.getRequestURI().getRawQuery();
        if (query == null) return params;
        for (String kv : query.split("&")) {
            int eq = kv.indexOf('=');
            if (eq <= 0) continue;
            params.put(URLDecoder.decode(kv.substring(0, eq), StandardCharsets.UTF_8),
                    URLDecoder.decode(kv.substring(eq + 1), StandardCharsets.UTF_8));
        }
        return params;
    }

    private static void handleProfileIcon(HttpExchange exchange) throws IOException {
        byte[] image = null;
        try {
            String idPart = exchange.getRequestURI().getPath()
                    .substring("/profile-icon/".length()).replaceAll("[^0-9]", "");
            if (!idPart.isEmpty()) {
                image = RitoApiUtils.getProfileIconImage(Integer.parseInt(idPart));
            }
        } catch (Exception e) {
            DebugManager.logFailure("[ProfileIcon] Bad icon request", e);
        }

        if (image == null || image.length == 0) {
            exchange.sendResponseHeaders(404, -1);
            return;
        }
        send(exchange, 200, "image/jpeg", ONE_DAY, image);
    }

    private static void send(HttpExchange exchange, int status, String contentType, String cacheControl, byte[] body)
            throws IOException {
        if (contentType != null) exchange.getResponseHeaders().set("Content-Type", contentType);
        if (cacheControl != null) exchange.getResponseHeaders().set("Cache-Control", cacheControl);
        if (body.length == 0) {
            exchange.sendResponseHeaders(status, -1);
            return;
        }
        exchange.sendResponseHeaders(status, body.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(body);
        }
    }
}
