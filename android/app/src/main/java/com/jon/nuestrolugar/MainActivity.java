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
    static String home() { return IS_JON ? BASE + "?editar" : BASE; }
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
        deliver();
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
                + "else{if(s&&window.__sharePhotos)window.__sharePhotos(s);else if(g)GOTO(g)}})()";
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

    void loadHome(String url) {
        if (wv == null) return;
        wv.getSettings().setCacheMode(online() ? WebSettings.LOAD_NO_CACHE : WebSettings.LOAD_CACHE_ELSE_NETWORK);
        pageReady = false;
        wv.loadUrl(url != null ? url : home());
    }

    @Override
    protected void onResume() {
        super.onResume();
        Notif.appVisible = true; // lo que llegue mientras la ve, ya lo ve en la página
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
            sp.edit().putBoolean("linked", true).apply();
            showWeb(home() + s.substring(i));
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
                if (u.toString().startsWith(BASE)) return false;
                try { startActivity(new Intent(Intent.ACTION_VIEW, u)); } catch (Exception ignored) { }
                return true;
            }

            @Override
            public void onPageFinished(final WebView v, String url) {
                pageReady = true;
                v.postDelayed(MainActivity.this::deliver, 1500);
                String w = sp.getString("who", "");
                if (!w.isEmpty() && url != null && url.startsWith(BASE)) {
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
                    if (!req.getOrigin().toString().startsWith("https://jonatan-montiel.github.io")) { req.deny(); return; }
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
        setContentView(wv);
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

    class Bridge {
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
