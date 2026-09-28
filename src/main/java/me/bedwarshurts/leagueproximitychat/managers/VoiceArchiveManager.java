package me.bedwarshurts.leagueproximitychat.managers;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

public final class VoiceArchiveManager {

    private static final String DIR = "voice";
    private static final String DATA_FILE = "utterances.bin";
    private static final String INDEX_FILE = "utterances.jsonl";
    private static final String POSITIONS_FILE = "positions.jsonl";

    private static final int MAX_HEADER_BYTES = 4 * 1024 * 1024;
    private static final int MAX_UTTERANCE_BYTES = 1024 * 1024;
    private static final long MAX_UTTERANCE_MS = 120_000;
    private static final int MAX_SPEAKER_LENGTH = 100;

    private static final Object lock = new Object();

    private VoiceArchiveManager() {
    }

    // Body: [u32 little-endian header length][header JSON][utterance payloads, back to back].
    // Header: {utterances: [{s, t, d, sr, n}], positions: {speaker: [[t, x, y, dead], ...]}}.
    public static boolean store(String matchId, byte[] body) throws IOException {
        if (!MatchHistoryManager.isValidId(matchId) || !MatchHistoryManager.acceptsRecording(matchId)) return false;
        if (body.length < 4) throw new IOException("empty upload");

        int headerLength = ByteBuffer.wrap(body, 0, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();
        if (headerLength <= 0 || headerLength > MAX_HEADER_BYTES || 4 + headerLength > body.length) {
            throw new IOException("bad header length " + headerLength);
        }
        JSONObject header = new JSONObject(new String(body, 4, headerLength, StandardCharsets.UTF_8));
        int payloadStart = 4 + headerLength;

        List<JSONObject> indexLines = new ArrayList<>();
        List<int[]> slices = new ArrayList<>();
        JSONArray utterances = header.optJSONArray("utterances");
        int cursor = payloadStart;
        if (utterances != null) {
            for (int i = 0; i < utterances.length(); i++) {
                JSONObject u = utterances.getJSONObject(i);
                int n = u.getInt("n");
                long duration = u.getLong("d");
                String speaker = u.getString("s");
                if (n <= 0 || n > MAX_UTTERANCE_BYTES || cursor + n > body.length) throw new IOException("bad utterance size");
                if (duration <= 0 || duration > MAX_UTTERANCE_MS || !validSpeaker(speaker)) throw new IOException("bad utterance");
                slices.add(new int[]{cursor, n});
                indexLines.add(new JSONObject()
                        .put("s", speaker)
                        .put("t", u.getLong("t"))
                        .put("d", duration)
                        .put("sr", u.optInt("sr", 48000))
                        .put("n", n));
                cursor += n;
            }
        }

        List<String> positionLines = new ArrayList<>();
        JSONObject positions = header.optJSONObject("positions");
        if (positions != null) {
            for (String speaker : positions.keySet()) {
                JSONArray samples = positions.optJSONArray(speaker);
                if (!validSpeaker(speaker) || samples == null || samples.isEmpty()) continue;
                positionLines.add(new JSONObject().put("s", speaker).put("p", samples).toString());
            }
        }

        synchronized (lock) {
            Path dir = MatchHistoryManager.matchDir(matchId).resolve(DIR);
            Files.createDirectories(dir);
            Path data = dir.resolve(DATA_FILE);
            long offset = Files.exists(data) ? Files.size(data) : 0;

            if (!slices.isEmpty()) {
                try (OutputStream out = Files.newOutputStream(data, StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
                    for (int[] slice : slices) out.write(body, slice[0], slice[1]);
                }
                try (BufferedWriter index = Files.newBufferedWriter(dir.resolve(INDEX_FILE), StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
                    for (int i = 0; i < indexLines.size(); i++) {
                        index.write(indexLines.get(i).put("o", offset).toString());
                        index.newLine();
                        offset += slices.get(i)[1];
                    }
                }
            }
            if (!positionLines.isEmpty()) {
                try (BufferedWriter out = Files.newBufferedWriter(dir.resolve(POSITIONS_FILE), StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
                    for (String line : positionLines) {
                        out.write(line);
                        out.newLine();
                    }
                }
            }
        }
        return true;
    }

    private static boolean validSpeaker(String speaker) {
        return speaker != null && !speaker.isBlank() && speaker.length() <= MAX_SPEAKER_LENGTH;
    }

    public static Path dataFile(String matchId) {
        if (!MatchHistoryManager.isValidId(matchId)) return null;
        Path file = MatchHistoryManager.matchDir(matchId).resolve(DIR).resolve(DATA_FILE);
        return Files.exists(file) ? file : null;
    }

    public static JSONObject summary(String matchId) {
        List<JSONObject> index = readLines(matchId, INDEX_FILE);
        if (index.isEmpty()) return null;
        Set<String> speakers = new HashSet<>();
        long ms = 0;
        for (JSONObject u : index) {
            speakers.add(u.optString("s").toLowerCase(Locale.ROOT));
            ms += u.optLong("d");
        }
        return new JSONObject()
                .put("utterances", index.size())
                .put("speakers", speakers.size())
                .put("seconds", Math.round(ms / 1000.0));
    }

    public static String voiceJson(String matchId) {
        JSONObject record = MatchHistoryManager.recordCopy(matchId);
        if (record == null) return null;
        List<long[]> clock = clockOf(record);
        List<JSONObject> index = readLines(matchId, INDEX_FILE);
        if (clock.isEmpty() || index.isEmpty()) {
            return new JSONObject().put("available", false).toString();
        }

        Map<String, Integer> speakerIndex = new LinkedHashMap<>();
        JSONArray speakers = new JSONArray();
        JSONObject stats = record.optJSONObject("stats");
        JSONArray players = stats != null ? stats.optJSONArray("players") : null;

        JSONArray utterances = new JSONArray();
        index.sort(Comparator.comparingLong(u -> u.optLong("t")));
        for (JSONObject u : index) {
            int s = speakerFor(u.optString("s"), speakerIndex, speakers, players);
            utterances.put(new JSONObject()
                    .put("s", s)
                    .put("g", round(gameTimeAt(clock, u.getLong("t")), 3))
                    .put("d", round(u.getLong("d") / 1000.0, 3))
                    .put("sr", u.optInt("sr", 48000))
                    .put("o", u.getLong("o"))
                    .put("n", u.getInt("n")));
        }

        Map<Integer, List<double[]>> tracks = new LinkedHashMap<>();
        for (JSONObject line : readLines(matchId, POSITIONS_FILE)) {
            int s = speakerFor(line.optString("s"), speakerIndex, speakers, players);
            JSONArray samples = line.optJSONArray("p");
            if (samples == null) continue;
            List<double[]> track = tracks.computeIfAbsent(s, k -> new ArrayList<>());
            for (int i = 0; i < samples.length(); i++) {
                JSONArray p = samples.optJSONArray(i);
                if (p == null || p.length() < 4) continue;
                double x = p.optDouble(1, Double.NaN);
                double y = p.optDouble(2, Double.NaN);
                if (!Double.isFinite(x) || !Double.isFinite(y)) continue;
                track.add(new double[]{gameTimeAt(clock, p.getLong(0)), x, y, p.optInt(3, 0)});
            }
        }
        JSONObject positions = new JSONObject();
        tracks.forEach((s, track) -> {
            track.sort(Comparator.comparingDouble(p -> p[0]));
            JSONArray out = new JSONArray();
            for (double[] p : track) {
                out.put(new JSONArray().put(round(p[0], 2)).put(round(p[1], 2)).put(round(p[2], 2)).put((int) p[3]));
            }
            positions.put(String.valueOf(s), out);
        });

        return new JSONObject()
                .put("available", true)
                .put("mapNumber", record.optInt("mapNumber", 0))
                .put("speakers", speakers)
                .put("utterances", utterances)
                .put("positions", positions)
                .toString();
    }

    private static int speakerFor(String id, Map<String, Integer> speakerIndex, JSONArray speakers, JSONArray players) {
        String key = id.toLowerCase(Locale.ROOT);
        Integer known = speakerIndex.get(key);
        if (known != null) return known;

        JSONObject speaker = new JSONObject().put("id", id).put("champion", "").put("team", "");
        if (players != null) {
            for (int i = 0; i < players.length(); i++) {
                JSONObject p = players.getJSONObject(i);
                if (p.optString("riotId").equalsIgnoreCase(id)) {
                    speaker.put("champion", p.optString("champion")).put("team", p.optString("team"));
                    break;
                }
            }
        }
        speakers.put(speaker);
        speakerIndex.put(key, speakers.length() - 1);
        return speakers.length() - 1;
    }

    private static List<long[]> clockOf(JSONObject record) {
        List<long[]> clock = new ArrayList<>();
        JSONArray saved = record.optJSONArray("clock");
        if (saved == null) return clock;
        for (int i = 0; i < saved.length(); i++) {
            JSONArray pair = saved.optJSONArray(i);
            if (pair == null || pair.length() < 2) continue;
            clock.add(new long[]{pair.getLong(0), Math.round(pair.getDouble(1) * 1000)});
        }
        clock.sort(Comparator.comparingLong(p -> p[0]));
        return clock;
    }

    static double gameTimeAt(List<long[]> clock, long epochMs) {
        long[] first = clock.getFirst();
        if (epochMs <= first[0]) return (first[1] - (first[0] - epochMs)) / 1000.0;

        int lo = 0, hi = clock.size() - 1;
        while (lo < hi) {
            int mid = (lo + hi + 1) >>> 1;
            if (clock.get(mid)[0] <= epochMs) lo = mid;
            else hi = mid - 1;
        }
        long[] before = clock.get(lo);
        long game = before[1] + (epochMs - before[0]);
        if (lo + 1 < clock.size()) game = Math.min(game, clock.get(lo + 1)[1]);
        return game / 1000.0;
    }

    private static List<JSONObject> readLines(String matchId, String file) {
        List<JSONObject> lines = new ArrayList<>();
        if (!MatchHistoryManager.isValidId(matchId)) return lines;
        Path path = MatchHistoryManager.matchDir(matchId).resolve(DIR).resolve(file);
        if (!Files.exists(path)) return lines;
        synchronized (lock) {
            try (Stream<String> stream = Files.lines(path, StandardCharsets.UTF_8)) {
                stream.filter(l -> !l.isBlank()).forEach(l -> {
                    try {
                        lines.add(new JSONObject(l));
                    } catch (Exception ignored) {
                    }
                });
            } catch (IOException e) {
                DebugManager.logFailure("[Voice] Could not read " + path, e);
            }
        }
        return lines;
    }

    private static double round(double value, int places) {
        double scale = Math.pow(10, places);
        return Math.round(value * scale) / scale;
    }
}
