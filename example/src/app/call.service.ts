import { Injectable, computed, inject, signal } from '@angular/core';
import { Router } from '@angular/router';
import { Capacitor } from '@capacitor/core';
import type { PluginListenerHandle } from '@capacitor/core';
import { PushNotifications } from '@capacitor/push-notifications';
import { CallKit } from 'ionic-callkit';
import type { CallKitPermissionStatus } from 'ionic-callkit';

export type CallStatus = 'ringing' | 'dialing' | 'active' | 'ended';

export interface DemoCall {
  callId: string;
  name: string;
  handle: string;
  hasVideo: boolean;
  direction: 'incoming' | 'outgoing';
  status: CallStatus;
  muted: boolean;
  held: boolean;
  speaker: boolean;
}

/**
 * Wires every ionic-callkit event into app state. This is where a real app would
 * start / stop its WebRTC session (see `startMedia` / `stopMedia`).
 */
@Injectable({ providedIn: 'root' })
export class CallService {
  private readonly router = inject(Router);
  private readonly handles: PluginListenerHandle[] = [];
  private initialised = false;

  readonly platform = Capacitor.getPlatform();
  readonly token = signal<string | null>(null);
  readonly permissions = signal<CallKitPermissionStatus | null>(null);
  readonly fullScreenAllowed = signal<boolean | null>(null);
  readonly log = signal<string[]>([]);
  readonly calls = signal<Record<string, DemoCall>>({});
  readonly activeCalls = computed(() => Object.values(this.calls()).filter((c) => c.status !== 'ended'));

  async init(): Promise<void> {
    if (this.initialised) return;
    this.initialised = true;

    // Register listeners before anything else: events that happened while the app was
    // starting (e.g. the user tapped Answer on the lock screen) are delivered now.
    await this.listen('voipToken', ({ token }) => {
      this.token.set(token);
      // POST the token to your backend here.
    });

    await this.listen('incomingCall', (data) => {
      this.upsert(data.callId, {
        name: data.callerName,
        handle: data.handle,
        hasVideo: data.hasVideo,
        direction: 'incoming',
        status: 'ringing',
      });
      this.open(data.callId);
    });

    await this.listen('callAnswered', ({ callId }) => {
      this.upsert(callId, { status: 'active' });
      this.open(callId);
      this.startMedia(callId);
    });

    await this.listen('callStarted', (data) => {
      this.upsert(data.callId, {
        name: data.calleeName,
        handle: data.handle,
        direction: 'outgoing',
        status: 'dialing',
      });
      this.open(data.callId);
    });

    await this.listen('callEnded', ({ callId }) => this.finish(callId));
    await this.listen('callRejected', ({ callId }) => this.finish(callId));
    await this.listen('muted', ({ callId, muted }) => this.upsert(callId, { muted }));
    await this.listen('held', ({ callId, hold }) => this.upsert(callId, { held: hold }));
    await this.listen('speakerChanged', ({ callId, on }) => {
      for (const call of callId ? [callId] : this.activeCalls().map((c) => c.callId)) {
        this.upsert(call, { speaker: on });
      }
    });
    await this.listen('audioRouteChanged', () => undefined);
    await this.listen('showIncomingCallUi', () => undefined);
    await this.listen('incomingCallFailed', () => undefined);
    await this.listen('hasActiveCall', () => undefined);

    // Normal (non-call) FCM messages still reach @capacitor/push-notifications:
    // ionic-callkit forwards everything that isn't a call to it.
    if (this.platform !== 'web') {
      await PushNotifications.addListener('pushNotificationReceived', (n) =>
        this.write(`push-notifications: ${JSON.stringify(n.data)}`),
      );
    }

    try {
      await CallKit.setup({
        appName: 'CallKit Demo',
        supportsVideo: true,
        android: { selfManaged: true },
      });
      await CallKit.setReachable();
      this.write('setup ok');
    } catch (e) {
      this.write(`setup failed: ${this.message(e)}`);
    }

    await this.refreshPermissions();
    await this.registerToken();
  }

  async registerToken(): Promise<void> {
    try {
      const { token } = await CallKit.registerVoipToken();
      this.token.set(token);
    } catch (e) {
      this.write(`registerVoipToken: ${this.message(e)}`);
    }
  }

  async refreshPermissions(): Promise<void> {
    try {
      this.permissions.set(await CallKit.checkPermissions());
      this.fullScreenAllowed.set((await CallKit.canUseFullScreenIntent()).value);
    } catch (e) {
      this.write(`checkPermissions: ${this.message(e)}`);
    }
  }

