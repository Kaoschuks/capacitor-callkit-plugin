import type { PermissionState, PluginListenerHandle } from '@capacitor/core';

export interface CallKitPlugin {
  // ─── Setup / tokens ────────────────────────────────────────────────────────

  /**
   * Configure the plugin. Call once at app start, before any other call method.
   *
   * Android: registers the PhoneAccount with Telecom and stores the settings so
   * that a push received while the app is killed can still ring.
   */
  setup(options: CallSetupOptions): Promise<void>;

  /**
   * Get the push token used to ring this device.
   * iOS: PushKit VoIP token. Android: FCM registration token.
   * Also emitted as the `voipToken` event (including on token refresh).
   */
  registerVoipToken(): Promise<{ token: string }>;

  /** Stop receiving call pushes (Android: deletes the FCM token). */
  unregisterVoipToken(): Promise<void>;

  // ─── Incoming ──────────────────────────────────────────────────────────────

  /**
   * Show the native incoming call UI. Normally triggered natively by a push;
   * call it yourself when the call arrives over your own signalling channel.
   */
  displayIncomingCall(options: IncomingCallOptions): Promise<void>;

  /** Answer a ringing call from your own UI. Emits `callAnswered`. */
  answerCall(options: CallIdOptions): Promise<void>;

  /** Decline a ringing call. Emits `callRejected`. */
  rejectCall(options: CallIdOptions): Promise<void>;

  // ─── Outgoing ──────────────────────────────────────────────────────────────

  /** Start an outgoing call. Emits `callStarted`. */
  startCall(options: OutgoingCallOptions): Promise<void>;

  /** Mark an outgoing call as connected (remote side picked up). */
  setCallActive(options: CallIdOptions): Promise<void>;

  /**
   * Report the call's connection state to the system (callkeep's `setConnectionState`),
   * e.g. `dialing` while your signalling connects, `active` once media flows.
   * Emits `callStateChanged`.
   */
  setCallState(options: { callId: string; state: SettableCallState }): Promise<void>;

  /** Hang up a call locally. Emits `callEnded`. */
  endCall(options: CallIdOptions): Promise<void>;

  /** Hang up every call. */
  endAllCalls(): Promise<void>;

  /**
   * Report that a call ended for a reason other than the local user hanging up
   * (remote hang-up, missed, answered elsewhere…). Does not emit `callEnded`.
   */
  reportEndCall(options: ReportEndCallOptions): Promise<void>;

  /** Update the name / handle shown in the system call UI. */
  updateDisplay(options: UpdateDisplayOptions): Promise<void>;

  // ─── In-call controls ──────────────────────────────────────────────────────

  /**
   * Update the mute state shown in the system UI. Emits `muted`.
   * The plugin does not touch your media tracks — mute them in your WebRTC layer.
   */
  setMuted(options: { callId: string; muted: boolean }): Promise<void>;

  /** Route audio to the loudspeaker or back to the earpiece. Emits `speakerChanged`. */
  setSpeaker(options: { on: boolean; callId?: string }): Promise<void>;

  /** Put a call on hold / resume it. Emits `held`. */
  setOnHold(options: { callId: string; hold: boolean }): Promise<void>;

  /** Play DTMF digits on a call. Emits `dtmf`. */
  sendDTMF(options: { callId: string; digits: string }): Promise<void>;

  /** List available audio routes. Android and iOS. */
  getAudioRoutes(): Promise<{ routes: AudioRoute[] }>;

  /** Select an audio route. Android and iOS. */
  setAudioRoute(options: { callId: string; route: AudioRouteType }): Promise<void>;

  // ─── Android specifics ─────────────────────────────────────────────────────

  /**
   * Tell the ConnectionService whether the app can take calls
   * (e.g. `false` while logged out) so the user doesn't get stuck in the native UI.
   * Android only; no-op elsewhere.
   */
  setAvailable(options: { available: boolean }): Promise<void>;

  /**
   * Tell the plugin the JS layer is ready to handle calls. Android only.
   * Adding a `callAnswered` listener counts as reachable too. Answered calls are ended if
   * the app isn't reachable within `android.answerReachabilityTimeout`.
   */
  setReachable(): Promise<void>;

