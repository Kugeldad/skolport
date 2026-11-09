package se.skolport;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.webkit.CookieManager;
import android.webkit.WebStorage;
import android.webkit.WebView;

import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/** Settings, trusted sites and clearing of web data. */
final class Privacy {
    private Privacy() {}

    static final String PREFS = "skolport";
    static final String KEY_LOGOUT_ON_LEAVE = "logout_on_leave";
    static final String KEY_THIRD_PARTY_COOKIES = "third_party_cookies";
    static final String KEY_TRUSTED_SITES = "trusted_sites";

    /** Sites that never need confirmation. The start address's site is added at runtime. */
    static final String[] BASE_SITES = {"infomentor.se", "skola24.se", "skola24.com", "bankid.com"};

    static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** login.uppsala.se -> uppsala.se (last two labels; good enough for .se/.com/.nu). */
    static String siteOf(String host) {
        if (host == null) return "";
        String h = host.toLowerCase(Locale.ROOT);
        if (h.endsWith(".")) h = h.substring(0, h.length() - 1);
        int last = h.lastIndexOf('.');
        if (last <= 0) return h;
        int prev = h.lastIndexOf('.', last - 1);
        return prev < 0 ? h : h.substring(prev + 1);
    }

    static boolean hostMatches(String host, Iterable<String> sites) {
        if (host == null) return false;
        String h = host.toLowerCase(Locale.ROOT);
        for (String s : sites) {
            if (s == null || s.isEmpty()) continue;
            if (h.equals(s) || h.endsWith("." + s)) return true;
        }
        return false;
    }

    static Set<String> trustedSites(Context c, String startUrl) {
        Set<String> all = new HashSet<String>();
        Collections.addAll(all, BASE_SITES);
        all.addAll(prefs(c).getStringSet(KEY_TRUSTED_SITES, Collections.<String>emptySet()));
        if (startUrl != null) all.add(siteOf(Uri.parse(startUrl).getHost()));
        return all;
    }

    static void trust(Context c, String host) {
        SharedPreferences p = prefs(c);
        Set<String> s = new HashSet<String>(p.getStringSet(KEY_TRUSTED_SITES, Collections.<String>emptySet()));
        s.add(siteOf(host));
        p.edit().putStringSet(KEY_TRUSTED_SITES, s).apply();
    }

    static void forgetTrusted(Context c) {
        prefs(c).edit().remove(KEY_TRUSTED_SITES).apply();
    }

    /** Removes cookies, web storage, cache and form data for every site. */
    static void clearSessions(Context c) {
        CookieManager cm = CookieManager.getInstance();
        cm.removeAllCookies(null);
        cm.flush();
        WebStorage.getInstance().deleteAllData();
        WebView w = new WebView(c);
        w.clearCache(true);
        w.clearFormData();
        w.clearHistory();
        w.destroy();
    }
}
