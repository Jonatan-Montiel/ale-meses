package com.jon.nuestrolugar;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.graphics.Color;
import android.os.Build;
import android.content.SharedPreferences;
import org.json.JSONObject;

class Notif {
    // v30: canal nuevo con el tono propio (el sonido de un canal no se puede cambiar después de crearlo)
    static final String CH = "avisos2", CH_ZB = "zumbido";
    static final long[] VIB_ZB = {0, 400, 150, 400, 150, 700};

    static Uri snd(Context c, String n) { return Uri.parse("android.resource://" + c.getPackageName() + "/raw/" + n); }

    static void channel(Context c) {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = c.getSystemService(NotificationManager.class);
            if (nm == null) return;
            android.media.AudioAttributes aa = new android.media.AudioAttributes.Builder()
                .setUsage(android.media.AudioAttributes.USAGE_NOTIFICATION).setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION).build();
            String quien = MainActivity.IS_JON ? "Ale" : "Jon";
            NotificationChannel ch = new NotificationChannel(CH, "Avisos de " + quien, NotificationManager.IMPORTANCE_HIGH);
            ch.setDescription("Notitas, mensajes de voz y todo lo que te deje en nuestro lugar");
            ch.enableLights(true);
            ch.setLightColor(Color.parseColor("#e85d84"));
            ch.setSound(snd(c, "tono"), aa);
            nm.createNotificationChannel(ch);
            NotificationChannel z = new NotificationChannel(CH_ZB, "Zumbidos 📳", NotificationManager.IMPORTANCE_HIGH);
            z.setDescription("Cuando " + quien + " te manda un zumbido");
            z.enableVibration(true);
            z.setVibrationPattern(VIB_ZB);
            z.enableLights(true);
            z.setLightColor(Color.parseColor("#e85d84"));
            z.setSound(snd(c, "zumbido"), aa);
            nm.createNotificationChannel(z);
            try { nm.deleteNotificationChannel("avisos"); } catch (Exception ignored) { }
        }
    }

    /** Vibra fuerte (para el zumbido cuando la app está abierta). */
    static void vibrar(Context c) {
        try {
            android.os.Vibrator v;
            if (Build.VERSION.SDK_INT >= 31) v = ((android.os.VibratorManager) c.getSystemService(Context.VIBRATOR_MANAGER_SERVICE)).getDefaultVibrator();
            else v = (android.os.Vibrator) c.getSystemService(Context.VIBRATOR_SERVICE);
            if (v == null) return;
            if (Build.VERSION.SDK_INT >= 26) v.vibrate(android.os.VibrationEffect.createWaveform(VIB_ZB, -1)); else v.vibrate(VIB_ZB, -1);
        } catch (Exception ignored) { }
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
        String msg = o.optString("message", ""), title = o.optString("title", ""), tp = null;
        try { String ck = o.optString("click", ""); if (!ck.isEmpty()) tp = Uri.parse(ck).getQueryParameter("ir"); } catch (Exception ignored) { }
        // aviso cifrado con la llave de la página (nl1:…): ntfy.sh no ve el texto
        if (msg.startsWith("nl1:")) {
            try { JSONObject p = new JSONObject(Loc.dec(Loc.key(c), msg.substring(4))); title = p.optString("t", ""); msg = p.optString("m", ""); String r = p.optString("ir", ""); tp = r.isEmpty() ? null : r; }
            catch (Exception e) { title = ""; msg = "Tienes algo nuevo 💗"; tp = null; }
        }
        boolean zb = "zumbido".equals(tp);
        if (!zb) try { WidgetEstado.fromNtfy(c, tp, msg); } catch (Exception ignored) { }
        if (zb && appVisible) { vibrar(c); MainActivity.zumbidoEnPagina(); return; }
        if (appVisible) return; // ya lo está viendo en la página
        if (title.isEmpty()) title = "💗 Nuestro lugar";
        // a qué sección llevar al tocarla (…?ir=tipo, o dentro del aviso cifrado)
        String ir = (tp != null && tp.matches("[a-z]{2,20}")) ? "n:" + tp + "\n" + msg : null;
        if (zb) { showZb(c, id.hashCode(), title, msg, ir); return; }
        show(c, id.hashCode(), title, msg, true, ir);
    }

    static void showZb(Context c, int id, String title, String msg, String ir) {
        channel(c);
        Intent i = new Intent(c, MainActivity.class);
        i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        if (ir != null) i.putExtra("ir", ir);
        PendingIntent pi = PendingIntent.getActivity(c, id, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(c, CH_ZB) : new Notification.Builder(c);
        b.setSmallIcon(R.drawable.ic_stat).setColor(Color.parseColor("#e85d84")).setContentTitle(title).setContentText(msg)
            .setAutoCancel(true).setContentIntent(pi).setCategory(Notification.CATEGORY_MESSAGE);
        if (Build.VERSION.SDK_INT < 26) b.setPriority(Notification.PRIORITY_HIGH).setVibrate(VIB_ZB).setSound(snd(c, "zumbido"));
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        if (nm != null) nm.notify(id, b.build());
    }

    static void show(Context c, int id, String title, String msg) { show(c, id, title, msg, false); }

    static void show(Context c, int id, String title, String msg, boolean actions) { show(c, id, title, msg, actions, null); }

    static void show(Context c, int id, String title, String msg, boolean actions, String ir) {
        channel(c);
        Intent i = new Intent(c, MainActivity.class);
        i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        if (ir != null) i.putExtra("ir", ir);
        PendingIntent pi = PendingIntent.getActivity(c, ir != null ? id : 0, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(c, CH) : new Notification.Builder(c);
        b.setSmallIcon(R.drawable.ic_stat)
                .setColor(Color.parseColor("#e85d84"))
                .setContentTitle(title)
                .setContentText(msg)
                .setStyle(new Notification.BigTextStyle().bigText(msg))
                .setAutoCancel(true)
                .setContentIntent(pi);
        if (Build.VERSION.SDK_INT < 26) b.setPriority(Notification.PRIORITY_HIGH).setDefaults(Notification.DEFAULT_VIBRATE | Notification.DEFAULT_LIGHTS).setSound(snd(c, "tono"));
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
