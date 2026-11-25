package com.jon.nuestrolugar;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;

import org.json.JSONObject;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.SecureRandom;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Cifrado con la misma llave de la página (para los avisos cifrados y la señal de «app instalada").
 * v33: ya no hay ubicación ni widget de distancia (se quitaron para poder subir la app a Play Store).
 */
class Loc {
    static SharedPreferences sp(Context c) { return c.getSharedPreferences("nl", Context.MODE_PRIVATE); }

    static byte[] key(Context c) { String k = SecureKey.get(c); if (k.isEmpty()) return null; try { return Base64.decode(k, Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING); } catch (Exception e) { return null; } }
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

    static void post(String topic, String msg) throws Exception {
        JSONObject o = new JSONObject(); o.put("topic", topic); o.put("message", msg); o.put("priority", 1);
        HttpURLConnection h = (HttpURLConnection) new URL("https://ntfy.sh/").openConnection();
        h.setConnectTimeout(15000); h.setReadTimeout(15000); h.setDoOutput(true); h.setRequestMethod("POST");
        try (OutputStream os = h.getOutputStream()) { os.write(o.toString().getBytes("UTF-8")); }
        h.getResponseCode(); h.disconnect();
    }
}