  /**
   * Allow or refuse concurrent calls. When `false`, a new incoming call while another is in
   * progress is refused and `incomingCallFailed` is emitted with `error: 'busy'`, and
   * `startCall` rejects. Persisted, so pushes handled while the app is killed respect it.
   */
  setCanMakeMultipleCalls(options: { allow: boolean }): Promise<void>;
  // On iOS a refused push-driven call is briefly reported and ended (iOS requires every VoIP
  // push to report a call), so it may appear as a missed call.

  /**
   * Events that happened before JS attached (e.g. the call was answered from the lock
   * screen during a cold start), oldest first. They are also delivered to listeners; use
   * this to route directly at startup. Events with `restored: true` come from a previous
   * app process that died before delivering them — their calls no longer exist.
   * Kept until `clearInitialEvents()`. Android and iOS (empty on web).
   */
  getInitialEvents(): Promise<{ events: InitialEvent[] }>;

  /** Forget the events returned by `getInitialEvents()`. */
  clearInitialEvents(): Promise<void>;

  /**
   * Whether the calling account is registered and enabled.
   * Always `true` in self-managed mode. Android only.
   */
  hasPhoneAccount(): Promise<{ value: boolean }>;

  /** Open the system "Calling accounts" screen (managed mode). Android only. */
  openPhoneAccountSettings(): Promise<void>;

  /**
   * Whether the app may show a full-screen incoming call over the lock screen.
   * Android 14+ may revoke this. Android only.
   */
  canUseFullScreenIntent(): Promise<{ value: boolean }>;

  /** Open the settings page to grant full-screen intents (Android 14+). */
  openFullScreenIntentSettings(): Promise<void>;

  /**
   * Bring the app to the foreground (e.g. after answering from the lock screen). Android only;
   * rejects as unimplemented on iOS, which doesn't allow it.
   */
  backToForeground(): Promise<void>;

  /** Currently tracked calls. */
  getActiveCalls(): Promise<{ calls: ActiveCall[] }>;

  // ─── Permissions ───────────────────────────────────────────────────────────

  checkPermissions(): Promise<CallKitPermissionStatus>;
  requestPermissions(options?: { permissions: CallKitPermissionType[] }): Promise<CallKitPermissionStatus>;

  // ─── Events ────────────────────────────────────────────────────────────────

  addListener(eventName: 'voipToken', listenerFunc: (data: { token: string }) => void): Promise<PluginListenerHandle>;
  addListener(
    eventName: 'incomingCall',
    listenerFunc: (data: IncomingCallEvent) => void,
  ): Promise<PluginListenerHandle>;
  addListener(
    eventName: 'callAnswered',
    listenerFunc: (data: CallAnsweredEvent) => void,
  ): Promise<PluginListenerHandle>;
  addListener(eventName: 'callStarted', listenerFunc: (data: CallStartedEvent) => void): Promise<PluginListenerHandle>;
  addListener(eventName: 'callEnded', listenerFunc: (data: CallEndedEvent) => void): Promise<PluginListenerHandle>;
  addListener(eventName: 'callRejected', listenerFunc: (data: CallEvent) => void): Promise<PluginListenerHandle>;
  addListener(eventName: 'muted', listenerFunc: (data: MutedEvent) => void): Promise<PluginListenerHandle>;
  addListener(eventName: 'held', listenerFunc: (data: HeldEvent) => void): Promise<PluginListenerHandle>;
  addListener(
    eventName: 'callStateChanged',
    listenerFunc: (data: CallStateChangedEvent) => void,
  ): Promise<PluginListenerHandle>;
  addListener(eventName: 'dtmf', listenerFunc: (data: DtmfEvent) => void): Promise<PluginListenerHandle>;
  addListener(
    eventName: 'speakerChanged',
    listenerFunc: (data: SpeakerChangedEvent) => void,
  ): Promise<PluginListenerHandle>;
  addListener(
    eventName: 'audioRouteChanged',
    listenerFunc: (data: AudioRouteChangedEvent) => void,
  ): Promise<PluginListenerHandle>;
  addListener(eventName: 'audioSessionActivated', listenerFunc: () => void): Promise<PluginListenerHandle>;
  addListener(
    eventName: 'showIncomingCallUi',
    listenerFunc: (data: IncomingCallEvent) => void,
  ): Promise<PluginListenerHandle>;
  addListener(eventName: 'silenceIncomingCall', listenerFunc: (data: CallEvent) => void): Promise<PluginListenerHandle>;
  addListener(
    eventName: 'incomingCallFailed',
    listenerFunc: (data: CallFailedEvent) => void,
  ): Promise<PluginListenerHandle>;
  addListener(eventName: 'checkReachability', listenerFunc: () => void): Promise<PluginListenerHandle>;
  addListener(eventName: 'hasActiveCall', listenerFunc: () => void): Promise<PluginListenerHandle>;

