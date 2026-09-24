import { WebPlugin } from '@capacitor/core';

import type {
  ActiveCall,
  AudioRoute,
  CallIdOptions,
  CallKitPermissionStatus,
  CallKitPlugin,
  CallKitPermissionType,
  IncomingCallOptions,
  OutgoingCallOptions,
  ReportEndCallOptions,
  UpdateDisplayOptions,
} from './definitions';

/**
 * Browsers have no CallKit / ConnectionService. The web implementation keeps track of
 * calls and emits the same events as native, so the app can drive its own HTML call
 * screen (e.g. an ion-modal) with identical listener code.
 */
export class CallKitWeb extends WebPlugin implements CallKitPlugin {
  private calls = new Map<string, ActiveCall>();

  async setup(): Promise<void> {
    // Nothing to configure on web.
  }

  async registerVoipToken(): Promise<{ token: string }> {
    throw this.unimplemented('VoIP push tokens are not available on web.');
  }

  async unregisterVoipToken(): Promise<void> {
    throw this.unimplemented('VoIP push tokens are not available on web.');
  }

  async displayIncomingCall(options: IncomingCallOptions): Promise<void> {
    const { callId, callerName } = options;
    const handle = options.handle ?? callerName;
    this.calls.set(callId, { callId, callerName, handle });
    this.notifyListeners('incomingCall', {
      callId,
      callerName,
      handle,
      hasVideo: options.hasVideo ?? false,
      payload: options.payload,
    });
  }

  async answerCall(options: CallIdOptions): Promise<void> {
    this.requireCall(options.callId, 'answerCall');
    this.notifyListeners('callAnswered', { callId: options.callId, hasVideo: false });
  }

  async rejectCall(options: CallIdOptions): Promise<void> {
    this.requireCall(options.callId, 'rejectCall');
    this.calls.delete(options.callId);
    this.notifyListeners('callRejected', { callId: options.callId });
  }

  async startCall(options: OutgoingCallOptions): Promise<void> {
    const { callId, calleeName } = options;
    const handle = options.handle ?? calleeName;
    this.calls.set(callId, { callId, callerName: calleeName, handle });
    this.notifyListeners('callStarted', { callId, calleeName, handle });
  }

  async setCallActive(options: CallIdOptions): Promise<void> {
    this.requireCall(options.callId, 'setCallActive');
  }

  async endCall(options: CallIdOptions): Promise<void> {
    this.requireCall(options.callId, 'endCall');
    this.calls.delete(options.callId);
    this.notifyListeners('callEnded', { callId: options.callId });
  }

  async endAllCalls(): Promise<void> {
    for (const callId of [...this.calls.keys()]) {
      this.calls.delete(callId);
      this.notifyListeners('callEnded', { callId });
    }
  }

  async reportEndCall(options: ReportEndCallOptions): Promise<void> {
    this.requireCall(options.callId, 'reportEndCall');
    this.calls.delete(options.callId);
  }

  async updateDisplay(options: UpdateDisplayOptions): Promise<void> {
    const call = this.requireCall(options.callId, 'updateDisplay');
    call.callerName = options.callerName;
    call.handle = options.handle ?? options.callerName;
  }

  async setMuted(options: { callId: string; muted: boolean }): Promise<void> {
    this.requireCall(options.callId, 'setMuted');
    this.notifyListeners('muted', { callId: options.callId, muted: options.muted });
  }

  async setSpeaker(options: { on: boolean; callId?: string }): Promise<void> {
    this.notifyListeners('speakerChanged', { callId: options.callId, on: options.on });
  }

  async setOnHold(options: { callId: string; hold: boolean }): Promise<void> {
    this.requireCall(options.callId, 'setOnHold');
    this.notifyListeners('held', { callId: options.callId, hold: options.hold });
  }

  async sendDTMF(options: { callId: string; digits: string }): Promise<void> {
    this.requireCall(options.callId, 'sendDTMF');
    this.notifyListeners('dtmf', { callId: options.callId, digits: options.digits });
  }

  async getAudioRoutes(): Promise<{ routes: AudioRoute[] }> {
    throw this.unimplemented('Audio routes are not available on web.');
  }

  async setAudioRoute(): Promise<void> {
    throw this.unimplemented('Audio routes are not available on web.');
  }

  async setAvailable(): Promise<void> {
    // Android only.
  }

  async setReachable(): Promise<void> {
    // Android only.
  }

  async hasPhoneAccount(): Promise<{ value: boolean }> {
    return { value: true };
  }

  async openPhoneAccountSettings(): Promise<void> {
    throw this.unimplemented('Not available on web.');
  }

  async canUseFullScreenIntent(): Promise<{ value: boolean }> {
    return { value: false };
  }

  async openFullScreenIntentSettings(): Promise<void> {
    throw this.unimplemented('Not available on web.');
  }

  async backToForeground(): Promise<void> {
    if (typeof window !== 'undefined') {
      window.focus();
    }
  }

  async getActiveCalls(): Promise<{ calls: ActiveCall[] }> {
    return { calls: [...this.calls.values()].map((call) => ({ ...call })) };
  }

  async checkPermissions(): Promise<CallKitPermissionStatus> {
    return {
      microphone: await this.microphoneState(),
      notifications: this.notificationState(),
      phone: 'granted',
    };
  }

  async requestPermissions(options?: { permissions: CallKitPermissionType[] }): Promise<CallKitPermissionStatus> {
    const wanted = options?.permissions ?? ['microphone', 'notifications'];

    if (wanted.includes('microphone') && typeof navigator !== 'undefined' && navigator.mediaDevices?.getUserMedia) {
      try {
        const stream = await navigator.mediaDevices.getUserMedia({ audio: true });
        stream.getTracks().forEach((track) => track.stop());
      } catch {
        // Denied — reflected by checkPermissions below.
      }
    }
    if (wanted.includes('notifications') && typeof Notification !== 'undefined') {
      await Notification.requestPermission();
    }
    return this.checkPermissions();
  }

  private requireCall(callId: string, method: string): ActiveCall {
    const call = this.calls.get(callId);
    if (!call) {
      throw new Error(`${method} ignored because no call found, callId: ${callId}`);
    }
    return call;
  }

  private async microphoneState(): Promise<CallKitPermissionStatus['microphone']> {
    if (typeof navigator === 'undefined' || !navigator.permissions?.query) {
      return 'prompt';
    }
    try {
      const status = await navigator.permissions.query({ name: 'microphone' as PermissionName });
      return status.state;
    } catch {
      return 'prompt';
    }
  }

  private notificationState(): CallKitPermissionStatus['notifications'] {
    if (typeof Notification === 'undefined') {
      return 'denied';
    }
    return Notification.permission === 'default' ? 'prompt' : Notification.permission;
  }
}
