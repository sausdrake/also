package com.example.autoauction.diagnostic.media.infrastructure.video;

import com.example.autoauction.diagnostic.media.domain.port.VideoProcessor;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * MEDIA-3.4: deterministic VideoProcessor test double, used only under the
 * test profile (no ffmpeg binary needed to run the suite) - mirrors
 * FakeStorageClient's conventions exactly (one-shot configurable failure,
 * configurable deterministic success, real local file produced where the
 * caller expects one).
 */
@Component
@Profile("test")
public class FakeVideoProcessor implements VideoProcessor {

    private static final byte[] FAKE_THUMBNAIL_CONTENT = new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0x01};

    private volatile int nextDurationSeconds = 10;
    private volatile int nextWidth = 640;
    private volatile int nextHeight = 480;
    private volatile RuntimeException nextFailure = null;

    /** Test-only: configures the deterministic metadata the next process() call returns. */
    public void configureSuccess(int durationSeconds, int width, int height) {
        this.nextDurationSeconds = durationSeconds;
        this.nextWidth = width;
        this.nextHeight = height;
    }

    /** Test-only: the next process() call throws this instead of succeeding (one-shot, then resets). */
    public void configureFailure(RuntimeException failure) {
        this.nextFailure = failure;
    }

    @Override
    public ProcessingResult process(Path videoFile, ProcessingLimits limits) {
        if (nextFailure != null) {
            RuntimeException failure = nextFailure;
            nextFailure = null;
            throw failure;
        }
        try {
            // Real file, in videoFile's own parent directory - same containment contract a real
            // FfmpegVideoProcessor must honor, so callers can test cleanup against this double too.
            Path thumbnail = Files.createTempFile(videoFile.getParent(), "fake-thumbnail-", ".jpg");
            Files.write(thumbnail, FAKE_THUMBNAIL_CONTENT);
            return new ProcessingResult(nextDurationSeconds, nextWidth, nextHeight, thumbnail);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
