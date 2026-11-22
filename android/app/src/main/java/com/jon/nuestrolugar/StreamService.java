package com.jon.nuestrolugar;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;

/** Conexión abierta con ntfy para que los avisos lleguen al instante (como la app ntfy). */
public class StreamService extends Service {
    static final String CH_BG = "conexion";
    static final int FG_ID = 77;
    private volatile boolean running = false;
    private volatile HttpURLConnection conn;
    private Thread worker;

    static void start(Context c) {
        SharedPreferences sp = c.getSharedPreferences("nl", Context.MODE_PRIVATE);
        if (sp.getString("topic", "").isEmpty()) return;
        Intent i = new Intent(c, StreamService.class);
        try {
            if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i);
            else c.startService(i);
        } catch (Exception ignored) { }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(CH_BG, "Conexión de avisos", NotificationManager.IMPORTANCE_MIN);
            ch.setDescription("Mantiene la app lista para recibir los avisos al instante");
            ch.setShowBadge(false);
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(ch);
        }
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 1, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, CH_BG) : new Notification.Builder(this);
        b.setSmallIcon(R.drawable.ic_stat).setContentTitle("Nuestro lugar 💗").setContentText("Lista para recibir tus avisos")
                .setOngoing(true).setContentIntent(pi);
        if (Build.VERSION.SDK_INT < 26) b.setPriority(Notification.PRIORITY_MIN);
        Notification n = b.build();
        try {
            if (Build.VERSION.SDK_INT >= 34) startForeground(FG_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING);
            else startForeground(FG_ID, n);
        } catch (Exception e) { stopSelf(); }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (!running) {
            running = true;
            worker = new Thread(this::loop, "ntfy-stream");
            worker.start();
        }
        return START_STICKY;
    }

    private void loop() {
        long wait = 5000;
        while (running) {
            SharedPreferences sp = getSharedPreferences("nl", MODE_PRIVATE);
            String topic = sp.getString("topic", "");
            if (topic.isEmpty()) { stopSelf(); return; }
            String since = sp.getString("since", "");
            if (since.isEmpty()) since = String.valueOf(System.currentTimeMillis() / 1000);
            try {
                URL u = new URL("https://ntfy.sh/" + URLEncoder.encode(topic, "UTF-8") + "/json?since=" + URLEncoder.encode(since, "UTF-8"));
                HttpURLConnection h = (HttpURLConnection) u.openConnection();
                conn = h;
                h.setConnectTimeout(20000);
                h.setReadTimeout(120000); // ntfy manda un "keepalive" cada ~45 s
                try (BufferedReader r = new BufferedReader(new InputStreamReader(h.getInputStream(), "UTF-8"))) {
                    wait = 5000;
                    String line;
                    while (running && (line = r.readLine()) != null) {
                        if (line.trim().isEmpty()) continue;
                        JSONObject o = new JSONObject(line);
                        if (!"message".equals(o.optString("event"))) continue;
                        Notif.fromNtfy(this, o);
                    }
                } finally {
                    h.disconnect();
                }
            } catch (Exception ignored) { }
            if (!running) break;
            try { Thread.sleep(wait); } catch (InterruptedException e) { break; }
            wait = Math.min(wait * 2, 120000);
        }
    }

    @Override
    public void onDestroy() {
        running = false;
        try { if (conn != null) conn.disconnect(); } catch (Exception ignored) { }
        if (worker != null) worker.interrupt();
        super.onDestroy();
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        // Si cierran la app desde recientes, la conexión sigue viva.
        start(getApplicationContext());
        super.onTaskRemoved(rootIntent);
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}
