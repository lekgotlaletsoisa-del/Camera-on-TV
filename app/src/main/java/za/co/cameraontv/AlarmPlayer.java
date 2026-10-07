package za.co.cameraontv;

import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;

/** Synthesizes and plays the ambulance-style alarm heard when a camera overlay starts. */
final class AlarmPlayer {

    private static final int SAMPLE_RATE_HZ = 48_000;
    private static final int ALARM_DURATION_MS = 500;
    private static final int CHUNK_SAMPLE_COUNT = 1_024;
    private static final double SWEEP_RATE_HZ = 4.8;
    private static final double CENTER_FREQUENCY_HZ = 1_050;
    private static final double FREQUENCY_DEVIATION_HZ = 470;

    private final Object playbackLock = new Object();
    private long playbackGeneration;
    private AudioTrack activeTrack;

    /** Plays a fresh rapid siren sweep, replacing any alarm that is still active. */
    void play() {
        stop();
        final long generation;
        synchronized (playbackLock) {
            generation = ++playbackGeneration;
        }
        Thread playbackThread = new Thread(
                () -> playAlarm(generation),
                "CameraOnTvAlarm");
        playbackThread.setDaemon(true);
        playbackThread.start();
    }

    /** Stops and releases the active alarm, if present. */
    void stop() {
        AudioTrack track;
        synchronized (playbackLock) {
            playbackGeneration++;
            track = activeTrack;
            activeTrack = null;
        }
        if (track != null) {
            releaseTrack(track);
        }
    }

    @SuppressWarnings("deprecation") // Legacy constructor works reliably on supported Android 6-9 TVs.
    private void playAlarm(long generation) {
        int minimumBufferSize = AudioTrack.getMinBufferSize(
                SAMPLE_RATE_HZ,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT);
        if (minimumBufferSize <= 0) {
            return;
        }

        AudioTrack track = null;
        try {
            track = new AudioTrack(
                    AudioManager.STREAM_MUSIC,
                    SAMPLE_RATE_HZ,
                    AudioFormat.CHANNEL_OUT_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    Math.max(minimumBufferSize, CHUNK_SAMPLE_COUNT * 2),
                    AudioTrack.MODE_STREAM);
            if (track.getState() != AudioTrack.STATE_INITIALIZED
                    || !claimTrack(track, generation)) {
                track.release();
                return;
            }

            track.play();
            writeAlarm(track, generation);
            if (isCurrent(track, generation)) {
                Thread.sleep(100);
            }
        } catch (IllegalArgumentException | IllegalStateException ignored) {
            // An alarm failure must never prevent the camera overlay from appearing.
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        } finally {
            releaseIfOwned(track, generation);
        }
    }

    private void writeAlarm(AudioTrack track, long generation) {
        int totalSamples = SAMPLE_RATE_HZ * ALARM_DURATION_MS / 1_000;
        byte[] pcm = new byte[CHUNK_SAMPLE_COUNT * 2];
        double phase = 0;
        int generatedSamples = 0;

        while (generatedSamples < totalSamples && isCurrent(track, generation)) {
            int samplesThisChunk = Math.min(CHUNK_SAMPLE_COUNT, totalSamples - generatedSamples);
            for (int chunkIndex = 0; chunkIndex < samplesThisChunk; chunkIndex++) {
                int sampleIndex = generatedSamples + chunkIndex;
                double time = sampleIndex / (double) SAMPLE_RATE_HZ;
                double frequency = CENTER_FREQUENCY_HZ
                        + FREQUENCY_DEVIATION_HZ
                        * Math.sin(2 * Math.PI * SWEEP_RATE_HZ * time);
                phase += 2 * Math.PI * frequency / SAMPLE_RATE_HZ;

                double fadeIn = Math.min(1, time / 0.02);
                double remaining = (totalSamples - sampleIndex) / (double) SAMPLE_RATE_HZ;
                double fadeOut = Math.min(1, remaining / 0.04);
                double signal = Math.sin(phase) + 0.22 * Math.sin(2 * phase);
                int value = (int) Math.round(
                        Short.MAX_VALUE * 0.70 * fadeIn * fadeOut * signal / 1.22);
                int byteIndex = chunkIndex * 2;
                pcm[byteIndex] = (byte) value;
                pcm[byteIndex + 1] = (byte) (value >> 8);
            }

            int byteCount = samplesThisChunk * 2;
            int written = track.write(pcm, 0, byteCount);
            if (written != byteCount) {
                return;
            }
            generatedSamples += samplesThisChunk;
        }
    }

    private boolean claimTrack(AudioTrack track, long generation) {
        synchronized (playbackLock) {
            if (playbackGeneration != generation) {
                return false;
            }
            activeTrack = track;
            return true;
        }
    }

    private boolean isCurrent(AudioTrack track, long generation) {
        synchronized (playbackLock) {
            return activeTrack == track && playbackGeneration == generation;
        }
    }

    private void releaseIfOwned(AudioTrack track, long generation) {
        if (track == null) {
            return;
        }
        synchronized (playbackLock) {
            if (activeTrack != track || playbackGeneration != generation) {
                return;
            }
            activeTrack = null;
        }
        releaseTrack(track);
    }

    private static void releaseTrack(AudioTrack track) {
        try {
            track.pause();
            track.flush();
            track.stop();
        } catch (IllegalStateException ignored) {
            // The track may have ended while the stop request was being processed.
        }
        track.release();
    }
}
