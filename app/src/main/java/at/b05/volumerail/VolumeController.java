package at.b05.volumerail;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.media.audiofx.Equalizer;
import android.os.Build;
import android.os.SystemClock;

/**
 * Rechnet mit "virtuellen" Feinstufen.
 *
 * Ohne Zwischenstufen (substeps = 1) entspricht eine Feinstufe genau einer
 * System-Lautstärkestufe – die App sorgt dann nur dafür, dass immer nur um
 * eine einzelne Stufe verändert wird.
 *
 * Mit Zwischenstufen (substeps = N > 1) wird jede Systemstufe in N Teile
 * geteilt. Die Zwischenwerte entstehen, indem die nächsthöhere Systemstufe
 * eingestellt und das Signal über den System-Equalizer (Audio-Session 0)
 * abgesenkt wird. Die Absenkung wird aus dem dB-Abstand der beiden
 * Systemstufen berechnet, damit die Feinstufen gleichmäßig dazwischen liegen.
 */
final class VolumeController {

    interface Listener {
        void onLevel(int virtual, int maxVirtual);
    }

    private final AudioManager am;
    private final SharedPreferences prefs;
    private Listener listener;

    private Equalizer eq;
    private boolean eqFailed;

    /** Anzahl Zwischenstufen, um die gerade abgesenkt ist (0 = keine). */
    private int att = 0;
    /** Systemstufe, zu der {@link #att} gehört. */
    private int attIndex = -1;

    /** Zuletzt gesetzte Equalizer-Absenkung in Millibel (0 = aus). */
    private int lastMb = 0;
    private boolean test;

    private int expectedIndex = -1;
    private long expectedUntil = 0;
    private long lastExternal = 0;
    private long dragUntil = 0;

    VolumeController(Context c) {
        am = (AudioManager) c.getApplicationContext().getSystemService(Context.AUDIO_SERVICE);
        prefs = Prefs.get(c);
        try {
            eq = new Equalizer(1000, 0);
            eq.setEnabled(false);
        } catch (Throwable t) {
            eqFailed = true;
            eq = null;
        }
    }

    void setListener(Listener l) {
        listener = l;
    }

    int stream() {
        return prefs.getInt(Prefs.STREAM, Prefs.DEF_STREAM);
    }

    int subSteps() {
        int n = prefs.getInt(Prefs.SUBSTEPS, Prefs.DEF_SUBSTEPS);
        if (n < 1 || eqFailed) return 1;
        return n;
    }

    boolean subStepsSupported() {
        return !eqFailed;
    }

    int currentIndex() {
        return am.getStreamVolume(stream());
    }

    int maxIndex() {
        return am.getStreamMaxVolume(stream());
    }

    int minIndex() {
        if (Build.VERSION.SDK_INT >= 28) {
            try {
                return am.getStreamMinVolume(stream());
            } catch (Throwable ignored) {
            }
        }
        return 0;
    }

    int maxVirtual() {
        return maxIndex() * subSteps();
    }

    int currentVirtual() {
        int idx = currentIndex();
        int n = subSteps();
        if (idx != attIndex && att != 0) {
            // Lautstärke wurde von außen verändert -> Absenkung verwerfen
            att = 0;
            applyEq();
        }
        int a = (idx == 0) ? 0 : Math.min(att, n - 1);
        return idx * n - a;
    }

    void step(int dir, boolean showSystemUi) {
        setVirtual(currentVirtual() + dir, showSystemUi);
    }

    void setVirtual(int v, boolean showSystemUi) {
        int n = subSteps();
        int max = maxIndex() * n;
        int min = minIndex() * n;
        if (v > max) v = max;
        if (v < min) v = min;

        int idx = (v + n - 1) / n;
        int a = idx * n - v;
        if (idx == 0) a = 0;

        int s = stream();
        int cur = am.getStreamVolume(s);
        if (idx > cur) {
            // erst absenken, dann Systemstufe erhöhen -> kein lauter Ausreißer
            att = a;
            attIndex = idx;
            applyEq();
            setSystem(s, idx, showSystemUi);
        } else {
            if (idx < cur) setSystem(s, idx, showSystemUi);
            att = a;
            attIndex = idx;
            applyEq();
        }

        if (listener != null) listener.onLevel(v, max);
    }

