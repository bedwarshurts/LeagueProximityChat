package me.bedwarshurts.leagueproximitychat.utils;

import me.bedwarshurts.leagueproximitychat.data.LeagueGame;
import me.bedwarshurts.leagueproximitychat.data.LeaguePlayer;
import org.json.JSONArray;
import org.json.JSONObject;

import javax.net.ssl.*;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Scanner;

public class RitoApiUtils {

    private static boolean sslBypassed = false;
    private static String cachedSummonerName = null;
    private static final Map<String, Integer> profileIconCache = new HashMap<>();
    private static final Path DEFAULT_LOCKFILE_PATH = Paths.get("C:\\Riot Games\\League of Legends\\lockfile");
    private static volatile Path resolvedLockfilePath = null;

    private static volatile String cachedPlayerListJson = null;
    private static volatile long cachedPlayerListAtMs = 0;
    private static final long PLAYER_LIST_CACHE_MS = 300;

    private record LockfileAuth(String port, String password, String base64Auth) {}

    public static void disableSSLChecks() {
        if (sslBypassed) return;
        try {
            TrustManager[] trustAllCerts = new TrustManager[]{
                    new X509TrustManager() {
                        public X509Certificate[] getAcceptedIssuers() {
                            return null;
                        }

                        public void checkClientTrusted(X509Certificate[] certs, String authType) {
                        }

                        public void checkServerTrusted(X509Certificate[] certs, String authType) {
                        }
                    }
            };
            SSLContext sc = SSLContext.getInstance("SSL");
            sc.init(null, trustAllCerts, new java.security.SecureRandom());
            HttpsURLConnection.setDefaultSSLSocketFactory(sc.getSocketFactory());
            HttpsURLConnection.setDefaultHostnameVerifier((hostname, session) -> true);
            sslBypassed = true;
        } catch (Exception e) {
            System.err.println("Failed to bypass SSL: " + e.getMessage());
        }
    }

