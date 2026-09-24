# Device test plan

Everything below needs real devices: CallKit and PushKit don't work in the iOS simulator, and Android
emulators don't reproduce vendor battery/lock-screen behaviour. The flows have already been verified on an
Android 13 emulator (see [WORK.md](WORK.md)); this plan confirms them on hardware.

Run each scenario with the **example app** (`example/`). Its Home page shows the push token, a ready-to-copy
**test push command**, permission state, test buttons and a live **event log**; the call screen shows the
call state and in-call controls.

## 1. One-time setup

### Android

1. Firebase console → add an Android app with package `com.example.plugin` → download `google-services.json`
   into `example/android/app/`.
2. Firebase → Project settings → Service accounts → **Generate new private key** → save as
   `service-account.json` in the plugin repo root (don't commit it).
3. Build and install:
   ```bash
   cd example && npm install && npm run build && npx cap sync android
   cd android && ./gradlew installDebug
   ```
4. Open the app → **Request permissions** (microphone + notifications). On Android 14+, if
   *Full-screen intent* shows "not allowed", tap it and allow it.
5. Watch native logs while testing: `adb logcat -s IonicCallkit Capacitor/Console`

### iOS (Mac, Xcode 16)

1. Change the bundle id from `com.example.plugin` to one of yours in Xcode (App target → Signing &
   Capabilities), pick your team, and keep **Push Notifications** and **Background Modes → Voice over IP**.
2. Create a **VoIP Services certificate** for that bundle id and export `app.pem`
   (see README → iOS setup). Put `app.pem` in the plugin repo root.
3. Build:
   ```bash
   cd example && npm install && npm run build && npx cap sync ios
   cd ios/App && pod install && open App.xcworkspace
   ```
   Run on the device from Xcode. If it doesn't compile, send the Xcode errors — the iOS code has only been
   syntax-checked so far.
4. Unit tests: `xcodebuild test -scheme IonicCallkit -destination 'platform=iOS Simulator,name=iPhone 16'`
   (run from the plugin repo root; these don't need a device).
5. Watch logs: Xcode console, or Console.app filtered on your app.

### Sending test pushes

Copy the **Test push command** from the app's Home page (it contains the token) and run it from the plugin
repo root. Replace `com.example.plugin` with your bundle id on iOS.

```bash
# Android
dev/sendFcm.sh service-account.json <token> <callId> Alice          # ring
dev/sendFcm.sh service-account.json <token> <callId> --end          # caller hung up
# iOS
dev/sendVoip.sh <callId> <token> <bundleId> false Alice              # ring
```

Use a new `callId` for every call (`uuidgen`). A push for a call id that ended in the last 5 minutes is
ignored on purpose.

## 2. Scenarios

Mark each ✅/❌ per device. "Log" means the example app's event log (Home page) or `getInitialEvents()`.

| # | Scenario | Steps | Expected |
|---|---|---|---|
| 1 | Token | Open app | Home shows the token; log has `setup ok` |
| 2 | Ring, app open | Send ring push | Native incoming UI + ringtone; app navigates to the call screen; log: `incomingCall` |
| 3 | Ring, app in background | Home button, send ring push | Heads-up call notification (Android) / CallKit banner (iOS) with Answer/Decline |
| 4 | **Ring, app killed + phone locked** | Swipe the app away, lock the phone, wait 30 s, send ring push | Full-screen incoming call over the lock screen, ringtone loops |
| 5 | Answer from lock screen | During 4, answer | **Android:** app opens over the lock screen on the call screen. **iOS:** CallKit in-call screen (app stays in background — expected). Log / `initial events`: `incomingCall … callAnswered` |
| 6 | Decline from lock screen | Repeat 4, decline | Ringing stops; `callRejected` |
| 7 | Hang up from the system | Answer, then hang up from the ongoing-call notification (Android) / CallKit screen (iOS) | `callEnded`; Android ongoing notification disappears |
| 8 | Hang up from the app | Answer, tap the red button in the app | `callEnded`; system call UI closes |
| 9 | Caller cancels while ringing | Send ring, then the `--end` push (Android) | Ringing stops; `callEnded` with `reason: 6` (missed); appears as missed call |
| 10 | Mute / speaker / hold | During a call use the app buttons, then the system UI buttons | `muted`, `speakerChanged`/`audioRouteChanged`, `held`; both UIs stay in sync |
| 11 | Bluetooth / wired headset | Connect a headset during a call; press its answer/hang-up button on a new call | Audio moves to the headset (`audioRouteChanged`); button answers/ends the call |
| 12 | Outgoing | **Outgoing call** button | System shows the outgoing call (`callStarted`, state `dialing`), becomes `active` after 3 s, hang up works |
| 13 | One call at a time | Enable the toggle, start a call, send a ring push for another id | Second call does not ring; log: `refused: busy` (`incomingCallFailed`). iOS may show it as a missed call |
| 14 | Duplicate push | Send the same ring push twice | Rings once |
| 15 | Screen off 5 min | Answer, lock the phone, wait 5 min, hang up from the lock screen | Call stays up the whole time (Android: ongoing notification visible; process not killed) |
| 16 | App restarted mid-ring | Android: send ring, immediately `adb shell am force-stop com.example.plugin`, reopen | No ghost call screen; log: `initial events: … (restored)` |
| 17 | Normal pushes still work | Android: send an FCM message without `callId` (e.g. from Firebase console) | Log: `push-notifications: …` (forwarded to `@capacitor/push-notifications`) |
| 18 | Token refresh | Reinstall the app | New token shown; old token rejected by FCM/APNs |

### Android only

| # | Scenario | Expected |
|---|---|---|
| A1 | Notifications permission denied (Android 13+) | No ring UI — confirms why the app must request it |
| A2 | Full-screen intent revoked (Android 14+ settings) | Heads-up notification instead of full-screen; `canUseFullScreenIntent()` → false |
| A3 | Answer watchdog | Set `android.answerReachabilityTimeout: 1` in `setup`, kill app, ring, answer quickly from the notification → call ends within ~1 s (restore the default afterwards) |

### Vendors (Android)

Run scenarios **4, 5, 6, 9, 15** on each. Note the vendor setting you had to change, if any.

| Vendor | Settings to check if calls don't ring when killed |
|---|---|
| Pixel | — (reference device) |
| Samsung (One UI) | Battery → Background usage limits: app not "Sleeping"; outgoing calls from the system dialer (`setInitialized` quirk) |
| Xiaomi (MIUI / HyperOS) | Autostart ON, Battery saver "No restrictions", Other permissions → "Show on lock screen" |
| Oppo / Realme (ColorOS) | Auto-launch ON, "Allow background activity" |
| Vivo (Funtouch / OriginOS) | "High background power consumption" allowed, lock-screen display permission |
| OnePlus (OxygenOS) | Battery optimization → Don't optimize |

Android versions to cover at least once: 8.0 (API 26), 10, 13, 14, 15+.

## 3. What to send back if something fails

- Scenario number, device model, OS version, and whether the app was open / background / killed / locked.
- Android: `adb logcat -d -s IonicCallkit Capacitor/Console AndroidRuntime` right after the failure.
- iOS: Xcode console output (filter `CallKit`, `PushKit`, your app) and any Xcode build errors.
- The example app's event log (screenshot of the Home page).
