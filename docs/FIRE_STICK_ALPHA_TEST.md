# Fleezy Player — Fire Stick Alpha Test

Use this checklist for the next real-device validation of Fleezy Player 0.1.1.

## 1. Install / launch

- Install the latest debug APK built from `fleezy-alpha-0.1`.
- Confirm Fire TV shows the Fleezy Player app name/banner.
- Launch the app.
- Expected: no startup crash; Fleezy sign-in overlay appears on a fresh install.

If launch fails, capture:

```bat
adb logcat -c
adb shell am start -n stream.fleezy.player.debug/com.ultratv.tv.nativeapp.MainActivity
adb logcat -b crash -d
```

## 2. Login validation

### Invalid credentials
- Enter an intentionally invalid username/password once.
- Expected: sign-in fails visibly and stays on the login screen.
- Expected: no empty/ghost account is left behind.

### Valid credentials
- Enter the real Fleezy test username/password.
- Expected: account validates.
- Expected: Live TV loads first.
- Expected: login overlay disappears after Live TV is ready rather than waiting for the full Movies/Series catalog.

Record roughly how long it takes from pressing **Sign In** until the main app becomes usable.

## 3. Live TV

- Open Live TV.
- Confirm categories load.
- Confirm channel count is non-zero.
- Move through several categories.
- Move focus through several channels quickly with the Fire remote.
- Expected: UI remains responsive.

## 4. EPG / Now & Next

- Pause focus on several channels for at least one second each.
- Expected: Fleezy requests short EPG on demand.
- Expected: Now/Next data begins appearing where the provider supplies EPG data.
- Open TV Guide.
- Manual full XMLTV refresh can still be tested separately.

## 5. Internal playback

Test several Live channels, including at least one known stream that plays in VLC.

Expected:
- playback starts
- no credential-bearing stream URL is shown on screen
- playback error is visible if a stream fails
- **Retry** can be selected after a failure

Test a few different channels/codecs if available.

## 6. Channel zapping

While a Live channel is playing:

- Press D-pad Up / Down.
- Expected: channel changes without returning to Live TV.
- Open the live drawer with OK/centre.
- Pick another channel.
- Expected: title and playback context update to the new channel.

## 7. External player

- Zap away from the originally opened channel.
- Choose **Open externally**.
- Expected: the external player receives the currently playing/zapped channel, not the original channel.

## 8. Picture-in-Picture

- Start Live playback.
- Press Home.
- Expected on supported Fire OS versions: playback enters PiP rather than stopping unexpectedly.

## 9. Movies / Series background load

After Live TV is usable:

- Wait for the background library sync.
- Open Movies.
- Open Series.
- Expected: those catalogs populate without requiring a new login.

If they are still empty, use Settings → Re-sync once and note whether they populate.

## 10. Search

- Search for a known Movie.
- Search for a known Series.
- Search for a known Live channel.
- Expected filters shown: All / Movies / Series / Channels.
- Open a Live result.
- Expected: playback/PiP context behaves the same as opening from Live TV.

## 11. Guide playback

- Open TV Guide.
- Start a Live channel from the Guide.
- Expected: playback context and channel zapping work normally.

## 12. Saved login / relaunch

- Exit Fleezy Player completely.
- Reopen it.
- Expected: login is retained.
- Expected: no username/password prompt on normal relaunch.
- Expected: existing catalogs remain available while any refresh occurs.

## 13. Settings / privacy

- Confirm the server URL is not shown in Settings/login.
- Confirm there is no plaintext Backup/Restore control.
- Confirm About Fleezy Player shows version and Ultra TV / khalilbenaz MIT attribution.

## 14. Failure capture

For any app crash:

```bat
adb logcat -c
adb shell am start -n stream.fleezy.player.debug/com.ultratv.tv.nativeapp.MainActivity
adb logcat -b crash -d
```

For playback that fails without crashing, record:
- channel name
- whether VLC plays the same stream
- exact Fleezy error shown
- whether Retry changes the result

Do not post real usernames, passwords, or full Xtream stream URLs into public GitHub issues.
