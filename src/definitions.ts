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

  /** List available audio outputs. Android only. */
  getAudioRoutes(): Promise<{ routes: AudioRoute[] }>;

  /** Select an audio output. Android only. */
  setAudioRoute(options: { callId: string; route: AudioRouteType }): Promise<void>;

  // ─── Android specifics ─────────────────────────────────────────────────────

  /**
   * Tell the ConnectionService whether the app can take calls
   * (e.g. `false` while logged out) so the user doesn't get stuck in the native UI.
   * Android only; no-op elsewhere.
   */
  setAvailable(options: { available: boolean }): Promise<void>;

  /** Tell the plugin the JS layer is ready to handle calls. Android only. */
  setReachable(): Promise<void>;

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

  /** Bring the app to the foreground (e.g. after answering from the lock screen). Android only. */
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
  /** Name shown in the system call UI / calling account. Defaults to the app label. */
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
  /** Allow more than one simultaneous call. Default: true. */
  canMakeMultipleCalls?: boolean;
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
