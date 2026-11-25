package com.jon.nuestrolugar;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;

/** Revisa cada ~15 minutos si Jon le dejó algo y muestra la notificación. */
public class PollJob extends JobService {
    static final int ID = 1203;

    static void schedule(Context c) {
        JobScheduler js = c.getSystemService(JobScheduler.class);
        if (js == null || js.getPendingJob(ID) != null) return;
        JobInfo ji = new JobInfo.Builder(ID, new ComponentName(c, PollJob.class))
                .setPeriodic(15 * 60 * 1000L)
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPersisted(true)
                .build();
        try { js.schedule(ji); } catch (Exception ignored) { }
    }

    @Override
    public boolean onStartJob(final JobParameters p) {
        new Thread(() -> {
            try { poll(getApplicationContext()); } catch (Exception ignored) { }
            try { Beat.send(getApplicationContext()); } catch (Exception ignored) { }
            try { WidgetCuenta.refresh(getApplicationContext()); WidgetEstado.refresh(getApplicationContext()); } catch (Exception ignored) { }
            jobFinished(p, false);
        }).start();
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters p) { return true; }

    static void poll(Context c) throws Exception {
        SharedPreferences sp = c.getSharedPreferences("nl", Context.MODE_PRIVATE);
        String topic = sp.getString("topic", "");
        if (topic.isEmpty()) return;
        String since = sp.getString("since", "");
        if (since.isEmpty()) {
            sp.edit().putString("since", String.valueOf(System.currentTimeMillis() / 1000)).apply();
            return;
        }
        URL u = new URL("https://ntfy.sh/" + URLEncoder.encode(topic, "UTF-8") + "/json?poll=1&since=" + URLEncoder.encode(since, "UTF-8"));
        HttpURLConnection h = (HttpURLConnection) u.openConnection();
        h.setConnectTimeout(15000);
        h.setReadTimeout(25000);
        try (BufferedReader r = new BufferedReader(new InputStreamReader(h.getInputStream(), "UTF-8"))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (line.trim().isEmpty()) continue;
                JSONObject o = new JSONObject(line);
                if (!"message".equals(o.optString("event"))) continue;
                Notif.fromNtfy(c, o);
            }
        } finally {
            h.disconnect();
        }
        StreamService.start(c);
    }
}
