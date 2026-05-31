package me.bedwarshurts.leagueproximitychat.utils;

import javax.net.ssl.*;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.URL;
import java.security.cert.X509Certificate;

public class RitoApiUtils {

    private static boolean sslBypassed = false;
    private static String cachedSummonerName = null;

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

    public static String fetchAPI(String endpoint) {
        disableSSLChecks();
        try {
            URL url = new URI(endpoint).toURL();
            HttpsURLConnection conn = (HttpsURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(2000);
            conn.setReadTimeout(2000);

            BufferedReader in = new BufferedReader(new InputStreamReader(conn.getInputStream()));
            StringBuilder content = new StringBuilder();
            String inputLine;
            while ((inputLine = in.readLine()) != null) {
                content.append(inputLine);
            }
            in.close();
            conn.disconnect();
            return content.toString();
        } catch (Exception e) {
            return null;
        }
    }

    public static String getLocalSummonerName() {
        if (cachedSummonerName != null) return cachedSummonerName;

        String activePlayerJson = fetchAPI("https://127.0.0.1:2999/liveclientdata/activeplayer");
        if (activePlayerJson == null) return "Couldn't get name";

        String marker = "\"summonerName\":";
        int idx = activePlayerJson.indexOf(marker);
        if (idx == -1) return "Couldn't get name";

        int start = activePlayerJson.indexOf("\"", idx + marker.length()) + 1;
        int end = activePlayerJson.indexOf("\"", start);
        if (start > 0 && end > start) {
            cachedSummonerName = activePlayerJson.substring(start, end);
        } else {
            cachedSummonerName = "Couldn't get name";
        }

        return cachedSummonerName;
    }
}