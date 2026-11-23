package com.jon.nuestrolugar;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.widget.RemoteViews;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/** Widget: un botón «🥺 Te extraño» que le avisa al otro sin abrir la app. */
public class WidgetExtrano extends AppWidgetProvider {
    static final String TAP = "com.jon.nuestrolugar.EXTRANO";
    static String today() { SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd", Locale.US); f.setTimeZone(TimeZone.getTimeZone("America/Mexico_City")); return f.format(new Date()); }

    @Override public void onUpdate(Context c, AppWidgetManager m, int[] ids) { for (int id : ids) draw(c, m, id, null); }

    @Override public void onReceive(final Context c, Intent i) {
        super.onReceive(c, i);
        if (!TAP.equals(i.getAction())) return;
        SharedPreferences sp = c.getSharedPreferences("nl", Context.MODE_PRIVATE);
        String d = today(); int n = d.equals(sp.getString("ex-day", "")) ? sp.getInt("ex-n", 0) : 0;
        sp.edit().putString("ex-day", d).putInt("ex-n", n + 1).apply();
        Action.send(c, "extrano", null);
        final PendingResult pr = goAsync();
        refresh(c, "¡Enviado! 💗");
        new Thread(() -> { try { Thread.sleep(2500); } catch (Exception ignored) { } refresh(c, null); pr.finish(); }).start();
    }

    static void refresh(Context c, String msg) { AppWidgetManager m = AppWidgetManager.getInstance(c); for (int id : m.getAppWidgetIds(new ComponentName(c, WidgetExtrano.class))) draw(c, m, id, msg); }
    static void draw(Context c, AppWidgetManager m, int id, String msg) {
        RemoteViews v = new RemoteViews(c.getPackageName(), R.layout.widget_extrano);
        SharedPreferences sp = c.getSharedPreferences("nl", Context.MODE_PRIVATE);
        int n = today().equals(sp.getString("ex-day", "")) ? sp.getInt("ex-n", 0) : 0;
        v.setTextViewText(R.id.xSub, msg != null ? msg : n > 0 ? "hoy: " + n + (n == 1 ? " vez" : " veces") : "toca para decírselo");
        Intent i = new Intent(c, WidgetExtrano.class).setAction(TAP);
        v.setOnClickPendingIntent(R.id.xRoot, PendingIntent.getBroadcast(c, 22, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));
        m.updateAppWidget(id, v);
    }
}
