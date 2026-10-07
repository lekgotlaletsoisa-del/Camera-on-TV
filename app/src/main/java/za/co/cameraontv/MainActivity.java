package za.co.cameraontv;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.RadioGroup;
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
    private RadioGroup soundModeGroup;
    private CheckBox compatibilityDecoder;
    private boolean refreshingSoundMode;
    private boolean refreshingDecoderSetting;

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
        soundModeGroup = findViewById(R.id.sound_mode_group);
        compatibilityDecoder = findViewById(R.id.compatibility_decoder);

        grantPermissionButton.setOnClickListener(view -> requestOverlayPermission());
        findViewById(R.id.stop_stream_button).setOnClickListener(view -> stopCurrentStream());
        findViewById(R.id.refresh_status_button).setOnClickListener(view -> refreshStatus());
        soundModeGroup.setOnCheckedChangeListener((group, checkedId) -> saveSoundMode(checkedId));
        compatibilityDecoder.setOnCheckedChangeListener(
                (button, checked) -> saveDecoderSetting(checked));

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
        refreshSoundMode();
        refreshDecoderSetting();
    }

    private void refreshSoundMode() {
        int checkedId;
        switch (SoundSettings.getMode(this)) {
            case ALWAYS_ON:
                checkedId = R.id.sound_mode_always_on;
                break;
            case ALWAYS_OFF:
                checkedId = R.id.sound_mode_always_off;
                break;
            case FOLLOW_API:
            default:
                checkedId = R.id.sound_mode_follow_api;
                break;
        }
        refreshingSoundMode = true;
        soundModeGroup.check(checkedId);
        refreshingSoundMode = false;
    }

    private void saveSoundMode(int checkedId) {
        if (refreshingSoundMode || checkedId == View.NO_ID) {
            return;
        }
        SoundSettings.Mode mode;
        if (checkedId == R.id.sound_mode_always_on) {
            mode = SoundSettings.Mode.ALWAYS_ON;
        } else if (checkedId == R.id.sound_mode_always_off) {
            mode = SoundSettings.Mode.ALWAYS_OFF;
        } else {
            mode = SoundSettings.Mode.FOLLOW_API;
        }
        SoundSettings.setMode(this, mode);
        Toast.makeText(this, R.string.sound_mode_saved, Toast.LENGTH_SHORT).show();
    }

    private void refreshDecoderSetting() {
        refreshingDecoderSetting = true;
        compatibilityDecoder.setChecked(DecoderSettings.isEnabled(this));
        refreshingDecoderSetting = false;
    }

    private void saveDecoderSetting(boolean enabled) {
        if (refreshingDecoderSetting) {
            return;
        }
        DecoderSettings.setEnabled(this, enabled);
        Toast.makeText(this, R.string.decoder_setting_saved, Toast.LENGTH_SHORT).show();
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
