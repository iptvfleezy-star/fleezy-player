# Fleezy Player 0.1.1 — Known Alpha Limitations

These are known development limitations, not confirmed regressions.

## Direct HTTP provider transport

The current development configuration points directly at the configured Xtream origin and allows cleartext HTTP because many IPTV origins are HTTP-only.

Until HTTPS is verified from the real network or a Fleezy-owned HTTPS gateway is introduced, usernames/passwords may travel over cleartext between the device and provider origin.

Do not silently change the provider URL to HTTPS without confirming that the endpoint supports it.

## Background Movies / Series population

First login is intentionally Live-first.

After Live TV is ready, Movies and Series continue syncing in the app ViewModel scope. If the app is force-closed immediately after login, that background library sync can be cancelled.

A later manual/full re-sync can recover. Consider persistent WorkManager-based library completion only if real-device testing shows it is needed.

## EPG strategy

The app does not force a full XMLTV download during first login.

Live TV requests short EPG for the focused Xtream channel after a short debounce. This is intended to populate Now/Next without making initial sign-in wait for a massive guide feed.

Full XMLTV refresh remains available from the Guide.

## Debug signing

The alpha package is:

`stream.fleezy.player.debug`

The stable debug key is present in the public repository for repeatable test installs. It is not a production security identity.

Never distribute a debug APK as the customer product.

Production package:

`stream.fleezy.player`

must use the permanent private Fleezy release key.

## Internal legacy names

Some internal implementation names remain from Ultra TV, including package/database/theme identifiers.

Examples can include:

- `com.ultratv.tv.nativeapp`
- `UltraTvApp`
- `UltraDb`
- `Theme.UltraTv`

These names are not customer-facing and are not a release blocker by themselves.

## M3U / Stalker backend code

Legacy M3U and Stalker support still exists in backend repository classes.

Fleezy customer UI exposes only the Xtream username/password flow. M3U/Stalker setup is not reachable through the customer provider dialogs.

Removing those backend implementations can be considered later after the Xtream-only product is stable.

## Public source / backend origin

Hiding a server URL in the UI does not make a string embedded in an APK or public source repository secret.

If origin concealment is a production requirement, use a Fleezy-owned API/gateway rather than relying on app obfuscation.

## Release status

Alpha 0.1.1 is not a production release.

A real Fire TV test and the production release checklist must pass before creating a public `v*` release tag.