  removeAllListeners(): Promise<void>;
}

// ─── Options ─────────────────────────────────────────────────────────────────

export interface CallSetupOptions {
  /**
   * Name of the calling account on Android. Defaults to the app label.
   * iOS always shows the app's display name (CallKit no longer accepts a custom one).
   */
  appName?: string;
  /** Default: false */
  supportsVideo?: boolean;
  /** Android drawable / iOS asset name used as the calling account / CallKit icon. */
  imageName?: string;
  android?: AndroidSetupOptions;
  ios?: IOSSetupOptions;
}

export interface AndroidSetupOptions {
  /**
   * Self-managed ConnectionService: the app owns the call UI and no
   * "enable calling account" step is needed. Default: true.
   * Set to false for managed mode (system dialer UI, needs phone permissions).
   */
  selfManaged?: boolean;
  /**
   * Show the plugin's native incoming-call notification (full-screen intent
   * + Answer/Decline) in self-managed mode. Default: true.
   */
  showIncomingCallNotification?: boolean;
  /**
   * What the full-screen incoming-call alert opens over the lock screen:
   * - `native` (default): the plugin's built-in call screen (caller + Answer / Decline). Shows
   *   instantly and reliably over a secure lock screen; Answer then opens your app.
   * - `app`: your app's own call page (listen to `incomingCall`). Needs the WebView to load first.
   * Restyle / translate the native screen by overriding the `ionic_callkit_*` resources.
   */
  incomingCallScreen?: 'native' | 'app';
  /**
   * Ringtone file in `android/app/src/main/res/raw/` (name with or without extension).
   * Default: the device ringtone. The plugin rings itself; silent / vibrate modes are respected.
   */
  ringtoneSound?: string;
  /** Vibrate while ringing (unless the phone is on silent). Default: true. */
  vibrate?: boolean;
  /** Small icon (drawable or mipmap resource name) for call notifications. */
  notificationIcon?: string;
  /** Channel name for incoming calls. Default: "Incoming calls". */
  incomingCallChannelName?: string;
  /**
   * Ongoing-call foreground service notification (keeps the mic alive in the
   * background). Enabled by default; pass `false` to disable.
   */
  foregroundService?:
    | false
    | {
        channelId?: string;
        channelName?: string;
        notificationTitle?: string;
        notificationIcon?: string;
      };
  /** Allow more than one simultaneous call. Default: true. See `setCanMakeMultipleCalls`. */
  canMakeMultipleCalls?: boolean;
  /**
   * End an answered call if the app hasn't called `setReachable()` (or added a
   * `callAnswered` listener) within this many milliseconds — e.g. the WebView failed to
   * start after answering from the lock screen. Default: 10000. 0 disables.
   */
  answerReachabilityTimeout?: number;
  /**
   * If set, an incoming call is dropped when the JS layer hasn't called
   * `setReachable()` within this many milliseconds.
   */
  displayCallReachabilityTimeout?: number;
}

export interface IOSSetupOptions {
  /** Ringtone sound file in the app bundle. */
  ringtoneSound?: string;
  /** Default: 1 */
  maximumCallGroups?: number;
  /** Default: 1 */
  maximumCallsPerCallGroup?: number;
  /** Default: false */
  includesCallsInRecents?: boolean;
  /** CallKit handle type for `handle` values. Default: `generic`. */
  handleType?: 'generic' | 'number' | 'email';
  /**
   * Audio session CallKit activates for the call (callkeep's `audioSession` setting).
   * Set `autoConfigure: false` if your media SDK configures AVAudioSession itself.
   */
  audioSession?: {
    /** Default: true */
    autoConfigure?: boolean;
    /** Raw `AVAudioSession.CategoryOptions`. Default: allowBluetooth | allowBluetoothA2DP. */
    categoryOptions?: number;
    /** `AVAudioSession.Mode` raw value, e.g. `AVAudioSessionModeVoiceChat`. Default: `AVAudioSessionModeDefault`. */
    mode?: string;
  };
}

