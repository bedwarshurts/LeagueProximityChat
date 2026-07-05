package me.bedwarshurts.leagueproximitychat.livekit;

import io.livekit.server.AccessToken;
import io.livekit.server.CanPublish;
import io.livekit.server.CanSubscribe;
import io.livekit.server.RoomJoin;
import io.livekit.server.RoomName;
import io.livekit.server.RoomServiceClient;
import lombok.Getter;
import me.bedwarshurts.leagueproximitychat.managers.ConfigManager;

import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class LivekitRoom {

    @Getter private final String roomID;

    private final Set<LiveKitUser> participants = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private final Set<LiveKitUser> banned  = Collections.newSetFromMap(new ConcurrentHashMap<>());

    private final String roomLeaderRiotId;
    private LiveKitUser roomLeader = null;

    private final RoomServiceClient client;

    public LivekitRoom(String roomID, String roomLeaderRiotId) {
        this.roomID = roomID;
        this.roomLeaderRiotId = roomLeaderRiotId;

        this.client = RoomServiceClient.createClient(
                ConfigManager.getLivekitUrl(),
                ConfigManager.getApiKey(),
                ConfigManager.getApiSecret()
        );
    }

    public void addParticipant(LiveKitUser user) {
        participants.remove(user);
        participants.add(user);

        if (user.identity().equals(roomLeaderRiotId)) {
            roomLeader = user;
        }
        System.out.println("[Room " + roomID + "] " + user.name() + " joined. Total: " + participants.size());
    }

    public void removeParticipant(LiveKitUser user) {
        participants.remove(user);

        if (user.equals(roomLeader)) {
            roomLeader = null;
        }
        System.out.println("[Room " + roomID + "] " + user.identity() + " left. Total: " + participants.size());
    }

    public boolean kickUser(LiveKitUser user, LiveKitUser moderator) {
        if (!moderator.equals(roomLeader)) {
            System.err.println("Only the active room leader can kick users.");
            return false;
        }

        try {
            var response = client.removeParticipant(roomID, user.identity()).execute();

            if (response.isSuccessful()) {
                banned.add(user);
                System.out.println("[LiveKit Server] Forcefully kicked user: " + user.identity());
                return true;
            }
            System.err.println("Failed to kick user. Code: " + response.code());
        } catch (Exception e) {
            System.err.println("Error kicking user " + user.identity() + ": " + e.getMessage());
        }
        return false;
    }

    public boolean revokeBan(LiveKitUser user, LiveKitUser moderator) {
        if (!moderator.equals(roomLeader)) {
            System.err.println("Only the active room leader can revoke bans.");
            return false;
        }
        banned.remove(user);
        System.out.println("[Room " + roomID + "] Ban revoked for: " + user.identity());
        return true;
    }

    public boolean isBanned(LiveKitUser user) {
        return banned.contains(user);
    }

    public Set<LiveKitUser> getParticipants() {
        return Set.copyOf(participants);
    }

    public String generateRoomToken(String name, String identity) {
        AccessToken token = new AccessToken(ConfigManager.getApiKey(), ConfigManager.getApiSecret());

        token.setName(name);
        token.setIdentity(identity);

        token.addGrants(
                new RoomJoin(true),
                new RoomName(roomID),
                new CanPublish(true),
                new CanSubscribe(true)
        );

        return token.toJwt();
    }
}