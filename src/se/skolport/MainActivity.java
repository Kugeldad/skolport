package se.skolport;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Locale;

/** Start screen: pick InfoMentor or Skola24. Long-press a button to change its address. */
public class MainActivity extends Activity {

    static final String KEY_INFOMENTOR = "url_infomentor";
    static final String KEY_SKOLA24 = "url_skola24";
    static final String DEFAULT_INFOMENTOR = "https://hub.infomentor.se";
    static final String SKOLA24_LIST = "https://www.skola24.se";

    private SharedPreferences prefs;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = Privacy.prefs(this);
        // Fresh start (also after the app was swiped away): log out if the user asked for that.
        if (state == null && prefs.getBoolean(Privacy.KEY_LOGOUT_ON_LEAVE, false)) {
            Privacy.clearSessions(this);
        }

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_VERTICAL);
        root.setBackgroundColor(Color.WHITE);
        root.setFitsSystemWindows(true);
        int pad = Ui.dp(this, 24);
        root.setPadding(pad, pad, pad, pad);

        TextView heading = Ui.text(this, "Välj tjänst", 20, true);
        heading.setGravity(Gravity.CENTER);
        root.addView(heading, matchWrap(0));

        Button infomentor = Ui.button(this, "InfoMentor", 28, 3);
        infomentor.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { open(KEY_INFOMENTOR, "InfoMentor"); }
        });
        infomentor.setOnLongClickListener(new View.OnLongClickListener() {
            @Override public boolean onLongClick(View v) { editUrl(KEY_INFOMENTOR, "InfoMentor"); return true; }
        });
        root.addView(infomentor, fixed(110, 20));

        Button skola24 = Ui.button(this, "Skola24", 28, 3);
        skola24.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { open(KEY_SKOLA24, "Skola24"); }
        });
        skola24.setOnLongClickListener(new View.OnLongClickListener() {
            @Override public boolean onLongClick(View v) { editUrl(KEY_SKOLA24, "Skola24"); return true; }
        });
        root.addView(skola24, fixed(110, 20));

        TextView hint = Ui.text(this, "Håll in en knapp för att ändra adressen.", 14, false);
        hint.setGravity(Gravity.CENTER);
        root.addView(hint, matchWrap(24));

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);

        Button settings = Ui.button(this, "Inställningar", 16, 2);
        settings.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { showSettings(); }
        });
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(0, Ui.dp(this, 48), 1f);
        sl.rightMargin = Ui.dp(this, 6);
        row.addView(settings, sl);

        Button logout = Ui.button(this, "Logga ut", 16, 2);
        logout.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { confirmLogout(); }
        });
        LinearLayout.LayoutParams ll = new LinearLayout.LayoutParams(0, Ui.dp(this, 48), 1f);
        ll.leftMargin = Ui.dp(this, 6);
        row.addView(logout, ll);

        root.addView(row, matchWrap(16));

        setContentView(root);
    }

    private LinearLayout.LayoutParams matchWrap(int topDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Ui.dp(this, topDp);
        return lp;
    }

    private LinearLayout.LayoutParams fixed(int heightDp, int topDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, Ui.dp(this, heightDp));
        lp.topMargin = Ui.dp(this, topDp);
        return lp;
    }

    private String currentUrl(String key) {
        String url = prefs.getString(key, null);
        if (url == null && KEY_INFOMENTOR.equals(key)) url = DEFAULT_INFOMENTOR;
        return url;
    }

    private void open(String key, String name) {
        String url = currentUrl(key);
        if (url == null) {
            askSkola24Domain();
            return;
        }
        launch(url, name);
    }

    private void launch(String url, String name) {
        Intent i = new Intent(this, WebActivity.class);
        i.putExtra(WebActivity.EXTRA_URL, url);
        i.putExtra(WebActivity.EXTRA_TITLE, name);
        startActivity(i);
        overridePendingTransition(0, 0);
    }

    private EditText input(String hint, String value) {
        EditText in = new EditText(this);
        in.setSingleLine(true);
        in.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_VARIATION_URI
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        in.setHint(hint);
        if (value != null) {
            in.setText(value);
            in.setSelection(value.length());
        }
        return in;
    }

    private View padded(View v) {
        FrameLayout f = new FrameLayout(this);
        int p = Ui.dp(this, 20);
        f.setPadding(p, Ui.dp(this, 8), p, 0);
        f.addView(v);
        return f;
    }

    /** First use of Skola24: ask for the municipality's Skola24 name and use its BankID (-sso) address. */
    private void askSkola24Domain() {
        final EditText in = input("t.ex. uppsala", null);
        new AlertDialog.Builder(this)
                .setTitle("Skola24")
                .setMessage("Skriv det som står före .skola24.se i kommunens Skola24-adress "
                        + "(t.ex. uppsala).")
                .setView(padded(in))
                .setPositiveButton("Spara", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        String domain = normalizeDomain(in.getText().toString());
                        if (domain == null) {
                            Ui.toast(MainActivity.this, "Namnet gick inte att använda. Försök igen.");
                            return;
                        }
                        String url = "https://" + domain + ".skola24.se";
                        prefs.edit().putString(KEY_SKOLA24, url).apply();
                        launch(url, "Skola24");
                    }
                })
                .setNeutralButton("Välj i lista", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) { launch(SKOLA24_LIST, "Skola24"); }
                })
                .setNegativeButton("Avbryt", null)
                .show();
    }

    static String normalizeDomain(String raw) {
        String d = raw.trim().toLowerCase(Locale.ROOT)
                .replace("å", "a").replace("ä", "a").replace("ö", "o").replace("é", "e");
        d = d.replaceFirst("^https?://", "");
        int slash = d.indexOf('/');
        if (slash >= 0) d = d.substring(0, slash);
        if (d.endsWith(".skola24.se")) d = d.substring(0, d.length() - ".skola24.se".length());
        d = d.replace(' ', '-');
        return d.matches("[a-z0-9][a-z0-9-]*") ? d : null;
    }

    /** Long-press: edit the full start address of a service. */
    private void editUrl(final String key, final String name) {
        String current = currentUrl(key);
        final EditText in = input("https://…", current);
        AlertDialog.Builder b = new AlertDialog.Builder(this)
                .setTitle("Adress för " + name)
                .setView(padded(in))
                .setPositiveButton("Spara", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        String url = normalizeUrl(in.getText().toString());
                        if (url == null) {
                            Ui.toast(MainActivity.this, "Adressen måste börja med https://");
                            return;
                        }
                        prefs.edit().putString(key, url).apply();
                        Ui.toast(MainActivity.this, "Sparat.");
                    }
                })
                .setNeutralButton("Återställ", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        prefs.edit().remove(key).apply();
                        Ui.toast(MainActivity.this, "Återställt.");
                    }
                })
                .setNegativeButton("Avbryt", null);
        b.show();
    }

    static String normalizeUrl(String raw) {
        String s = raw.trim();
        if (s.isEmpty()) return null;
        if (!s.contains("://")) s = "https://" + s;
        Uri u = Uri.parse(s);
        if (!"https".equalsIgnoreCase(u.getScheme()) || u.getHost() == null || u.getHost().isEmpty()) return null;
        return s;
    }

    private void confirmLogout() {
        new AlertDialog.Builder(this)
                .setTitle("Logga ut från allt?")
                .setMessage("Inloggningar, sparad webbdata och webbplatser du har litat på tas bort. "
                        + "Adresserna och inställningarna ligger kvar.")
                .setPositiveButton("Logga ut", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        Privacy.clearSessions(MainActivity.this);
                        Privacy.forgetTrusted(MainActivity.this);
                        Ui.toast(MainActivity.this, "Utloggad.");
                    }
                })
                .setNegativeButton("Avbryt", null)
                .show();
    }

    private void showSettings() {
        final String[] keys = {Privacy.KEY_LOGOUT_ON_LEAVE, Privacy.KEY_THIRD_PARTY_COOKIES};
        String[] labels = {
                "Logga ut automatiskt när jag lämnar en tjänst eller stänger appen",
                "Tillåt tredjepartscookies (behövs ibland för inloggning)",
        };
        boolean[] checked = {prefs.getBoolean(keys[0], false), prefs.getBoolean(keys[1], false)};
        new AlertDialog.Builder(this)
                .setTitle("Inställningar")
                .setMultiChoiceItems(labels, checked, new DialogInterface.OnMultiChoiceClickListener() {
                    @Override public void onClick(DialogInterface d, int which, boolean isChecked) {
                        prefs.edit().putBoolean(keys[which], isChecked).apply();
                    }
                })
                .setNeutralButton("Glöm betrodda sidor", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        Privacy.forgetTrusted(MainActivity.this);
                        Ui.toast(MainActivity.this, "Appen frågar igen innan andra webbplatser öppnas.");
                    }
                })
                .setPositiveButton("Klar", null)
                .show();
    }
}
