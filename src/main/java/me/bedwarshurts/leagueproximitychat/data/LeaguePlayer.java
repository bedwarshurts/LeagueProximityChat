package me.bedwarshurts.leagueproximitychat.data;

import lombok.Getter;
import me.bedwarshurts.leagueproximitychat.utils.RitoApiUtils;

import java.util.List;

@Getter
public class LeaguePlayer {

    private final String championName;
    private final boolean isBot;
    private final boolean isDead;
    private final int level;
    private final String position;
    private final String rawChampionName;
    private final String rawSkinName;
    private final double respawnTimer;
    private final String riotId;
    private final String riotIdGameName;
    private final String riotIdTagLine;
    private final int skinID;
    private final String skinName;
    private final String summonerName;
    private final String team;

    private final List<Item> items;
    private final Runes runes;
    private final Score score;
    private final SummonerSpells summonerSpells;

    public LeaguePlayer(boolean isBot, boolean isDead, int level, String position,
                        String rawChampionName, String rawSkinName, double respawnTimer,
                        String riotId, String riotIdGameName, String riotIdTagLine,
                        int skinID, String skinName, String summonerName, String team,
                        List<Item> items, Runes runes, Score score, SummonerSpells summonerSpells) {

        this.isBot = isBot;
        this.isDead = isDead;
        this.level = level;
        this.position = position;
        this.rawChampionName = rawChampionName;
        this.rawSkinName = rawSkinName;
        this.respawnTimer = respawnTimer;
        this.riotId = riotId;
        this.riotIdGameName = riotIdGameName;
        this.riotIdTagLine = riotIdTagLine;
        this.skinID = skinID;
        this.skinName = skinName;
        this.summonerName = summonerName;
        this.team = team;
        this.items = items;
        this.runes = runes;
        this.score = score;
        this.summonerSpells = summonerSpells;
        this.championName = RitoApiUtils.sanitizeChampionName(rawChampionName);
    }

    public int getEffectiveSkinId() {
        if (skinID > 0) {
            return skinID;
        }
        String source = (rawSkinName != null && !rawSkinName.isEmpty()) ? rawSkinName : skinName;
        if (source != null) {
            // The skin number is the run of digits at the end of the name.
            String name = source.trim();
            int digitsStart = name.length();
            while (digitsStart > 0) {
                char c = name.charAt(digitsStart - 1);
                if (c < '0' || c > '9') break;
                digitsStart--;
            }
            if (digitsStart < name.length()) {
                try {
                    return Integer.parseInt(name.substring(digitsStart));
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return 0;
    }

    public record Item(boolean canUse, boolean consumable, int count, String displayName, int itemID, int price,
                       String rawDescription, String rawDisplayName, int slot) {
    }

    public record Runes(RuneDetail keystone, RuneDetail primaryRuneTree, RuneDetail secondaryRuneTree) {
    }

    public record RuneDetail(String displayName, int id, String rawDescription, String rawDisplayName) {
    }

    public record Score(int assists, int creepScore, int deaths, int kills, double wardScore) {
    }

    public record SummonerSpells(SpellDetail summonerSpellOne, SpellDetail summonerSpellTwo) {
    }

    public record SpellDetail(String displayName, String rawDescription, String rawDisplayName) {
    }
}