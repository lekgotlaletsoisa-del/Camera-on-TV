package za.co.cameraontv;

import android.content.Context;
import android.content.SharedPreferences;

/** Stores and resolves the user's persistent alarm-sound override. */
final class SoundSettings {

    private static final String PREFERENCES_NAME = "camera_on_tv_settings";
    private static final String KEY_MODE = "sound_mode";

    private SoundSettings() {
    }

    /** Available policies for resolving the REST request's {@code sound} value. */
    enum Mode {
        FOLLOW_API("follow_api"),
        ALWAYS_ON("always_on"),
        ALWAYS_OFF("always_off");

        private final String storedValue;

        Mode(String storedValue) {
            this.storedValue = storedValue;
        }

        /** @return stable value used in preferences and API status responses */
        String value() {
            return storedValue;
        }

        private static Mode fromValue(String value) {
            for (Mode mode : values()) {
                if (mode.storedValue.equals(value)) {
                    return mode;
                }
            }
            return FOLLOW_API;
        }
    }

    /**
     * Reads the current sound policy.
     *
     * @param context Android context used to access private preferences
     * @return stored mode, defaulting to {@link Mode#FOLLOW_API}
     */
    static Mode getMode(Context context) {
        SharedPreferences preferences = context.getSharedPreferences(
                PREFERENCES_NAME,
                Context.MODE_PRIVATE);
        return Mode.fromValue(preferences.getString(KEY_MODE, Mode.FOLLOW_API.value()));
    }

    /**
     * Persists a sound policy selected on the TV settings screen.
     *
     * @param context Android context used to access private preferences
     * @param mode new policy
     */
    static void setMode(Context context, Mode mode) {
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_MODE, mode.value())
                .apply();
    }

    /**
     * Resolves whether the alarm should play for a stream request.
     *
     * @param context Android context used to read the current policy
     * @param requested {@code sound} value supplied by the REST client
     * @return effective alarm state after applying the user override
     */
    static boolean shouldPlay(Context context, boolean requested) {
        Mode mode = getMode(context);
        if (mode == Mode.ALWAYS_ON) {
            return true;
        }
        if (mode == Mode.ALWAYS_OFF) {
            return false;
        }
        return requested;
    }
}
