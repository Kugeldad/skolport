package se.skolport;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ContentResolver;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.DocumentsContract;
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
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.Locale;

/**
 * Shows one service in the system WebView.
 *
 * - The title bar always shows the domain of the current page.
 * - Clicking a link to a site outside InfoMentor/Skola24 asks first (once per site).
 * - BankID "on this device" links go to the BankID app with redirect=null, so Android
 *   returns here when the user is done and the page completes the login by itself.
 * - Downloads are fetched by the app itself and saved where the user picks; session
 *   cookies are never handed to the system download service.
 */
public class WebActivity extends Activity {

    static final String EXTRA_URL = "url";
    static final String EXTRA_TITLE = "title";

    private static final String BANKID_PACKAGE = "com.bankid.bus";
    private static final String BANKID_HOST = "app.bankid.com";
    private static final int REQ_FILE = 1;
    private static final int REQ_SAVE = 2;
    /** Keep page scripts running this long after handing off to BankID, so the login can complete. */
    private static final long BANKID_GRACE_MS = 5 * 60 * 1000L;

    private final Handler main = new Handler(Looper.getMainLooper());
    private WebView web;
    private TextView title;
    private String startUrl;
    private boolean loading;
    private ValueCallback<Uri[]> fileCallback;
    private PendingDownload pendingDownload;
    private String lastBankIdUri;
    private long lastBankIdAt;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        startUrl = getIntent().getStringExtra(EXTRA_URL);
        if (startUrl == null) {
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

        // The domain is the security-relevant part, so cut from the start: "…skola24.se".
        title = Ui.text(this, "", 15, true);
        title.setGravity(Gravity.CENTER);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.START);
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
        showHost(startUrl);

        if (state == null || web.restoreState(state) == null) {
            web.loadUrl(startUrl);
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
        s.setAllowContentAccess(false);
        s.setMediaPlaybackRequiresUserGesture(true);
        // Look like the ordinary mobile browser; some login pages treat in-app WebViews differently.
        String ua = s.getUserAgentString();
        if (ua != null) s.setUserAgentString(ua.replace("; wv)", ")"));

        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        cm.setAcceptThirdPartyCookies(web,
                Privacy.prefs(this).getBoolean(Privacy.KEY_THIRD_PARTY_COOKIES, false));

        web.setWebViewClient(new Client());
        web.setWebChromeClient(new Chrome());
        web.setDownloadListener(new Downloads());
    }

    private void showHost(String url) {
        String host = url == null ? null : Uri.parse(url).getHost();
        String text = host == null ? "" : host;
        title.setText(loading ? text + " …" : text);
    }

    // ---------------------------------------------------------------- navigation

