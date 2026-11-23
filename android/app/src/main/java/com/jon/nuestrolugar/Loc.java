package com.jon.nuestrolugar;

import android.Manifest;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationManager;
import android.os.Build;
import android.util.Base64;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Distancia entre los dos para el widget. Cada celular manda su ubicación CIFRADA (con la misma llave de la página)
 * a un canal privado; el otro la descarga, calcula la distancia y solo muestra los kilómetros. Se puede apagar.
 */
class Loc {
    static SharedPreferences sp(Context c) { return c.getSharedPreferences("nl", Context.MODE_PRIVATE); }
    static String me() { return MainActivity.IS_JON ? "yo" : "ella"; }
    static String other() { return MainActivity.IS_JON ? "ella" : "yo"; }
    static boolean on(Context c) { return sp(c).getBoolean("loc-on", false); }
    static boolean perm(Context c) { return c.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED || c.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED; }
    static boolean bg(Context c) { return Build.VERSION.SDK_INT < 29 || c.checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED; }

    static byte[] key(Context c) { String k = sp(c).getString("k", ""); if (k.isEmpty()) return null; try { return Base64.decode(k, Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING); } catch (Exception e) { return null; } }
    static String topic(Context c, String who) throws Exception {
        String k = sp(c).getString("k", "");
        byte[] h = MessageDigest.getInstance("SHA-256").digest(("nl-loc:" + k + ":" + who).getBytes("UTF-8"));
        StringBuilder b = new StringBuilder("nlloc"); for (int i = 0; i < 12; i++) b.append(String.format("%02x", h[i])); return b.toString();
    }
    static String enc(byte[] k, String s) throws Exception {
        byte[] iv = new byte[12]; new SecureRandom().nextBytes(iv);
        Cipher ci = Cipher.getInstance("AES/GCM/NoPadding"); ci.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(k, "AES"), new GCMParameterSpec(128, iv));
        byte[] ct = ci.doFinal(s.getBytes("UTF-8")); byte[] o = new byte[12 + ct.length]; System.arraycopy(iv, 0, o, 0, 12); System.arraycopy(ct, 0, o, 12, ct.length);
        return Base64.encodeToString(o, Base64.NO_WRAP);
    }
    static String dec(byte[] k, String b64) throws Exception {
        byte[] a = Base64.decode(b64, Base64.NO_WRAP);
        Cipher ci = Cipher.getInstance("AES/GCM/NoPadding"); ci.init(Cipher.DECRYPT_MODE, new SecretKeySpec(k, "AES"), new GCMParameterSpec(128, a, 0, 12));
        return new String(ci.doFinal(a, 12, a.length - 12), "UTF-8");
    }

    static Location current(Context c) {
        if (!perm(c)) return null;
        LocationManager lm = c.getSystemService(LocationManager.class); if (lm == null) return null;
        Location best = null;
        for (String p : lm.getProviders(true)) {
            try { Location l = lm.getLastKnownLocation(p); if (l != null && (best == null || l.getTime() > best.getTime())) best = l; } catch (SecurityException ignored) { }
        }
        if ((best == null || System.currentTimeMillis() - best.getTime() > 20 * 60 * 1000L) && Build.VERSION.SDK_INT >= 30) {
            final Location[] got = {null}; final CountDownLatch done = new CountDownLatch(1);
            String prov = lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER) ? LocationManager.NETWORK_PROVIDER : LocationManager.GPS_PROVIDER;
            try { lm.getCurrentLocation(prov, null, c.getMainExecutor(), l -> { got[0] = l; done.countDown(); }); done.await(25, TimeUnit.SECONDS); } catch (Exception ignored) { }
            if (got[0] != null) best = got[0];
        }
        return best;
    }

    static void post(String topic, String msg) throws Exception {
        JSONObject o = new JSONObject(); o.put("topic", topic); o.put("message", msg); o.put("priority", 1);
        HttpURLConnection h = (HttpURLConnection) new URL("https://ntfy.sh/").openConnection();
        h.setConnectTimeout(15000); h.setReadTimeout(15000); h.setDoOutput(true); h.setRequestMethod("POST");
        try (OutputStream os = h.getOutputStream()) { os.write(o.toString().getBytes("UTF-8")); }
        h.getResponseCode(); h.disconnect();
    }

    /** Manda la mía (si está prendido) y trae la del otro. Correr fuera del hilo principal. */
    static synchronized void sync(Context c) {
        byte[] k = key(c); if (k == null) return;
        SharedPreferences sp = sp(c);
        try {
            if (on(c) && perm(c)) {
                Location l = current(c);
                if (l != null) {
                    JSONObject j = new JSONObject(); j.put("la", l.getLatitude()); j.put("lo", l.getLongitude()); j.put("ac", l.getAccuracy()); j.put("t", l.getTime());
                    sp.edit().putString("myloc", j.toString()).putBoolean("loc-off-sent", false).apply();
                    long last = sp.getLong("loc-sent", 0);
                    if (System.currentTimeMillis() - last > 10 * 60 * 1000L) { post(topic(c, me()), enc(k, j.toString())); sp.edit().putLong("loc-sent", System.currentTimeMillis()).apply(); }
                }
            } else if (!sp.getBoolean("loc-off-sent", false)) {
                post(topic(c, me()), enc(k, "{\"off\":1,\"t\":" + System.currentTimeMillis() + "}"));
                sp.edit().putBoolean("loc-off-sent", true).remove("myloc").apply();
            }
        } catch (Exception ignored) { }
        try {
            HttpURLConnection h = (HttpURLConnection) new URL("https://ntfy.sh/" + topic(c, other()) + "/json?poll=1&since=12h").openConnection();
            h.setConnectTimeout(15000); h.setReadTimeout(20000);
            String last = null;
            try (BufferedReader r = new BufferedReader(new InputStreamReader(h.getInputStream(), "UTF-8"))) {
                String line; while ((line = r.readLine()) != null) { if (line.trim().isEmpty()) continue; JSONObject o = new JSONObject(line); if ("message".equals(o.optString("event"))) last = o.optString("message"); }
            } finally { h.disconnect(); }
            if (last != null) sp.edit().putString("oloc", dec(k, last)).apply();
        } catch (Exception ignored) { }
        WidgetDist.refresh(c);
    }

    /** Lo que muestra el widget. */
    static String[] state(Context c) {
        SharedPreferences sp = sp(c);
        String on = MainActivity.IS_JON ? "Ale" : "Jon";
        try {
            if (!on(c)) return new String[]{"off", "Prende «Distancia» en la app 📍"};
            if (!perm(c)) return new String[]{"off", "Falta el permiso de ubicación"};
            JSONObject o = new JSONObject(sp.getString("oloc", "{}"));
            if (o.optInt("off") == 1) return new String[]{"off", on + " pausó su ubicación"};
            if (!o.has("la")) return new String[]{"off", "Esperando la ubicación de " + on + "…"};
            JSONObject m = new JSONObject(sp.getString("myloc", "{}"));
            if (!m.has("la")) return new String[]{"off", "Buscando tu ubicación…"};
            float[] d = new float[1];
            Location.distanceBetween(m.getDouble("la"), m.getDouble("lo"), o.getDouble("la"), o.getDouble("lo"), d);
            float tol = Math.max(200f, Math.min(600f, (float) (m.optDouble("ac", 100) + o.optDouble("ac", 100))));
            if (d[0] <= tol) return new String[]{"juntos", "¡Juntos! ❤️"};
            String txt = d[0] < 1000 ? Math.round(d[0] / 10) * 10 + " m" : d[0] < 10000 ? String.format(java.util.Locale.US, "%.1f km", d[0] / 1000) : Math.round(d[0] / 1000) + " km";
            return new String[]{"lejos", txt};
        } catch (Exception e) { return new String[]{"off", "📍"}; }
    }
}