export interface CallIdOptions {
  callId: string;
}

export interface IncomingCallOptions {
  callId: string;
  callerName: string;
  /** Phone number / user id shown as the caller address. Defaults to `callerName`. */
  handle?: string;
  hasVideo?: boolean;
  /** Extra data passed back in the `incomingCall` event. */
  payload?: Record<string, string>;
}

export interface OutgoingCallOptions {
  callId: string;
  calleeName: string;
  /** Phone number / user id of the callee. Defaults to `calleeName`. */
  handle?: string;
  hasVideo?: boolean;
}

export enum CallEndReason {
  Failed = 1,
  RemoteEnded = 2,
  Unanswered = 3,
  AnsweredElsewhere = 4,
  DeclinedElsewhere = 5,
  Missed = 6,
}

export interface ReportEndCallOptions {
  callId: string;
  reason: CallEndReason;
}

export interface UpdateDisplayOptions {
  callId: string;
  callerName: string;
  handle?: string;
}

export type AudioRouteType = 'Phone' | 'Speaker' | 'Headset' | 'Bluetooth';

export interface AudioRoute {
  name: string;
  type: AudioRouteType;
  selected?: boolean;
}

export interface ActiveCall {
  callId: string;
  callerName?: string;
  handle?: string;
  state?: CallState;
}

/** Connection states reported by `callStateChanged` / `getActiveCalls`. */
export type CallState = 'initializing' | 'new' | 'ringing' | 'dialing' | 'active' | 'held' | 'disconnected' | 'pulling';

/** States an app may set with `setCallState`. */
export type SettableCallState = 'initializing' | 'ringing' | 'dialing' | 'active' | 'held';

export interface InitialEvent {
  /** Event name, e.g. `incomingCall`, `callAnswered`. */
  name: string;
  data: Record<string, unknown>;
  /** Epoch milliseconds. */
  timestamp: number;
  /** From a previous app process that died before delivering it; the call is gone. */
  restored: boolean;
}

// ─── Events ──────────────────────────────────────────────────────────────────

export interface CallEvent {
  callId: string;
}

export interface CallEndedEvent extends CallEvent {
  /**
   * Set when the call was ended remotely by a `call_ended` push
   * (`Missed` if it was never answered, otherwise `RemoteEnded`).
   * Absent when the user hung up.
   */
  reason?: CallEndReason;
}

export interface IncomingCallEvent extends CallEvent {
  callerName: string;
  handle: string;
  hasVideo: boolean;
  payload?: Record<string, string>;
}

export interface CallAnsweredEvent extends CallEvent {
  hasVideo: boolean;
}

export interface CallStartedEvent extends CallEvent {
  calleeName: string;
  handle: string;
}

export interface CallFailedEvent extends CallEvent {
  callerName?: string;
  handle?: string;
  error?: string;
}

export interface MutedEvent extends CallEvent {
  muted: boolean;
}

export interface CallStateChangedEvent extends CallEvent {
  state: CallState;
}

export interface HeldEvent extends CallEvent {
  hold: boolean;
}

export interface DtmfEvent extends CallEvent {
  digits: string;
}

export interface SpeakerChangedEvent {
  callId?: string;
  on: boolean;
}

export interface AudioRouteChangedEvent {
  callId?: string;
  /** Android: EARPIECE | SPEAKER | WIRED_HEADSET | BLUETOOTH | STREAMING */
  output: string;
}

// ─── Permissions ─────────────────────────────────────────────────────────────

/**
 * - `microphone`: RECORD_AUDIO
 * - `notifications`: POST_NOTIFICATIONS (Android 13+)
 * - `phone`: READ_PHONE_STATE / CALL_PHONE — managed mode only; must be declared in your app manifest
 */
export type CallKitPermissionType = 'microphone' | 'notifications' | 'phone';

export interface CallKitPermissionStatus {
  microphone: PermissionState;
  notifications: PermissionState;
  phone: PermissionState;
}
