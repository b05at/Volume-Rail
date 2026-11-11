package at.b05.volumerail;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity {

    private static final int WHITE = Color.WHITE;
    private static final int GRAY = 0xFF9AA4AE;
    private static final int ACCENT = 0xFF4FC3F7;
    private static final int AMBER = 0xFFFFC107;
    private static final int CARD = 0xFF1E2227;

    private static final String ADB_CMD =
            "adb shell settings put secure enabled_accessibility_services "
                    + "at.b05.volumerail/at.b05.volumerail.FineVolumeService\n"
                    + "adb shell settings put secure accessibility_enabled 1";

    private interface Fmt {
        String f(int v);
    }

    private final Handler h = new Handler(Looper.getMainLooper());
    private SharedPreferences prefs;
    private AudioManager am;

    private TextView status, levelText, eqInfo;
    private Button minus, plus;
    private SeekBar seek;
    private boolean seekTouch;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            refresh();
            h.postDelayed(this, 500);
        }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = Prefs.get(this);
        am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);

        ScrollView sv = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(20), dp(20), dp(28));
        sv.addView(root);

        TextView title = text("Volume-Rail", 26, WHITE);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(title);
        root.addView(text("Lautstärke in feineren Schritten", 14, GRAY));

        // --- Dienst ---
        LinearLayout c1 = card(root, "Dienst");
        status = text("", 15, WHITE);
        c1.addView(status);
        Button open = button("Bedienungshilfen öffnen");
        open.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openA11y();
            }
        });
        c1.addView(open, wrap(dp(10)));
        c1.addView(text("Fehlt die Einstellung am Panel, aktivierst du den Dienst per ADB:", 12, GRAY), wrap(dp(10)));
        TextView adb = text(ADB_CMD, 11, WHITE);
        adb.setTypeface(Typeface.MONOSPACE);
        adb.setTextIsSelectable(true);
        adb.setPadding(dp(10), dp(8), dp(10), dp(8));
        adb.setBackground(round(0xFF15181C, 8));
        c1.addView(adb, wrap(dp(6)));

        // --- Lautstärke ---
        LinearLayout c2 = card(root, "Lautstärke");
        levelText = text("", 15, WHITE);
        c2.addView(levelText);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        minus = button("−");
        plus = button("+");
        minus.setTextSize(24);
        plus.setTextSize(24);
        minus.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                stepVia(-1);
            }
        });
        plus.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                stepVia(1);
            }
        });
        seek = new SeekBar(this);
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                FineVolumeService svc = FineVolumeService.instance;
                if (fromUser && svc != null && svc.controller() != null) {
                    svc.controller().setVirtual(p, false);
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar s) {
                seekTouch = true;
            }

            @Override
            public void onStopTrackingTouch(SeekBar s) {
                seekTouch = false;
            }
        });
        row.addView(minus, new LinearLayout.LayoutParams(dp(64), dp(56)));
        row.addView(seek, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(plus, new LinearLayout.LayoutParams(dp(64), dp(56)));
        c2.addView(row, wrap(dp(8)));

        // --- Kanal ---
        LinearLayout c3 = card(root, "Welche Lautstärke");
        RadioGroup rg = new RadioGroup(this);
        addStream(rg, "Medien (Musik, Sprachausgabe)", AudioManager.STREAM_MUSIC);
        addStream(rg, "Wecker", AudioManager.STREAM_ALARM);
        addStream(rg, "Benachrichtigungen", AudioManager.STREAM_NOTIFICATION);
        addStream(rg, "Klingelton", AudioManager.STREAM_RING);
        c3.addView(rg);

        // --- Verhalten ---
        LinearLayout c4 = card(root, "Verhalten");
        sw(c4, "Lautstärketasten abfangen",
                "Jeder Tastendruck ändert nur um eine Feinstufe.",
                Prefs.INTERCEPT, Prefs.DEF_INTERCEPT);
        sw(c4, "Große Sprünge verkleinern",
                "Ändert das System (z. B. die +/- Tasten im Panel-Menü) um mehrere Stufen, setzt Volume-Rail auf eine Feinstufe in dieselbe Richtung zurück. Ziehen am Schieberegler bleibt erlaubt.",
                Prefs.CORRECT, Prefs.DEF_CORRECT);
        sw(c4, "Pegel einblenden", "Kleine Anzeige oben am Bildschirm.",
                Prefs.OVERLAY, Prefs.DEF_OVERLAY);
        sw(c4, "Rail am Bildschirmrand",
                "Immer sichtbare Tasten am Bildschirmrand, gedrückt halten wiederholt.",
                Prefs.FLOAT, Prefs.DEF_FLOAT);
        sw(c4, "Rail links anzeigen", null, Prefs.FLOAT_LEFT, Prefs.DEF_FLOAT_LEFT);

        // --- Feinabstimmung ---
        LinearLayout c5 = card(root, "Feinabstimmung");
        stepper(c5, "Größter Sprung, der verkleinert wird", Prefs.MAX_JUMP, Prefs.DEF_MAX_JUMP, 2, 15,
                new Fmt() {
                    @Override
                    public String f(int v) {
                        return v + " Stufen";
                    }
                },
                "Größere Sprünge (z. B. Tippen weit auf den Regler) werden nicht verändert.");
        stepper(c5, "Zwischenstufen je Systemstufe", Prefs.SUBSTEPS, Prefs.DEF_SUBSTEPS, 1, 5,
                new Fmt() {
                    @Override
                    public String f(int v) {
                        return v == 1 ? "aus" : v + "×";
                    }
                },
                "Experimentell: teilt jede Systemstufe weiter auf, indem der Ton zusätzlich leicht abgesenkt wird. Nur nötig, wenn schon eine einzelne Systemstufe zu grob ist.");
        stepper(c5, "Absenkung je Zwischenstufe", Prefs.DB_TENTHS, Prefs.DEF_DB_TENTHS, 5, 50,
                new Fmt() {
                    @Override
                    public String f(int v) {
                        return (v / 10) + "," + (v % 10) + " dB";
                    }
                },
                "Passt die Zwischenstufen an den Abstand der Systemstufen an.");
        eqInfo = text("", 12, AMBER);
        c5.addView(eqInfo, wrap(dp(6)));

        setContentView(sv);
    }

    @Override
    protected void onResume() {
        super.onResume();
        h.removeCallbacks(tick);
        h.post(tick);
    }

    @Override
    protected void onPause() {
        super.onPause();
        h.removeCallbacks(tick);
    }

    private void stepVia(int dir) {
        FineVolumeService svc = FineVolumeService.instance;
        if (svc != null && svc.controller() != null) {
            svc.controller().step(dir, false);
            refresh();
        }
    }

    private void refresh() {
        FineVolumeService svc = FineVolumeService.instance;
        VolumeController vc = svc != null ? svc.controller() : null;
        boolean on = vc != null;
        if (on) {
            status.setText("✓ Aktiv");
            status.setTextColor(ACCENT);
        } else {
            status.setText(isA11yEnabled()
                    ? "Aktiviert, startet gerade …"
                    : "Nicht aktiv – schalte „Volume-Rail“ unter Bedienungshilfen ein.");
            status.setTextColor(AMBER);
        }
        minus.setEnabled(on);
        plus.setEnabled(on);
        seek.setEnabled(on);

        int stream = prefs.getInt(Prefs.STREAM, Prefs.DEF_STREAM);
        int idx = am.getStreamVolume(stream);
        int maxIdx = am.getStreamMaxVolume(stream);
        if (on) {
            int v = vc.currentVirtual();
            int max = vc.maxVirtual();
            int pct = Math.round(v * 100f / Math.max(1, max));
            String s = pct + " %   ·   Stufe " + v + " von " + max;
            if (vc.subSteps() > 1) s += "\nSystemstufe " + idx + " von " + maxIdx;
            levelText.setText(s);
            if (!seekTouch) {
                seek.setMax(max);
                seek.setProgress(v);
            }
            eqInfo.setText(vc.subStepsSupported() ? ""
                    : "Zwischenstufen werden von diesem Gerät nicht unterstützt.");
        } else {
            levelText.setText("Systemstufe " + idx + " von " + maxIdx);
            if (!seekTouch) {
                seek.setMax(Math.max(1, maxIdx));
                seek.setProgress(idx);
            }
        }
    }

    private boolean isA11yEnabled() {
        String s = Settings.Secure.getString(getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (s == null) return false;
        String full = new ComponentName(this, FineVolumeService.class).flattenToString();
        String shortName = new ComponentName(this, FineVolumeService.class).flattenToShortString();
        for (String part : s.split(":")) {
            if (part.equalsIgnoreCase(full) || part.equalsIgnoreCase(shortName)) return true;
        }
        return false;
    }

    private void openA11y() {
        try {
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception e) {
            try {
                startActivity(new Intent(Settings.ACTION_SETTINGS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (Exception e2) {
                Toast.makeText(this, "Einstellungen nicht erreichbar – bitte per ADB aktivieren.",
                        Toast.LENGTH_LONG).show();
            }
        }
    }

    // ---------- UI-Helfer ----------

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private GradientDrawable round(int color, int r) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(r));
        return g;
    }

    private LinearLayout.LayoutParams wrap(int top) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = top;
        return lp;
    }

    private TextView text(String s, int sp, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        return t;
    }

    private Button button(String s) {
        Button b = new Button(this);
        b.setText(s);
        b.setAllCaps(false);
        return b;
    }

    private LinearLayout card(LinearLayout parent, String header) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(16), dp(14), dp(16), dp(16));
        c.setBackground(round(CARD, 14));
        TextView hd = text(header, 13, ACCENT);
        hd.setTypeface(Typeface.DEFAULT_BOLD);
        hd.setPadding(0, 0, 0, dp(6));
        c.addView(hd);
        parent.addView(c, wrap(dp(14)));
        return c;
    }

    private void addStream(RadioGroup rg, String label, final int stream) {
        RadioButton r = new RadioButton(this);
        r.setText(label);
        r.setTextColor(WHITE);
        r.setTextSize(15);
        r.setId(View.generateViewId());
        rg.addView(r);
        if (prefs.getInt(Prefs.STREAM, Prefs.DEF_STREAM) == stream) rg.check(r.getId());
        r.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton b, boolean checked) {
                if (checked) prefs.edit().putInt(Prefs.STREAM, stream).apply();
            }
        });
    }

    private void sw(LinearLayout p, String label, String sub, final String key, boolean def) {
        Switch s = new Switch(this);
        s.setText(label);
        s.setTextColor(WHITE);
        s.setTextSize(16);
        s.setChecked(prefs.getBoolean(key, def));
        s.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton b, boolean checked) {
                prefs.edit().putBoolean(key, checked).apply();
            }
        });
        p.addView(s, wrap(dp(10)));
        if (sub != null) p.addView(text(sub, 12, GRAY), wrap(dp(2)));
    }

    private void stepper(LinearLayout p, String label, final String key, int def,
                         final int min, final int max, final Fmt fmt, String sub) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.addView(text(label, 16, WHITE),
                new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        final int[] cur = {prefs.getInt(key, def)};
        final TextView val = text(fmt.f(cur[0]), 15, ACCENT);
        val.setGravity(Gravity.CENTER);
        Button m = button("−");
        Button pl = button("+");
        m.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (cur[0] > min) {
                    cur[0]--;
                    prefs.edit().putInt(key, cur[0]).apply();
                    val.setText(fmt.f(cur[0]));
                }
            }
        });
        pl.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (cur[0] < max) {
                    cur[0]++;
                    prefs.edit().putInt(key, cur[0]).apply();
                    val.setText(fmt.f(cur[0]));
                }
            }
        });
        row.addView(m, new LinearLayout.LayoutParams(dp(52), dp(48)));
        row.addView(val, new LinearLayout.LayoutParams(dp(84), LinearLayout.LayoutParams.WRAP_CONTENT));
        row.addView(pl, new LinearLayout.LayoutParams(dp(52), dp(48)));
        p.addView(row, wrap(dp(12)));
        if (sub != null) p.addView(text(sub, 12, GRAY), wrap(dp(2)));
    }
}
