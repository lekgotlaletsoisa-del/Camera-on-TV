package za.co.cameraontv;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;

import androidx.annotation.OptIn;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.rtsp.RtspMediaSource;
import androidx.media3.ui.PlayerView;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicLong;

import fi.iki.elonen.NanoHTTPD;

/**
 * Persistent foreground service hosting both the REST API and top-right RTSP video overlay.
 *
 * <p>All window and player work is marshalled onto Android's main thread. API stop requests remove
 * the overlay window first and release playback second, minimizing visible stop latency.</p>
 */
public final class CameraOverlayService extends Service implements CameraApiServer.Controller {

    /** TCP port exposed by the local REST API. */
    public static final int API_PORT = 8787;

    /** Intent action used by the configuration screen to stop the current stream. */
    public static final String ACTION_STOP_STREAM = "za.co.cameraontv.action.STOP_STREAM";

    private static final int NOTIFICATION_ID = 1001;
    private static final String NOTIFICATION_CHANNEL_ID = "camera_overlay_service";

    private static volatile boolean running;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final AtomicLong commandGeneration = new AtomicLong();
    private WindowManager windowManager;
    private View overlayView;
    private PlayerView playerView;
    private ExoPlayer player;
    private CameraApiServer apiServer;
    private volatile String currentUrl;
    private volatile String playbackState = "stopped";
    private volatile String lastError;
    private volatile boolean muted = true;

    /** Creates the service instance required by the Android component loader. */
    public CameraOverlayService() {
    }

    /**
     * Returns whether the service process currently owns a running service instance.
     *
     * @return {@code true} while Android has an active service instance
     */
    public static boolean isRunning() {
        return running;
    }

    /** Starts foreground operation and opens the HTTP listener. */
    @Override
    public void onCreate() {
        super.onCreate();
        running = true;
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        createNotificationChannel();
        startForeground(NOTIFICATION_ID, createNotification());
        startApiServer();
    }

