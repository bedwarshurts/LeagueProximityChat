package me.bedwarshurts.leagueproximitychat.data;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.Collectors;

public record LeagueGame(List<LeaguePlayer> players) {

    public LeaguePlayer findPlayer(String riotId) {
        for (LeaguePlayer p : players) {
            if (p.getRiotId() != null && p.getRiotId().equalsIgnoreCase(riotId)) {
                return p;
            }
        }
        return null;
    }

    public String createRoomHash() {
        String sortedPlayerIds = players.stream()
                .map(LeaguePlayer::getRiotId)
                .filter(riotId -> riotId != null && !riotId.isBlank())
                .map(riotId -> riotId.trim().toLowerCase())
                .sorted()
                .collect(Collectors.joining("_"));

        byte[] bytes = sortedPlayerIds.getBytes(StandardCharsets.UTF_8);

        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by every Java runtime", e);
        }
        byte[] hashedBytes = digest.digest(bytes);

        var result = HexFormat.of().formatHex(hashedBytes);

        System.out.println("Room hash: " + result);
        return result;
    }
}
