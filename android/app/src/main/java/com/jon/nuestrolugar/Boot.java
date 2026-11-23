package com.jon.nuestrolugar;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Al prender el celular o actualizar la app, vuelve a conectar los avisos. */
public class Boot extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent i) {
        PollJob.schedule(c);
        StreamService.start(c);
        Reminder.schedule(c);
        Widget.refresh(c);
    }
}
