package com.jon.nuestrolugar;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInstaller;
import android.os.Build;
import android.util.Log;
import android.widget.Toast;

/** Resultado de la instalación de PackageInstaller (la sesión la crea Updater.installApk). */
public class UpdRx extends BroadcastReceiver {
    static String motivo(int st, String msg) {
        String m = msg == null ? "" : msg.trim();
        String t;
        switch (st) {
            case PackageInstaller.STATUS_FAILURE_BLOCKED: t = "El celular bloqueó la instalación (revisa «Instalar apps desconocidas» o el control parental)."; break;
            case PackageInstaller.STATUS_FAILURE_CONFLICT: t = "Choca con la versión instalada (firma distinta)."; break;
            case PackageInstaller.STATUS_FAILURE_INCOMPATIBLE: t = "La versión nueva no es compatible con este celular."; break;
            case PackageInstaller.STATUS_FAILURE_INVALID: t = "El archivo descargado no es válido."; break;
            case PackageInstaller.STATUS_FAILURE_STORAGE: t = "No hay espacio suficiente en el celular."; break;
            default: t = "El instalador del sistema falló (código " + st + ")."; break;
        }
        return m.isEmpty() ? t : t + " " + (m.length() > 120 ? m.substring(0, 120) : m);
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onReceive(Context c, Intent i) {
        try {
            int st = i.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
            String msg = i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
            boolean silent = i.getBooleanExtra("silent", false);
            Log.i(Updater.T, "resultado de la instalación: " + st + " " + msg);
            Updater.resolved();
            SharedPreferences sp = Loc.sp(c);
            if (st == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                // El sistema pide confirmar (flujo con un toque, o no quiso instalar en silencio: la próxima vez ya no se intenta en silencio para esta versión).
                sp.edit().putInt("upd-n", 2).putInt("upd-pua", i.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1)).apply();
                Intent conf = Build.VERSION.SDK_INT >= 33 ? i.getParcelableExtra(Intent.EXTRA_INTENT, Intent.class) : (Intent) i.getParcelableExtra(Intent.EXTRA_INTENT);
                if (conf == null) {
                    Updater.closeProgress();
                    if (!silent) Updater.failFront(c, "El sistema no mandó la pantalla de confirmación.");
                    return;
                }
                Updater.rec(c, "esperando que confirmes en el instalador", null);
                // respaldo: la notificación se deja siempre (copia del intent: notifyConfirm le agrega FLAG_ACTIVITY_NEW_TASK)
                Updater.notifyConfirm(c, new Intent(conf));
                // con la app a la vista se abre desde la propia Activity (un receptor no tiene permiso fiable de abrir pantallas desde Android 10)
                final Activity a = MainActivity.cur;
                if (a != null) {
                    final Intent go = conf;
                    a.runOnUiThread(() -> {
                        Updater.closeProgress();
                        try { a.startActivity(go); }
                        catch (Throwable t) {
                            Log.i(Updater.T, "no se pudo abrir la confirmación: " + t);
                            try { Toast.makeText(a, "Toca la notificación «Hay una versión nueva» para instalarla.", Toast.LENGTH_LONG).show(); } catch (Throwable ignored) { }
                        }
                    });
                } else Updater.closeProgress();
            } else if (st == PackageInstaller.STATUS_SUCCESS) {
                Updater.rec(c, "instalada", null);
            } else if (Updater.samsungBlock(c, i, st, msg)) {
                Updater.onSamsungBlock(c, silent, "estado " + st + (msg == null || msg.isEmpty() ? "" : " · " + (msg.length() > 120 ? msg.substring(0, 120) : msg)));
            } else if (st == PackageInstaller.STATUS_FAILURE_ABORTED) {
                Updater.rec(c, "instalación cancelada", msg);
                if (silent) sp.edit().putInt("upd-n", 2).apply();
                Updater.closeProgress();
                final Activity a = MainActivity.cur;
                if (!silent && a != null) a.runOnUiThread(() -> { try { Toast.makeText(a, "Cancelaste la instalación. Cuando quieras, toca «Actualizar» otra vez.", Toast.LENGTH_LONG).show(); } catch (Throwable ignored) { } });
            } else {
                String m = motivo(st, msg);
                if (silent) { sp.edit().putInt("upd-n", 2).apply(); Updater.rec(c, "falló la instalación automática", m); } // la próxima vez que abra sale el aviso normal
                else Updater.failFront(c, m);
            }
        } catch (Throwable t) { Log.i(Updater.T, "UpdRx: " + t); }
    }
}
