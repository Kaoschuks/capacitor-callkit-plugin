import assert from 'node:assert/strict';
import { beforeEach, test } from 'node:test';

import { CallKitWeb } from '../dist/esm/web.js';

let plugin;
let events;

beforeEach(async () => {
  plugin = new CallKitWeb();
  events = [];
  for (const name of [
    'incomingCall',
    'callAnswered',
    'callStarted',
    'callEnded',
    'callRejected',
    'muted',
    'held',
    'dtmf',
    'speakerChanged',
  ]) {
    await plugin.addListener(name, (data) => events.push([name, data]));
  }
});

const incoming = { callId: 'abc', callerName: 'Alice' };

test('displayIncomingCall emits incomingCall with defaults', async () => {
  await plugin.displayIncomingCall({ ...incoming, payload: { room: 'r1' } });
  assert.deepEqual(events, [
    ['incomingCall', { callId: 'abc', callerName: 'Alice', handle: 'Alice', hasVideo: false, payload: { room: 'r1' } }],
  ]);
  assert.deepEqual((await plugin.getActiveCalls()).calls, [
    { callId: 'abc', callerName: 'Alice', handle: 'Alice', state: 'ringing' },
  ]);
});

test('answer then end emits callAnswered and callEnded and forgets the call', async () => {
  await plugin.displayIncomingCall(incoming);
  await plugin.answerCall({ callId: 'abc' });
  await plugin.endCall({ callId: 'abc' });
  assert.deepEqual(
    events.slice(1).map(([name]) => name),
    ['callAnswered', 'callEnded'],
  );
  assert.deepEqual((await plugin.getActiveCalls()).calls, []);
});

test('rejectCall emits callRejected', async () => {
  await plugin.displayIncomingCall(incoming);
  await plugin.rejectCall({ callId: 'abc' });
  assert.deepEqual(events[1], ['callRejected', { callId: 'abc' }]);
});

test('startCall emits callStarted', async () => {
  await plugin.startCall({ callId: 'out', calleeName: 'Bob', handle: '+1' });
  assert.deepEqual(events, [['callStarted', { callId: 'out', calleeName: 'Bob', handle: '+1' }]]);
});

test('in-call controls emit their events', async () => {
  await plugin.displayIncomingCall(incoming);
  await plugin.setMuted({ callId: 'abc', muted: true });
  await plugin.setOnHold({ callId: 'abc', hold: true });
  await plugin.sendDTMF({ callId: 'abc', digits: '12' });
  await plugin.setSpeaker({ on: true });
  assert.deepEqual(events.slice(1), [
    ['muted', { callId: 'abc', muted: true }],
    ['held', { callId: 'abc', hold: true }],
    ['dtmf', { callId: 'abc', digits: '12' }],
    ['speakerChanged', { callId: undefined, on: true }],
  ]);
});

test('reportEndCall forgets the call without emitting callEnded', async () => {
  await plugin.displayIncomingCall(incoming);
  await plugin.reportEndCall({ callId: 'abc', reason: 2 });
  assert.equal(events.length, 1);
  assert.deepEqual((await plugin.getActiveCalls()).calls, []);
});

test('endAllCalls ends every call', async () => {
  await plugin.displayIncomingCall(incoming);
  await plugin.startCall({ callId: 'out', calleeName: 'Bob' });
  await plugin.endAllCalls();
  assert.deepEqual(
    events.filter(([name]) => name === 'callEnded').map(([, data]) => data.callId),
    ['abc', 'out'],
  );
});

test('methods on an unknown call reject', async () => {
  await assert.rejects(plugin.answerCall({ callId: 'nope' }), /no call found/);
  await assert.rejects(plugin.endCall({ callId: 'nope' }), /no call found/);
});

test('push token methods are unimplemented on web', async () => {
  await assert.rejects(plugin.registerVoipToken(), /not available on web/);
});

test('state changes are reported and tracked', async () => {
  const states = [];
  await plugin.addListener('callStateChanged', (d) => states.push(d.state));
  await plugin.startCall({ callId: 'out', calleeName: 'Bob' });
  await plugin.setCallState({ callId: 'out', state: 'active' });
  await plugin.setOnHold({ callId: 'out', hold: true });
  await plugin.setOnHold({ callId: 'out', hold: false });
  assert.deepEqual(states, ['active', 'held', 'active']);
  assert.equal((await plugin.getActiveCalls()).calls[0].state, 'active');
});

test('canMakeMultipleCalls=false refuses a second call as busy', async () => {
  const failed = [];
  await plugin.addListener('incomingCallFailed', (d) => failed.push(d));
  await plugin.setCanMakeMultipleCalls({ allow: false });
  await plugin.displayIncomingCall(incoming);
  await assert.rejects(plugin.displayIncomingCall({ callId: 'second', callerName: 'Eve' }), /busy/);
  await assert.rejects(plugin.startCall({ callId: 'out', calleeName: 'Bob' }), /busy/);
  assert.deepEqual(failed, [{ callId: 'second', callerName: 'Eve', handle: 'Eve', error: 'busy' }]);

  await plugin.setCanMakeMultipleCalls({ allow: true });
  await plugin.displayIncomingCall({ callId: 'second', callerName: 'Eve' });
  assert.equal((await plugin.getActiveCalls()).calls.length, 2);
});

test('initial events are empty on web', async () => {
  assert.deepEqual(await plugin.getInitialEvents(), { events: [] });
  await plugin.clearInitialEvents();
});

test('a duplicate call id is rejected', async () => {
  await plugin.displayIncomingCall(incoming);
  await assert.rejects(plugin.displayIncomingCall(incoming), /already exists/);
  assert.equal(events.filter(([name]) => name === 'incomingCall').length, 1);
});
