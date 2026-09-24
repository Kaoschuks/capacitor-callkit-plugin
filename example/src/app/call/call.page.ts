import { Component, computed, inject, input } from '@angular/core';

import { CallService } from '../call.service';

@Component({
  selector: 'app-call',
  templateUrl: 'call.page.html',
  styleUrls: ['call.page.scss'],
  standalone: false,
})
export class CallPage {
  readonly calls = inject(CallService);

  /** Route param, bound via withComponentInputBinding(). */
  readonly id = input.required<string>();
  readonly call = computed(() => this.calls.calls()[this.id()]);
}
