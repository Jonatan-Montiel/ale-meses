package com.jon.nuestrolugar;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.content.pm.SigningInfo;
import android.graphics.Color;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.util.Log;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.net.URL;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Actualizaciones de la app.
 *
 * - Revisa la versión al abrir la app (máx. cada 10 min) y en segundo plano desde PollJob (máx. cada 6 h).
 * - Si Android lo permite (API 31+, permiso «Instalar apps desconocidas» concedido a esta app, targetSdk suficiente) descarga el APK,
 *   lo verifica (SHA-256 si el CI lo publica, paquete, versión y firma) y lo instala SIN preguntar con PackageInstaller
 *   (USER_ACTION_NOT_REQUIRED), cuando la app NO está a la vista (al salir de ella o desde el job).
 * - Si no se puede (API < 31, falta el permiso, el sistema pide confirmar o falla), cae al aviso de siempre: «Hay una versión nueva» y un toque.
 * Pregunta primero a Vercel (llega aunque la compañía de celular no llegue a GitHub), luego a GitHub Pages y al final a la API de GitHub.
 */
class Updater {
    static final String T = "NLUpd";
    static final String[] SRC = {MainActivity.MIRROR + "dl/version.json", MainActivity.BASE + "dl/version.json"};
    static final String API = "https://api.github.com/repos/Jonatan-Montiel/ale-meses/releases/tags/apk";
    static final String ACT = "com.jon.nuestrolugar.UPD_RESULT";
    static final String CH = "actualizacion";
    static final long BG_EVERY = 6 * 60 * 60 * 1000L;
    static final long MAX_APK = 80L * 1024 * 1024;
    static final String RELEASE = "https://github.com/Jonatan-Montiel/ale-meses/releases/download/apk/";
    static volatile boolean showing = false;
    static final AtomicBoolean busy = new AtomicBoolean(false);
    /** Flujo manual («Actualizar»): hay uno en curso / se espera la respuesta del instalador / diálogo de avance. */
    static final AtomicBoolean manualBusy = new AtomicBoolean(false);
    static volatile boolean awaiting = false;
    static volatile AlertDialog prog = null;
    static volatile String lastInstallErr = "";

    static class Remote { long vc = -1; String url, sha, src; }

    interface Step { void on(String s); }

    static void step(Step st, String s) { if (st != null) { try { st.on(s); } catch (Throwable ignored) { } } }

    /** Descarga directa del APK de este sabor, para abrirla en el navegador (Vercel primero: llega aunque GitHub no). */
    static String directUrl() { return MainActivity.MIRROR + "dl/" + apkName() + "?t=" + System.currentTimeMillis(); }

    /** Fuentes del APK en orden: la que dio version.json primero, luego las demás. */
    static List<String> candidates(Remote r) {
        List<String> l = new ArrayList<>();
        String a = apkName();
        if (r.url != null) l.add(r.url);
        for (String u : new String[]{MainActivity.MIRROR + "dl/" + a, MainActivity.BASE + "dl/" + a, RELEASE + a}) if (!l.contains(u)) l.add(u);
        return l;
    }

    // ---------------------------------------------------------------- último resultado (para depurar desde la página)

    static void rec(Context c, String step, String err) {
        try { Loc.sp(c).edit().putString("upd-last-step", step).putString("upd-last-err", err == null ? "" : err).putLong("upd-last-t", System.currentTimeMillis()).apply(); }
        catch (Throwable ignored) { }
        Log.i(T, "estado: " + step + (err == null || err.isEmpty() ? "" : " · " + err));
    }

    /** JSON {step, err, t, vc, perm, silent} para la página (vacío si todavía no hubo ningún intento). */
    static String info(Context c) {
        try {
            SharedPreferences sp = Loc.sp(c);
            JSONObject o = new JSONObject();
            o.put("step", sp.getString("upd-last-step", "")); o.put("err", sp.getString("upd-last-err", "")); o.put("t", sp.getLong("upd-last-t", 0));
            o.put("vc", Beat.vc(c));
            o.put("perm", Build.VERSION.SDK_INT < 26 || c.getPackageManager().canRequestPackageInstalls());
            o.put("silent", canSilent(c));
            return o.toString();
        } catch (Exception e) { return ""; }
    }

