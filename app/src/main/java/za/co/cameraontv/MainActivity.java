package za.co.cameraontv;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import java.net.Inet4Address;
import java.net.NetworkInterface;
import java.util.Collections;

/**
 * Configuration and status screen for the Camera on TV background service.
 *
 * <p>The activity is not involved in playback. It obtains Android's one-time overlay permission,
 * starts the persistent REST service, and shows the address clients should call.</p>
 */
public final class MainActivity extends Activity {

    private TextView permissionStatus;
    private TextView apiAddress;
    private TextView serviceStatus;
    private Button grantPermissionButton;

    /** Creates the activity instance required by the Android component loader. */
    public MainActivity() {
    }

    /**
     * Creates the status UI and starts the REST API foreground service.
     *
     * @param savedInstanceState prior activity state supplied by Android, or {@code null}
     */
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        permissionStatus = findViewById(R.id.permission_status);
        apiAddress = findViewById(R.id.api_address);
        serviceStatus = findViewById(R.id.service_status);
        grantPermissionButton = findViewById(R.id.grant_permission_button);

        grantPermissionButton.setOnClickListener(view -> requestOverlayPermission());
        findViewById(R.id.stop_stream_button).setOnClickListener(view -> stopCurrentStream());
        findViewById(R.id.refresh_status_button).setOnClickListener(view -> refreshStatus());

        startApiService();
        refreshStatus();
    }

    /** Refreshes permission and network information after returning from Android settings. */
    @Override
    protected void onResume() {
        super.onResume();
        refreshStatus();
    }

    private void startApiService() {
        Intent intent = new Intent(this, CameraOverlayService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }
    }

    private void requestOverlayPermission() {
        Intent intent = new Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:" + getPackageName()));
        startActivity(intent);
        Toast.makeText(this, R.string.permission_screen_opened, Toast.LENGTH_LONG).show();
    }

    private void stopCurrentStream() {
        Intent intent = new Intent(this, CameraOverlayService.class);
        intent.setAction(CameraOverlayService.ACTION_STOP_STREAM);
        startService(intent);
        Toast.makeText(this, R.string.stream_stop_requested, Toast.LENGTH_SHORT).show();
    }

    private void refreshStatus() {
        boolean permissionGranted = Settings.canDrawOverlays(this);
        permissionStatus.setText(permissionGranted
                ? R.string.overlay_permission_granted
                : R.string.overlay_permission_required);
        permissionStatus.setTextColor(getColor(permissionGranted
                ? R.color.success
                : R.color.warning));
        grantPermissionButton.setVisibility(permissionGranted ? View.GONE : View.VISIBLE);

        String address = findLanIpv4Address();
        apiAddress.setText(address == null
                ? getString(R.string.api_address_unavailable)
                : "http://" + address + ":" + CameraOverlayService.API_PORT);
        serviceStatus.setText(CameraOverlayService.isRunning()
                ? R.string.api_service_running
                : R.string.api_service_starting);
    }

    private static String findLanIpv4Address() {
        try {
            for (NetworkInterface network : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!network.isUp() || network.isLoopback()) {
                    continue;
                }
                for (java.net.InetAddress address : Collections.list(network.getInetAddresses())) {
                    if (address instanceof Inet4Address && address.isSiteLocalAddress()) {
                        return address.getHostAddress();
                    }
                }
            }
        } catch (Exception ignored) {
            // The UI will show a network-not-connected message.
        }
        return null;
    }
}
