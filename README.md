# League of Legends Proximity Chat

[![version](https://img.shields.io/github/v/release/bedwarshurts/LeagueProximityChat?color=blue&label=version)](https://github.com/bedwarshurts/LeagueProximityChat/releases/latest)
![platform](https://img.shields.io/badge/platform-Windows-lightgrey)
![java](https://img.shields.io/badge/Java-21-orange)

Positional voice chat for League of Legends. Hear allies **and** enemies with volume scaling based on how close
they are to you on the map, with full spatial (left/right) audio. No mods, no client injection - the app reads
your position by looking at the screen and routes voice through LiveKit.

---

## Features

- **Proximity voice** - teammates and enemies fade in as they approach and go silent at range. Dead players are
  muted until they respawn.
- **Spatial audio** - HRTF panning places each voice where they actually are relative to you.
- **Automatic rooms** - every player in the match derives the same room ID from the lobby roster (SHA-256 of the
  sorted Riot IDs), so everyone lands in the same voice room with no codes to share.
- **Play of the Game** - after the match, the app replays your best moments with the voice chat from that exact
  moment layered on top. Up to 5 highlights per game, saveable to disk.
- **End-game scoreboard** - a themed post-game screen with KDA, CS, wards, gold, items and a champion-damage
  chart, over each champion's splash art.
- **Moderation** - the lobby leader can kick and ban disruptive players from the voice room.
- **Krisp noise cancellation** - free via LiveKit, toggleable in the UI.
- **Discord Rich Presence** - shows your champion, score and match state.
- **Mostly Local** - Except the livekit audio, all the math and image analysis are done on your machine, there is no server for this project.
  
---

## Requirements

- **Windows** (the app uses Win32 APIs for window tracking and hotkeys)
- **Java 21+**
- **League of Legends** running windowed or borderless
- A **LiveKit** project - the free cloud tier is plenty ([cloud.livekit.io](https://cloud.livekit.io))

---

## Setup

### 1. Get LiveKit credentials

Create a free project at [cloud.livekit.io](https://cloud.livekit.io), then open **Settings → Keys** and copy the
project **URL**, **API key** and **API secret**.

> **Everyone who plays together must use the exact same three values.** They identify the shared voice server;
> players on different credentials cannot hear each other.

### 2. Run the app

Double click the EXE or 

```bash
java -jar leagueproximitychat-3.6.1.jar
```

On first launch a setup screen asks for the three LiveKit values. They are saved locally to
`%APPDATA%\LeagueProximityChat\livekit.properties` and never leave your machine.

### 3. Play

1. Launch the app while you're in the **lobby** (it reads the lobby leader for moderation rights).
2. Press **Connect to Audio**. The button switches to "Waiting for match…".
3. Start the game. Once your position is detected, your mic unmutes automatically and voice goes live.

---

## How It Works

If the League game client is focused, the system takes a screenshot of your game every few miliseconds. Then tries to detect your position using 2 methods. If your champion's healthbar is found on the screen then the camera bounding box is used on the minimap to determine your in-game location,
the location where the healthbar was found is accounted for.

If the healthbar is not found then the system looks for the champion's icon on the minimap. At the start of the game, the system will try to find the icon once by comparing copies of many different resolutions (from rito's ddragon api) to the minimap. Once the system feels confident, it will lock onto that resolution
and only use that specific one for subsequent scans. Furthermore, after the system feels even more confident, it will extract the champion's icon straight from the minimap (screenshot) for even more accurate detections. 

New detected locations that are close to the previous ones are "boosted" in the detection system. There are many checks that use these 2 systems together to ensure that champion clones such as Shaco do not alter or cheat the system. There are also many checks that also look for false locks, if the healthbar is found correctly for example but the minimap icon template that got extracted matches some location in the other side of the map then the system will realise something is wrong, let go of the lock and start running the minimap algorithm from the beginning.

There is also a calibration system to help reduce the offset between the 2 methods.

> **Note**: Viego and Kayn Champions are not yet supported, I havent thought of a solution, please do not pick Viego or Kayn (They are literally 200 year design anyway).

---

## How Play of the Game works

While you're in a match, the app watches League's live event feed for your kills, multikills, objectives and aces,
and scores them. The best moments are kept as highlights (up to 5).

- **Video** is captured at 60 fps from the screen in the embedded browser and kept in a short rolling buffer, so
  only the moments that matter are ever retained.
- **Voice** is recorded into a rolling 120-second stereo ring buffer and sliced to match the play - so the clip
  contains exactly the players who were audible to you, at the volumes and positions you heard them.
- **Fallback** - if browser video capture is unavailable, clips are rebuilt from a JPEG frame ring captured by the
  position tracker, so you still get a highlight.
- Multikill clips are anchored to the **first** kill of the streak, so the whole fight is captured.

Saved highlights are written to `%USERPROFILE%\Videos\LeagueProximityChat\` as `.webm` with the voice muxed in.

---

## Controls

| Hotkey | Action |
| --- | --- |
| `Shift + F8` | This hides / shows the APP |
| `Shift + F9` | Mute / unmute your mic |
| `Shift + F10` | Deafen / undeafen |

Per-player volume sliders and local mutes are available in the app.

---

## Building from source

```bash
mvn clean package
```

Produces a fat jar in `target/` (Maven Shade, main class
`me.bedwarshurts.leagueproximitychat.LeagueProximityChat`).

**Disclaimer**: This is a fan-made project and is not affiliated with Riot Games or League of Legends.

---

## Troubleshooting

**"Please launch this app while waiting in the game lobby!"**
The League client wasn't running or wasn't in a lobby when the app started. Moderation (kick/ban) needs the lobby
leader, so start the app from the lobby.

**Position stays at "Waiting for data…"**
Tracking only runs while the League window is focused. Make sure the game is windowed or borderless - exclusive
fullscreen can block screen capture.

**Nobody can hear each other**
Confirm every player entered the *identical* LiveKit URL, key and secret. Mismatched credentials mean separate
servers.

**You're muted at the start of a match**
By design: your mic is hard-muted until your position is known, so you can't be heard from an unknown location. It
lifts automatically on the first position fix.

**Clips are low framerate**
Check that `LPC_DEBUG` is **not** enabled - it writes debug images every frame and throttles the tracker heavily.

### Debug mode

```bash
set LPC_DEBUG=1
```

Writes annotated tracking images to `debug/` and enables verbose logging. **Significantly reduces performance** -
use it for diagnosing tracking problems only. This will require launching the app as ADMIN.

---

## Privacy

Everything runs locally. LiveKit credentials are stored on your machine only, voice is peer-routed through your own
LiveKit project, and highlight clips are written to your local Videos folder - nothing is uploaded anywhere by the
app.

---

Have fun!
