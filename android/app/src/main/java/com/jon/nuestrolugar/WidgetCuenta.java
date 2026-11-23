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

import java.util.ArrayList;
import java.util.Calendar;
import java.util.TimeZone;

/** Widget: cuenta regresiva a lo que sigue (el día de cada mes, cumpleaños y «Lo que viene»). */
public class WidgetCuenta extends AppWidgetProvider {
    @Override public void onUpdate(Context c, AppWidgetManager m, int[] ids) { for (int id : ids) draw(c, m, id); }
    static void refresh(Context c) { AppWidgetManager m = AppWidgetManager.getInstance(c); for (int id : m.getAppWidgetIds(new ComponentName(c, WidgetCuenta.class))) draw(c, m, id); }

    static final TimeZone MX = TimeZone.getTimeZone("America/Mexico_City");
    static Calendar day(int y, int mo, int d) { Calendar c = Calendar.getInstance(MX); c.clear(); c.set(y, mo, d, 0, 0, 0); return c; }
    static Calendar today() { Calendar n = Calendar.getInstance(MX); return day(n.get(Calendar.YEAR), n.get(Calendar.MONTH), n.get(Calendar.DAY_OF_MONTH)); }
    static Calendar parse(String s) { try { return day(Integer.parseInt(s.substring(0, 4)), Integer.parseInt(s.substring(5, 7)) - 1, Integer.parseInt(s.substring(8, 10))); } catch (Exception e) { return null; } }
    static long days(Calendar a, Calendar b) { return Math.round((b.getTimeInMillis() - a.getTimeInMillis()) / 86400000.0); }

    static ArrayList<Object[]> upcoming(Context c) {
        ArrayList<Object[]> L = new ArrayList<>(); Calendar t = today();
        try {
            JSONObject o = new JSONObject(c.getSharedPreferences("nl", Context.MODE_PRIVATE).getString("cuenta", "{}"));
            Calendar ini = parse(o.optString("inicio", ""));
            if (ini != null) {
                for (int k = 1; k < 1200; k++) {
                    Calendar x = (Calendar) ini.clone(); x.add(Calendar.MONTH, k);
                    if (days(t, x) >= 0) { L.add(new Object[]{x, k % 12 == 0 ? (k == 12 ? "nuestro primer año 💞" : "nuestros " + (k / 12) + " años 💞") : "nuestros " + k + (k == 1 ? " mes 💗" : " meses 💗")}); break; }
                }
            }
            String[][] B = {{o.optString("cumple"), "el cumple de " + o.optString("ella", "Ale") + " 🎂"}, {o.optString("cumpleYo"), "el cumple de " + o.optString("yo", "Jon") + " 🎂"}};
            for (String[] b : B) { Calendar x = parse(b[0]); if (x == null) continue; x.set(Calendar.YEAR, t.get(Calendar.YEAR)); if (days(t, x) < 0) x.add(Calendar.YEAR, 1); L.add(new Object[]{x, b[1]}); }
            JSONArray ev = o.optJSONArray("eventos");
            for (int i = 0; ev != null && i < ev.length(); i++) { JSONObject e = ev.getJSONObject(i); Calendar x = parse(e.optString("f")); if (x != null && days(t, x) >= 0) L.add(new Object[]{x, e.optString("t")}); }
        } catch (Exception ignored) { }
        L.sort((a, b) -> Long.compare(((Calendar) a[0]).getTimeInMillis(), ((Calendar) b[0]).getTimeInMillis()));
        return L;
    }

    static void draw(Context c, AppWidgetManager m, int id) {
        RemoteViews v = new RemoteViews(c.getPackageName(), R.layout.widget_cuenta);
        ArrayList<Object[]> L = upcoming(c); Calendar t = today();
        if (L.isEmpty()) { v.setTextViewText(R.id.cN, "⏳"); v.setTextViewText(R.id.cU, ""); v.setTextViewText(R.id.cT, "Abre la app para ver lo que sigue"); v.setTextViewText(R.id.cNext, ""); }
        else {
            long d = days(t, (Calendar) L.get(0)[0]);
            v.setTextViewText(R.id.cN, d == 0 ? "¡Hoy!" : String.valueOf(d));
            v.setTextViewText(R.id.cU, d == 0 ? "" : d == 1 ? "día para" : "días para");
            v.setTextViewText(R.id.cT, (String) L.get(0)[1]);
            if (L.size() > 1) { long d2 = days(t, (Calendar) L.get(1)[0]); v.setTextViewText(R.id.cNext, "después: " + L.get(1)[1] + " · " + (d2 == 0 ? "hoy" : d2 + (d2 == 1 ? " día" : " días"))); }
            else v.setTextViewText(R.id.cNext, "");
        }
        Intent i = new Intent(c, MainActivity.class).putExtra("ir", "viene"); i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        v.setOnClickPendingIntent(R.id.cRoot, PendingIntent.getActivity(c, 24, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));
        m.updateAppWidget(id, v);
    }
}