    static String why(Throwable e) {
        if (e instanceof UnknownHostException || e instanceof SocketTimeoutException || e instanceof ConnectException) return "No hubo conexión con el servidor de descargas.";
        String m = e.getMessage();
        if (m == null || m.isEmpty()) m = e.getClass().getSimpleName();
        return m.length() > 160 ? m.substring(0, 160) : m;
    }

    static String apkName() { return MainActivity.IS_JON ? "nuestro-lugar-jon.apk" : "nuestro-lugar.apk"; }

    // ---------------------------------------------------------------- red

    static HttpURLConnection open(String u, boolean bust, String accept) throws Exception {
        String full = bust ? u + (u.contains("?") ? "&" : "?") + "t=" + System.currentTimeMillis() : u;
        HttpURLConnection h = (HttpURLConnection) new URL(full).openConnection();
        h.setConnectTimeout(15000); h.setReadTimeout(30000); h.setUseCaches(false);
        if (accept != null) h.setRequestProperty("Accept", accept);
        h.setRequestProperty("User-Agent", "NuestroLugar");
        return h;
    }

    static String get(String u) throws Exception { return get(u, true); }

    static String get(String u, boolean bust) throws Exception {
        HttpURLConnection h = open(u, bust, "application/json");
        try {
            if (h.getResponseCode() != 200) throw new Exception("HTTP " + h.getResponseCode());
            StringBuilder sb = new StringBuilder();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(h.getInputStream(), "UTF-8"))) { String l; while ((l = r.readLine()) != null) sb.append(l); }
            return sb.toString();
        } finally { h.disconnect(); }
    }

    static boolean online(Context c) {
        try {
            ConnectivityManager cm = (ConnectivityManager) c.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return false;
            Network n = cm.getActiveNetwork();
            if (n == null) return false;
            NetworkCapabilities nc = cm.getNetworkCapabilities(n);
            return nc != null && nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) && nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
        } catch (Exception e) { return true; }
    }

    static boolean metered(Context c) {
        try { ConnectivityManager cm = (ConnectivityManager) c.getSystemService(Context.CONNECTIVITY_SERVICE); return cm != null && cm.isActiveNetworkMetered(); }
        catch (Exception e) { return false; }
    }

    /** Batería razonable para bajar e instalar en segundo plano: cargando, o al menos 20 % y sin ahorro de batería. */
    static boolean batteryOk(Context c) {
        try {
            Intent b = c.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (b == null) return true;
            int st = b.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
            if (st == BatteryManager.BATTERY_STATUS_CHARGING || st == BatteryManager.BATTERY_STATUS_FULL) return true;
            int lvl = b.getIntExtra(BatteryManager.EXTRA_LEVEL, -1), sc = b.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
            if (lvl >= 0 && sc > 0 && lvl * 100 / sc < 20) return false;
            PowerManager pm = (PowerManager) c.getSystemService(Context.POWER_SERVICE);
            return pm == null || !pm.isPowerSaveMode();
        } catch (Exception e) { return true; }
    }

    /** En el emulador de la prueba del CI solo se revisa y se registra: nunca se descarga ni se instala. */
    static boolean isEmulator() {
        String f = Build.FINGERPRINT == null ? "" : Build.FINGERPRINT, hw = Build.HARDWARE == null ? "" : Build.HARDWARE, p = Build.PRODUCT == null ? "" : Build.PRODUCT;
        return f.startsWith("generic") || f.contains("emulator") || hw.contains("ranchu") || hw.contains("goldfish") || p.contains("sdk_gphone");
    }

    // ---------------------------------------------------------------- versión remota

    static Remote fetchRemote() {
        String apk = apkName();
        Remote r = new Remote();
        for (String s : SRC) {
            try {
                JSONObject o = new JSONObject(get(s));
                r.vc = o.getLong("vc"); r.url = s.replace("version.json", apk); r.src = s;
                JSONObject h = o.optJSONObject("sha"); // campo aditivo del CI: {"nuestro-lugar.apk":"<sha256>", ...}
                if (h != null) { String x = h.optString(apk, ""); if (x.matches("[0-9a-fA-F]{64}")) r.sha = x.toLowerCase(Locale.ROOT); }
                break;
            } catch (Exception e) { Log.i(T, "no respondió " + s + ": " + e); r.vc = -1; }
        }
        boolean viaApi = false;
        if (r.vc < 0) {
            try {
                JSONObject o = new JSONObject(get(API, false));
                Matcher m = Pattern.compile("vc=(\\d+)").matcher(o.optString("body", ""));
                if (m.find()) r.vc = Long.parseLong(m.group(1));
                JSONArray as = o.optJSONArray("assets");
                for (int i = 0; as != null && i < as.length(); i++) if (apk.equals(as.getJSONObject(i).optString("name"))) r.url = as.getJSONObject(i).optString("browser_download_url");
                r.src = API; viaApi = true;
            } catch (Exception e) { Log.i(T, "no respondió la API: " + e); }
        }
        if (r.vc < 0) return null;
        if (r.sha == null && r.url != null) { // si el JSON no trae el hash, prueba el archivo .sha256 junto al APK
            try {
                String t = get(r.url + ".sha256", !viaApi).trim();
                String x = t.split("\\s+")[0];
                if (x.matches("[0-9a-fA-F]{64}")) r.sha = x.toLowerCase(Locale.ROOT);
            } catch (Exception e) { Log.i(T, "sin sha256 publicado: " + e); }
        }
        return r;
    }

    // ---------------------------------------------------------------- permisos / requisitos

    /** ¿Puede esta app instalarse una actualización a sí misma sin preguntar? */
    static boolean canSilent(Context c) {
        if (Build.VERSION.SDK_INT < 31) return false;
        try {
            if (!c.getPackageManager().canRequestPackageInstalls()) return false;
            // targetSdk mínimo que exige Android para USER_ACTION_NOT_REQUIRED (docs de SessionParams.setRequireUserAction)
            int sdk = Build.VERSION.SDK_INT;
            int need = sdk >= 37 ? 35 : sdk >= 36 ? 34 : sdk >= 35 ? 33 : sdk >= 34 ? 31 : sdk >= 33 ? 30 : 29;
            return c.getApplicationInfo().targetSdkVersion >= need;
        } catch (Exception e) { return false; }
    }

    static void openUnknownSources(Activity a) {
        try {
            if (Build.VERSION.SDK_INT >= 26) a.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + a.getPackageName())));
        } catch (Exception ignored) { }
    }

    // ---------------------------------------------------------------- descarga y verificación

    static File dir(Context c) { File d = new File(c.getCacheDir(), "upd"); d.mkdirs(); return d; }

    static File staged(Context c, long vc) { return new File(dir(c), "nl-" + vc + ".apk"); }

    static void cleanup(Context c) {
        try { File[] fs = dir(c).listFiles(); if (fs != null) for (File f : fs) f.delete(); } catch (Exception ignored) { }
        try { Loc.sp(c).edit().remove("upd-staged").remove("upd-sha").apply(); } catch (Exception ignored) { }
    }

    static String hex(byte[] b) { StringBuilder s = new StringBuilder(); for (byte x : b) s.append(String.format("%02x", x)); return s.toString(); }

    static String sha256(File f) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (InputStream in = new FileInputStream(f)) { byte[] b = new byte[16384]; int n; while ((n = in.read(b)) != -1) md.update(b, 0, n); }
        return hex(md.digest());
    }

    static Set<String> certs(Signature[] ss) {
        Set<String> out = new HashSet<>();
        try { if (ss != null) for (Signature s : ss) out.add(hex(MessageDigest.getInstance("SHA-256").digest(s.toByteArray()))); } catch (Exception ignored) { }
        return out;
    }

    /** 1 = misma firma, 0 = distinta, -1 = el sistema no dio la firma del archivo. */
    static int sameSigner(PackageInfo apk, PackageInfo cur) {
        Set<String> a, b;
        if (Build.VERSION.SDK_INT >= 28) {
            SigningInfo sa = apk.signingInfo, sb = cur.signingInfo;
            if (sa == null || sb == null) return -1;
            a = certs(sa.getApkContentsSigners());
            b = certs(sb.getApkContentsSigners());
            if (!sb.hasMultipleSigners()) b.addAll(certs(sb.getSigningCertificateHistory()));
        } else {
            a = certs(apk.signatures); b = certs(cur.signatures);
        }
        if (a.isEmpty()) return -1;
        return b.containsAll(a) ? 1 : 0;
    }

    /** null si el APK es válido; si no, el motivo. */
    static String verify(Context c, File f, Remote r) {
        try {
            if (!f.isFile() || f.length() < 10000) return "archivo vacío o muy chico";
            if (r.sha != null && !r.sha.equalsIgnoreCase(sha256(f))) return "el SHA-256 no coincide";
            PackageManager pm = c.getPackageManager();
            int fl = Build.VERSION.SDK_INT >= 28 ? PackageManager.GET_SIGNING_CERTIFICATES : PackageManager.GET_SIGNATURES;
            PackageInfo ai = pm.getPackageArchiveInfo(f.getAbsolutePath(), fl);
            if (ai == null) return "no se pudo leer el APK";
            if (!c.getPackageName().equals(ai.packageName)) return "es de otro paquete: " + ai.packageName;
            long av = Build.VERSION.SDK_INT >= 28 ? ai.getLongVersionCode() : ai.versionCode;
            if (av <= Beat.vc(c)) return "no es más nueva que la instalada";
            if (r.vc > 0 && av != r.vc) return "su versión (" + av + ") no es la anunciada (" + r.vc + ")";
            int sg = sameSigner(ai, pm.getPackageInfo(c.getPackageName(), fl));
            if (sg == 0) return "la firma no coincide con la instalada";
            if (sg < 0 && r.sha == null) return "no se pudo comprobar la firma ni hay SHA-256";
            return null;
        } catch (Exception e) { return "error al verificar: " + e; }
    }

    /** Descarga (si hace falta) y verifica el APK. Devuelve el archivo listo o lanza excepción. */
    static File stage(Context c, Remote r, boolean background) throws Exception { return stage(c, r, background, r.url, null); }

    static File stage(Context c, Remote r, boolean background, String url, Step st) throws Exception {
        File d = dir(c), f = staged(c, r.vc);
        if (f.exists()) { step(st, "Verificando…"); if (verify(c, f, r) == null) return f; f.delete(); }
        File[] old = d.listFiles(); if (old != null) for (File o : old) o.delete();
        File part = new File(d, "nl-" + r.vc + ".part");
        boolean api = url.startsWith("https://github.com/") || url.contains("githubusercontent.com");
        step(st, "Descargando…");
        HttpURLConnection h = open(url, !api, null);
        try {
            h.setUseCaches(false); h.setRequestProperty("Cache-Control", "no-cache");
            if (h.getResponseCode() != 200) throw new Exception("HTTP " + h.getResponseCode());
            long len = h.getContentLength();
            if (background && len > 15L * 1024 * 1024 && metered(c)) throw new Exception("APK grande con datos móviles: se espera a Wi-Fi");
            try (InputStream in = h.getInputStream(); OutputStream out = new FileOutputStream(part)) {
                byte[] b = new byte[16384]; long t = 0; int n, lastPct = -10;
                while ((n = in.read(b)) != -1) {
                    t += n; if (t > MAX_APK) throw new IOException("APK demasiado grande"); out.write(b, 0, n);
                    if (st != null && len > 0) { int pct = (int) (t * 100 / len); if (pct >= lastPct + 10) { lastPct = pct; step(st, "Descargando… " + pct + " %"); } }
                }
            }
        } finally { h.disconnect(); }
        if (!part.renameTo(f)) { part.delete(); throw new IOException("no se pudo guardar el APK"); }
        step(st, "Verificando…");
        String bad = verify(c, f, r);
        if (bad != null) { f.delete(); throw new Exception("verificación fallida: " + bad); }
        Log.i(T, "APK v" + r.vc + " descargado y verificado (" + f.length() + " bytes, sha256 " + (r.sha != null ? "ok" : "no publicado") + ")");
        return f;
    }

    /** Sha-256 publicado junto a esa fuente (null si no hay). */
    static String shaOf(String url) {
        try {
            String x = get(url + ".sha256", !url.startsWith("https://github.com/")).trim().split("\\s+")[0];
            return x.matches("[0-9a-fA-F]{64}") ? x.toLowerCase(Locale.ROOT) : null;
        } catch (Exception e) { return null; }
    }

    /** Como stage, pero si una fuente falla (caché vieja, versión o SHA que no coinciden, HTTP) prueba las demás antes de rendirse. */
    static File stageAny(Context c, Remote r, boolean background, Step st) throws Exception {
        Exception last = null; boolean first = true;
        for (String u : candidates(r)) {
            try {
                Remote q = r;
                if (!first) { q = new Remote(); q.vc = r.vc; q.url = u; q.src = u; q.sha = shaOf(u); } // cada fuente se verifica con su propio hash
                return stage(c, q, background, u, st);
            } catch (Exception e) {
                last = e; first = false;
                Log.i(T, "falló la fuente " + u + ": " + e);
                String m = String.valueOf(e.getMessage());
                if (m.contains("Wi-Fi") || m.contains("demasiado grande")) throw e;
            }
        }
        throw last != null ? last : new Exception("no hay fuente de descarga");
    }

    // ---------------------------------------------------------------- instalación

    /** Crea la sesión de PackageInstaller y la manda. El resultado llega a UpdRx. */
    static boolean installApk(Context c, File f, boolean silent) {
        try {
            PackageInstaller pi = c.getPackageManager().getPackageInstaller();
            for (PackageInstaller.SessionInfo si : pi.getMySessions()) { try { pi.abandonSession(si.getSessionId()); } catch (Exception ignored) { } }
            PackageInstaller.SessionParams p = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
            p.setAppPackageName(c.getPackageName());
            p.setSize(f.length());
            if (Build.VERSION.SDK_INT >= 31) p.setRequireUserAction(silent ? PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED : PackageInstaller.SessionParams.USER_ACTION_REQUIRED);
            int id = pi.createSession(p);
            PackageInstaller.Session s = pi.openSession(id);
            try {
                try (InputStream in = new FileInputStream(f); OutputStream out = s.openWrite("nl.apk", 0, f.length())) {
                    byte[] b = new byte[16384]; int n;
                    while ((n = in.read(b)) != -1) out.write(b, 0, n);
                    s.fsync(out);
                }
                Intent it = new Intent(c, UpdRx.class).setAction(ACT).putExtra("silent", silent);
                // el sistema agrega datos al intent, por eso debe ser mutable (y explícito)
                PendingIntent pend = PendingIntent.getBroadcast(c, id, it, PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0));
                Log.i(T, "commit de la sesión " + id + (silent ? " (sin preguntar)" : " (con confirmación)"));
                s.commit(pend.getIntentSender());
            } catch (Exception e) {
                try { s.abandon(); } catch (Exception ignored) { }
                throw e;
            } finally {
                try { s.close(); } catch (Exception ignored) { }
            }
            return true;
        } catch (Exception e) {
            Log.i(T, "no se pudo instalar con PackageInstaller: " + e);
            lastInstallErr = why(e);
            return false;
        }
    }

    static void commitSilent(Context c, File f, long vc) {
        SharedPreferences sp = Loc.sp(c);
        sp.edit().putInt("upd-n", sp.getInt("upd-n", 0) + 1).apply(); // intentos; con 2 se pasa al aviso con un toque
        Log.i(T, "instalando v" + vc + " sin preguntar");
        rec(c, "instalando sin preguntar (v" + vc + ")", null);
        if (!installApk(c, f, true)) rec(c, "falló la instalación automática", lastInstallErr);
    }

    /** Al salir de la app (pantalla apagada, inicio…): si ya hay una versión lista y verificada, se instala sin molestar. */
    static void onLeave(final Context c) {
        if (isEmulator() || !canSilent(c)) return;
        final SharedPreferences sp = Loc.sp(c);
        final long sv = sp.getLong("upd-staged", 0);
        if (sv <= Beat.vc(c) || sp.getLong("upd-cv", 0) != sv || sp.getInt("upd-n", 0) >= 2) return;
        if (!busy.compareAndSet(false, true)) return;
        new Thread(() -> {
            try {
                File f = staged(c, sv);
                Remote r = new Remote(); r.vc = sv; String x = sp.getString("upd-sha", ""); r.sha = x.isEmpty() ? null : x;
                if (Notif.appVisible || !batteryOk(c)) return;
                if (verify(c, f, r) == null) commitSilent(c, f, sv);
            } catch (Throwable t) { Log.i(T, "onLeave: " + t); }
            finally { busy.set(false); }
        }).start();
    }

    // ---------------------------------------------------------------- revisión

    /** Al abrir la app (máximo cada 10 min). */
    static void check(Activity a) { check(a, false); }

    /** Al tocar el aviso «hay una versión nueva»: revisa sin esperar. */
    static void force(Activity a) { Loc.sp(a).edit().putLong("upd-check", 0).apply(); check(a, true); }

    static void check(final Activity a, boolean force) {
        final SharedPreferences sp = a.getSharedPreferences("nl", Activity.MODE_PRIVATE);
        if (System.currentTimeMillis() - sp.getLong("upd-check", 0) < 10 * 60 * 1000L) return; // máximo cada 10 min
        if (!busy.compareAndSet(false, true)) return;
        sp.edit().putLong("upd-check", System.currentTimeMillis()).apply();
        new Thread(() -> {
            try {
                if (!process(a, true, a)) sp.edit().putLong("upd-check", 0).apply(); // sin respuesta: vuelve a intentar la próxima vez que abra
            } catch (Throwable t) { Log.i(T, "check: " + t); sp.edit().putLong("upd-check", 0).apply(); }
            finally { busy.set(false); }
        }).start();
    }

    /** Desde PollJob (ya en un hilo aparte): máximo cada 6 h, o en cada vuelta si ya hay una versión descargada esperando para instalarse. */
    static void background(Context c) {
        SharedPreferences sp = Loc.sp(c);
        long now = System.currentTimeMillis();
        boolean waiting = sp.getLong("upd-staged", 0) > Beat.vc(c) && sp.getInt("upd-n", 0) < 2;
        if (!waiting && now - sp.getLong("upd-bg", 0) < BG_EVERY) return;
        if (Notif.appVisible) return;
        if (!busy.compareAndSet(false, true)) return;
        try {
            sp.edit().putLong("upd-bg", now).apply();
            if (!process(c, false, null)) sp.edit().putLong("upd-bg", now - BG_EVERY + 30 * 60 * 1000L).apply(); // sin red: reintenta en ~30 min
        } catch (Throwable t) { Log.i(T, "background: " + t); }
        finally { busy.set(false); }
    }

    /** Devuelve false si no hubo respuesta de ningún servidor (para reintentar pronto). */
    static boolean process(Context c, boolean fg, Activity a) {
        SharedPreferences sp = Loc.sp(c);
        Remote r = fetchRemote();
        long local = Beat.vc(c);
        Log.i(T, "remota=" + (r == null ? -1 : r.vc) + " instalada=" + local + " fuente=" + (r == null ? null : r.src) + (fg ? " (al abrir)" : " (segundo plano)"));
        if (r == null) return false;
        if (r.vc <= local || r.url == null) { if (sp.getLong("upd-staged", 0) != 0) cleanup(c); return true; }
        if (isEmulator()) { Log.i(T, "emulador: no se descarga ni se instala"); return true; }
        if (sp.getLong("upd-cv", 0) != r.vc) sp.edit().putLong("upd-cv", r.vc).putInt("upd-n", 0).putInt("upd-d", 0).remove("upd-staged").remove("upd-sha").apply();
        boolean silent = canSilent(c) && sp.getInt("upd-n", 0) < 2 && sp.getInt("upd-d", 0) < 3;
        Log.i(T, "instalación sin preguntar " + (silent ? "disponible" : "NO disponible (se avisará)"));
        if (silent) {
            if (!online(c) || (!fg && !batteryOk(c))) return true;
            try {
                File f = stageAny(c, r, !fg, null);
                rec(c, "descargada y verificada (v" + r.vc + ")", null);
                sp.edit().putLong("upd-staged", r.vc).putString("upd-sha", r.sha == null ? "" : r.sha).apply();
                // con la app a la vista no se instala (cerraría la app en la cara de Ale): se instala al salir (onLeave) o desde el job
                if (!Notif.appVisible) commitSilent(c, f, r.vc);
            } catch (Exception e) {
                Log.i(T, "descarga/verificación: " + e);
                rec(c, "falló la descarga automática", why(e));
                sp.edit().putInt("upd-d", sp.getInt("upd-d", 0) + 1).apply();
            }
            return true;
        }
        if (fg && a != null) showDialog(a, r, local);
        else notifyAvailable(c, r.vc);
        return true;
    }

    // ---------------------------------------------------------------- avisos (flujo de siempre: un toque)

    static void showDialog(final Activity a, final Remote r, final long local) {
        a.runOnUiThread(() -> {
            if (a.isFinishing() || showing) return;
            showing = true;
            boolean sinPermiso = Build.VERSION.SDK_INT >= 31 && !a.getPackageManager().canRequestPackageInstalls();
            AlertDialog.Builder b = new AlertDialog.Builder(a)
                    .setTitle("Hay una versión nueva 💗")
                    .setMessage("Versión " + r.vc + " (tienes la " + local + "). Se descarga y la instalas encima; no se borra nada."
                            + (sinPermiso ? "\n\nSi activas «Instalar apps desconocidas» para esta app, las próximas se instalan solas." : ""))
                    .setPositiveButton("Actualizar", (d, w) -> manual(a, r))
                    .setNegativeButton("Después", null)
                    .setOnDismissListener(d -> showing = false);
            if (sinPermiso) b.setNeutralButton("Activar automáticas", (d, w) -> openUnknownSources(a));
            b.show();
        });
    }

    /** Diálogo de avance del flujo manual: un solo diálogo cuyo texto va cambiando (Descargando…, Verificando…, Abriendo el instalador…). */
    static void progress(final Activity a, final String msg) {
        a.runOnUiThread(() -> {
            try {
                if (a.isFinishing()) return;
                if (prog == null || !prog.isShowing()) {
                    AlertDialog d = new AlertDialog.Builder(a).setTitle("Actualizando 💗").setMessage(msg).setCancelable(true).create();
                    prog = d; d.show();
                } else prog.setMessage(msg);
            } catch (Throwable ignored) { }
        });
    }

    static void closeProgress() {
        new Handler(Looper.getMainLooper()).post(() -> {
            try { AlertDialog d = prog; prog = null; if (d != null && d.isShowing()) d.dismiss(); } catch (Throwable ignored) { }
        });
    }

    /** Algo salió mal en el flujo manual: se dice el motivo corto y se ofrece bajarla desde el navegador (nunca falla en silencio). */
    static void fail(final Activity a, final String motivo) {
        rec(a.getApplicationContext(), "no se pudo actualizar", motivo);
        a.runOnUiThread(() -> {
            try {
                closeProgress();
                if (a.isFinishing()) return;
                new AlertDialog.Builder(a)
                        .setTitle("No pude actualizar solita 😕")
                        .setMessage(motivo + "\n\nPuedes bajarla desde el navegador e instalarla encima; no se borra nada.")
                        .setPositiveButton("Descargar en el navegador", (d, w) -> viewFallback(a))
                        .setNegativeButton("Cerrar", null)
                        .show();
            } catch (Throwable ignored) { }
        });
    }

    /** El instalador respondió (UpdRx): ya no hay que esperar. */
    static void resolved() { awaiting = false; }

    /** Fallo que llegó desde UpdRx: con la app a la vista, diálogo; si no, notificación. */
    static void failFront(Context c, String motivo) {
        MainActivity a = MainActivity.cur;
        if (a != null) { fail(a, motivo); return; }
        rec(c, "no se pudo actualizar", motivo);
        Intent i = new Intent(c, MainActivity.class).setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP).putExtra("upd", 1);
        note(c, 4101, "No se pudo actualizar 😕", motivo + " Toca para intentarlo de nuevo.",
                PendingIntent.getActivity(c, 4101, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));
    }

    /** «Actualizar»: baja y verifica el APK y lo instala con la confirmación del sistema; cada paso se ve y si algo falla se explica y se ofrece el navegador. */
    static void manual(final Activity a, final Remote r) {
        final Context c = a.getApplicationContext();
        if (Build.VERSION.SDK_INT >= 26 && !c.getPackageManager().canRequestPackageInstalls()) {
            rec(c, "sin permiso de instalar", "se abrió la descarga en el navegador");
            try { Toast.makeText(a, "Abriendo el navegador para bajar la actualización. Al terminar, toca el archivo descargado para instalarla.", Toast.LENGTH_LONG).show(); } catch (Exception ignored) { }
            viewFallback(a);
            return;
        }
        if (!manualBusy.compareAndSet(false, true)) { try { Toast.makeText(a, "Ya se está actualizando…", Toast.LENGTH_SHORT).show(); } catch (Exception ignored) { } return; }
        rec(c, "descargando (v" + r.vc + ")", null);
        progress(a, "Descargando…");
        new Thread(() -> {
            try {
                File f = stageAny(c, r, false, s -> progress(a, s));
                rec(c, "verificada, abriendo el instalador", null);
                progress(a, "Abriendo el instalador…");
                awaiting = true;
                if (!installApk(c, f, false)) { awaiting = false; fail(a, "El sistema no aceptó iniciar la instalación (" + lastInstallErr + ")."); return; }
                // si el instalador no contesta, no se deja el diálogo colgado
                new Handler(Looper.getMainLooper()).postDelayed(() -> {
                    if (awaiting) { awaiting = false; fail(a, "El instalador no respondió. Si te llegó una notificación de «versión nueva», tócala para instalarla."); }
                }, 25000);
            } catch (Throwable e) {
                Log.i(T, "flujo con confirmación: " + e);
                fail(a, "No se pudo bajar o verificar la actualización: " + why(e));
            } finally { manualBusy.set(false); }
        }).start();
    }

    static void viewFallback(Activity a) {
        try {
            rec(a.getApplicationContext(), "descarga en el navegador", null);
            a.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(directUrl())));
        } catch (Exception e) {
            try { Toast.makeText(a, "No encontré un navegador para abrir la descarga.", Toast.LENGTH_LONG).show(); } catch (Exception ignored) { }
        }
    }

    static void channel(Context c) {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = c.getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(new NotificationChannel(CH, "Actualizaciones de la app", NotificationManager.IMPORTANCE_LOW));
        }
    }

    static void note(Context c, int id, String title, String text, PendingIntent pi) {
        try {
            channel(c);
            Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(c, CH) : new Notification.Builder(c);
            b.setSmallIcon(R.drawable.ic_stat).setColor(Color.parseColor("#e85d84")).setContentTitle(title).setContentText(text).setAutoCancel(true).setContentIntent(pi);
            NotificationManager nm = c.getSystemService(NotificationManager.class);
            if (nm != null) nm.notify(id, b.build());
        } catch (Exception ignored) { }
    }

    /** Segundo plano y sin instalación silenciosa: una sola notificación por versión; al tocarla sale el diálogo «Actualizar». */
    static void notifyAvailable(Context c, long vc) {
        SharedPreferences sp = Loc.sp(c);
        if (sp.getLong("upd-notified", 0) == vc) return;
        sp.edit().putLong("upd-notified", vc).apply();
        Intent i = new Intent(c, MainActivity.class).setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP).putExtra("upd", 1);
        note(c, 4103, "Hay una versión nueva 💗", "Toca para actualizar Nuestro lugar (versión " + vc + ").",
                PendingIntent.getActivity(c, 4103, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));
    }

    /** El sistema pidió confirmar la instalación y la app no está a la vista: aviso que abre la pantalla de confirmación. */
    static void notifyConfirm(Context c, Intent confirm) {
        confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        note(c, 4102, "Hay una versión nueva 💗", "Toca para instalarla (un toque y listo).",
                PendingIntent.getActivity(c, 4102, confirm, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));
    }

    // ---------------------------------------------------------------- al abrir

    /** «Se actualizó a la vN 💗» (discreto) y, una sola vez, la invitación a activar las actualizaciones automáticas. */
    static void afterOpen(final Activity a) {
        final SharedPreferences sp = Loc.sp(a);
        long v = Beat.vc(a), last = sp.getLong("last-vc", 0);
        sp.edit().putLong("last-vc", v).apply();
        boolean linked = sp.getBoolean("linked", false);
        if (last > 0 && v > last && linked) {
            try { Toast.makeText(a, "Se actualizó a la v" + v + " 💗", Toast.LENGTH_LONG).show(); } catch (Exception ignored) { }
            try { NotificationManager nm = a.getSystemService(NotificationManager.class); if (nm != null) { nm.cancel(4102); nm.cancel(4103); } } catch (Exception ignored) { }
        }
        if (Build.VERSION.SDK_INT >= 31 && linked && !isEmulator() && !sp.getBoolean("auto-ask", false) && !a.getPackageManager().canRequestPackageInstalls()) {
            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                if (a.isFinishing() || a.isDestroyed() || showing || sp.getBoolean("auto-ask", false)) return;
                sp.edit().putBoolean("auto-ask", true).apply();
                showing = true;
                new AlertDialog.Builder(a)
                        .setTitle("Actualizaciones automáticas 💗")
                        .setMessage("Para que la app se actualice sola, sin que tengas que hacer nada, activa «Permitir de esta fuente» en la pantalla que sigue.")
                        .setPositiveButton("Activar", (d, w) -> openUnknownSources(a))
                        .setNegativeButton("Ahora no", null)
                        .setOnDismissListener(d -> showing = false)
                        .show();
            }, 5000);
        }
    }
}
