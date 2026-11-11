![Dream Displays](https://i.imgur.com/oJPS9DB.png)

# Dream Displays for Minecraft 1.21.8

This is a fork of [Dream Displays](https://github.com/arnodoelinger/dreamdisplays) for Fabric 1.21.8 that adds features
for watching videos together on a server.

## What this fork adds

### Watching together
- Every viewer sees the same frame, including players who join late or come back into range.
- Pressing play, pausing, seeking or changing the video takes effect for everyone at the same moment.
- Each display has a queue of videos that play one after another. The next video loads in the background, so it starts
  as soon as the current one ends.
- Playback speed goes from 0.1× to 10×.
- A clear button removes the video from a display.

### How syncing works

The server decides what every display is showing: which video, how far into it, whether it is paused, and how fast it
plays. Each player's game copies that.

- Your game knows how long messages take to reach you from the server, and how long your computer takes to start a
  video. It starts the video that much early, so it lines up with everyone else.
- When someone presses play, seeks or picks a new video, the server waits until the players nearby have it loaded (a
  few seconds at most) and then starts it for everyone at once.
- While a video plays, your game keeps checking that it is still in the right place. If it is a little off, it plays
  about 1% faster or slower until it catches up, which you can't hear. If it is far off, it jumps to the right spot.
- Your game learns how long a jump takes on your computer and aims a little ahead, so it lands where it should.
- If you join late or walk back into range, the video starts at the same point everyone else is watching.

### Karaoke

Karaoke needs [Simple Voice Chat](https://modrinth.com/plugin/simple-voice-chat).

A singer's voice has to travel from their microphone through the server to each listener, so it arrives after the
music it was sung to. Karaoke mode lines the two back up:

- The singer's game plays the video slightly ahead of everyone else, so they are singing along to a part the audience
  has not heard yet.
- The server holds the singer's voice back separately for each listener, so it arrives exactly when that listener's
  video reaches the same moment.
- The delays are measured automatically from each player's connection, audio device and microphone, so singers have
  nothing to set up.

Players can sign up in a line to take turns, and any number of people can sing together. Singers in a group hear each
other quietly, because each of them hears the song early by a different amount and the others would sound out of time
at full volume.

### Screen sharing
- On Windows you can share a screen or a window, with audio.
- On Linux you can share a screen, with audio.
- On Mac you can share a screen. Mac shares have no sound.
- When you stop sharing, the stream is cut off for every viewer right away.

### Watching on your own screen
- You can pop a display out into picture-in-picture, a separate window, or fullscreen, and keep watching while you play.
- Server staff can push a video fullscreen to a group of players for events. Players' games can load it ahead of time,
  so it starts the moment staff run the command.

### Sound and picture
- Display audio comes from the display's position in the world, gets quieter with distance, and follows your volume
  settings.
- Videos with transparency (WebM) play with the background showing through.
- A paused video keeps showing its frame, including in picture-in-picture and fullscreen.

### Video loading
- YouTube videos that fail to load are retried with other methods.
- When a video can't play, the display shows the reason.
- Players' errors are sent to the server, so server owners can see what went wrong.

## Install

Download both jars from the [latest release](https://github.com/Carter4242/dreamdisplays-1.21.8-fabric/releases/latest).

- Players put the `dreamdisplays-fabric` jar in their `mods` folder. It needs Fabric Loader 0.16.14 or newer and Fabric API.
- Servers put the `dreamdisplays-paper` jar in the `plugins` folder of a Paper 1.21.8 server.

## Server setup

- Run `/display help` to see every command.
- All commands are op-only by default. Permissions start with `dreamdisplays.` and work with LuckPerms.
- You can change settings in `plugins/DreamDisplays/config.toml` or with `/display config`.
- Screen sharing needs a [MediaMTX](https://github.com/bluenviron/mediamtx) relay, which you set up under `[live]` in the config.
- Errors from players' clients are saved to `plugins/DreamDisplays/client-errors.log`, and `/display diag errors` shows them.

## Credits

The original [Dream Displays](https://github.com/arnodoelinger/dreamdisplays) is by [arnodoelinger](https://github.com/arnodoelinger) and contributors. This fork uses the same [LGPL-3.0](LICENSE) license.
