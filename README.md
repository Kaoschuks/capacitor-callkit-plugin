# ionic-callkit

Native incoming / outgoing call UI for Ionic & Capacitor 7 apps.

| Platform | Native layer | Push |
|---|---|---|
| Android | Self-managed `ConnectionService` (ported from [react-native-callkeep](https://github.com/livekit/react-native-callkeep)), full-screen ringing notification, `phoneCall` foreground service | FCM (data-only, high priority) |
| iOS | CallKit (`CXProvider`) | PushKit VoIP |
| Web | Emits the same events so you can drive an HTML call screen | — |

The plugin shows and tracks the call. **Media is yours:** start your WebRTC / SIP session on `callAnswered` / `callStarted` and stop it on `callEnded`.

> **Status:** Android, iOS and web implement the API below. Android is verified on an emulator; the iOS implementation (ported from callkeep's `RNCallKeep.m`) still needs its first build and device test — see [WORK.md](WORK.md). The previous iOS `plugin_events` listener is gone; the legacy `ConnectionId` / `Username` push keys are still accepted.

## Install

```bash
npm install ionic-callkit
npx cap sync
```

Requires Capacitor 7, Android 8.0+ (API 26) and iOS 14+.

## Android setup

1. **Firebase** — add your app in the Firebase console and place `google-services.json` in `android/app/`. The Capacitor Android template applies the Google Services plugin automatically when that file exists.
2. **minSdk 26** — in `android/variables.gradle` set `minSdkVersion = 26`.
3. **Permissions** — the plugin manifest already declares everything self-managed mode needs (`MANAGE_OWN_CALLS`, `RECORD_AUDIO`, `FOREGROUND_SERVICE_PHONE_CALL`, `FOREGROUND_SERVICE_MICROPHONE`, `POST_NOTIFICATIONS`, `USE_FULL_SCREEN_INTENT`, …). At runtime call `requestPermissions()` for microphone + notifications.
4. **Full-screen intent (Android 14+)** — Google Play only grants `USE_FULL_SCREEN_INTENT` by default to calling / alarm apps. Check `canUseFullScreenIntent()` and send the user to `openFullScreenIntentSettings()` if needed; without it a locked phone shows a heads-up notification instead of the full-screen call.
5. **Incoming-call screen** — when the phone is locked or the screen is off, the ringing call opens a native full-screen call screen (caller, Answer, Decline) over the lock screen, even a PIN/biometric one; Answer then opens your app over the lock screen. When the phone is unlocked and in use, Android shows a heads-up notification with Answer/Decline instead (same as every calling app). Set `android.incomingCallScreen: 'app'` to open your own call page instead of the native screen. Translate / restyle the native screen by overriding the `ionic_callkit_*` strings and colors in your app's `res/values`.
6. **Ringtone** — the plugin rings with the device ringtone and vibrates, following the phone's ringer mode (normal → sound + vibration, vibrate → vibration, silent → nothing); a volume-key press silences it. Custom sound: put a file in `android/app/src/main/res/raw/` and set `android.ringtoneSound: 'my_ring'`; disable vibration with `android.vibrate: false`.
7. **Managed mode (optional)** — `setup({ android: { selfManaged: false } })` uses the system dialer UI instead. Add `READ_PHONE_STATE`, `READ_PHONE_NUMBERS` and `CALL_PHONE` to your app manifest, request the `phone` permission, and have the user enable the calling account (`openPhoneAccountSettings()`).

### Using it with `@capacitor/push-notifications`

Both plugins work together with no extra setup. Android delivers FCM to a single `FirebaseMessagingService`; ionic-callkit's service is declared with a higher priority, handles call pushes (`type: incoming_call` / `call_ended`) itself, and forwards every other message and token refresh to `@capacitor/push-notifications`, so your `pushNotificationReceived` / `registration` listeners keep firing. Both plugins see the same FCM token.

If your app has its **own** `FirebaseMessagingService`, remove the plugin's from the merged manifest and pass call messages to it:

```xml
<service android:name="io.kreador.callkit.CallMessagingService" tools:node="remove" />
```

```java
if (io.kreador.callkit.CallMessagingService.handleRemoteMessage(this, remoteMessage)) return;
```

## Web

Browsers have no system call UI, so on the web **your app renders the call screen** (e.g. an `ion-modal` or a `/call/:id` page). The web implementation tracks calls and fires the same events as native, so the same listeners drive it. Web gets no pushes: call `displayIncomingCall()` when your own signalling (WebSocket, LiveKit room event, …) reports a call.

```typescript
// Web: your signalling reports an incoming call
await CallKit.displayIncomingCall({ callId, callerName: 'Alice' });
// → 'incomingCall' fires → the same listener opens your call page
```

`registerVoipToken`, `getAudioRoutes`, `setAudioRoute` and the Android settings helpers reject with "not implemented" on web.

## iOS setup

1. Xcode → *Signing & Capabilities*: add **Push Notifications** and **Background Modes → Voice over IP**.

   ![](https://miro.medium.com/max/700/1*zVc9U601x_qUqweRKfsfow.png)

2. Create a VoIP Services certificate on [developer.apple.com/certificates](https://developer.apple.com/certificates), download it and import it into Keychain Access.

   ![](https://miro.medium.com/max/700/1*Z2q66Vo2Emho4_IVXRN8GQ.png)

3. Export it as `.p12`, then convert it for the test script:

   ![](https://miro.medium.com/max/700/1*7N7d7-dEa6WAMzWbFXO66A.png)

   ```bash
   openssl pkcs12 -in YOUR_CERTIFICATES.p12 -out app.pem -nodes -clcerts
   ```

### iOS notes

- **Every VoIP push must show a call.** iOS 13+ terminates apps that receive a VoIP push without reporting a call to CallKit. The plugin reports calls synchronously from the push. For pushes it can't show (not a call payload, `call_ended` for an unknown call, busy) it reports and immediately ends a call, which can appear briefly or as a missed call. Prefer sending `call_ended` over your signalling or a regular (non-VoIP) push when the app is running.
- **Answering from the lock screen does not open the app.** CallKit keeps the call in the system UI; your app is running in the background, so start media on `callAnswered` without relying on the WebView being visible.
- **Call ids:** CallKit needs UUIDs. Use UUIDs as `callId` if you can; other strings work too and are mapped internally.
- **Early PushKit registration (recommended):** call `CallKitVoip.start()` in `application(_:didFinishLaunchingWithOptions:)` (`import IonicCallkit` with CocoaPods, `import CallKitPlugin` with SPM). The plugin also registers when it loads, but registering at launch means a VoIP push that starts the app doesn't wait for the WebView.
- **Audio:** `ios.audioSession.autoConfigure: false` lets your media SDK own `AVAudioSession`; otherwise the plugin configures play-and-record with Bluetooth when CallKit activates the session.
- **Not available on iOS:** `backToForeground`, `openPhoneAccountSettings`, `openFullScreenIntentSettings` (reject as unimplemented); `setAvailable`, `setReachable` are no-ops; `showIncomingCallUi` / `silenceIncomingCall` / `checkReachability` / `hasActiveCall` are Android-only events.

## Usage

```typescript
import { CallKit } from 'ionic-callkit';

// Register listeners first: events that happened while the app was starting
// (e.g. the user answered from the lock screen) are delivered as soon as you listen.
await CallKit.addListener('voipToken', ({ token }) => {
  // POST the token to your backend (PushKit token on iOS, FCM token on Android)
});

await CallKit.addListener('incomingCall', ({ callId, callerName }) => {
  router.navigate(['/call', callId]);
});

await CallKit.addListener('callAnswered', ({ callId }) => webrtc.startSession(callId));
await CallKit.addListener('callEnded', ({ callId }) => webrtc.endSession(callId));
await CallKit.addListener('callRejected', ({ callId }) => signalling.decline(callId));

await CallKit.setup({ appName: 'My App', supportsVideo: true });
await CallKit.requestPermissions();
await CallKit.registerVoipToken();
```

Outgoing:

```typescript
await CallKit.startCall({ callId, calleeName: 'Bob', handle: 'bob@example.com' });
// …when the remote side picks up:
await CallKit.setCallActive({ callId });
// …when it hangs up:
await CallKit.reportEndCall({ callId, reason: CallEndReason.RemoteEnded });
```

A complete Ionic Angular demo lives in [`example/`](example/).

### Cold starts, reachability and concurrency (Android)

- **Cold start:** when a push wakes a killed app, events raised before your listeners exist (`incomingCall`, `callAnswered` from the lock screen, …) are queued natively, persisted, and delivered as soon as you add listeners. `getInitialEvents()` also returns them (oldest first) so you can route straight to the call screen at startup; call `clearInitialEvents()` once handled. Events marked `restored: true` come from a previous process that died before delivering them — Android has already dropped those calls.
- **Reachability watchdog:** if a call is answered but your app never comes up to handle it, the plugin ends it after `android.answerReachabilityTimeout` ms (default 10000, `0` disables). Adding a `callAnswered` listener or calling `setReachable()` marks the app as ready.
- **Call state:** `setCallState({ callId, state })` reports `dialing` / `ringing` / `active` / `held` to the system; every change is emitted as `callStateChanged`.
- **One call at a time:** `setCanMakeMultipleCalls({ allow: false })` (or `android.canMakeMultipleCalls: false`) refuses a second incoming call with `incomingCallFailed { error: 'busy' }` and makes `startCall` reject — tell the caller "busy" from that event.


## Backend contract

The plugin only produces tokens; your server sends the pushes. Never ship APNs / FCM credentials in the app.

Both platforms read the same keys:

| Key | Required | Notes |
|---|---|---|
| `type` | no | `incoming_call` (default) or `call_ended` (caller hung up / cancelled) |
| `callId` | yes | Your call id; every event carries it |
| `callerName` | no | Shown in the call UI |
| `handle` | no | Number / user id; defaults to `callerName` |
| `hasVideo` | no | `"true"` / `"false"` |

The legacy keys `ConnectionId` / `Username` are still accepted.

Use a **new `callId` per call** (a UUID is ideal — iOS uses it directly). Pushes are de-duplicated per call id, and a push for a call id that ended in the last 5 minutes is ignored, so retries and a `call_ended` that overtakes its `incoming_call` never ring a finished call.

**iOS — APNs VoIP push** (`https://api.push.apple.com/3/device/<token>`, headers `apns-topic: <bundle>.voip`, `apns-push-type: voip`, `apns-priority: 10`):

```json
{
  "aps": { "alert": "Incoming call", "content-available": 1 },
  "type": "incoming_call",
  "callId": "abc-123",
  "callerName": "Alice",
  "hasVideo": "false"
}
```

iOS 13+ terminates apps that receive a VoIP push without reporting a call, so only send VoIP pushes for real incoming calls.

**Android — FCM HTTP v1** (`POST https://fcm.googleapis.com/v1/projects/<project>/messages:send`). The message must be **data-only** (no `notification` block) with **high priority**, otherwise it will not reach the app while it is killed:

```json
{
  "message": {
    "token": "<fcm token>",
    "android": { "priority": "high", "ttl": "30s" },
    "data": { "type": "incoming_call", "callId": "abc-123", "callerName": "Alice", "hasVideo": "false" }
  }
}
```

### Test scripts

```bash
# iOS (needs app.pem in the current directory)
dev/sendVoip.sh <callId> <voipToken> <bundleId> [hasVideo] [callerName] [sandbox|production]

# Android (service account JSON from Firebase → Project settings → Service accounts)
dev/sendFcm.sh <service-account.json> <fcmToken> [callId] [callerName] [hasVideo]
dev/sendFcm.sh <service-account.json> <fcmToken> <callId> --end
```

For production APNs use `api.push.apple.com`; sending a sandbox token there returns `BadDeviceToken`.

## Credits

The Android implementation is a port of [livekit/react-native-callkeep](https://github.com/livekit/react-native-callkeep) (ISC, © The CallKeep Authors), adapted for Capacitor.

## API

<docgen-index>

* [`setup(...)`](#setup)
* [`registerVoipToken()`](#registervoiptoken)
* [`unregisterVoipToken()`](#unregistervoiptoken)
* [`displayIncomingCall(...)`](#displayincomingcall)
* [`answerCall(...)`](#answercall)
* [`rejectCall(...)`](#rejectcall)
* [`startCall(...)`](#startcall)
* [`setCallActive(...)`](#setcallactive)
* [`setCallState(...)`](#setcallstate)
* [`endCall(...)`](#endcall)
* [`endAllCalls()`](#endallcalls)
* [`reportEndCall(...)`](#reportendcall)
* [`updateDisplay(...)`](#updatedisplay)
* [`setMuted(...)`](#setmuted)
* [`setSpeaker(...)`](#setspeaker)
* [`setOnHold(...)`](#setonhold)
* [`sendDTMF(...)`](#senddtmf)
* [`getAudioRoutes()`](#getaudioroutes)
* [`setAudioRoute(...)`](#setaudioroute)
* [`setAvailable(...)`](#setavailable)
* [`setReachable()`](#setreachable)
* [`setCanMakeMultipleCalls(...)`](#setcanmakemultiplecalls)
* [`getInitialEvents()`](#getinitialevents)
* [`clearInitialEvents()`](#clearinitialevents)
* [`hasPhoneAccount()`](#hasphoneaccount)
* [`openPhoneAccountSettings()`](#openphoneaccountsettings)
* [`canUseFullScreenIntent()`](#canusefullscreenintent)
* [`openFullScreenIntentSettings()`](#openfullscreenintentsettings)
* [`backToForeground()`](#backtoforeground)
* [`getActiveCalls()`](#getactivecalls)
* [`checkPermissions()`](#checkpermissions)
* [`requestPermissions(...)`](#requestpermissions)
* [`addListener('voipToken', ...)`](#addlistenervoiptoken-)
* [`addListener('incomingCall', ...)`](#addlistenerincomingcall-)
* [`addListener('callAnswered', ...)`](#addlistenercallanswered-)
* [`addListener('callStarted', ...)`](#addlistenercallstarted-)
* [`addListener('callEnded', ...)`](#addlistenercallended-)
* [`addListener('callRejected', ...)`](#addlistenercallrejected-)
* [`addListener('muted', ...)`](#addlistenermuted-)
* [`addListener('held', ...)`](#addlistenerheld-)
* [`addListener('callStateChanged', ...)`](#addlistenercallstatechanged-)
* [`addListener('dtmf', ...)`](#addlistenerdtmf-)
* [`addListener('speakerChanged', ...)`](#addlistenerspeakerchanged-)
* [`addListener('audioRouteChanged', ...)`](#addlisteneraudioroutechanged-)
* [`addListener('audioSessionActivated', ...)`](#addlisteneraudiosessionactivated-)
* [`addListener('showIncomingCallUi', ...)`](#addlistenershowincomingcallui-)
* [`addListener('silenceIncomingCall', ...)`](#addlistenersilenceincomingcall-)
* [`addListener('incomingCallFailed', ...)`](#addlistenerincomingcallfailed-)
* [`addListener('checkReachability', ...)`](#addlistenercheckreachability-)
* [`addListener('hasActiveCall', ...)`](#addlistenerhasactivecall-)
* [`removeAllListeners()`](#removealllisteners)
* [Interfaces](#interfaces)
* [Type Aliases](#type-aliases)
* [Enums](#enums)

</docgen-index>

<docgen-api>
<!--Update the source file JSDoc comments and rerun docgen to update the docs below-->

### setup(...)

```typescript
setup(options: CallSetupOptions) => Promise<void>
```

Configure the plugin. Call once at app start, before any other call method.

Android: registers the PhoneAccount with Telecom and stores the settings so
that a push received while the app is killed can still ring.

| Param         | Type                                                          |
| ------------- | ------------------------------------------------------------- |
| **`options`** | <code><a href="#callsetupoptions">CallSetupOptions</a></code> |

--------------------


### registerVoipToken()

```typescript
registerVoipToken() => Promise<{ token: string; }>
```

Get the push token used to ring this device.
iOS: PushKit VoIP token. Android: FCM registration token.
Also emitted as the `voipToken` event (including on token refresh).

**Returns:** <code>Promise&lt;{ token: string; }&gt;</code>

--------------------


### unregisterVoipToken()

```typescript
unregisterVoipToken() => Promise<void>
```

Stop receiving call pushes (Android: deletes the FCM token).

--------------------


### displayIncomingCall(...)

```typescript
displayIncomingCall(options: IncomingCallOptions) => Promise<void>
```

Show the native incoming call UI. Normally triggered natively by a push;
call it yourself when the call arrives over your own signalling channel.

| Param         | Type                                                                |
| ------------- | ------------------------------------------------------------------- |
| **`options`** | <code><a href="#incomingcalloptions">IncomingCallOptions</a></code> |

--------------------


### answerCall(...)

```typescript
answerCall(options: CallIdOptions) => Promise<void>
```

Answer a ringing call from your own UI. Emits `callAnswered`.

| Param         | Type                                                    |
| ------------- | ------------------------------------------------------- |
| **`options`** | <code><a href="#callidoptions">CallIdOptions</a></code> |

--------------------


### rejectCall(...)

```typescript
rejectCall(options: CallIdOptions) => Promise<void>
```

Decline a ringing call. Emits `callRejected`.

| Param         | Type                                                    |
| ------------- | ------------------------------------------------------- |
| **`options`** | <code><a href="#callidoptions">CallIdOptions</a></code> |

--------------------


### startCall(...)

```typescript
startCall(options: OutgoingCallOptions) => Promise<void>
```

Start an outgoing call. Emits `callStarted`.

| Param         | Type                                                                |
| ------------- | ------------------------------------------------------------------- |
| **`options`** | <code><a href="#outgoingcalloptions">OutgoingCallOptions</a></code> |

--------------------


### setCallActive(...)

```typescript
setCallActive(options: CallIdOptions) => Promise<void>
```

Mark an outgoing call as connected (remote side picked up).

| Param         | Type                                                    |
| ------------- | ------------------------------------------------------- |
| **`options`** | <code><a href="#callidoptions">CallIdOptions</a></code> |

--------------------


### setCallState(...)

```typescript
setCallState(options: { callId: string; state: SettableCallState; }) => Promise<void>
```

Report the call's connection state to the system (callkeep's `setConnectionState`),
e.g. `dialing` while your signalling connects, `active` once media flows.
Emits `callStateChanged`.

| Param         | Type                                                                                        |
| ------------- | ------------------------------------------------------------------------------------------- |
| **`options`** | <code>{ callId: string; state: <a href="#settablecallstate">SettableCallState</a>; }</code> |

--------------------


### endCall(...)

```typescript
endCall(options: CallIdOptions) => Promise<void>
```

Hang up a call locally. Emits `callEnded`.

| Param         | Type                                                    |
| ------------- | ------------------------------------------------------- |
| **`options`** | <code><a href="#callidoptions">CallIdOptions</a></code> |

--------------------


### endAllCalls()

```typescript
endAllCalls() => Promise<void>
```

Hang up every call.

--------------------


### reportEndCall(...)

```typescript
reportEndCall(options: ReportEndCallOptions) => Promise<void>
```

Report that a call ended for a reason other than the local user hanging up
(remote hang-up, missed, answered elsewhere…). Does not emit `callEnded`.

| Param         | Type                                                                  |
| ------------- | --------------------------------------------------------------------- |
| **`options`** | <code><a href="#reportendcalloptions">ReportEndCallOptions</a></code> |

--------------------


### updateDisplay(...)

```typescript
updateDisplay(options: UpdateDisplayOptions) => Promise<void>
```

Update the name / handle shown in the system call UI.

| Param         | Type                                                                  |
| ------------- | --------------------------------------------------------------------- |
| **`options`** | <code><a href="#updatedisplayoptions">UpdateDisplayOptions</a></code> |

--------------------


### setMuted(...)

```typescript
setMuted(options: { callId: string; muted: boolean; }) => Promise<void>
```

Update the mute state shown in the system UI. Emits `muted`.
The plugin does not touch your media tracks — mute them in your WebRTC layer.

| Param         | Type                                             |
| ------------- | ------------------------------------------------ |
| **`options`** | <code>{ callId: string; muted: boolean; }</code> |

--------------------


### setSpeaker(...)

```typescript
setSpeaker(options: { on: boolean; callId?: string; }) => Promise<void>
```

Route audio to the loudspeaker or back to the earpiece. Emits `speakerChanged`.

| Param         | Type                                           |
| ------------- | ---------------------------------------------- |
| **`options`** | <code>{ on: boolean; callId?: string; }</code> |

--------------------


### setOnHold(...)

```typescript
setOnHold(options: { callId: string; hold: boolean; }) => Promise<void>
```

Put a call on hold / resume it. Emits `held`.

| Param         | Type                                            |
| ------------- | ----------------------------------------------- |
| **`options`** | <code>{ callId: string; hold: boolean; }</code> |

--------------------


### sendDTMF(...)

```typescript
sendDTMF(options: { callId: string; digits: string; }) => Promise<void>
```

Play DTMF digits on a call. Emits `dtmf`.

| Param         | Type                                             |
| ------------- | ------------------------------------------------ |
| **`options`** | <code>{ callId: string; digits: string; }</code> |

--------------------


### getAudioRoutes()

```typescript
getAudioRoutes() => Promise<{ routes: AudioRoute[]; }>
```

List available audio routes. Android and iOS.

**Returns:** <code>Promise&lt;{ routes: AudioRoute[]; }&gt;</code>

--------------------


### setAudioRoute(...)

```typescript
setAudioRoute(options: { callId: string; route: AudioRouteType; }) => Promise<void>
```

Select an audio route. Android and iOS.

| Param         | Type                                                                                  |
| ------------- | ------------------------------------------------------------------------------------- |
| **`options`** | <code>{ callId: string; route: <a href="#audioroutetype">AudioRouteType</a>; }</code> |

--------------------


### setAvailable(...)

```typescript
setAvailable(options: { available: boolean; }) => Promise<void>
```

Tell the ConnectionService whether the app can take calls
(e.g. `false` while logged out) so the user doesn't get stuck in the native UI.
Android only; no-op elsewhere.

| Param         | Type                                 |
| ------------- | ------------------------------------ |
| **`options`** | <code>{ available: boolean; }</code> |

--------------------


### setReachable()

```typescript
setReachable() => Promise<void>
```

Tell the plugin the JS layer is ready to handle calls. Android only.
Adding a `callAnswered` listener counts as reachable too. Answered calls are ended if
the app isn't reachable within `android.answerReachabilityTimeout`.

--------------------


### setCanMakeMultipleCalls(...)

```typescript
setCanMakeMultipleCalls(options: { allow: boolean; }) => Promise<void>
```

Allow or refuse concurrent calls. When `false`, a new incoming call while another is in
progress is refused and `incomingCallFailed` is emitted with `error: 'busy'`, and
`startCall` rejects. Persisted, so pushes handled while the app is killed respect it.

| Param         | Type                             |
| ------------- | -------------------------------- |
| **`options`** | <code>{ allow: boolean; }</code> |

--------------------


### getInitialEvents()

```typescript
getInitialEvents() => Promise<{ events: InitialEvent[]; }>
```

Events that happened before JS attached (e.g. the call was answered from the lock
screen during a cold start), oldest first. They are also delivered to listeners; use
this to route directly at startup. Events with `restored: true` come from a previous
app process that died before delivering them — their calls no longer exist.
Kept until `clearInitialEvents()`. Android and iOS (empty on web).

**Returns:** <code>Promise&lt;{ events: InitialEvent[]; }&gt;</code>

--------------------


### clearInitialEvents()

```typescript
clearInitialEvents() => Promise<void>
```

Forget the events returned by `getInitialEvents()`.

--------------------


### hasPhoneAccount()

```typescript
hasPhoneAccount() => Promise<{ value: boolean; }>
```

Whether the calling account is registered and enabled.
Always `true` in self-managed mode. Android only.

**Returns:** <code>Promise&lt;{ value: boolean; }&gt;</code>

--------------------


### openPhoneAccountSettings()

```typescript
openPhoneAccountSettings() => Promise<void>
```

Open the system "Calling accounts" screen (managed mode). Android only.

--------------------


### canUseFullScreenIntent()

```typescript
canUseFullScreenIntent() => Promise<{ value: boolean; }>
```

Whether the app may show a full-screen incoming call over the lock screen.
Android 14+ may revoke this. Android only.

**Returns:** <code>Promise&lt;{ value: boolean; }&gt;</code>

--------------------


### openFullScreenIntentSettings()

```typescript
openFullScreenIntentSettings() => Promise<void>
```

Open the settings page to grant full-screen intents (Android 14+).

--------------------


### backToForeground()

```typescript
backToForeground() => Promise<void>
```

Bring the app to the foreground (e.g. after answering from the lock screen). Android only;
rejects as unimplemented on iOS, which doesn't allow it.

--------------------


### getActiveCalls()

```typescript
getActiveCalls() => Promise<{ calls: ActiveCall[]; }>
```

Currently tracked calls.

**Returns:** <code>Promise&lt;{ calls: ActiveCall[]; }&gt;</code>

--------------------


### checkPermissions()

```typescript
checkPermissions() => Promise<CallKitPermissionStatus>
```

**Returns:** <code>Promise&lt;<a href="#callkitpermissionstatus">CallKitPermissionStatus</a>&gt;</code>

--------------------


### requestPermissions(...)

```typescript
requestPermissions(options?: { permissions: CallKitPermissionType[]; } | undefined) => Promise<CallKitPermissionStatus>
```

| Param         | Type                                                   |
| ------------- | ------------------------------------------------------ |
| **`options`** | <code>{ permissions: CallKitPermissionType[]; }</code> |

**Returns:** <code>Promise&lt;<a href="#callkitpermissionstatus">CallKitPermissionStatus</a>&gt;</code>

--------------------


### addListener('voipToken', ...)

```typescript
addListener(eventName: 'voipToken', listenerFunc: (data: { token: string; }) => void) => Promise<PluginListenerHandle>
```

| Param              | Type                                               |
| ------------------ | -------------------------------------------------- |
| **`eventName`**    | <code>'voipToken'</code>                           |
| **`listenerFunc`** | <code>(data: { token: string; }) =&gt; void</code> |

**Returns:** <code>Promise&lt;<a href="#pluginlistenerhandle">PluginListenerHandle</a>&gt;</code>

--------------------


### addListener('incomingCall', ...)

```typescript
addListener(eventName: 'incomingCall', listenerFunc: (data: IncomingCallEvent) => void) => Promise<PluginListenerHandle>
```

| Param              | Type                                                                               |
| ------------------ | ---------------------------------------------------------------------------------- |
| **`eventName`**    | <code>'incomingCall'</code>                                                        |
| **`listenerFunc`** | <code>(data: <a href="#incomingcallevent">IncomingCallEvent</a>) =&gt; void</code> |

**Returns:** <code>Promise&lt;<a href="#pluginlistenerhandle">PluginListenerHandle</a>&gt;</code>

--------------------


### addListener('callAnswered', ...)

```typescript
addListener(eventName: 'callAnswered', listenerFunc: (data: CallAnsweredEvent) => void) => Promise<PluginListenerHandle>
```

| Param              | Type                                                                               |
| ------------------ | ---------------------------------------------------------------------------------- |
| **`eventName`**    | <code>'callAnswered'</code>                                                        |
| **`listenerFunc`** | <code>(data: <a href="#callansweredevent">CallAnsweredEvent</a>) =&gt; void</code> |

**Returns:** <code>Promise&lt;<a href="#pluginlistenerhandle">PluginListenerHandle</a>&gt;</code>

--------------------


### addListener('callStarted', ...)

```typescript
addListener(eventName: 'callStarted', listenerFunc: (data: CallStartedEvent) => void) => Promise<PluginListenerHandle>
```

| Param              | Type                                                                             |
| ------------------ | -------------------------------------------------------------------------------- |
| **`eventName`**    | <code>'callStarted'</code>                                                       |
| **`listenerFunc`** | <code>(data: <a href="#callstartedevent">CallStartedEvent</a>) =&gt; void</code> |

**Returns:** <code>Promise&lt;<a href="#pluginlistenerhandle">PluginListenerHandle</a>&gt;</code>

--------------------


### addListener('callEnded', ...)

```typescript
addListener(eventName: 'callEnded', listenerFunc: (data: CallEndedEvent) => void) => Promise<PluginListenerHandle>
```

| Param              | Type                                                                         |
| ------------------ | ---------------------------------------------------------------------------- |
| **`eventName`**    | <code>'callEnded'</code>                                                     |
| **`listenerFunc`** | <code>(data: <a href="#callendedevent">CallEndedEvent</a>) =&gt; void</code> |

**Returns:** <code>Promise&lt;<a href="#pluginlistenerhandle">PluginListenerHandle</a>&gt;</code>

--------------------


### addListener('callRejected', ...)

```typescript
addListener(eventName: 'callRejected', listenerFunc: (data: CallEvent) => void) => Promise<PluginListenerHandle>
```

| Param              | Type                                                               |
| ------------------ | ------------------------------------------------------------------ |
| **`eventName`**    | <code>'callRejected'</code>                                        |
| **`listenerFunc`** | <code>(data: <a href="#callevent">CallEvent</a>) =&gt; void</code> |

**Returns:** <code>Promise&lt;<a href="#pluginlistenerhandle">PluginListenerHandle</a>&gt;</code>

--------------------


### addListener('muted', ...)

```typescript
addListener(eventName: 'muted', listenerFunc: (data: MutedEvent) => void) => Promise<PluginListenerHandle>
```

| Param              | Type                                                                 |
| ------------------ | -------------------------------------------------------------------- |
| **`eventName`**    | <code>'muted'</code>                                                 |
| **`listenerFunc`** | <code>(data: <a href="#mutedevent">MutedEvent</a>) =&gt; void</code> |

**Returns:** <code>Promise&lt;<a href="#pluginlistenerhandle">PluginListenerHandle</a>&gt;</code>

--------------------


### addListener('held', ...)

```typescript
addListener(eventName: 'held', listenerFunc: (data: HeldEvent) => void) => Promise<PluginListenerHandle>
```

| Param              | Type                                                               |
| ------------------ | ------------------------------------------------------------------ |
| **`eventName`**    | <code>'held'</code>                                                |
| **`listenerFunc`** | <code>(data: <a href="#heldevent">HeldEvent</a>) =&gt; void</code> |

**Returns:** <code>Promise&lt;<a href="#pluginlistenerhandle">PluginListenerHandle</a>&gt;</code>

--------------------


### addListener('callStateChanged', ...)

```typescript
addListener(eventName: 'callStateChanged', listenerFunc: (data: CallStateChangedEvent) => void) => Promise<PluginListenerHandle>
```

| Param              | Type                                                                                       |
| ------------------ | ------------------------------------------------------------------------------------------ |
| **`eventName`**    | <code>'callStateChanged'</code>                                                            |
| **`listenerFunc`** | <code>(data: <a href="#callstatechangedevent">CallStateChangedEvent</a>) =&gt; void</code> |

**Returns:** <code>Promise&lt;<a href="#pluginlistenerhandle">PluginListenerHandle</a>&gt;</code>

--------------------


### addListener('dtmf', ...)

```typescript
addListener(eventName: 'dtmf', listenerFunc: (data: DtmfEvent) => void) => Promise<PluginListenerHandle>
```

| Param              | Type                                                               |
| ------------------ | ------------------------------------------------------------------ |
| **`eventName`**    | <code>'dtmf'</code>                                                |
| **`listenerFunc`** | <code>(data: <a href="#dtmfevent">DtmfEvent</a>) =&gt; void</code> |

**Returns:** <code>Promise&lt;<a href="#pluginlistenerhandle">PluginListenerHandle</a>&gt;</code>

--------------------


### addListener('speakerChanged', ...)

```typescript
addListener(eventName: 'speakerChanged', listenerFunc: (data: SpeakerChangedEvent) => void) => Promise<PluginListenerHandle>
```

| Param              | Type                                                                                   |
| ------------------ | -------------------------------------------------------------------------------------- |
| **`eventName`**    | <code>'speakerChanged'</code>                                                          |
| **`listenerFunc`** | <code>(data: <a href="#speakerchangedevent">SpeakerChangedEvent</a>) =&gt; void</code> |

**Returns:** <code>Promise&lt;<a href="#pluginlistenerhandle">PluginListenerHandle</a>&gt;</code>

--------------------


### addListener('audioRouteChanged', ...)

```typescript
addListener(eventName: 'audioRouteChanged', listenerFunc: (data: AudioRouteChangedEvent) => void) => Promise<PluginListenerHandle>
```

| Param              | Type                                                                                         |
| ------------------ | -------------------------------------------------------------------------------------------- |
| **`eventName`**    | <code>'audioRouteChanged'</code>                                                             |
| **`listenerFunc`** | <code>(data: <a href="#audioroutechangedevent">AudioRouteChangedEvent</a>) =&gt; void</code> |

**Returns:** <code>Promise&lt;<a href="#pluginlistenerhandle">PluginListenerHandle</a>&gt;</code>

--------------------


### addListener('audioSessionActivated', ...)

```typescript
addListener(eventName: 'audioSessionActivated', listenerFunc: () => void) => Promise<PluginListenerHandle>
```

| Param              | Type                                 |
| ------------------ | ------------------------------------ |
| **`eventName`**    | <code>'audioSessionActivated'</code> |
| **`listenerFunc`** | <code>() =&gt; void</code>           |

**Returns:** <code>Promise&lt;<a href="#pluginlistenerhandle">PluginListenerHandle</a>&gt;</code>

--------------------


### addListener('showIncomingCallUi', ...)

```typescript
addListener(eventName: 'showIncomingCallUi', listenerFunc: (data: IncomingCallEvent) => void) => Promise<PluginListenerHandle>
```

| Param              | Type                                                                               |
| ------------------ | ---------------------------------------------------------------------------------- |
| **`eventName`**    | <code>'showIncomingCallUi'</code>                                                  |
| **`listenerFunc`** | <code>(data: <a href="#incomingcallevent">IncomingCallEvent</a>) =&gt; void</code> |

**Returns:** <code>Promise&lt;<a href="#pluginlistenerhandle">PluginListenerHandle</a>&gt;</code>

--------------------


### addListener('silenceIncomingCall', ...)

```typescript
addListener(eventName: 'silenceIncomingCall', listenerFunc: (data: CallEvent) => void) => Promise<PluginListenerHandle>
```

| Param              | Type                                                               |
| ------------------ | ------------------------------------------------------------------ |
| **`eventName`**    | <code>'silenceIncomingCall'</code>                                 |
| **`listenerFunc`** | <code>(data: <a href="#callevent">CallEvent</a>) =&gt; void</code> |

**Returns:** <code>Promise&lt;<a href="#pluginlistenerhandle">PluginListenerHandle</a>&gt;</code>

--------------------


### addListener('incomingCallFailed', ...)

```typescript
addListener(eventName: 'incomingCallFailed', listenerFunc: (data: CallFailedEvent) => void) => Promise<PluginListenerHandle>
```

| Param              | Type                                                                           |
| ------------------ | ------------------------------------------------------------------------------ |
| **`eventName`**    | <code>'incomingCallFailed'</code>                                              |
| **`listenerFunc`** | <code>(data: <a href="#callfailedevent">CallFailedEvent</a>) =&gt; void</code> |

**Returns:** <code>Promise&lt;<a href="#pluginlistenerhandle">PluginListenerHandle</a>&gt;</code>

--------------------


### addListener('checkReachability', ...)

```typescript
addListener(eventName: 'checkReachability', listenerFunc: () => void) => Promise<PluginListenerHandle>
```

| Param              | Type                             |
| ------------------ | -------------------------------- |
| **`eventName`**    | <code>'checkReachability'</code> |
| **`listenerFunc`** | <code>() =&gt; void</code>       |

**Returns:** <code>Promise&lt;<a href="#pluginlistenerhandle">PluginListenerHandle</a>&gt;</code>

--------------------


### addListener('hasActiveCall', ...)

```typescript
addListener(eventName: 'hasActiveCall', listenerFunc: () => void) => Promise<PluginListenerHandle>
```

| Param              | Type                         |
| ------------------ | ---------------------------- |
| **`eventName`**    | <code>'hasActiveCall'</code> |
| **`listenerFunc`** | <code>() =&gt; void</code>   |

**Returns:** <code>Promise&lt;<a href="#pluginlistenerhandle">PluginListenerHandle</a>&gt;</code>

--------------------


### removeAllListeners()

```typescript
removeAllListeners() => Promise<void>
```

--------------------


### Interfaces


#### CallSetupOptions

| Prop                | Type                                                                | Description                                                                                                                                          |
| ------------------- | ------------------------------------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------- |
| **`appName`**       | <code>string</code>                                                 | Name of the calling account on Android. Defaults to the app label. iOS always shows the app's display name (CallKit no longer accepts a custom one). |
| **`supportsVideo`** | <code>boolean</code>                                                | Default: false                                                                                                                                       |
| **`imageName`**     | <code>string</code>                                                 | Android drawable / iOS asset name used as the calling account / CallKit icon.                                                                        |
| **`android`**       | <code><a href="#androidsetupoptions">AndroidSetupOptions</a></code> |                                                                                                                                                      |
| **`ios`**           | <code><a href="#iossetupoptions">IOSSetupOptions</a></code>         |                                                                                                                                                      |


#### AndroidSetupOptions

| Prop                                 | Type                                                                                                                       | Description                                                                                                                                                                                                                                                                                                                                                                                                                       |
| ------------------------------------ | -------------------------------------------------------------------------------------------------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| **`selfManaged`**                    | <code>boolean</code>                                                                                                       | Self-managed ConnectionService: the app owns the call UI and no "enable calling account" step is needed. Default: true. Set to false for managed mode (system dialer UI, needs phone permissions).                                                                                                                                                                                                                                |
| **`showIncomingCallNotification`**   | <code>boolean</code>                                                                                                       | Show the plugin's native incoming-call notification (full-screen intent + Answer/Decline) in self-managed mode. Default: true.                                                                                                                                                                                                                                                                                                    |
| **`incomingCallScreen`**             | <code>'native' \| 'app'</code>                                                                                             | What the full-screen incoming-call alert opens over the lock screen: - `native` (default): the plugin's built-in call screen (caller + Answer / Decline). Shows instantly and reliably over a secure lock screen; Answer then opens your app. - `app`: your app's own call page (listen to `incomingCall`). Needs the WebView to load first. Restyle / translate the native screen by overriding the `ionic_callkit_*` resources. |
| **`ringtoneSound`**                  | <code>string</code>                                                                                                        | Ringtone file in `android/app/src/main/res/raw/` (name with or without extension). Default: the device ringtone. The plugin rings itself; silent / vibrate modes are respected.                                                                                                                                                                                                                                                   |
| **`vibrate`**                        | <code>boolean</code>                                                                                                       | Vibrate while ringing (unless the phone is on silent). Default: true.                                                                                                                                                                                                                                                                                                                                                             |
| **`notificationIcon`**               | <code>string</code>                                                                                                        | Small icon (drawable or mipmap resource name) for call notifications.                                                                                                                                                                                                                                                                                                                                                             |
| **`incomingCallChannelName`**        | <code>string</code>                                                                                                        | Channel name for incoming calls. Default: "Incoming calls".                                                                                                                                                                                                                                                                                                                                                                       |
| **`foregroundService`**              | <code>false \| { channelId?: string; channelName?: string; notificationTitle?: string; notificationIcon?: string; }</code> | Ongoing-call foreground service notification (keeps the mic alive in the background). Enabled by default; pass `false` to disable.                                                                                                                                                                                                                                                                                                |
| **`canMakeMultipleCalls`**           | <code>boolean</code>                                                                                                       | Allow more than one simultaneous call. Default: true. See `setCanMakeMultipleCalls`.                                                                                                                                                                                                                                                                                                                                              |
| **`answerReachabilityTimeout`**      | <code>number</code>                                                                                                        | End an answered call if the app hasn't called `setReachable()` (or added a `callAnswered` listener) within this many milliseconds — e.g. the WebView failed to start after answering from the lock screen. Default: 10000. 0 disables.                                                                                                                                                                                            |
| **`displayCallReachabilityTimeout`** | <code>number</code>                                                                                                        | If set, an incoming call is dropped when the JS layer hasn't called `setReachable()` within this many milliseconds.                                                                                                                                                                                                                                                                                                               |


#### IOSSetupOptions

| Prop                           | Type                                                                               | Description                                                                                                                                                      |
| ------------------------------ | ---------------------------------------------------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| **`ringtoneSound`**            | <code>string</code>                                                                | Ringtone sound file in the app bundle.                                                                                                                           |
| **`maximumCallGroups`**        | <code>number</code>                                                                | Default: 1                                                                                                                                                       |
| **`maximumCallsPerCallGroup`** | <code>number</code>                                                                | Default: 1                                                                                                                                                       |
| **`includesCallsInRecents`**   | <code>boolean</code>                                                               | Default: false                                                                                                                                                   |
| **`handleType`**               | <code>'number' \| 'generic' \| 'email'</code>                                      | CallKit handle type for `handle` values. Default: `generic`.                                                                                                     |
| **`audioSession`**             | <code>{ autoConfigure?: boolean; categoryOptions?: number; mode?: string; }</code> | Audio session CallKit activates for the call (callkeep's `audioSession` setting). Set `autoConfigure: false` if your media SDK configures AVAudioSession itself. |


#### IncomingCallOptions

| Prop             | Type                                                            | Description                                                                   |
| ---------------- | --------------------------------------------------------------- | ----------------------------------------------------------------------------- |
| **`callId`**     | <code>string</code>                                             |                                                                               |
| **`callerName`** | <code>string</code>                                             |                                                                               |
| **`handle`**     | <code>string</code>                                             | Phone number / user id shown as the caller address. Defaults to `callerName`. |
| **`hasVideo`**   | <code>boolean</code>                                            |                                                                               |
| **`payload`**    | <code><a href="#record">Record</a>&lt;string, string&gt;</code> | Extra data passed back in the `incomingCall` event.                           |


#### CallIdOptions

| Prop         | Type                |
| ------------ | ------------------- |
| **`callId`** | <code>string</code> |


#### OutgoingCallOptions

| Prop             | Type                 | Description                                                     |
| ---------------- | -------------------- | --------------------------------------------------------------- |
| **`callId`**     | <code>string</code>  |                                                                 |
| **`calleeName`** | <code>string</code>  |                                                                 |
| **`handle`**     | <code>string</code>  | Phone number / user id of the callee. Defaults to `calleeName`. |
| **`hasVideo`**   | <code>boolean</code> |                                                                 |


#### ReportEndCallOptions

| Prop         | Type                                                    |
| ------------ | ------------------------------------------------------- |
| **`callId`** | <code>string</code>                                     |
| **`reason`** | <code><a href="#callendreason">CallEndReason</a></code> |


#### UpdateDisplayOptions

| Prop             | Type                |
| ---------------- | ------------------- |
| **`callId`**     | <code>string</code> |
| **`callerName`** | <code>string</code> |
| **`handle`**     | <code>string</code> |


#### AudioRoute

| Prop           | Type                                                      |
| -------------- | --------------------------------------------------------- |
| **`name`**     | <code>string</code>                                       |
| **`type`**     | <code><a href="#audioroutetype">AudioRouteType</a></code> |
| **`selected`** | <code>boolean</code>                                      |


#### InitialEvent

| Prop            | Type                                                             | Description                                                                   |
| --------------- | ---------------------------------------------------------------- | ----------------------------------------------------------------------------- |
| **`name`**      | <code>string</code>                                              | Event name, e.g. `incomingCall`, `callAnswered`.                              |
| **`data`**      | <code><a href="#record">Record</a>&lt;string, unknown&gt;</code> |                                                                               |
| **`timestamp`** | <code>number</code>                                              | Epoch milliseconds.                                                           |
| **`restored`**  | <code>boolean</code>                                             | From a previous app process that died before delivering it; the call is gone. |


#### ActiveCall

| Prop             | Type                                            |
| ---------------- | ----------------------------------------------- |
| **`callId`**     | <code>string</code>                             |
| **`callerName`** | <code>string</code>                             |
| **`handle`**     | <code>string</code>                             |
| **`state`**      | <code><a href="#callstate">CallState</a></code> |


#### CallKitPermissionStatus

| Prop                | Type                                                        |
| ------------------- | ----------------------------------------------------------- |
| **`microphone`**    | <code><a href="#permissionstate">PermissionState</a></code> |
| **`notifications`** | <code><a href="#permissionstate">PermissionState</a></code> |
| **`phone`**         | <code><a href="#permissionstate">PermissionState</a></code> |


#### PluginListenerHandle

| Prop         | Type                                      |
| ------------ | ----------------------------------------- |
| **`remove`** | <code>() =&gt; Promise&lt;void&gt;</code> |


#### IncomingCallEvent

| Prop             | Type                                                            |
| ---------------- | --------------------------------------------------------------- |
| **`callerName`** | <code>string</code>                                             |
| **`handle`**     | <code>string</code>                                             |
| **`hasVideo`**   | <code>boolean</code>                                            |
| **`payload`**    | <code><a href="#record">Record</a>&lt;string, string&gt;</code> |


#### CallAnsweredEvent

| Prop           | Type                 |
| -------------- | -------------------- |
| **`hasVideo`** | <code>boolean</code> |


#### CallStartedEvent

| Prop             | Type                |
| ---------------- | ------------------- |
| **`calleeName`** | <code>string</code> |
| **`handle`**     | <code>string</code> |


#### CallEndedEvent

| Prop         | Type                                                    | Description                                                                                                                                             |
| ------------ | ------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------- |
| **`reason`** | <code><a href="#callendreason">CallEndReason</a></code> | Set when the call was ended remotely by a `call_ended` push (`Missed` if it was never answered, otherwise `RemoteEnded`). Absent when the user hung up. |


#### CallEvent

| Prop         | Type                |
| ------------ | ------------------- |
| **`callId`** | <code>string</code> |


#### MutedEvent

| Prop        | Type                 |
| ----------- | -------------------- |
| **`muted`** | <code>boolean</code> |


#### HeldEvent

| Prop       | Type                 |
| ---------- | -------------------- |
| **`hold`** | <code>boolean</code> |


#### CallStateChangedEvent

| Prop        | Type                                            |
| ----------- | ----------------------------------------------- |
| **`state`** | <code><a href="#callstate">CallState</a></code> |


#### DtmfEvent

| Prop         | Type                |
| ------------ | ------------------- |
| **`digits`** | <code>string</code> |


#### SpeakerChangedEvent

| Prop         | Type                 |
| ------------ | -------------------- |
| **`callId`** | <code>string</code>  |
| **`on`**     | <code>boolean</code> |


#### AudioRouteChangedEvent

| Prop         | Type                | Description                                                             |
| ------------ | ------------------- | ----------------------------------------------------------------------- |
| **`callId`** | <code>string</code> |                                                                         |
| **`output`** | <code>string</code> | Android: EARPIECE \| SPEAKER \| WIRED_HEADSET \| BLUETOOTH \| STREAMING |


#### CallFailedEvent

| Prop             | Type                |
| ---------------- | ------------------- |
| **`callerName`** | <code>string</code> |
| **`handle`**     | <code>string</code> |
| **`error`**      | <code>string</code> |


### Type Aliases


#### Record

Construct a type with a set of properties K of type T

<code>{ [P in K]: T; }</code>


#### SettableCallState

States an app may set with `setCallState`.

<code>'initializing' | 'ringing' | 'dialing' | 'active' | 'held'</code>


#### AudioRouteType

<code>'Phone' | 'Speaker' | 'Headset' | 'Bluetooth'</code>


#### CallState

Connection states reported by `callStateChanged` / `getActiveCalls`.

<code>'initializing' | 'new' | 'ringing' | 'dialing' | 'active' | 'held' | 'disconnected' | 'pulling'</code>


#### PermissionState

<code>'prompt' | 'prompt-with-rationale' | 'granted' | 'denied'</code>


#### CallKitPermissionType

- `microphone`: RECORD_AUDIO
- `notifications`: POST_NOTIFICATIONS (Android 13+)
- `phone`: READ_PHONE_STATE / CALL_PHONE — managed mode only; must be declared in your app manifest

<code>'microphone' | 'notifications' | 'phone'</code>


### Enums


#### CallEndReason

| Members                 | Value          |
| ----------------------- | -------------- |
| **`Failed`**            | <code>1</code> |
| **`RemoteEnded`**       | <code>2</code> |
| **`Unanswered`**        | <code>3</code> |
| **`AnsweredElsewhere`** | <code>4</code> |
| **`DeclinedElsewhere`** | <code>5</code> |
| **`Missed`**            | <code>6</code> |

</docgen-api>
