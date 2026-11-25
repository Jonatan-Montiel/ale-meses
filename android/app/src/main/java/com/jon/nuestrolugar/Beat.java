package com.jon.nuestrolugar;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;

import org.json.JSONObject;

import java.security.MessageDigest;

/** Señal de «sigo instalada»: cada ~30 min la app avisa (cifrado) que está viva, para que el otro lo vea en «Versiones». */
class Beat {
    static String topic(Context c, String who) throws Exception {
        String k = SecureKey.get(c);
        byte[] h = MessageDigest.getInstance("SHA-256").digest(("nl-hb:" + k + ":" + who).getBytes("UTF-8"));
        StringBuilder b = new StringBuilder("nlhb"); for (int i = 0; i < 12; i++) b.append(String.format("%02x", h[i])); return b.toString();
    }

    static long vc(Context c) {
        try { android.content.pm.PackageInfo pi = c.getPackageManager().getPackageInfo(c.getPackageName(), 0); return Build.VERSION.SDK_INT >= 28 ? pi.getLongVersionCode() : pi.versionCode; }
        catch (Exception e) { return 0; }
    }

    /** Correr fuera del hilo principal. */
    static synchronized void send(Context c) {
        try {
            byte[] k = Loc.key(c); if (k == null) return;
            SharedPreferences sp = Loc.sp(c);
            long now = System.currentTimeMillis();
            if (now - sp.getLong("beat", 0) < 25 * 60 * 1000L) return;
            JSONObject j = new JSONObject(); j.put("t", now); j.put("vc", vc(c));
            Loc.post(topic(c, MainActivity.IS_JON ? "yo" : "ella"), Loc.enc(k, j.toString()));
            sp.edit().putLong("beat", now).apply();
        } catch (Exception ignored) { }
    }
}
