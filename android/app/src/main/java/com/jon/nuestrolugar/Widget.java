package com.jon.nuestrolugar;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.widget.RemoteViews;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Calendar;
import java.util.TimeZone;

/** Widget de la pantalla de inicio: días juntos y el mensaje del día. */
public class Widget extends AppWidgetProvider {
    @Override
    public void onUpdate(Context c, AppWidgetManager m, int[] ids) { for (int id : ids) draw(c, m, id); }

    static void refresh(Context c) {
        AppWidgetManager m = AppWidgetManager.getInstance(c);
        int[] ids = m.getAppWidgetIds(new ComponentName(c, Widget.class));
        for (int id : ids) draw(c, m, id);
    }

    static void draw(Context c, AppWidgetManager m, int id) {
        RemoteViews v = new RemoteViews(c.getPackageName(), R.layout.widget);
        SharedPreferences sp = c.getSharedPreferences("nl", Context.MODE_PRIVATE);
        try {
            JSONObject o = new JSONObject(sp.getString("widget", "{}"));
            String ini = o.optString("inicio", "");
            Calendar now = Calendar.getInstance(TimeZone.getTimeZone("America/Mexico_City"));
            if (ini.length() >= 10) {
                Calendar s = Calendar.getInstance(TimeZone.getTimeZone("America/Mexico_City"));
                s.set(Integer.parseInt(ini.substring(0, 4)), Integer.parseInt(ini.substring(5, 7)) - 1, Integer.parseInt(ini.substring(8, 10)), 0, 0, 0);
                long days = (now.getTimeInMillis() - s.getTimeInMillis()) / 86400000L;
                v.setTextViewText(R.id.wDias, days + (days == 1 ? " día juntos 💗" : " días juntos 💗"));
            }
        } catch (Exception ignored) { }
        Intent i = new Intent(c, MainActivity.class);
        i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        v.setOnClickPendingIntent(R.id.wRoot, PendingIntent.getActivity(c, 5, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));
        m.updateAppWidget(id, v);
    }
}
