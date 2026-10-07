package za.co.cameraontv;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;

/** Stores the optional software-decoder workaround for affected Android TV devices. */
final class DecoderSettings {

    private static final String PREFERENCES_NAME = "camera_on_tv_settings";
    private static final String KEY_COMPATIBILITY_DECODER = "compatibility_decoder";

    private DecoderSettings() {
    }

    /**
     * Returns whether H.264 streams should prefer a software decoder.
     *
     * @param context Android context used to access private preferences
     * @return {@code true} when compatibility mode is enabled
     */
    static boolean isEnabled(Context context) {
        SharedPreferences preferences = context.getSharedPreferences(
                PREFERENCES_NAME,
                Context.MODE_PRIVATE);
        return preferences.contains(KEY_COMPATIBILITY_DECODER)
                ? preferences.getBoolean(KEY_COMPATIBILITY_DECODER, false)
                : isAutomaticallyRecommended();
    }

    /**
     * Detects MiBox-family hardware affected by the shared Amlogic decoder reset.
     *
     * @return {@code true} when compatibility mode should be enabled by default
     */
    static boolean isAutomaticallyRecommended() {
        boolean amlogic = containsIgnoreCase(Build.HARDWARE, "amlogic");
        boolean xiaomi = containsIgnoreCase(Build.MANUFACTURER, "xiaomi")
                || containsIgnoreCase(Build.BRAND, "xiaomi");
        boolean miBox = containsIgnoreCase(Build.MODEL, "mibox")
                || containsIgnoreCase(Build.MODEL, "mi box");
        return amlogic && (xiaomi || miBox);
    }

    /**
     * Persists the software-decoder preference.
     *
     * @param context Android context used to access private preferences
     * @param enabled whether future H.264 streams should prefer software decoding
     */
    static void setEnabled(Context context, boolean enabled) {
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_COMPATIBILITY_DECODER, enabled)
                .apply();
    }

    private static boolean containsIgnoreCase(String value, String expected) {
        return value != null && value.toLowerCase(java.util.Locale.US).contains(expected);
    }
}