    private boolean handleNavigation(Uri u, boolean mainFrame, boolean gesture) {
        String scheme = lower(u.getScheme());
        if (isBankId(u)) {
            openBankId(u);
            return true;
        }
        if (scheme.equals("https") || scheme.equals("http")) {
            // Redirects, auto-submitted login forms and iframes are part of login flows: allow.
            if (!mainFrame || !gesture) return false;
            if (Privacy.hostMatches(u.getHost(), Privacy.trustedSites(this, startUrl))) return false;
            confirmOtherSite(u);
            return true;
        }
        if (scheme.isEmpty() || scheme.equals("about") || scheme.equals("data")
                || scheme.equals("blob") || scheme.equals("javascript")) {
            return false;
        }
        if (scheme.equals("intent")) {
            handleIntentUrl(u.toString());
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

    /** A clicked link leads outside InfoMentor/Skola24: show the domain and ask. */
    private void confirmOtherSite(final Uri u) {
        final String host = u.getHost() == null ? "" : u.getHost();
        new AlertDialog.Builder(this)
                .setTitle("Öppna annan webbplats?")
                .setMessage(host + "\n\nSidan ligger utanför InfoMentor och Skola24. Öppna den bara om du "
                        + "väntade dig den, till exempel kommunens inloggning. Väljer du att lita på den "
                        + "frågar appen inte igen för " + Privacy.siteOf(host) + ".")
                .setPositiveButton("Lita på och öppna", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        Privacy.trust(WebActivity.this, host);
                        web.loadUrl(u.toString());
                    }
                })
                .setNeutralButton("Webbläsare", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        Intent i = new Intent(Intent.ACTION_VIEW, u);
                        i.addCategory(Intent.CATEGORY_BROWSABLE);
                        try {
                            startActivity(i);
                        } catch (ActivityNotFoundException e) {
                            Ui.toast(WebActivity.this, "Det finns ingen webbläsare på telefonen.");
                        }
                    }
                })
                .setNegativeButton("Avbryt", null)
                .show();
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
            Uri f = Uri.parse(fallback);
            if (Privacy.hostMatches(f.getHost(), Privacy.trustedSites(this, startUrl))) web.loadUrl(fallback);
            else confirmOtherSite(f);
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
        if (isFinishing() && Privacy.prefs(this).getBoolean(Privacy.KEY_LOGOUT_ON_LEAVE, false)) {
            Privacy.clearSessions(this);
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
        if (requestCode == REQ_SAVE) {
            PendingDownload p = pendingDownload;
            pendingDownload = null;
            if (p != null && resultCode == RESULT_OK && data != null && data.getData() != null) {
                save(p, data.getData());
            }
            return;
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    // ---------------------------------------------------------------- clients

    private class Client extends WebViewClient {
        /**
         * Overrides WebViewClient.shouldOverrideUrlLoading(WebView, WebResourceRequest), added in
         * API 24. It is missing from the API 23 stubs this is compiled against, so no @Override;
         * minSdk is 24, so Android always calls this one.
         */
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
            return handleNavigation(req.getUrl(), req.isForMainFrame(), req.hasGesture());
        }

        @Override
        public boolean shouldOverrideUrlLoading(WebView view, String url) {
            return handleNavigation(Uri.parse(url), true, true);
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
        public void onPageStarted(WebView view, String url, Bitmap favicon) {
            loading = true;
            showHost(url);
        }

        @Override
        public void onPageFinished(WebView view, String url) {
            loading = false;
            showHost(view.getUrl());
        }

        @Override
        public void doUpdateVisitedHistory(WebView view, String url, boolean isReload) {
            showHost(url);
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

    // ---------------------------------------------------------------- downloads

    private static final class PendingDownload {
        final String url;
        final String userAgent;
        final String fileName;

        PendingDownload(String url, String userAgent, String fileName) {
            this.url = url;
            this.userAgent = userAgent;
            this.fileName = fileName;
        }
    }

    /** Ask where to save; the file is fetched only after the user has picked a place. */
    private class Downloads implements DownloadListener {
        @Override
        public void onDownloadStart(String url, String userAgent, String contentDisposition,
                                    String mimeType, long contentLength) {
            if (url == null || !url.startsWith("https://")) {
                Ui.toast(WebActivity.this, "Den här filen kan inte hämtas i appen.");
                return;
            }
            String file = URLUtil.guessFileName(url, contentDisposition, mimeType);
            pendingDownload = new PendingDownload(url, userAgent, file);
            Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType(mimeType != null && !mimeType.isEmpty() ? mimeType : "application/octet-stream");
            i.putExtra(Intent.EXTRA_TITLE, file);
            try {
                startActivityForResult(i, REQ_SAVE);
            } catch (ActivityNotFoundException e) {
                pendingDownload = null;
                Ui.toast(WebActivity.this, "Det finns ingen filväljare på telefonen, så filen kan inte sparas.");
            }
        }
    }

    private void save(final PendingDownload p, final Uri target) {
        Ui.toast(this, "Hämtar " + p.fileName + " …");
        final ContentResolver cr = getApplicationContext().getContentResolver();
        new Thread(new Runnable() {
            @Override public void run() {
                boolean ok;
                try {
                    ok = fetch(p, target, cr);
                } catch (IOException | RuntimeException e) {
                    ok = false;
                }
                if (!ok) {
                    try {
                        DocumentsContract.deleteDocument(cr, target);
                    } catch (Exception ignored) {
                    }
                }
                final String msg = ok ? "Sparat: " + p.fileName : "Filen gick inte att hämta.";
                main.post(new Runnable() {
                    @Override public void run() { Ui.toast(getApplicationContext(), msg); }
                });
            }
        }, "skolport-download").start();
    }

    /**
     * Downloads over HTTPS only. Redirects are followed by hand so that each hop gets only the
     * cookies that belong to its own site, and never over plain HTTP.
     */
    private static boolean fetch(PendingDownload p, Uri target, ContentResolver cr) throws IOException {
        String url = p.url;
        for (int hop = 0; hop < 6; hop++) {
            if (!url.startsWith("https://")) return false;
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setInstanceFollowRedirects(false);
            c.setConnectTimeout(20000);
            c.setReadTimeout(60000);
            String cookies = CookieManager.getInstance().getCookie(url);
            if (cookies != null) c.setRequestProperty("Cookie", cookies);
            if (p.userAgent != null) c.setRequestProperty("User-Agent", p.userAgent);
            try {
                int code = c.getResponseCode();
                if (code >= 300 && code < 400) {
                    String location = c.getHeaderField("Location");
                    if (location == null) return false;
                    url = new URL(new URL(url), location).toString();
                    continue;
                }
                if (code != HttpURLConnection.HTTP_OK) return false;
                InputStream in = c.getInputStream();
                OutputStream out = cr.openOutputStream(target);
                if (out == null) {
                    close(in);
                    return false;
                }
                try {
                    byte[] buf = new byte[64 * 1024];
                    int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                    out.flush();
                } finally {
                    close(in);
                    close(out);
                }
                return true;
            } finally {
                c.disconnect();
            }
        }
        return false;
    }

    private static void close(Closeable c) {
        try {
            if (c != null) c.close();
        } catch (IOException ignored) {
        }
    }
}
