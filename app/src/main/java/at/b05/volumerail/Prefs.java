package at.b05.volumerail;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.AudioManager;

final class Prefs {
    static final String NAME = "volumerail";

    static final String STREAM = "stream";
    static final String INTERCEPT = "intercept_keys";
    static final String CORRECT = "correct_jumps";
    static final String MAX_JUMP = "max_jump";
    static final String SUBSTEPS = "substeps";
    static final String DB_TENTHS = "db_tenths";
    static final String FLOAT = "floating_buttons";
    static final String FLOAT_LEFT = "floating_left";
    static final String OVERLAY = "show_overlay";

    static final int DEF_STREAM = AudioManager.STREAM_MUSIC;
    static final boolean DEF_INTERCEPT = true;
    static final boolean DEF_CORRECT = true;
    static final int DEF_MAX_JUMP = 5;
    static final int DEF_SUBSTEPS = 1;
    static final int DEF_DB_TENTHS = 15;
    static final boolean DEF_FLOAT = false;
    static final boolean DEF_FLOAT_LEFT = false;
    static final boolean DEF_OVERLAY = true;

    private Prefs() {}

    static SharedPreferences get(Context c) {
        return c.getApplicationContext().getSharedPreferences(NAME, Context.MODE_PRIVATE);
    }
}
