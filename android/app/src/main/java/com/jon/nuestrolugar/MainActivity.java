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
        if (!sp.getBoolean("linked", false)) showLinkScreen();
        else if (sp.getString("who", "").isEmpty()) showWhoScreen(null);
        else showWeb(null);
    }

    @Override
    protected void onResume() {
        super.onResume();
        Notif.appVisible = true; // lo que llegue mientras la ve, ya lo ve en la página
    }

    @Override
    protected void onPause() {
        Notif.appVisible = false;
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
        t.setText("Bienvenidos a nuestro lugar 💗\nPega aquí el link especial");
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
        final String[] who = {sp.getString("who", "")};
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER);
        row.setPadding(0, 10, 0, 20);
        final Button bA = new Button(this), bJ = new Button(this);
        bA.setAllCaps(false); bJ.setAllCaps(false); btn.setAllCaps(false);
        bA.setText("Soy Ale");
        bJ.setText("Soy Jon");
        Runnable paint = () -> {
            bA.setAlpha("ella".equals(who[0]) ? 1f : .55f);
            bJ.setAlpha("yo".equals(who[0]) ? 1f : .55f);
            bA.setText(("ella".equals(who[0]) ? "✓ " : "") + "Soy Ale");
            bJ.setText(("yo".equals(who[0]) ? "✓ " : "") + "Soy Jon");
        };
        bA.setOnClickListener(v -> { who[0] = "ella"; paint.run(); });
        bJ.setOnClickListener(v -> { who[0] = "yo"; paint.run(); });
        paint.run();
        row.addView(bA);
        row.addView(bJ);
        btn.setOnClickListener(v -> {
            String s = e.getText().toString().trim();
            int i = s.indexOf("#k=");
            if (who[0].isEmpty()) { err.setText("Primero toca «Soy Ale» o «Soy Jon» 💗"); return; }
            if (i < 0) { err.setText("Ese no es el link correcto. Pídele a Jon el link especial 💗"); return; }
            sp.edit().putBoolean("linked", true).putString("who", who[0]).apply();
            showWeb(BASE + s.substring(i));
        });
        l.addView(ico);
        l.addView(t);
        TextView q = new TextView(this);
        q.setText("¿Quién eres?");
        q.setTextColor(Color.WHITE);
        q.setTextSize(17);
        q.setGravity(Gravity.CENTER);
        l.addView(q);
        l.addView(row);
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

    void showWhoScreen(String url) {
        wv = null;
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setGravity(Gravity.CENTER);
        l.setPadding(70, 70, 70, 70);
        l.setBackgroundColor(Color.parseColor("#3b0d1c"));
        TextView t = new TextView(this);
        t.setText("💗\n¿Quién usa este celular?");
        t.setTextColor(Color.WHITE);
        t.setTextSize(22);
        t.setGravity(Gravity.CENTER);
        t.setPadding(0, 0, 0, 40);
        Button bA = new Button(this), bJ = new Button(this);
        bA.setText("Soy Ale");
        bJ.setText("Soy Jon");
        bA.setOnClickListener(v -> { sp.edit().putString("who", "ella").apply(); showWeb(url); });
        bJ.setOnClickListener(v -> { sp.edit().putString("who", "yo").apply(); showWeb(url); });
        l.addView(t);
        l.addView(bA);
        l.addView(bJ);
        setContentView(l);
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
        wv.loadUrl(url != null ? url : BASE);
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
