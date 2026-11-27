package com.jon.nuestrolugar;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInstaller;
import android.os.Build;
import android.util.Log;

/** Resultado de la instalación de PackageInstaller (la sesión la crea Updater.installApk). */
public class UpdRx extends BroadcastReceiver {
    @Override
    @SuppressWarnings("deprecation")
    public void onReceive(Context c, Intent i) {
        try {
            int st = i.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
            Log.i(Updater.T, "resultado de la instalación: " + st + " " + i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE));
            SharedPreferences sp = Loc.sp(c);
            if (st == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                // El sistema no quiso instalar en silencio: se cae al flujo de un toque (y la próxima vez ya no se intenta en silencio para esta versión).
                sp.edit().putInt("upd-n", 2).apply();
                Intent conf = Build.VERSION.SDK_INT >= 33 ? i.getParcelableExtra(Intent.EXTRA_INTENT, Intent.class) : (Intent) i.getParcelableExtra(Intent.EXTRA_INTENT);
                if (conf == null) return;
                if (Notif.appVisible) { conf.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); c.startActivity(conf); }
                else Updater.notifyConfirm(c, conf);
            } else if (st != PackageInstaller.STATUS_SUCCESS) {
                if (i.getBooleanExtra("silent", false)) sp.edit().putInt("upd-n", 2).apply(); // falló en silencio: la próxima vez que abra sale el aviso normal
            }
        } catch (Throwable t) { Log.i(Updater.T, "UpdRx: " + t); }
    }
}
