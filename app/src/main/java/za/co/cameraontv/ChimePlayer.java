package za.co.cameraontv;

import android.media.AudioManager;
import android.media.ToneGenerator;
import android.os.Handler;

/** Generates and plays the short two-note notification heard when a camera overlay starts. */
final class ChimePlayer {

    private static final int TONE_VOLUME_PERCENT = 100;
    private static final int FIRST_NOTE_DURATION_MS = 180;
    private static final int SECOND_NOTE_DELAY_MS = 210;
    private static final int SECOND_NOTE_DURATION_MS = 320;
    private static final int RELEASE_DELAY_MS = 570;

    private final Handler handler;
    private ToneGenerator toneGenerator;
    private long playbackGeneration;

    /**
     * Creates a chime player whose sequencing callbacks run on the supplied handler.
     *
     * @param handler service handler used to sequence and release generated tones
     */
    ChimePlayer(Handler handler) {
        this.handler = handler;
    }

    /** Plays a fresh rising two-note chime, replacing any chime that is still active. */
    void play() {
        stop();
        long generation = ++playbackGeneration;
        try {
            ToneGenerator generator = new ToneGenerator(
                    AudioManager.STREAM_MUSIC,
                    TONE_VOLUME_PERCENT);
            toneGenerator = generator;
            generator.startTone(ToneGenerator.TONE_DTMF_2, FIRST_NOTE_DURATION_MS);
            handler.postDelayed(
                    () -> playSecondNote(generator, generation),
                    SECOND_NOTE_DELAY_MS);
            handler.postDelayed(
                    () -> releaseIfActive(generator, generation),
                    RELEASE_DELAY_MS);
        } catch (RuntimeException ignored) {
            // A chime failure must never prevent the camera overlay from appearing.
            stop();
        }
    }

    /** Stops and releases the active chime, if present. */
    void stop() {
        playbackGeneration++;
        ToneGenerator generator = toneGenerator;
        toneGenerator = null;
        if (generator == null) {
            return;
        }
        generator.stopTone();
        generator.release();
    }

    private void playSecondNote(ToneGenerator generator, long generation) {
        if (toneGenerator != generator || playbackGeneration != generation) {
            return;
        }
        generator.startTone(ToneGenerator.TONE_DTMF_6, SECOND_NOTE_DURATION_MS);
    }

    private void releaseIfActive(ToneGenerator generator, long generation) {
        if (toneGenerator != generator || playbackGeneration != generation) {
            return;
        }
        toneGenerator = null;
        generator.release();
    }
}
