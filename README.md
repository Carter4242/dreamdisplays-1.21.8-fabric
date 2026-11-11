![Dream Displays](https://i.imgur.com/oJPS9DB.png)

# Dream Displays for Minecraft 1.21.8

This is a fork of [Dream Displays](https://github.com/arnodoelinger/dreamdisplays) for Fabric 1.21.8 that adds features
for watching videos together on a server.

## Install

Download both jars from the [latest release](https://github.com/Carter4242/dreamdisplays-1.21.8-fabric/releases/latest).

- Players put the `dreamdisplays-fabric` jar in their `mods` folder. It needs Fabric Loader 0.16.14 or newer and Fabric API.
- Servers put the `dreamdisplays-paper` jar in the `plugins` folder of a Paper 1.21.8 server.
- Players and the server should use jars from the same release.

## What this fork adds

### Watching together
- Every viewer sees the same frame, including players who join late or come back into range.
- Play, pause, seek and video changes reach everyone at the same moment.
- Each display has a queue, and pasting a YouTube playlist adds every video in it.
- Playback speed goes from 0.1× to 10× and the pitch does not change.
- A clear button removes the video from a display.

### Screen sharing
- On Windows you can share a screen or a window, with audio.
- On Linux you can share a screen, with audio.
- On Mac you can share a screen. Mac shares have no sound.

### Karaoke

Karaoke needs [Simple Voice Chat](https://modrinth.com/plugin/simple-voice-chat).

In normal voice chat, a singer's voice reaches everyone late. It has to travel from their microphone through the server
to each listener, so it falls behind the music. This fork fixes that from both ends:

- The singer's game plays the video slightly ahead of everyone else, so they are singing along to a part the audience
  has not heard yet.
- The server holds the singer's voice back separately for each listener, so it arrives exactly when that listener's
  video reaches the same moment.
- The delays are measured automatically from each player's connection, audio device and microphone. Singers do not
  have to set anything up.

Players can sign up in a line to take turns, and any number of people can sing together. Singers in a group hear each
other only faintly. Each of them hears the song early, at their own offset, so the other singers would sound out of
time at full volume.

### Playback
- Videos with transparency (WebM) play correctly.
- Display audio gets quieter with distance and follows your volume settings.
- You can watch a display in picture-in-picture, in a separate window or in fullscreen.
- YouTube videos that fail to load are retried with other methods.

## Server setup

- Run `/display help` to see every command.
- All commands are op-only by default. Permissions start with `dreamdisplays.` and work with LuckPerms.
- You can change settings in `plugins/DreamDisplays/config.toml` or with `/display config`.
- Screen sharing needs a [MediaMTX](https://github.com/bluenviron/mediamtx) relay, which you set up under `[live]` in the config.
- Errors from players' clients are saved to `plugins/DreamDisplays/client-errors.log`, and `/display diag errors` shows them.

## Credits

The original [Dream Displays](https://github.com/arnodoelinger/dreamdisplays) is by [arnodoelinger](https://github.com/arnodoelinger) and contributors. This fork uses the same [LGPL-3.0](LICENSE) license.
