# ionic-callkit — Work Plan

Native call UI for Ionic/Capacitor apps: CallKit + PushKit on iOS, ConnectionService + FCM on Android.
The Android side is a port of [livekit/react-native-callkeep](https://github.com/livekit/react-native-callkeep)
adapted for Capacitor. The iOS side started from the tested Kaoschuks/kin9aziz PushKit plugin.

Status legend: `[ ]` todo · `[~]` in progress · `[x] ~~done~~`

## Decisions

| Topic | Decision |
|---|---|
| Base repo | Clone of `Kaoschuks/capacitor-callkit-plugin` (from `kin9aziz/capacitor-plugin-callkit-voip`). iOS PushKit flow already tested. No fork. |
| Package name | npm `ionic-callkit`; pod / SPM product `IonicCallkit`; JS export `CallKit`; Java package stays `io.kreador.callkit` |
| Capacitor | 7.x (latest 7.6.x). Peer dep `^7.0.0`. |
| Android min / target | minSdk 26 (self-managed ConnectionService), compile/target 35 (36 optional in app) |
| iOS min | 14.0 |
| Public API | Names from the original plan (`displayIncomingCall`, `answerCall`, `callAnswered`, …); callkeep internals mapped underneath |
| Android mode | Self-managed ConnectionService by default; managed mode still supported via `setup({ android: { selfManaged: false } })` |
| Android push | Native FCM `FirebaseMessagingService` in the plugin (rings when app is killed). Data-only, high priority messages. |
| callkeep source | livekit/react-native-callkeep @ `90bc581be1c6b05351193f27fec7e7a983775d54` (ISC licence notice kept in ported files) |
| Example app | Ionic + Angular in `example/` |

## Order of execution

2 → 3 → 5 → 6 → 7 (Android) → 8 → 4 → 7 (iOS)

### ~~Step 1 — Fork & rename~~ ✅
- [x] ~~Fork — not needed (existing clone)~~
- [x] ~~Rename to `ionic-callkit` (package.json, `IonicCallkit.podspec`, Package.swift, rollup, example)~~

### ~~Step 2 — Capacitor 7~~ ✅
- [x] ~~Plugin `package.json`: `@capacitor/*` ^7 (7.6.9), TypeScript 5.6, rollup 4 (`rollup.config.mjs`), docgen 0.3, peer `^7.0.0`~~
- [x] ~~`android/build.gradle`: AGP 8.7.2, Java 21, compileSdk 35, minSdk 26~~
- [x] ~~Gradle wrapper 8.11.1~~
- [x] ~~`Package.swift` → `capacitor-swift-pm` from 7.0.0, iOS 14; podspec `Capacitor ~> 7.0`, iOS 14~~
- [x] ~~`npm run build` passes~~

### ~~Step 3 — Unified API (`src/definitions.ts`)~~ ✅
- [x] ~~Methods: `setup`, `registerVoipToken`, `unregisterVoipToken`, `displayIncomingCall`, `answerCall`, `rejectCall`, `startCall`, `endCall`, `setMuted`, `setSpeaker`, `setOnHold`~~
- [x] ~~Extras: `setCallActive`, `endAllCalls`, `reportEndCall`, `updateDisplay`, `sendDTMF`, `getAudioRoutes`, `setAudioRoute`, `setAvailable`, `setReachable`, `hasPhoneAccount`, `openPhoneAccountSettings`, `canUseFullScreenIntent`, `openFullScreenIntentSettings`, `backToForeground`, `getActiveCalls`, `checkPermissions`, `requestPermissions`~~
- [x] ~~Events: `voipToken`, `incomingCall`, `callAnswered`, `callStarted`, `callEnded`, `callRejected`, `muted`, `held`, `dtmf`, `speakerChanged`, `audioRouteChanged`, `audioSessionActivated`, `showIncomingCallUi`, `silenceIncomingCall`, `incomingCallFailed`, `checkReachability`, `hasActiveCall`~~
- `getInitialEvents` / `clearInitialEvents` added back in Step 7c (initially dropped).

### ~~Step 5 — Android (callkeep port)~~ ✅
| File (`io.kreador.callkit`) | Ported from callkeep |
|---|---|
| `CallKeepModule.java` | `RNCallKeepModule.java` — React Native removed; Context-only so FCM / notification actions work with no WebView |
| `CallKitPlugin.java` | `@ReactMethod` surface of `RNCallKeepModule` → `@PluginMethod` bridge |
| `CallConnectionService.java` | `VoiceConnectionService.java` |
| `CallConnection.java` | `VoiceConnection.java` |
| `CallConference.java` | `VoiceConference.java` |
| `CallForegroundService.java` | foreground-service code from `VoiceConnectionService` (now `phoneCall\|microphone`, CallStyle ongoing + Hang up) |
| `IncomingCallNotification.java` | new — full-screen intent + CallStyle incoming notification, insistent ringtone |
| `CallActionReceiver.java` | new — Decline / Hang up notification buttons (Answer is a launch intent) |
| `CallMessagingService.java` | replaces `RNCallKeepBackgroundMessagingService` (HeadlessJS) with FCM |
| `CallPush.java` | new — FCM payload parser (accepts legacy `ConnectionId` / `Username`) |
| `CallEventBus.java` | replaces RN event emitter + `delayedEvents`; queues until the plugin attaches |
| `Constants.java` | copied (+ reject / notification actions) |

Deliberate deviations from callkeep (all commented in code):
- Default `selfManaged: true`; PhoneAccountHandle id = package name (stable across label changes).
- Answering a call calls `setActive()`; unknown `reportEndCall` reasons disconnect as REMOTE.
- Handlers use the main looper; legacy `PhoneStateListener` no longer runs `Looper.loop()` on the caller thread.
- `backToForeground` no longer ORs window flags into Intent flags; lock-screen display handled by the plugin.
- Activity destroy (finishing) ends calls but does not kill the process.
- Silent "ignored" paths now reject the JS promise with a reason.

- [x] ~~Copy + adapt the files above~~
- [x] ~~Manifest: permissions + services + receiver (managed-mode phone permissions left to the app)~~
- [x] ~~Gradle deps: `localbroadcastmanager`, `firebase-messaging`, `androidx.core`~~
- [x] ~~JVM unit tests: `CallEventBusTest`, `CallPushTest` (12 passing)~~
- [x] ~~`./gradlew build test` passes, lint 0 errors~~

### ~~Step 6 — `src/web.ts`~~ ✅
- [x] ~~Emit the same events so the app can drive an HTML call screen~~
- [x] ~~Unit tests: `test/web.test.mjs` (9 passing, `npm test`)~~

### Step 7 — Example app (Android first)
- [x] ~~Rebuild `example/` as Ionic 9 + Angular 21 (Angular 22 needs Node ≥ 22.22), Capacitor 7; `example/ios` kept from the tested project~~
- [x] ~~`example/android` regenerated with Capacitor 7 templates, minSdk 26; `assembleDebug` passes~~
- [x] ~~`dev/sendFcm.sh` (FCM HTTP v1, service-account JWT; signing verified against Google OAuth); `voip.sh` → `dev/sendVoip.sh` (sends new + legacy keys)~~
- [x] ~~`example/ios`: pod renamed to `IonicCallkit`, iOS 14 target, AppDelegate import updated (not built — needs macOS)~~
- [x] ~~Emulator (Pixel 6, API 33), driven through the plugin JS API:~~
  - incoming → Telecom RINGING (self-managed), CallStyle notification + full-screen intent, `incomingCall` / `showIncomingCallUi` with payload, app routes to `/call/:id`
  - answer via notification launch intent and via shade Answer button (app in background) → ACTIVE, ringing notification cleared, `CallForegroundService` foreground with ongoing CallStyle notification
  - mute / speaker / hold / unhold / DTMF events; `endCall` → `callEnded`, FGS stopped, notifications cleared
  - reject from JS and from shade Decline button → `callRejected`
  - outgoing → DIALING → `setCallActive` → ACTIVE → end
  - screen off → incoming call wakes the device and shows the app over the lock screen
  - errors reject with a reason (e.g. unknown callId)
  - Found & fixed: callkeep's `getPhoneAccount()` in `createConnection` crashes self-managed apps on API 33 (needs READ_PHONE_NUMBERS)
- [ ] **Needs a real device + `google-services.json`:** FCM from Google's servers end-to-end (`dev/sendFcm.sh`; receive path already verified on emulator), Bluetooth / wired headset routing
- [ ] Device matrix: API 26 / 29 / 34 / 35+ (only API 33 emulator tested)

### ~~Step 8 — Backend contract~~ ✅
- [x] ~~Document payloads in README (plus Android/iOS setup, push-notifications conflict, managed mode)~~
  - APNs: `apns-push-type: voip`, topic `<bundle>.voip`
  - FCM: data-only, `priority: high`
  - Shared keys: `type: "incoming_call"`, `callId`, `callerName`, `handle`, `hasVideo`

### ~~Step 7b — `@capacitor/push-notifications` coexistence~~ ✅
- [x] ~~`CallMessagingService` intent-filter priority 100 → FCM resolves it first (verified: `cmd package query-services`)~~
- [x] ~~Non-call messages + `onNewToken` forwarded by reflection to `PushNotificationsPlugin` (optional dependency; R8 keep rule in `consumer-rules.pro`)~~
- [x] ~~`call_ended` push → Telecom `MISSED` if unanswered (callkeep leaves ringing connections in STATE_NEW, so check `answered`), and JS gets `callEnded { reason }`~~
- [x] ~~Emulator, real FCM receive path (`c2dm RECEIVE` broadcast as root): normal push → `pushNotificationReceived`; call push with app killed + screen off → rings, wakes screen, app on `/call/:id`, answer works; `call_ended` → MISSED + `callEnded`~~
- [x] ~~Example app uses both plugins; README documents coexistence and the web UI~~

### ~~Step 7c — Android hardening~~ ✅
- [x] ~~`getInitialEvents()` / `clearInitialEvents()`: every event that happened before JS listened for it (queued before the plugin loaded, or delivered before a listener existed), oldest first~~
- [x] ~~Persistent ordered event queue (`CallEventBus` + SharedPreferences): survives process death; restored events are returned with `restored: true` and not replayed (their calls are gone); 5-min max age; bounded to 50~~
- [x] ~~`setCallState(callId, 'dialing' | 'ringing' | 'active' | 'held' | 'initializing')` + `callStateChanged` event + `state` in `getActiveCalls()`~~
- [x] ~~Fixed callkeep ordering (`setRinging()`/`setDialing()` then `setInitialized()`) that left calls reporting `new`~~
- [x] ~~Answer reachability watchdog: `android.answerReachabilityTimeout` (default 10000 ms, 0 disables); a `callAnswered` listener or `setReachable()` marks JS ready; WebView destroy marks it unreachable~~
- [x] ~~`setCanMakeMultipleCalls({ allow })` (persisted): refuses a 2nd incoming call → `incomingCallFailed { error: 'busy' }`; `startCall` rejects~~
- [x] ~~Tests: 18 Android JVM (`CallEventBusTest` 12, `CallPushTest` 6), 12 web~~
- [x] ~~Emulator (API 33, cold starts via `kill -9` + real FCM receive path): state sequences; busy from JS and push; watchdog ends call when JS not ready; cold-start answer survives with `callAnswered` in initial events; process death mid-ring → restored events, no ghost call screen~~
- [ ] OEM testing — background execution and full-screen behaviour differ per vendor. At minimum:
  - [ ] Pixel
  - [ ] Samsung (One UI: "Sleeping apps", `setInitialized` quirk on outgoing calls)
  - [ ] Xiaomi (MIUI/HyperOS: Autostart, "Show on lock screen", battery saver)
  - [ ] Oppo / Realme (ColorOS: auto-launch, background freeze)
  - [ ] Vivo (Funtouch/OriginOS: background high power consumption, lock-screen display)
  - [ ] OnePlus (OxygenOS: battery optimisation, `openPhoneAccounts` vendor screen)
  - For each: killed + locked push rings, full-screen UI shows, answer/decline from lock screen, call survives screen off 5 min, Bluetooth

### ~~Step 7d — Pre-device review~~ ✅
Code review of every device flow (push → ring → answer/decline → media → hang-up, cold start, background, lock screen) on both platforms, plus device-test tooling. Device testing itself: [TESTING.md](TESTING.md).
- [x] ~~Duplicate pushes: one call per call id (Android created a 2nd Telecom call; iOS dropped the live call from tracking)~~
- [x] ~~Late / out-of-order pushes: call ids that ended in the last 5 min don't ring again (`RecentCallIds`, Android + iOS, unit-tested)~~
- [x] ~~Android 8.0: show over lock screen via window flags (no `setShowWhenLocked` before 8.1)~~
- [x] ~~Android 14+: foreground service falls back to `phoneCall` type if `microphone` is refused in the background~~
- [x] ~~iOS: `CallKitVoip.start()` for AppDelegate (PushKit at launch); example AppDelegate no longer steals the notification delegate from Capacitor~~
- [x] ~~Confirmed from Capacitor source: WebView keeps running in background (`KeepRunning` default true); Back at root doesn't finish the activity~~
- [x] ~~Example app: test-push command with token, one-call-at-a-time toggle, busy log, UUID fallback for iOS WebView~~
- [x] ~~Emulator re-run: duplicate push rings once; out-of-order `call_ended` then `incoming_call` doesn't ring; Hang Up from ongoing notification ends call + stops FGS; late push for ended call ignored; decline + push forwarding regression OK~~
- [x] ~~Tests: Android 21 JVM, iOS 17 XCTest (Linux), web 13~~

### ~~Step 7e — Android ringing + lock-screen fixes (reported from a real device)~~ ✅
Reported: no ringtone / vibration; lock screen shows only the notification (no call UI).
- [x] ~~`CallRinger`: plugin plays the device ringtone (looping, ringtone audio usage) + vibration itself, following ringer mode; stops on answer / decline / end / remote end / volume key (`onSilence`). Options `android.ringtoneSound` (res/raw), `android.vibrate`~~
- [x] ~~Notification channel `ionic_callkit_incoming_v2` is silent (old sound channel deleted; channel settings are immutable)~~
- [x] ~~`IncomingCallActivity`: native call screen with manifest `showWhenLocked` / `turnScreenOn` as the full-screen intent target (runtime flags on the app's activity were too late on secure lock screens). Answer → app over the lock screen; Decline → reject. `android.incomingCallScreen: 'app'` keeps the old behaviour. Strings/colors overridable (`ionic_callkit_*`)~~
- [x] ~~Emulator with a PIN lock screen: killed app + screen off → native screen over the secure keyguard (occluded), ringtone playing + ringtone vibration; volume key silences; Answer → app over the lock screen, call active; backgrounded app → Decline stops ring + ends call; vibrate mode → vibration only~~
- [ ] Re-test on the reporting device (TESTING.md 4, 4b, 5, 6) — if the screen still doesn't appear: Android 14+ full-screen intent permission (Home page row), and on Xiaomi/Oppo/Vivo the "Show on lock screen" / pop-up permission

### Step 4 — iOS (after Android)
Ported from callkeep `ios/RNCallKeep/RNCallKeep.m` @ `90bc581`. Files in `ios/Sources/CallKitPlugin/`:

| File | Role |
|---|---|
| `CallKitPlugin.swift` | Capacitor bridge — same methods/events as Android (`CAPBridgedPlugin`, all 32 methods) |
| `CallKitManager.swift` | CXProvider + CXCallController + CXProviderDelegate (callkeep's provider/actions/delegate); callId ↔ UUID mapping |
| `PushKitManager.swift` | PushKit registration + token (original plugin's code, unchanged formatting) → CallKitManager |
| `AudioSessionManager.swift` | callkeep's configureAudioSession, didActivate interruption hack, audio routes, route-change events |
| `CallEventBus.swift` | Same design as Android: ordered persisted queue (UserDefaults), initial events, restored events |
| `CallPush.swift` | Payload parser (new keys + legacy `ConnectionId` / `Username`) |

- [x] ~~Split into `CallKitManager.swift`, `PushKitManager.swift` (existing code, unchanged logic), `AudioSessionManager.swift`~~
- [x] ~~Port answer / end / mute / hold / DTMF / start / `didActivate` / provider reset from callkeep `RNCallKeep.m`~~
- [x] ~~Map onto the Step 3 API; read new payload keys (old `ConnectionId`/`Username` as fallback); drop `plugin_events`~~
- [x] ~~Parity with Android hardening: `getInitialEvents` / persisted queue, `callStateChanged` + `setCallState`, `setCanMakeMultipleCalls` (busy), `call_ended` push → `callEnded { reason }`, `callRejected` for declined ringing calls~~
- [x] ~~iOS 13+ VoIP rule: every push reports a call (non-call / unknown `call_ended` / busy pushes are reported and ended immediately)~~
- [x] ~~XCTests: `CallPushTests` (7), `CallEventBusTests` (7) — pass on Linux (Swift 5.10) against the pure-Foundation files~~
- [x] ~~All Swift files parse; CallKit/PushKit/AVFoundation/Capacitor API calls reviewed against the iOS 14+ SDK (no Xcode here, so not type-checked)~~
- Not ported: answer reachability watchdog (answering on iOS keeps the app in the background, so "JS not ready" is normal); `backToForeground` (not allowed on iOS).
- [ ] **Needs a Mac:** `npx cap sync ios` + `pod install` in `example/ios/App`, build in Xcode 16, run `xcodebuild test -scheme IonicCallkit -destination 'platform=iOS Simulator,name=iPhone 16'`
- [ ] **Needs a device** (CallKit/PushKit don't work in the simulator): `dev/sendVoip.sh` with app killed + locked → rings; answer / decline / hang-up; mute / speaker / hold from system UI and JS; `call_ended` push; token refresh; cold-start `getInitialEvents()`

### Step 9 — Publish to npm
Checked 2026-09-24: name `ionic-callkit` is free on npm; `npm pack --dry-run` → 37 files, 56 kB (dist, android/src/main, ios/Sources, podspec, Package.swift; no example/tests/dev).

Before publishing
- [x] ~~Add a `LICENSE` file: MIT (© Favour Chukwuemeka) + the callkeep ISC notice for the ported Android files; `license: "MIT AND ISC"`~~
- [x] ~~`author`: Favour Chukwuemeka; `repository` / `bugs` / `homepage`: Kaoschuks/capacitor-callkit-plugin~~
- [ ] Pick the version: iOS isn't migrated yet, so publish a prerelease (e.g. `1.0.0-beta.1`) under the `beta` tag, not `latest`
- [x] ~~Fix `ios/Tests/CallKitPluginTests.swift` (still calls the removed `echo`) — replaced by `CallPushTests` / `CallEventBusTests`~~
- [ ] Add `CHANGELOG.md` (optional, recommended)

Verify the package exactly as users will get it
- [ ] `npm run build && npm test` and `cd android && ./gradlew build test`
- [ ] `npm pack` → in `example/`: `npm i ../ionic-callkit-<version>.tgz` (instead of `file:..`) → `npx cap sync` → build Android (and iOS on a Mac)
- [ ] Install the tarball in a fresh `ionic start` app to catch anything the example hides

Publish
- [ ] `npm login` (enable 2FA on the npm account)
- [ ] `npm version 1.0.0-beta.1 --no-git-tag-version` (or edit `version`), commit, `git tag v1.0.0-beta.1`
- [ ] `npm publish --tag beta` (`prepublishOnly` runs the build) → users install with `npm i ionic-callkit@beta`
- [ ] Push commit + tag to GitHub; create a GitHub release

After Step 4 (iOS) is done
- [ ] Publish `1.0.0` to `latest`: `npm publish` (or `npm dist-tag add ionic-callkit@1.0.0 latest`)

## Log

- 2026-09-24 — Plan agreed; WORK.md created.
- 2026-09-24 — Steps 1, 2, 3, 5, 6, 7 (Android), 8 done. Android verified on API 33 emulator. Next: real-device FCM test, then Step 4 (iOS).
- 2026-09-24 — @capacitor/push-notifications coexistence + `call_ended` fixes, verified on emulator.
- 2026-09-24 — Android hardening (initial events, persistent queue, call state, watchdog, busy); LICENSE + package metadata.
- 2026-09-24 — Step 4 iOS implemented (callkeep port); unit-tested on Linux; awaiting Mac build + device test.
- 2026-09-24 — Pre-device review (Step 7d): duplicate/late push handling, API 26 + Android 14 FGS fixes, iOS AppDelegate entry point; TESTING.md written. Remaining: device testing per TESTING.md, then Step 9 publish.


