package com.jon.nuestrolugar;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.net.Uri;
import android.os.Build;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Revisa si hay una versión nueva de la app (máximo cada 10 min) y ofrece actualizarla con un toque.
 * Primero pregunta a Vercel (llega aunque la compañía de celular no llegue a GitHub), luego a GitHub Pages y al final a la API de GitHub.
 */
class Updater {
    static final String T = "NLUpd";
    static final String[] SRC = {MainActivity.MIRROR + "dl/version.json", MainActivity.BASE + "dl/version.json"};
    static final String API = "https://api.github.com/repos/Jonatan-Montiel/ale-meses/releases/tags/apk";
    static volatile boolean showing = false;

    static String get(String u) throws Exception {
        HttpURLConnection h = (HttpURLConnection) new URL(u + (u.contains("?") ? "&" : "?") + "t=" + System.currentTimeMillis()).openConnection();
        h.setConnectTimeout(10000); h.setReadTimeout(10000); h.setUseCaches(false);
        h.setRequestProperty("Accept", "application/json"); h.setRequestProperty("User-Agent", "NuestroLugar");
        try {
            if (h.getResponseCode() != 200) throw new Exception("HTTP " + h.getResponseCode());
            StringBuilder sb = new StringBuilder();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(h.getInputStream(), "UTF-8"))) { String l; while ((l = r.readLine()) != null) sb.append(l); }
            return sb.toString();
        } finally { h.disconnect(); }
    }

    static void check(final Activity a) {
        final SharedPreferences sp = a.getSharedPreferences("nl", Activity.MODE_PRIVATE);
        if (System.currentTimeMillis() - sp.getLong("upd-check", 0) < 10 * 60 * 1000L) return; // máximo cada 10 min
        sp.edit().putLong("upd-check", System.currentTimeMillis()).apply();
        new Thread(() -> {
            String apk = MainActivity.IS_JON ? "nuestro-lugar-jon.apk" : "nuestro-lugar.apk";
            long remote = -1; String url = null, src = null;
            for (String s : SRC) {
                try { JSONObject o = new JSONObject(get(s)); remote = o.getLong("vc"); url = s.replace("version.json", apk); src = s; break; }
                catch (Exception e) { Log.i(T, "no respondió " + s + ": " + e); }
            }
            if (remote < 0) {
                try {
                    JSONObject o = new JSONObject(get(API));
                    Matcher m = Pattern.compile("vc=(\\d+)").matcher(o.optString("body", ""));
                    if (m.find()) remote = Long.parseLong(m.group(1));
                    JSONArray as = o.optJSONArray("assets");
                    for (int i = 0; as != null && i < as.length(); i++) if (apk.equals(as.getJSONObject(i).optString("name"))) url = as.getJSONObject(i).optString("browser_download_url");
                    src = API;
                } catch (Exception e) { Log.i(T, "no respondió la API: " + e); }
            }
            long local = 0;
            try { PackageInfo pi = a.getPackageManager().getPackageInfo(a.getPackageName(), 0); local = Build.VERSION.SDK_INT >= 28 ? pi.getLongVersionCode() : pi.versionCode; } catch (Exception ignored) { }
            Log.i(T, "remota=" + remote + " instalada=" + local + " fuente=" + src);
            if (remote < 0) { sp.edit().putLong("upd-check", 0).apply(); return; } // sin respuesta: vuelve a intentar la próxima vez que abra
            if (remote <= local || url == null) return;
            final String dl = url; final long rv = remote;
            a.runOnUiThread(() -> {
                if (a.isFinishing() || showing) return;
                showing = true;
                new AlertDialog.Builder(a)
                        .setTitle("Hay una versión nueva 💗")
                        .setMessage("Versión " + rv + " (tienes la " + ((MainActivity) a).vc() + "). Se descarga y la instalas encima; no se borra nada.")
                        .setPositiveButton("Actualizar", (d, w) -> { try { a.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(dl))); } catch (Exception ignored) { } })
                        .setNegativeButton("Después", null)
                        .setOnDismissListener(d -> showing = false)
                        .show();
            });
        }).start();
    }
}
