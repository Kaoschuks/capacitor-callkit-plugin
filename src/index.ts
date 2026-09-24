import { registerPlugin } from '@capacitor/core';

import type { CallKitPlugin } from './definitions';

const CallKit = registerPlugin<CallKitPlugin>('CallKit', {
  web: () => import('./web').then((m) => new m.CallKitWeb()),
});

export * from './definitions';
export { CallKit };
