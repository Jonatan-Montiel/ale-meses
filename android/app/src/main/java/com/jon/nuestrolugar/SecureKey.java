package com.jon.nuestrolugar;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * Guarda la llave AES de la página cifrada con una llave del Android Keystore (AES/GCM).
 * Formato nuevo: preferencia "kc" = Base64(iv[12] + cifrado). Formato viejo: "k" en texto plano.
 * Migración: al leer, si solo existe "k" se cifra, se guarda en "kc" y se borra "k".
 * Si el Keystore falla, se usa "k" en texto plano como antes (nunca se pierde la llave).
 */
class SecureKey {
    private static final String ALIAS = "nl_key_v1";
    private static final String PROVIDER = "AndroidKeyStore";
    private static final Object LOCK = new Object();

    private static SharedPreferences sp(Context c) { return c.getApplicationContext().getSharedPreferences("nl", Context.MODE_PRIVATE); }

    private static SecretKey ksKey() throws Exception {
        KeyStore ks = KeyStore.getInstance(PROVIDER);
        ks.load(null);
        if (ks.containsAlias(ALIAS)) {
            KeyStore.Entry e = ks.getEntry(ALIAS, null);
            if (e instanceof KeyStore.SecretKeyEntry) return ((KeyStore.SecretKeyEntry) e).getSecretKey();
        }
        KeyGenerator g = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER);
        g.init(new KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build());
        return g.generateKey();
    }

    private static String encrypt(String plain) throws Exception {
        Cipher ci = Cipher.getInstance("AES/GCM/NoPadding");
        ci.init(Cipher.ENCRYPT_MODE, ksKey());
        byte[] iv = ci.getIV();
        byte[] ct = ci.doFinal(plain.getBytes("UTF-8"));
        byte[] o = new byte[iv.length + ct.length];
        System.arraycopy(iv, 0, o, 0, iv.length);
        System.arraycopy(ct, 0, o, iv.length, ct.length);
        return Base64.encodeToString(o, Base64.NO_WRAP);
    }

    private static String decrypt(String b64) throws Exception {
        byte[] o = Base64.decode(b64, Base64.NO_WRAP);
        if (o.length < 13) throw new IllegalArgumentException("corto");
        Cipher ci = Cipher.getInstance("AES/GCM/NoPadding");
        ci.init(Cipher.DECRYPT_MODE, ksKey(), new GCMParameterSpec(128, o, 0, 12));
        return new String(ci.doFinal(o, 12, o.length - 12), "UTF-8");
    }

    /** Devuelve la llave (Base64 URL) o "" si no hay. Migra la llave vieja en texto plano si puede. */
    static String get(Context c) {
        synchronized (LOCK) {
            SharedPreferences sp = sp(c);
            String enc = sp.getString("kc", "");
            if (!enc.isEmpty()) {
                try { return decrypt(enc); } catch (Exception ignored) { /* Keystore perdido: cae a la vieja si existe */ }
            }
            String plain = sp.getString("k", "");
            if (!plain.isEmpty() && enc.isEmpty()) {
                try {
                    String e2 = encrypt(plain);
                    // Verifica que se pueda descifrar antes de borrar la llave vieja.
                    if (plain.equals(decrypt(e2))) sp.edit().putString("kc", e2).remove("k").apply();
                } catch (Exception ignored) { /* se queda en texto plano */ }
            }
            return plain;
        }
    }

    /** Guarda la llave cifrada; si el Keystore falla, la guarda como antes (texto plano). */
    static void put(Context c, String k) {
        synchronized (LOCK) {
            SharedPreferences sp = sp(c);
            try {
                String e = encrypt(k);
                if (!k.equals(decrypt(e))) throw new IllegalStateException("verificación");
                sp.edit().putString("kc", e).remove("k").apply();
            } catch (Exception ex) {
                sp.edit().putString("k", k).remove("kc").apply();
            }
        }
    }

    /** Borra la llave y la entrada del Keystore (al desvincular). */
    static void wipe(Context c) {
        synchronized (LOCK) {
            sp(c).edit().remove("k").remove("kc").apply();
            try { KeyStore ks = KeyStore.getInstance(PROVIDER); ks.load(null); if (ks.containsAlias(ALIAS)) ks.deleteEntry(ALIAS); } catch (Exception ignored) { }
        }
    }
}
