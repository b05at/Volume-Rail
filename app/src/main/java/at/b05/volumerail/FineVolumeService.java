package at.b05.volumerail;

import android.accessibilityservice.AccessibilityService;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

public class FineVolumeService extends AccessibilityService
        implements SharedPreferences.OnSharedPreferenceChangeListener {

    static volatile FineVolumeService instance;

    private static final int ACCENT = 0xFF4FC3F7;

    private final Handler h = new Handler(Looper.getMainLooper());
    private SharedPreferences prefs;
    private VolumeController vc;
    private WindowManager wm;

    private LinearLayout osd;
    private ProgressBar osdBar;
    private TextView osdText;
    private View floatView;

    private final Runnable hideOsd = new Runnable() {
        @Override
        public void run() {
            if (osd != null) osd.setVisibility(View.GONE);
        }
    };

    private final BroadcastReceiver volRx = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            if (vc == null) return;
            vc.onSystemVolumeChanged(
                    i.getIntExtra("android.media.EXTRA_VOLUME_STREAM_TYPE", -1),
                    i.getIntExtra("android.media.EXTRA_VOLUME_STREAM_VALUE", -1),
                    i.getIntExtra("android.media.EXTRA_PREV_VOLUME_STREAM_VALUE", -1));
        }
    };

    VolumeController controller() {
        return vc;
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        prefs = Prefs.get(this);
        vc = new VolumeController(this);
        vc.setListener(new VolumeController.Listener() {
            @Override
            public void onLevel(int virtual, int maxVirtual) {
                showLevel(virtual, maxVirtual);
            }
        });
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);

        IntentFilter f = new IntentFilter("android.media.VOLUME_CHANGED_ACTION");
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(volRx, f, Context.RECEIVER_EXPORTED);
        } else {
            registerReceiver(volRx, f);
        }
        prefs.registerOnSharedPreferenceChangeListener(this);
        updateFloating();
        instance = this;
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // Bildschirminhalte werden nicht benötigt
    }

    @Override
    public void onInterrupt() {
    }

    @Override
    protected boolean onKeyEvent(KeyEvent e) {
        int k = e.getKeyCode();
        if (k != KeyEvent.KEYCODE_VOLUME_UP && k != KeyEvent.KEYCODE_VOLUME_DOWN) return false;
        if (vc == null || !prefs.getBoolean(Prefs.INTERCEPT, Prefs.DEF_INTERCEPT)) return false;
        if (e.getAction() == KeyEvent.ACTION_DOWN) {
            vc.step(k == KeyEvent.KEYCODE_VOLUME_UP ? 1 : -1, !overlayOn());
        }
        return true; // Taste verbraucht -> System macht keinen großen Sprung
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences p, String key) {
        if (key == null) return;
        if (key.equals(Prefs.FLOAT) || key.equals(Prefs.FLOAT_LEFT)) {
            updateFloating();
        } else if (key.equals(Prefs.SUBSTEPS) || key.equals(Prefs.STREAM) || key.equals(Prefs.DB_TENTHS)) {
            if (vc != null) vc.reset();
        }
    }

    @Override
    public boolean onUnbind(Intent intent) {
        cleanup();
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        cleanup();
        super.onDestroy();
    }

    private void cleanup() {
        instance = null;
        try {
            unregisterReceiver(volRx);
        } catch (Exception ignored) {
        }
        if (prefs != null) prefs.unregisterOnSharedPreferenceChangeListener(this);
        h.removeCallbacksAndMessages(null);
        removeView(osd);
        osd = null;
        removeView(floatView);
        floatView = null;
        if (vc != null) {
            vc.reset();
            vc.release();
            vc = null;
        }
    }

    private boolean overlayOn() {
        return prefs.getBoolean(Prefs.OVERLAY, Prefs.DEF_OVERLAY);
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private void removeView(View v) {
        if (v == null || wm == null) return;
        try {
            wm.removeView(v);
        } catch (Exception ignored) {
        }
    }

    private GradientDrawable pill(int color, int radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(radiusDp));
        return g;
    }

    // ---------- Einblendung des Pegels ----------

    private void showLevel(final int v, final int max) {
        if (!overlayOn()) return;
        h.post(new Runnable() {
            @Override
            public void run() {
                if (!ensureOsd()) return;
                osdBar.setMax(Math.max(1, max));
                osdBar.setProgress(v);
                int pct = Math.round(v * 100f / Math.max(1, max));
                osdText.setText(pct + " %");
                osd.setVisibility(View.VISIBLE);
                h.removeCallbacks(hideOsd);
                h.postDelayed(hideOsd, 1500);
            }
        });
    }

    private boolean ensureOsd() {
        if (osd != null) return true;
        try {
            LinearLayout l = new LinearLayout(this);
            l.setOrientation(LinearLayout.HORIZONTAL);
            l.setGravity(Gravity.CENTER_VERTICAL);
            l.setPadding(dp(18), dp(12), dp(18), dp(12));
            l.setBackground(pill(0xE61E2227, 24));

            TextView icon = new TextView(this);
            icon.setText("🔊"); // Lautsprecher-Emoji
            icon.setTextSize(18);
            l.addView(icon);

            ProgressBar bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
            bar.setProgressTintList(ColorStateList.valueOf(ACCENT));
            bar.setProgressBackgroundTintList(ColorStateList.valueOf(0xFF3A4048));
            LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(dp(200), dp(8));
            bp.leftMargin = dp(12);
            bp.rightMargin = dp(12);
            l.addView(bar, bp);

            TextView t = new TextView(this);
            t.setTextColor(Color.WHITE);
            t.setTextSize(16);
            t.setMinWidth(dp(52));
            t.setGravity(Gravity.END);
            l.addView(t);

            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT);
            lp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
            lp.y = dp(36);

            l.setVisibility(View.GONE);
            wm.addView(l, lp);
            osd = l;
            osdBar = bar;
            osdText = t;
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    // ---------- Schwebende +/- Tasten ----------

    private void updateFloating() {
        removeView(floatView);
        floatView = null;
        if (!prefs.getBoolean(Prefs.FLOAT, Prefs.DEF_FLOAT)) return;
        boolean left = prefs.getBoolean(Prefs.FLOAT_LEFT, Prefs.DEF_FLOAT_LEFT);
        try {
            LinearLayout col = new LinearLayout(this);
            col.setOrientation(LinearLayout.VERTICAL);
            col.setBackground(pill(0xCC1E2227, 28));
            col.setPadding(dp(4), dp(4), dp(4), dp(4));
            col.addView(makeBtn("+", 1), new LinearLayout.LayoutParams(dp(56), dp(64)));
            View sep = new View(this);
            sep.setBackgroundColor(0x33FFFFFF);
            LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(dp(36), dp(1));
            sp.gravity = Gravity.CENTER_HORIZONTAL;
            col.addView(sep, sp);
            col.addView(makeBtn("−", -1), new LinearLayout.LayoutParams(dp(56), dp(64)));

            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT);
            lp.gravity = (left ? Gravity.START : Gravity.END) | Gravity.CENTER_VERTICAL;
            lp.x = dp(6);
            wm.addView(col, lp);
            floatView = col;
        } catch (Exception ignored) {
            floatView = null;
        }
    }

    private View makeBtn(String label, final int dir) {
        TextView b = new TextView(this);
        b.setText(label);
        b.setTextSize(30);
        b.setTextColor(Color.WHITE);
        b.setGravity(Gravity.CENTER);
        final Runnable repeat = new Runnable() {
            @Override
            public void run() {
                if (vc != null) vc.step(dir, !overlayOn());
                h.postDelayed(this, 140);
            }
        };
        b.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent ev) {
                switch (ev.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        v.setAlpha(0.5f);
                        if (vc != null) vc.step(dir, !overlayOn());
                        h.postDelayed(repeat, 450);
                        return true;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        v.setAlpha(1f);
                        h.removeCallbacks(repeat);
                        return true;
                    default:
                        return true;
                }
            }
        });
        return b;
    }
}
