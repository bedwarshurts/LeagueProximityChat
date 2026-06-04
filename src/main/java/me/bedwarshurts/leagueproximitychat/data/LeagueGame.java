package me.bedwarshurts.leagueproximitychat.data;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.Collectors;

public record LeagueGame(List<LeaguePlayer> players) {

    public String createRoomHash() throws NoSuchAlgorithmException {
        String sortedPlayerIds = players.stream()
                .map(LeaguePlayer::getRiotId)
                .filter(riotId -> riotId != null && !riotId.isBlank())
                .map(riotId -> riotId.trim().toLowerCase())
                .sorted()
                .collect(Collectors.joining("_"));

        byte[] bytes = sortedPlayerIds.getBytes(StandardCharsets.UTF_8);

        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] hashedBytes = digest.digest(bytes);

        var result = HexFormat.of().formatHex(hashedBytes);

        System.out.println("Room hash: " + result);
        return result;
    }
}