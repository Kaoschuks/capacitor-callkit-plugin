import { Component, inject } from '@angular/core';

import { CallService } from '../call.service';

@Component({
  selector: 'app-home',
  templateUrl: 'home.page.html',
  styleUrls: ['home.page.scss'],
  standalone: false,
})
export class HomePage {
  readonly calls = inject(CallService);

  async copyToken(): Promise<void> {
    const token = this.calls.token();
    if (token) {
      await navigator.clipboard?.writeText(token);
    }
  }
}
