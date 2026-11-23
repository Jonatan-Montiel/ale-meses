package com.jon.nuestrolugar;

import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

import org.json.JSONObject;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/** Botones de las notificaciones: «❤️ Me encanta» y «🥺 Te extraño», sin abrir la app. */
public class Action extends BroadcastReceiver {
    @Override
    public void onReceive(final Context c, Intent i) {
        final String act = i.getStringExtra("act");
        int nid = i.getIntExtra("nid", 0);
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        if (nm != null) nm.cancel(nid);
        final SharedPreferences sp = c.getSharedPreferences("nl", Context.MODE_PRIVATE);
        final String other = sp.getString("other", ""), me = sp.getString("myName", MainActivity.IS_JON ? "Jon" : "Ale");
        if ("extrano".equals(act)) sp.edit().putInt("pendExtrano", sp.getInt("pendExtrano", 0) + 1).apply();
        if (other.isEmpty()) return;
        final PendingResult pr = goAsync();
        new Thread(() -> {
            try {
                JSONObject o = new JSONObject();
                o.put("topic", other);
                if ("extrano".equals(act)) { o.put("title", "🥺 " + me + " te extraña"); o.put("message", "🥺 " + me + " te extraña"); }
                else { o.put("title", "❤️ A " + me + " le encantó"); o.put("message", "❤️ A " + me + " le encantó lo que le mandaste"); }
                o.put("priority", 4);
                HttpURLConnection h = (HttpURLConnection) new URL("https://ntfy.sh/").openConnection();
                h.setConnectTimeout(15000); h.setReadTimeout(15000); h.setDoOutput(true); h.setRequestMethod("POST");
                try (OutputStream os = h.getOutputStream()) { os.write(o.toString().getBytes("UTF-8")); }
                h.getResponseCode(); h.disconnect();
            } catch (Exception ignored) { }
            pr.finish();
        }).start();
    }
}
