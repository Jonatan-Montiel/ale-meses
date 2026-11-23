package com.jon.nuestrolugar;

import android.Manifest;
import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.ViewGroup;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceError;
import android.webkit.WebResourceResponse;
import android.webkit.SslErrorHandler;
import android.net.http.SslError;
import android.net.LinkProperties;
import android.net.Network;
import android.widget.FrameLayout;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URL;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import android.content.ContentValues;
import android.content.pm.ShortcutInfo;
import android.content.pm.ShortcutManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.Icon;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Base64;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import org.json.JSONArray;
import org.json.JSONObject;
import android.hardware.biometrics.BiometricManager;
import android.hardware.biometrics.BiometricPrompt;
import android.os.CancellationSignal;
import android.widget.ScrollView;
import android.view.inputmethod.EditorInfo;

public class MainActivity extends Activity {
    static final String BASE = "https://jonatan-montiel.github.io/ale-meses/";
    static final boolean IS_JON = "yo".equals(BuildConfig.WHO);
    /** Jon abre la página con el editor; Ale la abre normal. */
    // Copia de la página en otro servidor por si la compañía de celular no llega a GitHub
    static final String MIRROR = "https://ale-meses.vercel.app/";
    String base() { String h = sp == null ? BASE : sp.getString("host", BASE); return MIRROR.equals(h) ? MIRROR : BASE; }
    String other() { return BASE.equals(base()) ? MIRROR : BASE; }
    String home() { return home(base()); }
    String home(String b) { String k = sp == null ? "" : sp.getString("k", ""); return (IS_JON ? b + "?editar" : b) + (k.isEmpty() ? "" : "#k=" + k); }
    static boolean ours(String u) { return u != null && (u.startsWith(BASE) || u.startsWith(MIRROR)); }
    String goTo = null; JSONArray share = null; boolean pageReady = false;
    long loadedAt = 0, pausedAt = 0;
    WebView wv;
    ValueCallback<Uri[]> fileCb;
    PermissionRequest pendingPerm;
    SharedPreferences sp;
    boolean badLink = false;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        sp = getSharedPreferences("nl", MODE_PRIVATE);
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
        }
        Notif.channel(this);
        PollJob.schedule(this);
        StreamService.start(this);
        sp.edit().putString("who", BuildConfig.WHO).apply();
        if (!sp.getBoolean("linked", false)) showLinkScreen();
        else showWeb(null);
        Updater.check(this);
        Reminder.schedule(this);
        setupShortcuts();
        handleIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        setIntent(i);
        handleIntent(i);
        // si al volver se va a recargar la página, se entrega después de recargar (si no, se pierde)
        boolean reload = wv != null && pausedAt > 0 && System.currentTimeMillis() - pausedAt > 2 * 60 * 1000L;
        if (!reload) deliver();
    }

    void setupShortcuts() {
        if (Build.VERSION.SDK_INT < 25) return;
        try {
            ShortcutManager sm = getSystemService(ShortcutManager.class);
            if (sm == null) return;
            String[][] S = {{"buzon", "💌 Buzón", "Dejar una notita"}, {"extrano", "🥺 Te extraño", "Decir que lo extrañas"}, {"voz", "🎙️ Mensaje de voz", "Grabar un mensaje"}};
            ArrayList<ShortcutInfo> L = new ArrayList<>();
            for (String[] x : S) {
                Intent i = new Intent(Intent.ACTION_VIEW).setClass(this, MainActivity.class).putExtra("ir", x[0]);
                L.add(new ShortcutInfo.Builder(this, x[0]).setShortLabel(x[1]).setLongLabel(x[2]).setIcon(Icon.createWithResource(this, R.mipmap.ic_launcher)).setIntent(i).build());
            }
            sm.setDynamicShortcuts(L);
        } catch (Exception ignored) { }
    }

    /** Atajos del ícono y fotos compartidas desde la galería. */
    void handleIntent(Intent i) {
        if (i == null) return;
        String ir = i.getStringExtra("ir");
        if (ir != null) { goTo = ir; i.removeExtra("ir"); }
        String a = i.getAction();
        if (Intent.ACTION_SEND.equals(a) || Intent.ACTION_SEND_MULTIPLE.equals(a)) {
            ArrayList<Uri> us = new ArrayList<>();
            if (Intent.ACTION_SEND.equals(a)) { Uri u = i.getParcelableExtra(Intent.EXTRA_STREAM); if (u != null) us.add(u); }
            else { ArrayList<Uri> m = i.getParcelableArrayListExtra(Intent.EXTRA_STREAM); if (m != null) us.addAll(m); }
            JSONArray arr = new JSONArray();
            for (int k = 0; k < us.size() && k < 10; k++) { String d = shrink(us.get(k)); if (d != null) arr.put(d); }
            if (arr.length() > 0) { share = arr; goTo = "momentos"; }
            i.setAction(Intent.ACTION_MAIN);
        }
    }

    String shrink(Uri u) {
        try {
            BitmapFactory.Options o = new BitmapFactory.Options(); o.inJustDecodeBounds = true;
            try (InputStream in = getContentResolver().openInputStream(u)) { BitmapFactory.decodeStream(in, null, o); }
            int s = 1; while (Math.max(o.outWidth, o.outHeight) / s > 2400) s *= 2;
            BitmapFactory.Options o2 = new BitmapFactory.Options(); o2.inSampleSize = s;
            Bitmap b; try (InputStream in = getContentResolver().openInputStream(u)) { b = BitmapFactory.decodeStream(in, null, o2); }
            if (b == null) return null;
            float sc = Math.min(1f, 1600f / Math.max(b.getWidth(), b.getHeight()));
            if (sc < 1f) b = Bitmap.createScaledBitmap(b, Math.round(b.getWidth() * sc), Math.round(b.getHeight() * sc), true);
            ByteArrayOutputStream bo = new ByteArrayOutputStream(); b.compress(Bitmap.CompressFormat.JPEG, 85, bo);
            return "data:image/jpeg;base64," + Base64.encodeToString(bo.toByteArray(), Base64.NO_WRAP);
        } catch (Exception e) { return null; }
    }

    /** Le pasa a la página el atajo o las fotos pendientes (se usan después del candado). */
    void deliver() {
        if (wv == null || !pageReady || (goTo == null && share == null)) return;
        String js = "(function(){var g=" + (goTo == null ? "null" : JSONObject.quote(goTo)) + ",s=" + (share == null ? "null" : share.toString()) + ";"
                + "if(document.body.classList.contains('locked')||!window.GOTO){window.__goAfterUnlock=g;window.__pendingShare=s}"
                + "else{if(s&&window.__sharePhotos)window.__sharePhotos(s);else if(g)(window.GO_ANY||GOTO)(g)}})()";
        goTo = null; share = null;
        wv.evaluateJavascript(js, null);
    }

    boolean online() {
        try {
            ConnectivityManager cm = getSystemService(ConnectivityManager.class);
            NetworkCapabilities nc = cm.getNetworkCapabilities(cm.getActiveNetwork());
            return nc != null && nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
        } catch (Exception e) { return true; }
    }

    /* ---- diagnóstico: si la página tarda, muestra en pantalla qué está pasando ---- */
    TextView diag; final StringBuilder dlog = new StringBuilder(); long dt0 = 0;
    void dl(String m) {
        runOnUiThread(() -> {
            long t = (System.currentTimeMillis() - dt0) / 100; dlog.append(t / 10).append('.').append(t % 10).append("s  ").append(m).append('\n');
            if (diag != null) diag.setText("🔧 Diagnóstico (toca para ocultar)\n" + dlog);
        });
    }
    void showDiag() { runOnUiThread(() -> { if (diag != null) diag.setVisibility(android.view.View.VISIBLE); }); }
    String netInfo() {
        try {
            ConnectivityManager cm = getSystemService(ConnectivityManager.class); Network n = cm.getActiveNetwork();
            if (n == null) return "sin red activa";
            NetworkCapabilities nc = cm.getNetworkCapabilities(n); LinkProperties lp = cm.getLinkProperties(n);
            StringBuilder b = new StringBuilder();
            b.append(nc == null ? "?" : nc.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ? "wifi" : nc.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ? "datos" : "otra");
            if (nc != null) b.append(nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) ? " · validada" : " · NO validada");
            if (nc != null && nc.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) b.append(" · VPN");
            if (lp != null) {
                boolean v4 = false, v6 = false; for (android.net.LinkAddress a : lp.getLinkAddresses()) { if (a.getAddress() instanceof java.net.Inet4Address) v4 = true; else v6 = true; }
                b.append(" · ").append(v4 ? "IPv4 " : "").append(v6 ? "IPv6" : "");
                if (Build.VERSION.SDK_INT >= 28) b.append(" · DNS privado ").append(lp.isPrivateDnsActive() ? "sí" : "no");
            }
            return b.toString();
        } catch (Exception e) { return "red ? " + e.getClass().getSimpleName(); }
    }
    void netTest() {
        new Thread(() -> {
            String host = Uri.parse(curHost != null ? curHost : base()).getHost();
            long a = System.currentTimeMillis();
            try { InetAddress[] ips = InetAddress.getAllByName(host); StringBuilder b = new StringBuilder(); for (InetAddress i : ips) b.append(i.getHostAddress()).append(' ');
                dl("DNS " + host + " → " + b.toString().trim() + " (" + (System.currentTimeMillis() - a) + " ms)");
            } catch (Exception e) { dl("DNS falló: " + e); }
            a = System.currentTimeMillis();
            try { HttpURLConnection c = (HttpURLConnection) new URL((curHost != null ? curHost : base()) + "?diag=" + a).openConnection(); c.setConnectTimeout(8000); c.setReadTimeout(8000);
                int code = c.getResponseCode(); InputStream in = c.getInputStream(); byte[] buf = new byte[8192]; long n = 0; int r; while ((r = in.read(buf)) > 0) n += r; in.close();
                dl("prueba directa: HTTP " + code + " · " + (n / 1024) + " KB en " + (System.currentTimeMillis() - a) + " ms");
            } catch (Exception e) { dl("prueba directa falló (" + (System.currentTimeMillis() - a) + " ms): " + e); }
        }).start();
    }

    // Carga la página; si no responde (internet lento o sin permiso de datos) usa la copia guardada y, si tampoco, muestra cómo arreglarlo.
    int loadId = 0; boolean loadErr = false, fromCache = false, visible = false, triedOther = false; String curHost = null;
    void loadHome(String url) { loadHome(url, !online()); }
    void loadHome(String url, boolean cache) {
        if (wv == null) return;
        fromCache = cache; loadErr = false; pageReady = false; visible = false;
        wv.getSettings().setCacheMode(cache ? WebSettings.LOAD_CACHE_ELSE_NETWORK : WebSettings.LOAD_NO_CACHE);
        final int id = ++loadId; final WebView v = wv;
        if (url == null && !cache && !triedOther) { dt0 = System.currentTimeMillis(); dlog.setLength(0); dl("red: " + netInfo()); }
        if (curHost == null || url != null) curHost = base();
        dl("cargando " + (cache ? "(copia guardada)" : "(internet)") + " · " + Uri.parse(curHost).getHost());
        wv.loadUrl(url != null ? url : home(curHost));
        v.postDelayed(() -> { if (wv == v && id == loadId && !pageReady) { showDiag(); netTest(); } }, 6000);
        v.postDelayed(() -> { if (wv == v && id == loadId && !pageReady && !visible) fallback(); }, cache ? 12000 : 6000);
    }
    void fallback() {
        if (wv == null) return;
        if (!fromCache && !triedOther) { triedOther = true; wv.stopLoading(); curHost = BASE.equals(curHost) ? MIRROR : BASE; dl("probando el otro servidor"); loadHome(null, false); return; }
        if (!fromCache) { wv.stopLoading(); curHost = base(); loadHome(null, true); return; }
        wv.stopLoading(); loadId++;
        String h = "<html><head><meta name='viewport' content='width=device-width,initial-scale=1'></head><body style='margin:0;background:#3b0d1c;color:#fff7f0;font-family:sans-serif;display:flex;align-items:center;justify-content:center;min-height:100vh;text-align:center'>"
            + "<div style='padding:28px;max-width:340px'><div style='font-size:54px'>💌</div><h2 style='margin:10px 0 6px'>No pude conectarme</h2>"
            + "<p style='opacity:.85;line-height:1.45'>Revisa tu internet. Si estás con datos, revisa que <b>Nuestro lugar</b> tenga permiso de usar datos móviles:<br><small>Ajustes → Aplicaciones → Nuestro lugar → Datos móviles</small></p>"
            + "<button onclick='AndroidApp.retry()' style='margin-top:14px;border:0;border-radius:999px;padding:14px 28px;background:#e85d84;color:#fff;font-size:16px;font-weight:700'>Reintentar ♥</button></div></body></html>";
        wv.loadDataWithBaseURL(null, h, "text/html", "utf-8", null);
    }

    @Override
    protected void onResume() {
        super.onResume();
        Notif.appVisible = true; // lo que llegue mientras la ve, ya lo ve en la página
        if (Loc.on(this)) new Thread(() -> Loc.sync(getApplicationContext())).start();
        Updater.check(this); // cada vez que la abre (máximo cada 30 min) revisa si hay versión nueva
        // Si estuvo cerrada un rato, carga la versión más nueva de la página.
        if (wv != null && pausedAt > 0 && System.currentTimeMillis() - pausedAt > 2 * 60 * 1000L) { loadedAt = System.currentTimeMillis(); loadHome(null); }
    }

    @Override
    protected void onPause() {
        Notif.appVisible = false;
        pausedAt = System.currentTimeMillis();
        super.onPause();
    }

    void showLinkScreen() {
        wv = null;
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setGravity(Gravity.CENTER);
        l.setPadding(70, 70, 70, 70);
        l.setBackgroundColor(Color.parseColor("#3b0d1c"));
        TextView ico = new TextView(this);
        ico.setText("💌");
        ico.setTextSize(54);
        ico.setGravity(Gravity.CENTER);
        TextView t = new TextView(this);
        t.setText(IS_JON ? "Hola Jon 💗\nPega aquí tu link especial de la página" : "Bienvenida a nuestro lugar 💗\nPega aquí el link especial que te mandó Jon");
        t.setTextColor(Color.WHITE);
        t.setTextSize(20);
        t.setGravity(Gravity.CENTER);
        t.setPadding(0, 20, 0, 30);
        final EditText e = new EditText(this);
        e.setHint("https://…");
        e.setTextColor(Color.WHITE);
        e.setHintTextColor(Color.parseColor("#99ffffff"));
        e.setSingleLine(true);
        e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            if (cm != null && cm.hasPrimaryClip()) {
                ClipData cd = cm.getPrimaryClip();
                CharSequence c = cd != null && cd.getItemCount() > 0 ? cd.getItemAt(0).getText() : null;
                if (c != null && c.toString().contains("#k=")) e.setText(c);
            }
        } catch (Exception ignored) { }
        Button btn = new Button(this);
        btn.setText("Entrar ♥");
        e.setImeOptions(EditorInfo.IME_ACTION_GO);
        e.setOnEditorActionListener((tv, a, ev) -> { btn.performClick(); return true; });
        final TextView err = new TextView(this);
        err.setTextColor(Color.parseColor("#f7c6d3"));
        err.setGravity(Gravity.CENTER);
        err.setPadding(0, 24, 0, 0);
        btn.setAllCaps(false);
        btn.setOnClickListener(v -> {
            String s = e.getText().toString().trim();
            int i = s.indexOf("#k=");
            if (i < 0) { err.setText("Ese no es el link correcto. Pídele a Jon el link especial 💗"); return; }
            String k = s.substring(i + 3).replaceAll("[^A-Za-z0-9_-].*$", "");
            sp.edit().putBoolean("linked", true).putString("k", k).apply();
            showWeb(home());
            loadedAt = System.currentTimeMillis();
        });
        l.addView(ico);
        l.addView(t);
        l.addView(e, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        l.addView(btn);
        l.addView(err);
        if (badLink) err.setText("Ese link no funcionó. Pídele a Jon el link especial y pégalo otra vez 💗");
        ScrollView sv = new ScrollView(this);
        sv.setFillViewport(true);
        sv.setBackgroundColor(Color.parseColor("#3b0d1c"));
        sv.addView(l, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(sv);
    }

    void showWeb(String url) {
        wv = new WebView(this);
        WebSettings s = wv.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setAllowFileAccess(false);
        wv.setBackgroundColor(Color.parseColor("#3b0d1c"));
        wv.addJavascriptInterface(new Bridge(), "AndroidApp");
        wv.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                Uri u = r.getUrl();
                if (ours(u.toString())) return false;
                try { startActivity(new Intent(Intent.ACTION_VIEW, u)); } catch (Exception ignored) { }
                return true;
            }

            @Override
            public void onPageStarted(WebView v, String url, Bitmap fav) { if (ours(url)) dl("empezó a cargar"); }

            @Override
            public void onReceivedHttpError(WebView v, WebResourceRequest r, WebResourceResponse e) {
                dl("HTTP " + (e != null ? e.getStatusCode() : 0) + " en " + (r != null ? shortU(r.getUrl()) : "?") + (r != null && r.isForMainFrame() ? " (página)" : ""));
                if (r != null && r.isForMainFrame()) showDiag();
            }

            @Override
            public void onReceivedSslError(WebView v, SslErrorHandler h, SslError e) { dl("error de seguridad SSL: " + (e != null ? e.getPrimaryError() : "?") + " en " + (e != null ? e.getUrl() : "")); showDiag(); h.cancel(); }

            @Override
            public void onPageCommitVisible(WebView v, String url) {
                if (ours(url)) dl("la página ya se ve");
                if (ours(url) && !loadErr) { visible = true; triedOther = false; String h = url.startsWith(MIRROR) ? MIRROR : BASE; sp.edit().putString("host", h).apply(); }
            }

            @Override
            public void onReceivedError(WebView v, WebResourceRequest r, WebResourceError e) {
                dl("error " + (e != null ? e.getErrorCode() + " " + e.getDescription() : "?") + " en " + (r != null ? shortU(r.getUrl()) : "?") + (r != null && r.isForMainFrame() ? " (página)" : ""));
                if (r != null && r.isForMainFrame() && v == wv) { showDiag(); loadErr = true; v.post(MainActivity.this::fallback); }
            }

            @Override
            public void onPageFinished(final WebView v, String url) {
                if (loadErr || !ours(url)) return;
                dl("terminó de cargar");
                pageReady = true;
                v.postDelayed(MainActivity.this::deliver, 1500);
                v.postDelayed(MainActivity.this::deliver, 4000);
                String w = sp.getString("who", "");
                if (!w.isEmpty() && ours(url)) {
                    v.evaluateJavascript("(function(){try{if(localStorage.getItem('yo-soy')!=='" + w + "'){localStorage.setItem('yo-soy','" + w + "');location.reload();return 1}}catch(e){}return 0})()", null);
                }
                // Si el link pegado no era el correcto, regresa a la pantalla para pegarlo otra vez.
                v.postDelayed(() -> {
                    if (wv != v) return;
                    v.evaluateJavascript("(document.getElementById('lock')||{}).innerText||''", r -> {
                        if (r != null && r.contains("link no es correcto")) {
                            sp.edit().putBoolean("linked", false).apply();
                            badLink = true;
                            showLinkScreen();
                        }
                    });
                }, 6000);
            }
        });
        wv.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onPermissionRequest(final PermissionRequest req) {
                runOnUiThread(() -> {
                    String o = req.getOrigin().toString(); if (!o.startsWith("https://jonatan-montiel.github.io") && !o.startsWith("https://ale-meses.vercel.app")) { req.deny(); return; }
                    if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) req.grant(req.getResources());
                    else { pendingPerm = req; requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 2); }
                });
            }

            @Override
            public boolean onShowFileChooser(WebView v, ValueCallback<Uri[]> cb, FileChooserParams p) {
                if (fileCb != null) fileCb.onReceiveValue(null);
                fileCb = cb;
                try {
                    Intent i = p.createIntent();
                    if (p.getMode() == FileChooserParams.MODE_OPEN_MULTIPLE) i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                    startActivityForResult(i, 3);
                } catch (Exception ex) { fileCb = null; return false; }
                return true;
            }
        });
        FrameLayout root = new FrameLayout(this); root.addView(wv);
        diag = new TextView(this); diag.setTextSize(11); diag.setTextColor(Color.WHITE); diag.setBackgroundColor(Color.parseColor("#E6141414")); diag.setPadding(24, 20, 24, 28);
        diag.setVisibility(android.view.View.GONE); diag.setTextIsSelectable(false);
        diag.setOnClickListener(x -> diag.setVisibility(android.view.View.GONE));
        diag.setOnLongClickListener(x -> { try { ((ClipboardManager) getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("diag", dlog.toString())); Toast.makeText(this, "Copiado", Toast.LENGTH_SHORT).show(); } catch (Exception ignored) { } return true; });
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM);
        root.addView(diag, lp);
        setContentView(root);
        loadHome(url);
    }

    boolean bioBusy = false;

    boolean bioAvailable() {
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                BiometricManager bm = getSystemService(BiometricManager.class);
                return bm != null && bm.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK) == BiometricManager.BIOMETRIC_SUCCESS;
            } else if (Build.VERSION.SDK_INT == 29) {
                BiometricManager bm = getSystemService(BiometricManager.class);
                return bm != null && bm.canAuthenticate() == BiometricManager.BIOMETRIC_SUCCESS;
            } else if (Build.VERSION.SDK_INT == 28) {
                return getPackageManager().hasSystemFeature(PackageManager.FEATURE_FINGERPRINT);
            }
        } catch (Exception ignored) { }
        return false;
    }

    void askBio() {
        if (bioBusy || Build.VERSION.SDK_INT < 28 || !bioAvailable()) return;
        bioBusy = true;
        try {
            BiometricPrompt.Builder b = new BiometricPrompt.Builder(this)
                    .setTitle("Nuestro lugar 💗")
                    .setSubtitle("Usa tu huella para entrar")
                    .setNegativeButton("Usar código", getMainExecutor(), (d, w) -> bioBusy = false);
            if (Build.VERSION.SDK_INT >= 30) b.setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_WEAK);
            b.build().authenticate(new CancellationSignal(), getMainExecutor(), new BiometricPrompt.AuthenticationCallback() {
                @Override
                public void onAuthenticationSucceeded(BiometricPrompt.AuthenticationResult r) {
                    bioBusy = false;
                    if (wv != null) wv.evaluateJavascript("window.__bioOk&&window.__bioOk()", null);
                }
                @Override
                public void onAuthenticationError(int code, CharSequence msg) { bioBusy = false; }
            });
        } catch (Exception e) { bioBusy = false; }
    }

    @Override
    public void onRequestPermissionsResult(int c, String[] p, int[] g) {
        if (c == 7 || c == 8) {
            if (c == 7 && Loc.perm(this)) askBg();
            new Thread(() -> Loc.sync(getApplicationContext())).start();
            if (wv != null) wv.postDelayed(() -> { if (wv != null) wv.evaluateJavascript("window.__locUpd&&__locUpd()", null); }, 600);
            return;
        }
        if (c == 2 && pendingPerm != null) {
            if (g.length > 0 && g[0] == PackageManager.PERMISSION_GRANTED) pendingPerm.grant(pendingPerm.getResources());
            else pendingPerm.deny();
            pendingPerm = null;
        }
    }

    @Override
    protected void onActivityResult(int c, int r, Intent d) {
        if (c == 3 && fileCb != null) {
            Uri[] res = null;
            if (r == RESULT_OK && d != null) {
                if (d.getClipData() != null) {
                    ClipData cd = d.getClipData();
                    res = new Uri[cd.getItemCount()];
                    for (int i = 0; i < cd.getItemCount(); i++) res[i] = cd.getItemAt(i).getUri();
                } else if (d.getData() != null) res = new Uri[]{d.getData()};
            }
            fileCb.onReceiveValue(res);
            fileCb = null;
        } else super.onActivityResult(c, r, d);
    }

    @Override
    public void onBackPressed() {
        if (wv != null && wv.canGoBack()) wv.goBack();
        else super.onBackPressed();
    }

    String shortU(Uri u) { String x = u.toString(); x = x.replaceAll("#.*$", ""); return x.length() > 70 ? x.substring(0, 70) + "…" : x; }

    void askBg() {
        if (Build.VERSION.SDK_INT < 29 || Loc.bg(this)) return;
        new android.app.AlertDialog.Builder(this).setTitle("📍 Un paso más")
            .setMessage("Para que la distancia se actualice aunque la app esté cerrada, en la siguiente pantalla elige «Permitir todo el tiempo». Solo se comparte la distancia, cifrada.")
            .setPositiveButton("Continuar", (d, w) -> { try { requestPermissions(new String[]{Manifest.permission.ACCESS_BACKGROUND_LOCATION}, 8); } catch (Exception ignored) { } })
            .setNegativeButton("Ahora no", null).show();
    }

    class Bridge {
        @JavascriptInterface
        public int appVersion() { try { android.content.pm.PackageInfo pi = getPackageManager().getPackageInfo(getPackageName(), 0); return (int) (Build.VERSION.SDK_INT >= 28 ? pi.getLongVersionCode() : pi.versionCode); } catch (Exception e) { return 0; } }

        /* ---- Widgets: distancia, cómo está y cuenta regresiva ---- */
        @JavascriptInterface
        public void setLoc(boolean on) {
            sp.edit().putBoolean("loc-on", on).putBoolean("loc-off-sent", false).putLong("loc-sent", 0).apply();
            runOnUiThread(() -> {
                if (on && !Loc.perm(MainActivity.this)) requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION}, 7);
                else if (on) askBg();
            });
            new Thread(() -> Loc.sync(getApplicationContext())).start();
        }

        @JavascriptInterface
        public String locState() { return !Loc.on(MainActivity.this) ? "off" : !Loc.perm(MainActivity.this) ? "noperm" : !Loc.bg(MainActivity.this) ? "nobg" : "on"; }

        @JavascriptInterface
        public String distText() { String[] st = Loc.state(MainActivity.this); return st[0] + "|" + st[1]; }

        @JavascriptInterface
        public void askBgLoc() { runOnUiThread(MainActivity.this::askBg); }

        @JavascriptInterface
        public void setInfo(String key, String json) {
            if (!"cuenta".equals(key) && !"estado".equals(key)) return;
            try {
                if ("estado".equals(key)) { org.json.JSONObject n = new org.json.JSONObject(json), o = new org.json.JSONObject(sp.getString("estado", "{}")); if (n.optString("d").equals(o.optString("d"))) { java.util.Iterator<String> it = o.keys(); while (it.hasNext()) { String k = it.next(); if (!n.has(k)) n.put(k, o.get(k)); } } json = n.toString(); }
            } catch (Exception ignored) { }
            sp.edit().putString(key, json).apply();
            WidgetCuenta.refresh(MainActivity.this); WidgetEstado.refresh(MainActivity.this);
        }

        /* ---- Compartir en WhatsApp, Facebook, Instagram… ---- */
        @JavascriptInterface
        public boolean shareImage(String b64, String text) {
            try {
                byte[] data = Base64.decode(b64.replaceFirst("^data:[^,]*,", ""), Base64.DEFAULT);
                java.io.File dir = new java.io.File(getCacheDir(), "share"); dir.mkdirs();
                java.io.File[] old = dir.listFiles(); if (old != null) for (java.io.File o : old) if (System.currentTimeMillis() - o.lastModified() > 3600000L) o.delete();
                String name = "nl" + System.currentTimeMillis() + ".jpg";
                try (java.io.FileOutputStream os = new java.io.FileOutputStream(new java.io.File(dir, name))) { os.write(data); }
                Uri u = Uri.parse("content://" + getPackageName() + ".share/" + name);
                Intent i = new Intent(Intent.ACTION_SEND).setType("image/jpeg").putExtra(Intent.EXTRA_STREAM, u).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                if (text != null && !text.isEmpty()) i.putExtra(Intent.EXTRA_TEXT, text);
                i.setClipData(ClipData.newRawUri("", u));
                final Intent ch = Intent.createChooser(i, "Compartir 💗");
                runOnUiThread(() -> { try { startActivity(ch); } catch (Exception ignored) { } });
                return true;
            } catch (Exception e) { return false; }
        }

        @JavascriptInterface
        public boolean shareText(String text) {
            try {
                final Intent ch = Intent.createChooser(new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), "Compartir 💗");
                runOnUiThread(() -> { try { startActivity(ch); } catch (Exception ignored) { } });
                return true;
            } catch (Exception e) { return false; }
        }

        @JavascriptInterface
        public void saveKey(String k) { if (k != null && k.matches("[A-Za-z0-9_-]{40,}") && !k.equals(sp.getString("k", ""))) sp.edit().putString("k", k).apply(); }

        @JavascriptInterface
        public void diag(String m) { dl("página: " + m); }

        @JavascriptInterface
        public void retry() { runOnUiThread(() -> { triedOther = false; curHost = base(); loadHome(null, false); }); }

        @JavascriptInterface
        public void setTopic(String t) {
            String nt = t == null ? "" : t.trim();
            boolean changed = !nt.equals(sp.getString("topic", ""));
            sp.edit().putString("topic", nt).apply();
            PollJob.schedule(MainActivity.this);
            if (changed) stopService(new Intent(MainActivity.this, StreamService.class));
            StreamService.start(MainActivity.this);
        }

        @JavascriptInterface
        public void relink() {
            sp.edit().clear().apply();
            stopService(new Intent(MainActivity.this, StreamService.class));
            runOnUiThread(MainActivity.this::showLinkScreen);
        }

        @JavascriptInterface
        public boolean isApp() { return true; }

        @JavascriptInterface
        public boolean hasBio() { return bioAvailable(); }
        @JavascriptInterface
        public void setWidget(String json) { sp.edit().putString("widget", json).apply(); Widget.refresh(MainActivity.this); }

        @JavascriptInterface
        public void setRacha(int n) { sp.edit().putInt("racha", n).apply(); }

        @JavascriptInterface
        public void visited() { sp.edit().putString("visto", Reminder.today()).apply(); }

        @JavascriptInterface
        public void setOther(String topic, String myName) { sp.edit().putString("other", topic == null ? "" : topic.trim()).putString("myName", myName == null ? "" : myName).apply(); }

        @JavascriptInterface
        public int takeExtrano() { int n = sp.getInt("pendExtrano", 0); sp.edit().putInt("pendExtrano", 0).apply(); return n; }

        @JavascriptInterface
        public String saveFile(String name, String text) {
            try {
                byte[] data = text.getBytes("UTF-8");
                if (Build.VERSION.SDK_INT >= 29) {
                    ContentValues cv = new ContentValues();
                    cv.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
                    cv.put(MediaStore.MediaColumns.MIME_TYPE, "application/json");
                    cv.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
                    Uri u = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
                    try (OutputStream os = getContentResolver().openOutputStream(u)) { os.write(data); }
                    return "Descargas/" + name;
                } else {
                    java.io.File f = new java.io.File(getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), name);
                    try (OutputStream os = new java.io.FileOutputStream(f)) { os.write(data); }
                    return f.getAbsolutePath();
                }
            } catch (Exception e) { return ""; }
        }


        @JavascriptInterface
        public void bio() { runOnUiThread(MainActivity.this::askBio); }
    }
}
