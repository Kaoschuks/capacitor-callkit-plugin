import { WebPlugin } from '@capacitor/core';

import type { CallKitPlugin } from './definitions';

export class CallKitWeb extends WebPlugin implements CallKitPlugin {
  async echo(options: { value: string }): Promise<{ value: string }> {
    console.log('ECHO', options);
    return options;
  }
}
