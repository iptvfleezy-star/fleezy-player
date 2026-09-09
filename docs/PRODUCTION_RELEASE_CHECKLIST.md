# Fleezy Player — Production Release Checklist

Do not publish a customer production APK until every required gate below is complete.

## 1. Real Fire TV validation

- Latest debug candidate passes the full `docs/FIRE_STICK_ALPHA_TEST.md` checklist.
- No startup crash on the target Fire OS device.
- Login, Live, EPG, Movies, Series, Search, zapping, PiP, and retry behavior are validated.
- At least one provider stream known to play in VLC also plays internally in Fleezy.

## 2. Backend transport

Current development builds use the configured Xtream origin directly.

Before production:

- Verify whether the Fleezy service supports HTTPS end-to-end.
- Prefer a Fleezy-owned HTTPS gateway such as `api.fleezy.stream` so the upstream origin can change without rebuilding the app.
- Do not switch an existing working HTTP endpoint to HTTPS without testing it.
- If the origin remains HTTP, understand that Xtream usernames/passwords are transmitted over cleartext on the network.

## 3. Backend hiding / configuration

For production, decide whether the upstream Xtream origin should be hidden from the public source/APK.

Preferred architecture:

`Fleezy Player -> Fleezy-owned HTTPS gateway -> current provider origin`

A string embedded directly in an APK cannot be considered secret even if the UI hides it.

## 4. Production signing

Production package:

`stream.fleezy.player`

Required GitHub repository secrets:

- `FLEEZY_KEYSTORE_BASE64`
- `FLEEZY_KEYSTORE_PASSWORD`
- `FLEEZY_KEY_ALIAS`
- `FLEEZY_KEY_PASSWORD`

Release workflow must fail if any signing secret is missing.

The permanent production keystore must be backed up securely outside GitHub.

Never lose or casually rotate the production signing key after customers install the app.

## 5. Debug APK rule

Debug package:

`stream.fleezy.player.debug`

The alpha workflow uses a stable debug key stored in the public repository for development convenience.

Therefore:

- debug APKs are test-only
- never publish a debug APK as the customer product
- never treat the debug signing identity as trusted production security
- production package/signing remains separate

## 6. Release build validation

Before publishing:

- build the minified/shrunk Release variant
- verify the APK signature with `apksigner`
- install that exact release APK on a real Fire TV device
- repeat the critical launch/login/playback tests against the release build
- confirm R8 did not strip Hilt, Room, serialization, or Media3 behavior

## 7. Privacy

Verify production app still has:

- remote Ultra TV telemetry disabled
- no credential-bearing stream URL displayed on screen
- no HTTP request logger dumping credentials
- Android automatic/cloud app backup disabled
- no plaintext customer backup/export control
- server URL hidden from normal customer UI

## 8. Branding / attribution

Verify:

- launcher label is Fleezy Player
- Fire TV banner/icon are Fleezy assets
- customer UI does not visibly say Ultra TV except required attribution
- Settings → About visibly retains Ultra TV / khalilbenaz MIT attribution
- MIT `LICENSE` remains in the repository/distribution obligations

## 9. Versioning

- `VERSION` is the intended release version
- Git tag exactly matches `v$(cat VERSION)`
- versionCode remains monotonic
- release filename is stable: `FleezyPlayer.apk`
- publish SHA-256 alongside the APK

## 10. Update channel

Before enabling customer self-update:

- update metadata must be hosted under Fleezy control
- APK URL must point to a production-signed release
- updater must verify expected version and ideally SHA-256
- never restore the old Ultra TV GitHub updater/Cloudflare telemetry path

## 11. Distribution

Initial customer distribution may use sideloading/Downloader.

Before sharing:

- use only the production-signed `stream.fleezy.player` APK
- host it at a stable Fleezy-controlled HTTPS URL
- verify installation from a clean Fire Stick
- verify an upgrade install over the previous production version

## 12. Release decision

A GitHub `v*` tag should only be created after:

- real Fire TV alpha test passes
- production signing secrets are confirmed
- backend transport decision is confirmed
- release APK is tested
- final SHA-256 is recorded
