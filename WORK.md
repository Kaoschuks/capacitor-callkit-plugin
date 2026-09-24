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
- Dropped `getInitialEvents`: events raised before JS loads are queued natively (`CallEventBus`) and retained by Capacitor until a listener is added.

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

### Step 4 — iOS (after Android)
- [ ] Split into `CallKitManager.swift`, `PushKitManager.swift` (existing code, unchanged logic), `AudioSessionManager.swift`
- [ ] Port answer / end / mute / hold / `didActivate` from callkeep `RNCallKeep.m`
- [ ] Map onto the Step 3 API; read new payload keys (keep old `ConnectionId`/`Username` as fallback)
- [ ] Re-test on device

## Log

- 2026-09-24 — Plan agreed; WORK.md created.
- 2026-09-24 — Steps 1, 2, 3, 5, 6, 7 (Android), 8 done. Android verified on API 33 emulator. Next: real-device FCM test, then Step 4 (iOS).
- 2026-09-24 — @capacitor/push-notifications coexistence + `call_ended` fixes, verified on emulator.
