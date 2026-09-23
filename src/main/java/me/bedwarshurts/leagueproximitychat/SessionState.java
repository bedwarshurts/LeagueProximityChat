package me.bedwarshurts.leagueproximitychat;

import lombok.Getter;
import lombok.Setter;
import me.bedwarshurts.leagueproximitychat.livekit.LivekitRoom;
import me.bedwarshurts.leagueproximitychat.utils.LeagueConfigReader;

public final class SessionState {

    public static final String CONFIG_WARNING_ID = "league-settings";

    @Getter @Setter private volatile LivekitRoom activeRoom = null;
    @Getter @Setter private volatile boolean connectedToLiveKit = false;
    @Getter @Setter private volatile LeagueConfigReader.Warning configWarning = null;

    public void acknowledgeWarning(String id) {
        if (CONFIG_WARNING_ID.equals(id)) configWarning = null;
    }
}
