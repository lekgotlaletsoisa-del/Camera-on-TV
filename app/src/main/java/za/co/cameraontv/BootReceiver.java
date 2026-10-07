package za.co.cameraontv;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

/** Restarts the REST API service after the TV finishes booting. */
public final class BootReceiver extends BroadcastReceiver {

    /** Creates the receiver instance required by the Android component loader. */
    public BootReceiver() {
    }

    /**
     * Starts {@link CameraOverlayService} in response to the boot-completed broadcast.
     *
     * @param context receiver context supplied by Android
     * @param intent received broadcast intent
     */
    @Override
    public void onReceive(Context context, Intent intent) {
        if (!Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            return;
        }
        Intent serviceIntent = new Intent(context, CameraOverlayService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(serviceIntent);
        } else {
            context.startService(serviceIntent);
        }
    }
}
