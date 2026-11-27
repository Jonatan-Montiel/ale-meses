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
        if (Intent.ACTION_MY_PACKAGE_REPLACED.equals(i.getAction())) {
            // Recién actualizada: borra el APK descargado y avisa ya la versión nueva (para que Jon la vea en «Versiones»)
            final Context app = c.getApplicationContext();
            final PendingResult pr = goAsync();
            new Thread(() -> {
                try { Updater.cleanup(app); Loc.sp(app).edit().putLong("beat", 0).apply(); Beat.send(app); } catch (Throwable ignored) { }
                finally { pr.finish(); }
            }).start();
        }
    }
}
