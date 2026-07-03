# League of Legends, Proximity Chat

![version](https://img.shields.io/badge/version-2.9.0-blue)

Hear in game allies, and enemies with volume scaling on how closer they are to you with Spatial Audio Support.

## How it works
Takes a picture of your game and assumes the minimap is placed in the bottom right corner (TODO: Support for minimap on the left). After identifying where the minimap is using a combination of league's minimap UI setting and the resolution the game is running the app starts checking the player's position in 2 ways. First one is using the player's healthbar. Depending on if the colorblind mode is turned on the player's self healthbar is green or yellow, if the healthbar is at the player's screen then depending on where it is found on the screen it uses the camera white bounding box on the minimap to calculate the player's position. If it is not found it searches for the player's champ icon on the minimap. The champ icon is aqcuired by using the local client api and then calling riot's ddragon api for the icon. The first time the app will search for many resolutions to find the icon at least once. Once it is found that resolution will be "locked" and only that one will be used for subsequent searches. There is also a calibration system built in that aims to reduce the offset in the position between the 2 methods. All the audio is handled by livekit which also has a free cloud tier with free krisp noise cancellation.