    private static String executeGetRequest(String endpoint, String authHeader) throws Exception {
        disableSSLChecks();

        URL url = new URI(endpoint).toURL();
        HttpsURLConnection conn = (HttpsURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(2000);
        conn.setReadTimeout(2000);

        if (authHeader != null && !authHeader.isEmpty()) {
            conn.setRequestProperty("Authorization", "Basic " + authHeader);
            conn.setRequestProperty("Accept", "application/json");
        }

        if (conn.getResponseCode() != 200) {
            return null;
        }

        try (BufferedReader in = new BufferedReader(new InputStreamReader(conn.getInputStream()))) {
            StringBuilder content = new StringBuilder();
            String inputLine;
            while ((inputLine = in.readLine()) != null) {
                content.append(inputLine);
            }
            return content.toString();
        } finally {
            conn.disconnect();
        }
    }

    public static String fetchAPI(String endpoint) {
        try {
            return executeGetRequest(endpoint, null);
        } catch (Exception e) {
            return null;
        }
    }

    private static Path getLockfilePath() {
        if (resolvedLockfilePath != null && Files.exists(resolvedLockfilePath)) {
            return resolvedLockfilePath;
        }

        if (Files.exists(DEFAULT_LOCKFILE_PATH)) {
            resolvedLockfilePath = DEFAULT_LOCKFILE_PATH;
            return DEFAULT_LOCKFILE_PATH;
        }

        try {
            String programData = System.getenv("ProgramData");
            if (programData == null) return null;

            Path installs = Paths.get(programData, "Riot Games", "RiotClientInstalls.json");
            if (!Files.exists(installs)) return null;

            JSONObject json = new JSONObject(Files.readString(installs));
            JSONObject associated = json.optJSONObject("associated_client");
            if (associated == null) return null;

            for (String installDir : associated.keySet()) {
                if (!installDir.toLowerCase().contains("league of legends")) {
                    continue;
                }

                Path candidate = Paths.get(installDir, "lockfile");
                if (Files.exists(candidate)) {
                    System.out.println("[LCU] Found lockfile at non-default location: " + candidate);
                    resolvedLockfilePath = candidate;
                    return candidate;
                }
            }
        } catch (Exception ignored) {
        }

        return null;
    }

    private static String fetchClientAPI(String path) {
        try {
            LockfileAuth auth = getLockfileAuth();
            if (auth == null) return null;

            return executeGetRequest("https://127.0.0.1:" + auth.port() + path, auth.base64Auth());
        } catch (Exception ignored) {
            return null;
        }
    }

    public static void clearCache() {
        cachedSummonerName = null;
        profileIconCache.clear();
    }

    public static int getLocalProfileIconId() {
        int iconId = -1;
        try {
            String response = fetchClientAPI("/lol-summoner/v1/current-summoner");
            if (response != null && !response.isEmpty()) {
                iconId = new JSONObject(response).optInt("profileIconId", -1);
            }
        } catch (Exception ignored) {
        }
        if (iconId <= 0) {
            System.err.println("[ProfileIcon] current-summoner lookup failed (is the League client running?)");
        }
        return iconId;
    }

    private static LockfileAuth getLockfileAuth() {
        Path lockfile = getLockfilePath();
        if (lockfile == null) return null;

        String[] lockfileParts = lockfile.toString().split(":");
        String port = lockfileParts[2];
        String password = lockfileParts[3];
        String base64Auth = Base64.getEncoder().encodeToString(("riot:" + password).getBytes());

        return new LockfileAuth(port, password, base64Auth);
    }

    public static byte[] getProfileIconImage(int iconId) {
        try {
            LockfileAuth auth = getLockfileAuth();
            if (auth == null) return null;

            disableSSLChecks();
            URL url = new URI("https://127.0.0.1:" + auth.port() + "/lol-game-data/assets/v1/profile-icons/" + iconId + ".jpg").toURL();
            HttpsURLConnection conn = (HttpsURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(2000);
            conn.setReadTimeout(3000);
            conn.setRequestProperty("Authorization", "Basic " + auth.base64Auth());

            if (conn.getResponseCode() != 200) {
                conn.disconnect();
                return null;
            }
            try (InputStream in = conn.getInputStream()) {
                return in.readAllBytes();
            } finally {
                conn.disconnect();
            }
        } catch (Exception e) {
            return null;
        }
    }

    public static String getProfileIconDataUri(int iconId) {
        byte[] bytes = getProfileIconImage(iconId);
        if (bytes == null || bytes.length == 0) {
            return "";
        }
        String mime = (bytes[0] & 0xFF) == 0x89 ? "image/png" : "image/jpeg";
        return "data:" + mime + ";base64," + Base64.getEncoder().encodeToString(bytes);
    }

    public static int getProfileIconId(String gameName, String tagLine, String riotIdFallback) {
        if ((gameName == null || gameName.isEmpty()) && riotIdFallback != null && riotIdFallback.contains("#")) {
            String[] parts = riotIdFallback.split("#", 2);
            gameName = parts[0];
            tagLine = parts[1];
        }
        if (gameName == null || gameName.isEmpty()) {
            return -1;
        }

        String cacheKey = (gameName + "#" + tagLine).toLowerCase();
        Integer cached = profileIconCache.get(cacheKey);
        if (cached != null) {
            return cached;
        }

        int iconId = -1;
        try {
            String encodedName = URLEncoder.encode(gameName, StandardCharsets.UTF_8).replace("+", "%20");
            String encodedTag = URLEncoder.encode(tagLine == null ? "" : tagLine, StandardCharsets.UTF_8).replace("+", "%20");
            String query = "?gameName=" + encodedName + "&tagLine=" + encodedTag;
            String response = fetchClientAPI("/lol-summoner/v1/alias/lookup" + query);
            if (response != null && !response.isEmpty()) {
                String trimmed = response.trim();
                JSONObject summoner = trimmed.startsWith("[")
                        ? new JSONArray(trimmed).optJSONObject(0)
                        : new JSONObject(trimmed);
                if (summoner != null) {
                    iconId = summoner.optInt("profileIconId", -1);
                    if (iconId <= 0) {
                        String puuid = summoner.optString("puuid", "");
                        if (!puuid.isEmpty()) {
                            String byPuuid = fetchClientAPI("/lol-summoner/v2/summoners/puuid/" + puuid);
                            if (byPuuid != null) {
                                iconId = new JSONObject(byPuuid).optInt("profileIconId", -1);
                            }
                        }
                    }
                }
            }
        } catch (Exception ignored) {
        }

        if (iconId > 0) {
            profileIconCache.put(cacheKey, iconId);
        } else {
            System.err.println("[ProfileIcon] Could not resolve summoner icon for " + gameName + "#" + tagLine);
        }
        return iconId;
    }

    public static String getLocalSummonerName() {
        if (cachedSummonerName != null) {
            return cachedSummonerName;
        }

        String activePlayerJson = fetchAPI("https://127.0.0.1:2999/liveclientdata/activeplayer");
        if (activePlayerJson == null || activePlayerJson.isEmpty()) {
            return null;
        }

        try {
            JSONObject json = new JSONObject(activePlayerJson);

            String riotId = json.optString("riotId", "");
            String summonerName = json.optString("summonerName", "");

            String resolvedName = !riotId.isEmpty() ? riotId : summonerName;

            if (!resolvedName.isEmpty()) {
                cachedSummonerName = resolvedName;
                return cachedSummonerName;
            }
        } catch (Exception ignored) {
        }

        return null;
    }

    public static String getLobbyLeader() {
        try {
            String response = fetchClientAPI("/lol-lobby/v2/lobby");
            if (response == null) {
                return null;
            }

            JSONObject lobbyJson = new JSONObject(response);
            JSONArray members = lobbyJson.optJSONArray("members");

            if (members != null) {
                for (int i = 0; i < members.length(); i++) {
                    JSONObject member = members.getJSONObject(i);
                    if (member.optBoolean("isLeader", false)) {
                        long leaderId = member.optLong("summonerId", 0);
                        if (leaderId != 0) {
                            return getRiotIdFromSummonerId(leaderId);
                        }
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    public static String getGameflowPhase() {
        String response = fetchClientAPI("/lol-gameflow/v1/gameflow-phase");
        if (response == null) {
            return null;
        }
        return response.replace("\"", "").trim();
    }

    private static String getRiotIdFromSummonerId(long summonerId) {
        try {
            String response = fetchClientAPI("/lol-summoner/v1/summoners/" + summonerId);

            if (response == null) return "Unknown";

            JSONObject summonerJson = new JSONObject(response);
            String gameName = summonerJson.optString("gameName", "Unknown");
            String tagLine = summonerJson.optString("tagLine", "");

            return tagLine.isEmpty() ? gameName : gameName + "#" + tagLine;

        } catch (Exception e) {
            System.err.println("Failed to resolve Summoner ID: " + e.getMessage());
            return "Unknown";
        }
    }

    public static double getGameTime() {
        JSONObject stats = getGameStats();
        return stats != null ? stats.optDouble("gameTime", -1.0) : -1.0;
    }

    public static JSONObject getGameStats() {
        String json = fetchAPI("https://127.0.0.1:2999/liveclientdata/gamestats");
        if (json == null || json.isEmpty()) {
            return null;
        }
        try {
            return new JSONObject(json);
        } catch (Exception e) {
            return null;
        }
    }

    public static String getEndOfGameStatsBlock() {
        return fetchClientAPI("/lol-end-of-game/v1/eog-stats-block");
    }

    public static String fetchPlayerListRaw() {
        long now = System.currentTimeMillis();
        String cached = cachedPlayerListJson;
        if (cached != null && (now - cachedPlayerListAtMs) < PLAYER_LIST_CACHE_MS) {
            return cached;
        }

        String raw = fetchAPI("https://127.0.0.1:2999/liveclientdata/playerlist");
        if (raw != null && !raw.isEmpty()) {
            cachedPlayerListJson = raw;
            cachedPlayerListAtMs = now;
        } else {
            cachedPlayerListJson = null;
        }
        return raw;
    }

    public static LeagueGame getLivePlayerList() {
        String rawJson = fetchPlayerListRaw();

        if (rawJson == null || rawJson.isEmpty()) {
            return null;
        }

        List<LeaguePlayer> playersList = new ArrayList<>();

        try {
            JSONArray jsonArray = new JSONArray(rawJson);

            for (int i = 0; i < jsonArray.length(); i++) {
                JSONObject pJson = jsonArray.getJSONObject(i);

                boolean isBot = pJson.optBoolean("isBot", false);
                boolean isDead = pJson.optBoolean("isDead", false);
                int level = pJson.optInt("level", 1);
                String position = pJson.optString("position", "");
                String rawChampionName = pJson.optString("rawChampionName", "");
                String rawSkinName = pJson.optString("rawSkinName", "");
                double respawnTimer = pJson.optDouble("respawnTimer", 0.0);
                String riotId = pJson.optString("riotId", "");
                String riotIdGameName = pJson.optString("riotIdGameName", "");
                String riotIdTagLine = pJson.optString("riotIdTagLine", "");
                int skinID = pJson.optInt("skinID", 0);
                String skinName = pJson.optString("skinName", "");
                String summonerName = pJson.optString("summonerName", "");
                String team = pJson.optString("team", "");

                List<LeaguePlayer.Item> items = new ArrayList<>();
                Object itemsObj = pJson.opt("items");
                if (itemsObj instanceof JSONArray itemsArray) {
                    for (int j = 0; j < itemsArray.length(); j++) {
                        JSONObject iJson = itemsArray.getJSONObject(j);
                        items.add(new LeaguePlayer.Item(
                                iJson.optBoolean("canUse", false),
                                iJson.optBoolean("consumable", false),
                                iJson.optInt("count", 1),
                                iJson.optString("displayName", ""),
                                iJson.optInt("itemID", 0),
                                iJson.optInt("price", 0),
                                iJson.optString("rawDescription", ""),
                                iJson.optString("rawDisplayName", ""),
                                iJson.optInt("slot", 0)
                        ));
                    }
                }

                LeaguePlayer.Runes runes = null;
                Object runesObj = pJson.opt("runes");
                if (runesObj instanceof JSONObject rJson && !rJson.has("error")) {
                    runes = new LeaguePlayer.Runes(
                            parseRuneDetail(rJson.optJSONObject("keystone")),
                            parseRuneDetail(rJson.optJSONObject("primaryRuneTree")),
                            parseRuneDetail(rJson.optJSONObject("secondaryRuneTree"))
                    );
                }

                LeaguePlayer.Score scores = null;
                Object scoresObj = pJson.opt("scores");
                if (scoresObj instanceof JSONObject sJson && !sJson.has("error")) {
                    scores = new LeaguePlayer.Score(
                            sJson.optInt("assists", 0),
                            sJson.optInt("creepScore", 0),
                            sJson.optInt("deaths", 0),
                            sJson.optInt("kills", 0),
                            sJson.optDouble("wardScore", 0.0)
                    );
                }

                LeaguePlayer.SummonerSpells spells = null;
                Object spellsObj = pJson.opt("summonerSpells");
                if (spellsObj instanceof JSONObject spJson && !spJson.has("error")) {
                    spells = new LeaguePlayer.SummonerSpells(
                            parseSpellDetail(spJson.optJSONObject("summonerSpellOne")),
                            parseSpellDetail(spJson.optJSONObject("summonerSpellTwo"))
                    );
                }

                playersList.add(new LeaguePlayer(
                        isBot, isDead, level, position, rawChampionName, rawSkinName, respawnTimer,
                        riotId, riotIdGameName, riotIdTagLine, skinID, skinName, summonerName, team,
                        items, runes, scores, spells
                ));
            }

            return new LeagueGame(playersList);

        } catch (Exception e) {
            System.err.println("[getLivePlayerList] Failed to manually parse JSON: " + e.getMessage());
            return null;
        }
    }

    private static LeaguePlayer.RuneDetail parseRuneDetail(JSONObject obj) {
        if (obj == null) return null;
        return new LeaguePlayer.RuneDetail(
                obj.optString("displayName", ""),
                obj.optInt("id", 0),
                obj.optString("rawDescription", ""),
                obj.optString("rawDisplayName", "")
        );
    }

    private static LeaguePlayer.SpellDetail parseSpellDetail(JSONObject obj) {
        if (obj == null) return null;
        return new LeaguePlayer.SpellDetail(
                obj.optString("displayName", ""),
                obj.optString("rawDescription", ""),
                obj.optString("rawDisplayName", "")
        );
    }

    public static String sanitizeChampionName(String rawName) {
        if (rawName == null || rawName.isEmpty()) {
            return "Unknown";
        }

        if (rawName.startsWith("Character_") && rawName.endsWith("_Name")) {
            rawName = rawName.replace("Character_", "").replace("_Name", "");
        }

        if (rawName.startsWith("game_character_displayname_")) {
            rawName = rawName.replace("game_character_displayname_", "");
        }

        rawName = rawName.replace(" ", "")
                .replace("'", "")
                .replace(".", "");

        if (rawName.length() > 1) {
            rawName = rawName.substring(0, 1).toUpperCase() + rawName.substring(1);
        }

        return rawName;
    }


    public static String getLatestDataDragonVersion() throws Exception {
        URL url = new URI("https://ddragon.leagueoflegends.com/api/versions.json").toURL();
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(3000);
        conn.setReadTimeout(3000);

        Scanner scanner = new Scanner(new InputStreamReader(conn.getInputStream()));
        String response = scanner.useDelimiter("\\A").next();
        scanner.close();

        return response.split("\"")[1];
    }
}