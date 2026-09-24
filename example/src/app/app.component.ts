import { Component, inject } from '@angular/core';
import { addIcons } from 'ionicons';
import {
  arrowUpCircleOutline,
  call,
  callOutline,
  close,
  copyOutline,
  lockClosedOutline,
  mic,
  micOff,
  pause,
  personCircleOutline,
  volumeHigh,
} from 'ionicons/icons';

import { CallService } from './call.service';

@Component({
  selector: 'app-root',
  templateUrl: 'app.component.html',
  styleUrls: ['app.component.scss'],
  standalone: false,
})
export class AppComponent {
  private readonly calls = inject(CallService);

  constructor() {
    addIcons({
      arrowUpCircleOutline,
      call,
      callOutline,
      close,
      copyOutline,
      lockClosedOutline,
      mic,
      micOff,
      pause,
      personCircleOutline,
      volumeHigh,
    });
    void this.calls.init();
  }
}