    /**
     * Wird bei jeder Lautstärkeänderung im System aufgerufen. Kommt die
     * Änderung nicht von uns und ist sie ein Sprung über mehrere Stufen
     * (z. B. die +/- Tasten der Panel-Oberfläche), wird sie auf eine
     * einzelne Feinstufe in dieselbe Richtung verkleinert.
     */
    void onSystemVolumeChanged(int type, int value, int prev) {
        if (type != stream()) return;
        long now = SystemClock.uptimeMillis();

        if (value == expectedIndex && now < expectedUntil) {
            expectedIndex = -1;
            return;
        }
        if (!prefs.getBoolean(Prefs.CORRECT, Prefs.DEF_CORRECT)) return;
        if (prev < 0 || value < 0 || value == prev) return;

        // Schnelle Folge von Änderungen = Schieberegler wird gezogen -> nicht eingreifen
        if (now < dragUntil) {
            dragUntil = now + 700;
            lastExternal = now;
            return;
        }
        if (now - lastExternal < 250) {
            dragUntil = now + 700;
            lastExternal = now;
            return;
        }
        lastExternal = now;

        int diff = value - prev;
        int ad = Math.abs(diff);
        int n = subSteps();
        if (ad > prefs.getInt(Prefs.MAX_JUMP, Prefs.DEF_MAX_JUMP)) return;
        if (ad == 1 && n == 1) return; // ist bereits die kleinste Stufe

        int a = (prev == attIndex) ? att : 0;
        int prevV = prev * n - (prev == 0 ? 0 : Math.min(a, n - 1));
        setVirtual(prevV + (diff > 0 ? 1 : -1), false);
    }

    /** Zwischenstufen-Absenkung entfernen (z. B. nach Einstellungsänderung). */
    void reset() {
        att = 0;
        attIndex = -1;
        applyEq();
    }

    private void setSystem(int s, int idx, boolean showSystemUi) {
        expectedIndex = idx;
        expectedUntil = SystemClock.uptimeMillis() + 800;
        try {
            am.setStreamVolume(s, idx, showSystemUi ? AudioManager.FLAG_SHOW_UI : 0);
        } catch (SecurityException ignored) {
            // z. B. Klingelton bei "Nicht stören" ohne Berechtigung
        }
    }

    /** Lautstärke einer Systemstufe in dB (0 dB = volle Lautstärke). */
    private double dbOf(int idx) {
        if (Build.VERSION.SDK_INT >= 28) {
            try {
                float d = am.getStreamVolumeDb(stream(), idx, AudioDeviceInfo.TYPE_BUILTIN_SPEAKER);
                if (!Float.isNaN(d) && !Float.isInfinite(d) && d <= 0f && d > -120f) return d;
            } catch (Throwable ignored) {
            }
        }
        // Android-Standardkurve für Lautsprecher: (1 %, -56 dB) (20 %, -34) (60 %, -11) (100 %, 0)
        double pct = idx * 100.0 / Math.max(1, maxIndex());
        double[] x = {1, 20, 60, 100};
        double[] y = {-56, -34, -11, 0};
        if (pct <= x[0]) return y[0];
        for (int i = 1; i < x.length; i++) {
            if (pct <= x[i]) return y[i - 1] + (pct - x[i - 1]) * (y[i] - y[i - 1]) / (x[i] - x[i - 1]);
        }
        return 0;
    }

    /** Nötige Absenkung für die aktuelle Zwischenstufe in Millibel (<= 0). */
    private int attMb() {
        int n = subSteps();
        if (n <= 1 || att == 0 || attIndex <= 0) return 0;
        double gap = attIndex >= 2 ? dbOf(attIndex) - dbOf(attIndex - 1) : 12.0;
        if (gap < 1.0) gap = 1.0;
        if (gap > 15.0) gap = 15.0;
        double strength = prefs.getInt(Prefs.STRENGTH, Prefs.DEF_STRENGTH) / 10.0;
        return -(int) Math.round(att / (double) n * gap * strength * 100.0);
    }

    private void applyEq() {
        int mb = test ? -1200 : attMb();
        if (mb == 0) {
            lastMb = 0;
            if (eq != null) {
                try {
                    eq.setEnabled(false);
                } catch (Throwable ignored) {
                }
            }
            return;
        }
        if (eqFailed) return;
        try {
            if (eq == null) eq = new Equalizer(1000, 0);
            short[] range = eq.getBandLevelRange();
            short lvl = (short) Math.max(range[0], mb);
            short bands = eq.getNumberOfBands();
            for (short b = 0; b < bands; b++) eq.setBandLevel(b, lvl);
            eq.setEnabled(true);
            lastMb = lvl;
        } catch (Throwable t) {
            eqFailed = true;
            att = 0;
            lastMb = 0;
            release();
        }
    }

    /** Hörtest: senkt den Ton kurz um 12 dB ab. */
    void setTest(boolean on) {
        test = on;
        applyEq();
    }

    String eqStatus() {
        if (eqFailed) return "Equalizer: auf diesem Gerät nicht verfügbar";
        try {
            if (eq != null && !eq.hasControl()) return "Equalizer: von einer anderen App belegt";
        } catch (Throwable ignored) {
        }
        if (lastMb == 0) return "Equalizer: bereit (gerade keine Absenkung)";
        int t = Math.round(-lastMb / 10f);
        return "Equalizer: aktiv, \u2212" + (t / 10) + "," + (t % 10) + " dB";
    }

    void release() {
        if (eq != null) {
            try {
                eq.setEnabled(false);
                eq.release();
            } catch (Throwable ignored) {
            }
            eq = null;
        }
    }
}
