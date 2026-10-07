package za.co.cameraontv;

import android.media.AudioManager;
import android.media.ToneGenerator;
import android.os.Handler;

/** Generates and plays the alternating alarm heard when a camera overlay starts. */
final class AlarmPlayer {

    private static final int TONE_VOLUME_PERCENT = 100;
    private static final int NOTE_COUNT = 8;
    private static final int NOTE_DURATION_MS = 165;
    private static final int NOTE_INTERVAL_MS = 190;
    private static final int RELEASE_DELAY_MS = NOTE_DURATION_MS + 80;

    private final Handler handler;
    private ToneGenerator toneGenerator;
    private long playbackGeneration;

    /**
     * Creates an alarm player whose sequencing callbacks run on the supplied handler.
     *
     * @param handler service handler used to sequence and release generated tones
     */
    AlarmPlayer(Handler handler) {
        this.handler = handler;
    }

    /** Plays a fresh alarm burst, replacing any alarm that is still active. */
    void play() {
        stop();
        long generation = ++playbackGeneration;
        try {
            ToneGenerator generator = new ToneGenerator(
                    AudioManager.STREAM_MUSIC,
                    TONE_VOLUME_PERCENT);
            toneGenerator = generator;
            playNote(generator, generation, 0);
        } catch (RuntimeException ignored) {
            // An alarm failure must never prevent the camera overlay from appearing.
            stop();
        }
    }

    /** Stops and releases the active alarm, if present. */
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

    private void playNote(ToneGenerator generator, long generation, int noteIndex) {
        if (toneGenerator != generator || playbackGeneration != generation) {
            return;
        }
        int tone = noteIndex % 2 == 0
                ? ToneGenerator.TONE_DTMF_9
                : ToneGenerator.TONE_DTMF_1;
        generator.startTone(tone, NOTE_DURATION_MS);
        if (noteIndex + 1 < NOTE_COUNT) {
            handler.postDelayed(
                    () -> playNote(generator, generation, noteIndex + 1),
                    NOTE_INTERVAL_MS);
        } else {
            handler.postDelayed(
                    () -> releaseIfActive(generator, generation),
                    RELEASE_DELAY_MS);
        }
    }

    private void releaseIfActive(ToneGenerator generator, long generation) {
        if (toneGenerator != generator || playbackGeneration != generation) {
            return;
        }
        toneGenerator = null;
        generator.release();
    }
}
