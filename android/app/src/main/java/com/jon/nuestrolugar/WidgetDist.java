package com.jon.nuestrolugar;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.widget.RemoteViews;

/** Widget: dos puntitos con la inicial de cada uno, un lazo y la distancia; si están juntos, un corazón. */
public class WidgetDist extends AppWidgetProvider {
    @Override public void onUpdate(final Context c, AppWidgetManager m, int[] ids) {
        for (int id : ids) draw(c, m, id);
        final PendingResult pr = goAsync();
        new Thread(() -> { try { Loc.sync(c.getApplicationContext()); } catch (Exception ignored) { } pr.finish(); }).start();
    }
    static void refresh(Context c) { AppWidgetManager m = AppWidgetManager.getInstance(c); for (int id : m.getAppWidgetIds(new ComponentName(c, WidgetDist.class))) draw(c, m, id); }
    static void draw(Context c, AppWidgetManager m, int id) {
        RemoteViews v = new RemoteViews(c.getPackageName(), R.layout.widget_dist);
        String[] st = Loc.state(c);
        boolean jun = "juntos".equals(st[0]);
        v.setTextViewText(R.id.dLine, jun ? "─── ❤️ ───" : "off".equals(st[0]) ? "· · · 🤍 · · ·" : "~~~ 💗 ~~~");
        v.setTextViewText(R.id.dTxt, st[1]);
        Intent i = new Intent(c, MainActivity.class).putExtra("ir", "distancia"); i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        v.setOnClickPendingIntent(R.id.dRoot, PendingIntent.getActivity(c, 21, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));
        m.updateAppWidget(id, v);
    }
}
