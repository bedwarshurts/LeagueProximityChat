package me.bedwarshurts.leagueproximitychat.utils;

import me.bedwarshurts.leagueproximitychat.data.LeagueGame;
import me.bedwarshurts.leagueproximitychat.data.LeaguePlayer;
import org.json.JSONArray;
import org.json.JSONObject;

import javax.net.ssl.*;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

public class RitoApiUtils {

    private static boolean sslBypassed = false;
    private static String cachedSummonerName = null;

    public static void disableSSLChecks() {
        if (sslBypassed) return;
        try {
            TrustManager[] trustAllCerts = new TrustManager[]{
                    new X509TrustManager() {
                        public X509Certificate[] getAcceptedIssuers() { return null; }
                        public void checkClientTrusted(X509Certificate[] certs, String authType) {}
                        public void checkServerTrusted(X509Certificate[] certs, String authType) {}
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
            Path lockfilePath = Paths.get("C:\\Riot Games\\League of Legends\\lockfile");
            if (!Files.exists(lockfilePath)) {
                return null;
            }

            String lockfileData = Files.readString(lockfilePath);
            String[] lockfileParts = lockfileData.split(":");
            String port = lockfileParts[2];
            String password = lockfileParts[3];

            String base64Auth = Base64.getEncoder().encodeToString(("riot:" + password).getBytes());
            String response = executeGetRequest("https://127.0.0.1:" + port + "/lol-lobby/v2/lobby", base64Auth);

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
                            return getRiotIdFromSummonerId(port, base64Auth, leaderId);
                        }
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    public static String getRiotIdFromSummonerId(String port, String base64Auth, long summonerId) {
        try {
            String response = executeGetRequest("https://127.0.0.1:" + port + "/lol-summoner/v1/summoners/" + summonerId, base64Auth);

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

    public static LeagueGame getLivePlayerList() {
        String rawJson = fetchAPI("https://127.0.0.1:2999/liveclientdata/playerlist");

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
}