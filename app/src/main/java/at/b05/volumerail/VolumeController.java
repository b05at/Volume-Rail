package at.b05.volumerail;

import android.content.Context;
import android.content.SharedPreferences;
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
 * eingestellt und das Signal über einen globalen Equalizer (Audio-Session 0)
 * um einige dB abgesenkt wird.
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

    private int expectedIndex = -1;
    private long expectedUntil = 0;
    private long lastExternal = 0;
    private long dragUntil = 0;

    VolumeController(Context c) {
        am = (AudioManager) c.getApplicationContext().getSystemService(Context.AUDIO_SERVICE);
        prefs = Prefs.get(c);
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
        if (am.getStreamVolume(s) != idx) {
            expectedIndex = idx;
            expectedUntil = SystemClock.uptimeMillis() + 800;
            try {
                am.setStreamVolume(s, idx, showSystemUi ? AudioManager.FLAG_SHOW_UI : 0);
            } catch (SecurityException ignored) {
                // z. B. Klingelton bei "Nicht stören" ohne Berechtigung
            }
        }
        att = a;
        attIndex = idx;
        applyEq();

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

    private void applyEq() {
        int n = prefs.getInt(Prefs.SUBSTEPS, Prefs.DEF_SUBSTEPS);
        if (n <= 1 || att == 0) {
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
            int tenths = prefs.getInt(Prefs.DB_TENTHS, Prefs.DEF_DB_TENTHS);
            int mb = -(att * tenths * 10); // Zehntel-dB -> Millibel
            short lvl = (short) Math.max(range[0], mb);
            short bands = eq.getNumberOfBands();
            for (short b = 0; b < bands; b++) eq.setBandLevel(b, lvl);
            eq.setEnabled(true);
        } catch (Throwable t) {
            eqFailed = true;
            att = 0;
            release();
        }
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
