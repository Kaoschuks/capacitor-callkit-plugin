import { NgModule } from '@angular/core';
import { CommonModule } from '@angular/common';
import { IonicModule } from '@ionic/angular/lazy';
import { CallPage } from './call.page';

import { CallPageRoutingModule } from './call-routing.module';

@NgModule({
  imports: [CommonModule, IonicModule, CallPageRoutingModule],
  declarations: [CallPage],
})
export class CallPageModule {}
