# Fleezy Player

Fleezy Player is a native Android TV / Fire TV IPTV client focused on a simple customer experience and remote-friendly playback.

## Current status

**Alpha 0.1.1**

The current development branch is `fleezy-alpha-0.1`. The app is being validated on real Fire TV hardware before a public release is published.

## Customer experience

Fleezy Player is designed so customers only need to:

1. Open Fleezy Player.
2. Enter the username and password provided with their Fleezy account.
3. Sign in.
4. Start watching.

The provider server address is configured internally and is not shown in the customer login UI.

## Features

- Live TV
- TV Guide / EPG
- Movies / VOD
- Series
- Favorites
- Search
- Continue watching
- Live channel zapping
- Picture-in-Picture where supported
- D-pad / Fire TV remote navigation
- Saved login
- No advertising
- Local channel-logo overrides
- Parental controls
- Playback statistics and display controls

## Architecture

The active Android app lives under:

`android-native/`

It uses:

- Kotlin
- Jetpack Compose / Compose for TV
- Media3 / ExoPlayer
- Room
- Hilt
- WorkManager
- OkHttp
- Xtream Codes APIs

Development package:

`stream.fleezy.player.debug`

Production package:

`stream.fleezy.player`

## Privacy and security

Fleezy Player does not upload crash logs, device identifiers, viewing activity, or credentials to the original Ultra TV telemetry service.

Android automatic application backup is disabled so the local credential database is not automatically copied to device cloud backups.

Production releases are configured to fail closed unless the permanent Fleezy release-signing key is available.

## Content

Fleezy Player is an IPTV **client**. It does not include, host, or distribute television channels, movies, series, playlists, or other media.

Use only services, playlists, EPG sources, and credentials you are authorized to access.

## Upstream credit

Fleezy Player is based on the open-source **Ultra TV** project by **khalilbenaz**.

The original project is MIT-licensed. Fleezy retains the upstream attribution in the application About section and preserves the MIT license.

Original project:

`khalilbenaz/ultra-tv`

## License

MIT. See `LICENSE`.
