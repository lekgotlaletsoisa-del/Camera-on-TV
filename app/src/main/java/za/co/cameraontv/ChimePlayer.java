package za.co.cameraontv;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.os.Handler;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Generates and plays the short two-note notification heard when a camera overlay starts. */
final class ChimePlayer {

    private static final int SAMPLE_RATE_HZ = 44_100;
    private static final int CHIME_DURATION_MS = 620;
    private static final double FIRST_NOTE_HZ = 1_046.50; // C6
    private static final double SECOND_NOTE_HZ = 1_318.51; // E6

    private final Handler handler;
    private AudioTrack activeTrack;

    /**
     * Creates a chime player whose cleanup callbacks run on the supplied handler.
     *
     * @param handler service handler used to release completed audio tracks
     */
    ChimePlayer(Handler handler) {
        this.handler = handler;
    }

    /** Plays a fresh chime, replacing any chime that is still active. */
    void play() {
        stop();
        byte[] pcm = createPcm();
        AudioTrack track = null;
        try {
            track = new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build())
                    .setAudioFormat(new AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(SAMPLE_RATE_HZ)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build())
                    .setBufferSizeInBytes(pcm.length)
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .build();
            if (track.getState() != AudioTrack.STATE_INITIALIZED
                    || track.write(pcm, 0, pcm.length) != pcm.length) {
                track.release();
                return;
            }
            track.play();
            activeTrack = track;
            AudioTrack completedTrack = track;
            handler.postDelayed(() -> releaseIfActive(completedTrack), CHIME_DURATION_MS + 100L);
        } catch (RuntimeException exception) {
            if (track != null) {
                track.release();
            }
        }
    }

    /** Stops and releases the active chime, if present. */
    void stop() {
        AudioTrack track = activeTrack;
        activeTrack = null;
        if (track == null) {
            return;
        }
        try {
            track.stop();
        } catch (IllegalStateException ignored) {
            // The track may have already completed between the state check and stop request.
        }
        track.release();
    }

    private void releaseIfActive(AudioTrack track) {
        if (activeTrack != track) {
            return;
        }
        activeTrack = null;
        track.release();
    }

    private static byte[] createPcm() {
        int sampleCount = SAMPLE_RATE_HZ * CHIME_DURATION_MS / 1_000;
        ByteBuffer buffer = ByteBuffer.allocate(sampleCount * 2).order(ByteOrder.LITTLE_ENDIAN);
        for (int sampleIndex = 0; sampleIndex < sampleCount; sampleIndex++) {
            double timeSeconds = sampleIndex / (double) SAMPLE_RATE_HZ;
            double sample = note(timeSeconds, 0.00, 0.24, FIRST_NOTE_HZ)
                    + note(timeSeconds, 0.25, 0.34, SECOND_NOTE_HZ);
            int pcmValue = (int) Math.round(Short.MAX_VALUE * 0.62 * sample);
            pcmValue = Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, pcmValue));
            buffer.putShort((short) pcmValue);
        }
        return buffer.array();
    }

    private static double note(double time, double start, double duration, double frequency) {
        double noteTime = time - start;
        if (noteTime < 0 || noteTime >= duration) {
            return 0;
        }
        double attack = Math.min(1, noteTime / 0.012);
        double release = Math.min(1, (duration - noteTime) / 0.075);
        double envelope = attack * release * Math.exp(-1.5 * noteTime / duration);
        double fundamental = Math.sin(2 * Math.PI * frequency * noteTime);
        double harmonic = 0.18 * Math.sin(4 * Math.PI * frequency * noteTime);
        return envelope * (fundamental + harmonic) / 1.18;
    }
}