    /**
     * Handles the local stop action while otherwise keeping the API alive.
     *
     * @param intent start request, or {@code null} if Android recreates the service
     * @param flags Android service-start flags
     * @param startId identifier assigned to this start request
     * @return {@link Service#START_STICKY} so Android may recreate the API service
     */
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP_STREAM.equals(intent.getAction())) {
            stopStream();
        }
        return START_STICKY;
    }

    /**
     * Indicates that this started service does not support binding.
     *
     * @param intent binding request supplied by Android
     * @return always {@code null}
     */
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    /**
     * Queues playback after validating that Android permits overlay windows.
     *
     * @param rtspUrl validated RTSP URL to play
     * @param shouldMute whether the stream audio should be muted
     * @return an accepted result, or a rejected result when overlay permission is missing
     */
    @Override
    public CameraApiServer.StartResult startStream(String rtspUrl, boolean shouldMute) {
        if (!Settings.canDrawOverlays(this)) {
            return new CameraApiServer.StartResult(
                    false,
                    "Overlay permission is not granted. Open Camera on TV on the television first.");
        }

        currentUrl = rtspUrl;
        muted = shouldMute;
        playbackState = "starting";
        lastError = null;
        long generation = commandGeneration.incrementAndGet();
        mainHandler.postAtFrontOfQueue(() -> {
            if (commandGeneration.get() == generation) {
                showStream(rtspUrl, shouldMute);
            }
        });
        return new CameraApiServer.StartResult(true, "Stream start requested");
    }

    /** Removes the video window immediately and then releases the player asynchronously. */
    @Override
    public void stopStream() {
        currentUrl = null;
        playbackState = "stopped";
        lastError = null;
        commandGeneration.incrementAndGet();
        mainHandler.postAtFrontOfQueue(this::removeOverlayAndReleasePlayer);
    }

    /**
     * Returns a point-in-time description of permission, API, and playback state.
     *
     * @return JSON status object suitable for the {@code GET /status} response
     */
    @Override
    public JSONObject getStatus() {
        JSONObject status = new JSONObject();
        try {
            status.put("service", running ? "running" : "stopped");
            status.put("port", API_PORT);
            status.put("overlayPermission", Settings.canDrawOverlays(this));
            status.put("playback", playbackState);
            status.put("muted", muted);
            status.put("url", currentUrl == null ? JSONObject.NULL : currentUrl);
            status.put("error", lastError == null ? JSONObject.NULL : lastError);
        } catch (JSONException ignored) {
            // Primitive values used above are always JSON encodable.
        }
        return status;
    }

    /** Stops the listener and releases all window and media resources. */
    @Override
    public void onDestroy() {
        running = false;
        if (apiServer != null) {
            apiServer.stop();
            apiServer = null;
        }
        removeOverlayAndReleasePlayer();
        super.onDestroy();
    }

    private void startApiServer() {
        apiServer = new CameraApiServer(API_PORT, this);
        try {
            apiServer.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false);
        } catch (IOException exception) {
            playbackState = "api_error";
            lastError = "Could not listen on port " + API_PORT + ": " + exception.getMessage();
        }
    }

    @SuppressLint("InflateParams")
    @OptIn(markerClass = UnstableApi.class)
    private void showStream(String rtspUrl, boolean shouldMute) {
        removeOverlayAndReleasePlayer();
        currentUrl = rtspUrl;
        muted = shouldMute;
        playbackState = "connecting";

        try {
            overlayView = LayoutInflater.from(this).inflate(R.layout.overlay_player, null, false);
            playerView = overlayView.findViewById(R.id.overlay_player);

            DefaultLoadControl loadControl = new DefaultLoadControl.Builder()
                    .setBufferDurationsMs(250, 1500, 100, 250)
                    .build();
            player = new ExoPlayer.Builder(this)
                    .setLoadControl(loadControl)
                    .build();
            player.setVolume(shouldMute ? 0f : 1f);
            player.addListener(new Player.Listener() {
                @Override
                public void onPlaybackStateChanged(int state) {
                    if (state == Player.STATE_READY) {
                        playbackState = "playing";
                    } else if (state == Player.STATE_BUFFERING) {
                        playbackState = "buffering";
                    } else if (state == Player.STATE_ENDED) {
                        playbackState = "ended";
                    }
                }

                @Override
                public void onPlayerError(PlaybackException error) {
                    playbackState = "error";
                    lastError = error.getErrorCodeName() + ": " + error.getMessage();
                    removeOverlayAndReleasePlayer();
                }
            });
            playerView.setPlayer(player);

            windowManager.addView(overlayView, createOverlayLayoutParams());
            RtspMediaSource mediaSource = new RtspMediaSource.Factory()
                    .setForceUseRtpTcp(true)
                    .createMediaSource(MediaItem.fromUri(rtspUrl));
            player.setMediaSource(mediaSource);
            player.prepare();
            player.play();
        } catch (RuntimeException exception) {
            playbackState = "error";
            lastError = exception.getClass().getSimpleName() + ": " + exception.getMessage();
            removeOverlayAndReleasePlayer();
        }
    }

    @SuppressWarnings("deprecation") // Required for supported Android 6 and 7 TV devices.
    private WindowManager.LayoutParams createOverlayLayoutParams() {
        DisplayMetrics metrics = new DisplayMetrics();
        windowManager.getDefaultDisplay().getRealMetrics(metrics);
        int minWidth = dpToPx(320);
        int width = Math.max(minWidth, Math.round(metrics.widthPixels * 0.36f));
        width = Math.min(width, Math.round(metrics.widthPixels * 0.48f));
        int height = Math.round(width * 9f / 16f);

        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                width,
                height,
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                        ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                        : WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.OPAQUE);
        params.gravity = Gravity.TOP | Gravity.END;
        params.x = 0;
        params.y = 0;
        return params;
    }

    private void removeOverlayAndReleasePlayer() {
        if (overlayView != null) {
            try {
                windowManager.removeViewImmediate(overlayView);
            } catch (IllegalArgumentException ignored) {
                // The system may already have detached the window.
            }
            overlayView = null;
        }
        if (playerView != null) {
            playerView.setPlayer(null);
            playerView = null;
        }
        if (player != null) {
            player.stop();
            player.release();
            player = null;
        }
    }

    private int dpToPx(int dp) {
        return Math.round(dp * getResources().getDisplayMetrics().density);
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }
        NotificationChannel channel = new NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW);
        channel.setDescription(getString(R.string.notification_text));
        getSystemService(NotificationManager.class).createNotificationChannel(channel);
    }

    @SuppressWarnings("deprecation") // The channel-less builder is required below Android 8.
    private Notification createNotification() {
        Intent activityIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this,
                0,
                activityIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, NOTIFICATION_CHANNEL_ID)
                : new Notification.Builder(this);
        return builder
                .setSmallIcon(R.drawable.ic_stat_camera)
                .setContentTitle(getString(R.string.notification_title))
                .setContentText(getString(R.string.notification_text))
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .setCategory(Notification.CATEGORY_SERVICE)
                .build();
    }
}
