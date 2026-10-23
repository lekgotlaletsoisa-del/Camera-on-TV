package za.co.cameraontv;

import android.content.Context;
import android.content.SharedPreferences;

/** Stores and resolves persistent policies for automatic and independently requested alarms. */
final class SoundSettings {

    private static final String PREFERENCES_NAME = "camera_on_tv_settings";
    private static final String KEY_MODE = "sound_mode";

    private SoundSettings() {
    }

    /** Available policies for automatic and API-requested alarms. */
    enum Mode {
        /** Plays alarms only for explicit {@code POST /alarm} requests; the default policy. */
        FOLLOW_API("follow_api"),
        /** Plays alarms for stream starts and explicit {@code POST /alarm} requests. */
        ALWAYS_ON("always_on"),
        /** Suppresses both automatic and explicit alarms. */
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
     * Resolves whether starting or replacing a stream should play the alarm automatically.
     *
     * @param context Android context used to read the current policy
     * @return {@code true} only when the user selected the always-on policy
     */
    static boolean shouldPlayOnStreamStart(Context context) {
        return getMode(context) == Mode.ALWAYS_ON;
    }

    /**
     * Resolves whether an explicit {@code POST /alarm} request should play the alarm.
     *
     * @param context Android context used to read the current policy
     * @return {@code false} only when the user selected the always-off policy
     */
    static boolean shouldPlayApiRequest(Context context) {
        return getMode(context) != Mode.ALWAYS_OFF;
    }
}
