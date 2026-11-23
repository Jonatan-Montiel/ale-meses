package com.jon.nuestrolugar;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.net.Uri;
import android.os.Build;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Revisa si hay una versión nueva de la app y ofrece actualizarla con un toque. */
class Updater {
    static final String API = "https://api.github.com/repos/Jonatan-Montiel/ale-meses/releases/tags/apk";

    static void check(final Activity a) {
        final SharedPreferences sp = a.getSharedPreferences("nl", Activity.MODE_PRIVATE);
        long last = sp.getLong("upd-check", 0);
        if (System.currentTimeMillis() - last < 30 * 60 * 1000L) return; // máximo cada 30 min
        new Thread(() -> {
            try {
                HttpURLConnection h = (HttpURLConnection) new URL(API).openConnection();
                h.setConnectTimeout(15000);
                h.setReadTimeout(15000);
                h.setRequestProperty("Accept", "application/vnd.github+json");
                StringBuilder sb = new StringBuilder();
                try (BufferedReader r = new BufferedReader(new InputStreamReader(h.getInputStream(), "UTF-8"))) {
                    String line;
                    while ((line = r.readLine()) != null) sb.append(line);
                } finally { h.disconnect(); }
                JSONObject o = new JSONObject(sb.toString());
                Matcher m = Pattern.compile("vc=(\\d+)").matcher(o.optString("body", ""));
                if (!m.find()) return;
                long remote = Long.parseLong(m.group(1));
                PackageInfo pi = a.getPackageManager().getPackageInfo(a.getPackageName(), 0);
                long local = Build.VERSION.SDK_INT >= 28 ? pi.getLongVersionCode() : pi.versionCode;
                sp.edit().putLong("upd-check", System.currentTimeMillis()).apply();
                if (remote <= local) return;
                String want = MainActivity.IS_JON ? "nuestro-lugar-jon.apk" : "nuestro-lugar.apk";
                String url = null;
                JSONArray as = o.optJSONArray("assets");
                for (int i = 0; as != null && i < as.length(); i++) {
                    JSONObject x = as.getJSONObject(i);
                    if (want.equals(x.optString("name"))) url = x.optString("browser_download_url");
                }
                if (url == null) return;
                final String dl = url;
                a.runOnUiThread(() -> {
                    if (a.isFinishing()) return;
                    new AlertDialog.Builder(a)
                            .setTitle("Hay una versión nueva 💗")
                            .setMessage("Se descarga y la instalas encima; no se borra nada.")
                            .setPositiveButton("Actualizar", (d, w) -> {
                                try { a.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(dl))); } catch (Exception ignored) { }
                            })
                            .setNegativeButton("Después", null)
                            .show();
                });
            } catch (Exception ignored) { }
        }).start();
    }
}
