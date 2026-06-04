package me.bedwarshurts.leagueproximitychat.livekit;

public record LiveKitUser(String identity, String name) {

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof LiveKitUser casted)) return false;

        return casted.identity.equals(this.identity);
    }

    @Override
    public int hashCode() {
        return this.identity.hashCode();
    }
}
