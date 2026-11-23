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
import android.widget.ScrollView;
import android.view.inputmethod.EditorInfo;

public class MainActivity extends Activity {
    static final String BASE = "https://jonatan-montiel.github.io/ale-meses/";
    static final boolean IS_JON = "yo".equals(BuildConfig.WHO);
    /** Jon abre la página con el editor; Ale la abre normal. */
    static String home() { return (IS_JON ? BASE + "?editar&v=" : BASE + "?v=") + (System.currentTimeMillis() / 1000); }
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
    }

    @Override
    protected void onResume() {
        super.onResume();
        Notif.appVisible = true; // lo que llegue mientras la ve, ya lo ve en la página
        // Si estuvo cerrada un rato, carga la versión más nueva de la página.
        if (wv != null && pausedAt > 0 && System.currentTimeMillis() - pausedAt > 2 * 60 * 1000L) { loadedAt = System.currentTimeMillis(); wv.loadUrl(home()); }
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
        wv.loadUrl(url != null ? url : home());
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
    }
}
