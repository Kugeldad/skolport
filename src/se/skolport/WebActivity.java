package se.skolport;

import android.app.Activity;
import android.app.DownloadManager;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.DownloadListener;
import android.webkit.URLUtil;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.ByteArrayInputStream;
import java.net.URISyntaxException;
import java.util.Locale;

/**
 * Shows one service in the system WebView. BankID "on this device" links
 * (bankid:/// and https://app.bankid.com) are handed to the BankID app with
 * redirect=null, so Android returns here when the user is done and the page
 * finishes the login by itself.
 */
public class WebActivity extends Activity {

    static final String EXTRA_URL = "url";
    static final String EXTRA_TITLE = "title";

    private static final String BANKID_PACKAGE = "com.bankid.bus";
    private static final String BANKID_HOST = "app.bankid.com";
    private static final int REQ_FILE = 1;
    /** Keep page scripts running this long after handing off to BankID, so the login can complete. */
    private static final long BANKID_GRACE_MS = 5 * 60 * 1000L;

    private final Handler main = new Handler(Looper.getMainLooper());
    private WebView web;
    private TextView title;
    private String name = "";
    private ValueCallback<Uri[]> fileCallback;
    private String lastBankIdUri;
    private long lastBankIdAt;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        String url = getIntent().getStringExtra(EXTRA_URL);
        String t = getIntent().getStringExtra(EXTRA_TITLE);
        if (t != null) name = t;
        if (url == null) {
            finish();
            return;
        }

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.WHITE);
        root.setFitsSystemWindows(true);

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        int p = Ui.dp(this, 6);
        bar.setPadding(p, p, p, p);

        Button back = Ui.button(this, "‹ Bakåt", 16, 2);
        back.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { goBack(); }
        });
        bar.addView(back, barButton());

        title = Ui.text(this, name, 16, true);
        title.setGravity(Gravity.CENTER);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tl.leftMargin = p;
        tl.rightMargin = p;
        bar.addView(title, tl);

        Button reload = Ui.button(this, "↻", 20, 2);
        reload.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { web.reload(); }
        });
        LinearLayout.LayoutParams rl = barButton();
        rl.rightMargin = p;
        bar.addView(reload, rl);

        Button menu = Ui.button(this, "Meny", 16, 2);
        menu.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { finish(); }
        });
        bar.addView(menu, barButton());

        root.addView(bar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        View rule = new View(this);
        rule.setBackgroundColor(Color.BLACK);
        root.addView(rule, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 2)));

        web = new WebView(this);
        root.addView(web, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(root);

        configureWebView();

        if (state == null || web.restoreState(state) == null) {
            web.loadUrl(url);
        }
    }

    private LinearLayout.LayoutParams barButton() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, Ui.dp(this, 44));
    }

    private void configureWebView() {
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setBuiltInZoomControls(true);
        s.setDisplayZoomControls(false);
        s.setSupportMultipleWindows(false);          // target=_blank / window.open stay in this view
        s.setJavaScriptCanOpenWindowsAutomatically(true);
        s.setAllowFileAccess(false);
        s.setMediaPlaybackRequiresUserGesture(true);
        // Look like the ordinary mobile browser; some login pages treat in-app WebViews differently.
        String ua = s.getUserAgentString();
        if (ua != null) s.setUserAgentString(ua.replace("; wv)", ")"));

        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        cm.setAcceptThirdPartyCookies(web, true);    // SAML/e-legitimation flows hop between domains

        web.setWebViewClient(new Client());
        web.setWebChromeClient(new Chrome());
        web.setDownloadListener(new Downloads());
    }

    // ---------------------------------------------------------------- navigation

    private boolean handleUrl(String url) {
        Uri u = Uri.parse(url);
        String scheme = lower(u.getScheme());
        if (isBankId(u)) {
            openBankId(u);
            return true;
        }
        if (scheme.isEmpty() || scheme.equals("http") || scheme.equals("https") || scheme.equals("about")
                || scheme.equals("data") || scheme.equals("blob") || scheme.equals("javascript")) {
            return false;
        }
        if (scheme.equals("intent")) {
            handleIntentUrl(url);
            return true;
        }
        // tel:, mailto:, sms: and other app links
        Intent i = new Intent(Intent.ACTION_VIEW, u);
        i.addCategory(Intent.CATEGORY_BROWSABLE);
        try {
            startActivity(i);
        } catch (ActivityNotFoundException e) {
            Ui.toast(this, "Det finns ingen app som kan öppna länken.");
        }
        return true;
    }

    private void handleIntentUrl(String url) {
        Intent intent;
        try {
            intent = Intent.parseUri(url, Intent.URI_INTENT_SCHEME);
        } catch (URISyntaxException e) {
            return;
        }
        Uri data = intent.getData();
        if (BANKID_PACKAGE.equals(intent.getPackage()) || (data != null && isBankId(data))) {
            openBankId(data != null ? data : Uri.parse("bankid:///"));
            return;
        }
        intent.addCategory(Intent.CATEGORY_BROWSABLE);
        intent.setComponent(null);
        intent.setSelector(null);
        try {
            startActivity(intent);
            return;
        } catch (ActivityNotFoundException ignored) {
        }
        String fallback = intent.getStringExtra("browser_fallback_url");
        if (fallback != null && fallback.startsWith("https://")) {
            web.loadUrl(fallback);
        } else {
            Ui.toast(this, "Det finns ingen app som kan öppna länken.");
        }
    }

    private static String lower(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT);
    }

    private static boolean isBankId(Uri u) {
        String scheme = lower(u.getScheme());
        if (scheme.equals("bankid")) return true;
        return scheme.equals("https") && BANKID_HOST.equalsIgnoreCase(u.getHost());
    }

    private static String token(Uri u) {
        try {
            if (u.isHierarchical()) return u.getQueryParameter("autostarttoken");
        } catch (RuntimeException ignored) {
        }
        return null;
    }

    /** Start the BankID app on this device. redirect=null makes BankID close and return here when done. */
    private void openBankId(Uri source) {
        String token = token(source);
        String query = (token != null && !token.isEmpty())
                ? "?autostarttoken=" + Uri.encode(token) + "&redirect=null"
                : "?redirect=null";

        long now = SystemClock.elapsedRealtime();
        if (query.equals(lastBankIdUri) && now - lastBankIdAt < 3000) return; // same link fired twice
        lastBankIdUri = query;
        lastBankIdAt = now;

        Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse("https://" + BANKID_HOST + "/" + query));
        i.setPackage(BANKID_PACKAGE);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(i);
            return;
        } catch (ActivityNotFoundException ignored) {
        }
        Intent legacy = new Intent(Intent.ACTION_VIEW, Uri.parse("bankid:///" + query));
        legacy.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(legacy);
        } catch (ActivityNotFoundException e) {
            lastBankIdAt = 0;
            Ui.toast(this, "BankID-appen hittades inte på telefonen. Välj BankID med QR-kod "
                    + "och skanna med en annan enhet, eller installera BankID.");
        }
    }

    private void goBack() {
        if (web.canGoBack()) web.goBack();
        else finish();
    }

    @Override
    public void onBackPressed() {
        goBack();
    }

    @Override
    public void finish() {
        super.finish();
        overridePendingTransition(0, 0);
    }

    // ---------------------------------------------------------------- lifecycle

    @Override
    protected void onResume() {
        super.onResume();
        if (web != null) web.onResume();
    }

    @Override
    protected void onPause() {
        super.onPause();
        CookieManager.getInstance().flush();
        boolean waitingForBankId = lastBankIdAt > 0
                && SystemClock.elapsedRealtime() - lastBankIdAt < BANKID_GRACE_MS;
        if (web != null && !waitingForBankId) web.onPause();
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        if (web != null) web.saveState(out);
    }

    @Override
    protected void onDestroy() {
        if (web != null) {
            ViewGroup parent = (ViewGroup) web.getParent();
            if (parent != null) parent.removeView(web);
            web.destroy();
            web = null;
        }
        super.onDestroy();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQ_FILE) {
            if (fileCallback != null) {
                fileCallback.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(resultCode, data));
                fileCallback = null;
            }
            return;
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    // ---------------------------------------------------------------- clients

    private class Client extends WebViewClient {
        @Override
        public boolean shouldOverrideUrlLoading(WebView view, String url) {
            return handleUrl(url);
        }

        /** Catches BankID links loaded inside an iframe, which may not reach shouldOverrideUrlLoading. */
        @Override
        public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest req) {
            final Uri u = req.getUrl();
            if (!req.isForMainFrame() && isBankId(u) && token(u) != null) {
                main.post(new Runnable() {
                    @Override public void run() { openBankId(u); }
                });
                return new WebResourceResponse("text/plain", "utf-8", new ByteArrayInputStream(new byte[0]));
            }
            return null;
        }

        @Override
        public void onReceivedError(WebView view, WebResourceRequest req, WebResourceError err) {
            if (isBankId(req.getUrl())) openBankId(req.getUrl());
        }

        @Override
        public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
            title.setText("Laddar…");
        }

        @Override
        public void onPageFinished(WebView view, String url) {
            title.setText(name);
        }
    }

    private class Chrome extends WebChromeClient {
        @Override
        public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
            if (fileCallback != null) fileCallback.onReceiveValue(null);
            fileCallback = callback;
            try {
                startActivityForResult(params.createIntent(), REQ_FILE);
                return true;
            } catch (ActivityNotFoundException e) {
                fileCallback = null;
                Ui.toast(WebActivity.this, "Det finns ingen filväljare på telefonen.");
                return false;
            }
        }
    }

    private class Downloads implements DownloadListener {
        @Override
        public void onDownloadStart(String url, String userAgent, String contentDisposition,
                                    String mimeType, long contentLength) {
            if (url == null || !url.startsWith("https://")) {
                Ui.toast(WebActivity.this, "Den här filen kan inte hämtas i appen.");
                return;
            }
            String file = URLUtil.guessFileName(url, contentDisposition, mimeType);
            try {
                DownloadManager.Request r = new DownloadManager.Request(Uri.parse(url));
                String cookies = CookieManager.getInstance().getCookie(url);
                if (cookies != null) r.addRequestHeader("Cookie", cookies);
                if (userAgent != null) r.addRequestHeader("User-Agent", userAgent);
                if (mimeType != null) r.setMimeType(mimeType);
                r.setTitle(file);
                r.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
                try {
                    r.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, file);
                } catch (RuntimeException e) {
                    r.setDestinationInExternalFilesDir(WebActivity.this, Environment.DIRECTORY_DOWNLOADS, file);
                }
                DownloadManager dm = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
                dm.enqueue(r);
                Ui.toast(WebActivity.this, "Hämtar " + file + " till Hämtade filer.");
            } catch (RuntimeException e) {
                Ui.toast(WebActivity.this, "Filen gick inte att hämta.");
            }
        }
    }
}
