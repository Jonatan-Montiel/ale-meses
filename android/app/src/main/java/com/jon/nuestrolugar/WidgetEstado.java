package com.jon.nuestrolugar;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.widget.RemoteViews;

import org.json.JSONObject;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Widget: cómo está el otro hoy (carita de ánimo, termómetro y sello del día). */
public class WidgetEstado extends AppWidgetProvider {
    @Override public void onUpdate(Context c, AppWidgetManager m, int[] ids) { for (int id : ids) draw(c, m, id); }
    static void refresh(Context c) { AppWidgetManager m = AppWidgetManager.getInstance(c); for (int id : m.getAppWidgetIds(new ComponentName(c, WidgetEstado.class))) draw(c, m, id); }

    /** Lo actualiza también con las notificaciones que llegan, aunque la app esté cerrada. */
    static void fromNtfy(Context c, String tipo, String msg) {
        if (tipo == null || msg == null) return;
        String k = null, v = null; Matcher mm;
        if ("animo".equals(tipo) && (mm = Pattern.compile("se siente (.+?) hoy").matcher(msg)).find()) { k = "animo"; v = mm.group(1); }
        else if ("termo".equals(tipo) && (mm = Pattern.compile("te extraña (\\d+)%").matcher(msg)).find()) { k = "termo"; v = mm.group(1); }
        else if ("sello".equals(tipo) && (mm = Pattern.compile("sello de su día: (.+)$").matcher(msg)).find()) { k = "sello"; v = mm.group(1).trim(); }
        if (k == null) return;
        try {
            SharedPreferences sp = c.getSharedPreferences("nl", Context.MODE_PRIVATE);
            JSONObject o = new JSONObject(sp.getString("estado", "{}")); String d = WidgetExtrano.today();
            if (!d.equals(o.optString("d"))) o = new JSONObject().put("d", d);
            o.put(k, v); sp.edit().putString("estado", o.toString()).apply(); refresh(c);
        } catch (Exception ignored) { }
    }

    static void draw(Context c, AppWidgetManager m, int id) {
        RemoteViews v = new RemoteViews(c.getPackageName(), R.layout.widget_estado);
        SharedPreferences sp = c.getSharedPreferences("nl", Context.MODE_PRIVATE);
        String who = MainActivity.IS_JON ? "Ale" : "Jon";
        StringBuilder b = new StringBuilder();
        try {
            JSONObject o = new JSONObject(sp.getString("estado", "{}"));
            if (WidgetExtrano.today().equals(o.optString("d"))) {
                if (o.has("animo")) b.append("🌤️ se siente ").append(o.optString("animo")).append('\n');
                if (o.has("termo")) b.append("🌡️ te extraña ").append(o.optString("termo")).append("%\n");
                if (o.has("sello")) b.append("🗓️ su sello: ").append(o.optString("sello")).append('\n');
            }
        } catch (Exception ignored) { }
        v.setTextViewText(R.id.eT, who + " hoy 💗");
        v.setTextViewText(R.id.eB, b.length() > 0 ? b.toString().trim() : "todavía no dice cómo está 💭");
        Intent i = new Intent(c, MainActivity.class).putExtra("ir", "animo"); i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        v.setOnClickPendingIntent(R.id.eRoot, PendingIntent.getActivity(c, 23, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));
        m.updateAppWidget(id, v);
    }
}
