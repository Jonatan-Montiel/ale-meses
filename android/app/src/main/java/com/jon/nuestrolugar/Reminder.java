package com.jon.nuestrolugar;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Locale;
import java.util.TimeZone;

/** A las 9 pm: si hoy no entraste, recordatorio para no perder la racha. */
public class Reminder extends BroadcastReceiver {
    static String today() {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        f.setTimeZone(TimeZone.getTimeZone("America/Mexico_City"));
        return f.format(new java.util.Date());
    }

    static void schedule(Context c) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        Calendar t = Calendar.getInstance(TimeZone.getTimeZone("America/Mexico_City"));
        t.set(Calendar.HOUR_OF_DAY, 21); t.set(Calendar.MINUTE, 0); t.set(Calendar.SECOND, 0);
        if (t.getTimeInMillis() <= System.currentTimeMillis()) t.add(Calendar.DAY_OF_YEAR, 1);
        PendingIntent pi = PendingIntent.getBroadcast(c, 9, new Intent(c, Reminder.class), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        am.setInexactRepeating(AlarmManager.RTC_WAKEUP, t.getTimeInMillis(), AlarmManager.INTERVAL_DAY, pi);
    }

    @Override
    public void onReceive(Context c, Intent i) {
        SharedPreferences sp = c.getSharedPreferences("nl", Context.MODE_PRIVATE);
        Widget.refresh(c);
        if (!sp.getBoolean("linked", false)) return;
        if (today().equals(sp.getString("visto", ""))) return;
        int racha = sp.getInt("racha", 0);
        String msg = racha > 0 ? "Tu racha de " + racha + (racha == 1 ? " día" : " días") + " está en peligro 🔥 Entra hoy para no perderla"
                : "Hoy no has entrado a nuestro lugar 💗 Pásate un ratito";
        Notif.show(c, 2121, "🔥 Tu racha", msg);
    }
}
