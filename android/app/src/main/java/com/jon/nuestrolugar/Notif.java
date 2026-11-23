package com.jon.nuestrolugar;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.os.Build;
import android.content.SharedPreferences;
import org.json.JSONObject;

class Notif {
    static final String CH = "avisos";

    static void channel(Context c) {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(CH, "Avisos de Jon", NotificationManager.IMPORTANCE_HIGH);
            ch.setDescription("Notitas, mensajes de voz y todo lo que te deje en nuestro lugar");
            ch.enableLights(true);
            ch.setLightColor(Color.parseColor("#e85d84"));
            NotificationManager nm = c.getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(ch);
        }
    }

    static volatile boolean appVisible = false;

    /** Muestra un aviso de ntfy una sola vez (aunque llegue por la conexión y por la revisión periódica). */
    static synchronized void fromNtfy(Context c, JSONObject o) {
        SharedPreferences sp = c.getSharedPreferences("nl", Context.MODE_PRIVATE);
        String id = o.optString("id", "");
        String seen = sp.getString("seen", "");
        if (!id.isEmpty() && ("," + seen + ",").contains("," + id + ",")) return;
        String nseen = (id + "," + seen);
        if (nseen.length() > 600) nseen = nseen.substring(0, 600);
        sp.edit().putString("seen", nseen).putString("since", id.isEmpty() ? String.valueOf(System.currentTimeMillis() / 1000) : id).apply();
        if (appVisible) return; // ya lo está viendo en la página
        String title = o.optString("title", "");
        if (title.isEmpty()) title = "💗 Nuestro lugar";
        show(c, id.hashCode(), title, o.optString("message", ""), true);
    }

    static void show(Context c, int id, String title, String msg) { show(c, id, title, msg, false); }

    static void show(Context c, int id, String title, String msg, boolean actions) {
        channel(c);
        Intent i = new Intent(c, MainActivity.class);
        i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pi = PendingIntent.getActivity(c, 0, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(c, CH) : new Notification.Builder(c);
        b.setSmallIcon(R.drawable.ic_stat)
                .setColor(Color.parseColor("#e85d84"))
                .setContentTitle(title)
                .setContentText(msg)
                .setStyle(new Notification.BigTextStyle().bigText(msg))
                .setAutoCancel(true)
                .setContentIntent(pi);
        if (Build.VERSION.SDK_INT < 26) b.setPriority(Notification.PRIORITY_HIGH).setDefaults(Notification.DEFAULT_ALL);
        if (actions) {
            String[][] A = {{"love", "❤️ Me encanta"}, {"extrano", "🥺 Te extraño"}};
            for (int k = 0; k < A.length; k++) {
                Intent ai = new Intent(c, Action.class).putExtra("act", A[k][0]).putExtra("nid", id);
                PendingIntent ap = PendingIntent.getBroadcast(c, id * 3 + k, ai, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
                b.addAction(new Notification.Action.Builder(null, A[k][1], ap).build());
            }
        }
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        if (nm != null) nm.notify(id, b.build());
    }
}