  async requestPermissions(): Promise<void> {
    try {
      this.permissions.set(await CallKit.requestPermissions());
    } catch (e) {
      this.write(`requestPermissions: ${this.message(e)}`);
    }
  }

  async openFullScreenSettings(): Promise<void> {
    await this.run('openFullScreenIntentSettings', () => CallKit.openFullScreenIntentSettings());
  }

  /** Rings through the native UI without a push — lock the phone during the delay to test the lock screen. */
  async simulateIncoming(delayMs = 0): Promise<void> {
    const callId = crypto.randomUUID();
    this.write(`incoming call ${callId.slice(0, 8)} in ${delayMs / 1000}s`);
    setTimeout(() => {
      void this.run('displayIncomingCall', () =>
        CallKit.displayIncomingCall({
          callId,
          callerName: 'Alice',
          handle: 'alice@example.com',
          payload: { source: 'demo' },
        }),
      );
    }, delayMs);
  }

  /** Places an outgoing call; the "remote" answers after 3 s. */
  async startOutgoing(): Promise<void> {
    const callId = crypto.randomUUID();
    await this.run('startCall', () => CallKit.startCall({ callId, calleeName: 'Bob', handle: 'bob@example.com' }));
    setTimeout(() => {
      if (this.calls()[callId]?.status === 'dialing') {
        void this.run('setCallActive', () => CallKit.setCallActive({ callId }));
        this.upsert(callId, { status: 'active' });
        this.startMedia(callId);
      }
    }, 3000);
  }

  answer(callId: string) {
    return this.run('answerCall', () => CallKit.answerCall({ callId }));
  }

  decline(callId: string) {
    return this.run('rejectCall', () => CallKit.rejectCall({ callId }));
  }

  hangUp(callId: string) {
    return this.run('endCall', () => CallKit.endCall({ callId }));
  }

  toggleMute(call: DemoCall) {
    return this.run('setMuted', () => CallKit.setMuted({ callId: call.callId, muted: !call.muted }));
  }

  toggleSpeaker(call: DemoCall) {
    return this.run('setSpeaker', () => CallKit.setSpeaker({ callId: call.callId, on: !call.speaker }));
  }

  toggleHold(call: DemoCall) {
    return this.run('setOnHold', () => CallKit.setOnHold({ callId: call.callId, hold: !call.held }));
  }

  clearLog(): void {
    this.log.set([]);
  }

  // ─── Media hooks ─────────────────────────────────────────────────────────

  private startMedia(callId: string): void {
    // Start your WebRTC peer connection for `callId` here.
    this.write(`media: start ${callId.slice(0, 8)}`);
  }

  private stopMedia(callId: string): void {
    // Close your WebRTC peer connection for `callId` here.
    this.write(`media: stop ${callId.slice(0, 8)}`);
  }

  // ─── Internals ───────────────────────────────────────────────────────────

  private async listen(eventName: string, handler: (data: any) => void): Promise<void> {
    const handle = await (CallKit.addListener as any)(eventName, (data: any) => {
      this.write(`${eventName} ${data ? JSON.stringify(data) : ''}`);
      handler(data ?? {});
    });
    this.handles.push(handle);
  }

  private finish(callId: string): void {
    if (this.calls()[callId]?.status === 'active') {
      this.stopMedia(callId);
    }
    this.upsert(callId, { status: 'ended' });
    if (this.router.url.startsWith(`/call/${callId}`)) {
      void this.router.navigateByUrl('/home', { replaceUrl: true });
    }
  }

  private open(callId: string): void {
    if (!this.router.url.startsWith(`/call/${callId}`)) {
      void this.router.navigate(['/call', callId]);
    }
  }

  private upsert(callId: string, patch: Partial<DemoCall>): void {
    this.calls.update((calls) => {
      const existing: DemoCall = calls[callId] ?? {
        callId,
        name: 'Unknown',
        handle: '',
        hasVideo: false,
        direction: 'incoming',
        status: 'ringing',
        muted: false,
        held: false,
        speaker: false,
      };
      return { ...calls, [callId]: { ...existing, ...patch } };
    });
  }

  private async run(label: string, fn: () => Promise<unknown>): Promise<void> {
    try {
      await fn();
    } catch (e) {
      this.write(`${label} failed: ${this.message(e)}`);
    }
  }

  private write(line: string): void {
    const time = new Date().toLocaleTimeString();
    this.log.update((log) => [`${time}  ${line}`, ...log].slice(0, 200));
  }

  private message(e: unknown): string {
    return e instanceof Error ? e.message : String(e);
  }
}
