package com.example.autoauction.diagnostic.media.infrastructure.video;

import com.example.autoauction.diagnostic.media.domain.port.VideoProcessor;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * MEDIA-3.4: production VideoProcessor adapter - ffprobe for metadata,
 * ffmpeg for a single thumbnail frame, both invoked as external subprocesses
 * via ProcessBuilder with an explicit argument list (never a shell). No
 * FFmpeg Maven dependency - this is the only class in the project that knows
 * these binaries exist; VideoProcessingService (a later phase) only ever
 * sees this through the VideoProcessor port.
 *
 * ProcessingLimits is the caller's authoritative configuration - this class
 * never reads its own @Value processing limits and never maintains a second
 * copy of them (MEDIA-3.4 Phase 5 architectural constraint).
 *
 * @Profile("!test") - never instantiated under the test profile, which uses
 * FakeVideoProcessor instead (no ffmpeg binary needed to run the suite).
 */
@Slf4j
@Component
@Profile("!test")
public class FfmpegVideoProcessor implements VideoProcessor {

    // Explicit, server-controlled executable paths - default to the confirmed
    // Alpine `ffmpeg` community package layout (the Dockerfile installs it there).
    // MEDIA-PROD-04: overridable for a host without /usr/bin (an isolated end-to-end
    // run on Windows); still absolute paths from server configuration, never user input.
    static final String DEFAULT_FFPROBE_PATH = "/usr/bin/ffprobe";
    static final String DEFAULT_FFMPEG_PATH = "/usr/bin/ffmpeg";

    private final String ffprobePath;
    private final String ffmpegPath;

    private static final Duration FFPROBE_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration FFMPEG_TIMEOUT = Duration.ofSeconds(60);
    private static final Duration TERMINATION_GRACE = Duration.ofSeconds(5);

    // Bounds retained process output regardless of how much ffprobe/ffmpeg
    // actually emit - a crafted/malformed input must never be able to force
    // unbounded heap growth here.
    private static final int MAX_STDOUT_CAPTURE_BYTES = 1_048_576; // ffprobe JSON, generous for one video stream
    private static final int MAX_STDERR_CAPTURE_BYTES = 4_096;     // logging only, never parsed

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    public FfmpegVideoProcessor(@Value("${diagnostic.media.processing.ffprobe-path:" + DEFAULT_FFPROBE_PATH + "}") String ffprobePath,
                                @Value("${diagnostic.media.processing.ffmpeg-path:" + DEFAULT_FFMPEG_PATH + "}") String ffmpegPath) {
        this.ffprobePath = ffprobePath;
        this.ffmpegPath = ffmpegPath;
    }

    @Override
    public ProcessingResult process(Path videoFile, ProcessingLimits limits) {
        validateInputFile(videoFile);

        VideoMetadata metadata = extractMetadata(videoFile, limits);
        Path thumbnail = extractThumbnail(videoFile, limits);

        log.info("FfmpegVideoProcessor: processed video (duration={}s, {}x{}, thumbnailBytes={})",
                metadata.durationSeconds(), metadata.width(), metadata.height(), sizeQuietly(thumbnail));

        return new ProcessingResult(metadata.durationSeconds(), metadata.width(), metadata.height(), thumbnail);
    }

    private void validateInputFile(Path videoFile) {
        if (videoFile == null) {
            throw new IllegalArgumentException("videoFile must not be null");
        }
        if (!Files.exists(videoFile)) {
            throw new IllegalArgumentException("videoFile does not exist: " + videoFile);
        }
        if (!Files.isRegularFile(videoFile)) {
            throw new IllegalArgumentException("videoFile is not a regular file: " + videoFile);
        }
        if (!Files.isReadable(videoFile)) {
            throw new IllegalArgumentException("videoFile is not readable: " + videoFile);
        }
    }

    // ==================== ffprobe: metadata extraction + validation ====================

    private record VideoMetadata(int durationSeconds, int width, int height) {}

    private VideoMetadata extractMetadata(Path videoFile, ProcessingLimits limits) {
        List<String> command = List.of(ffprobePath,
                "-v", "quiet",
                "-print_format", "json",
                "-show_format",
                "-show_streams",
                videoFile.toString());

        ProcessResult result = runProcess(command, FFPROBE_TIMEOUT, MAX_STDOUT_CAPTURE_BYTES);
        if (result.exitCode() != 0) {
            throw new IllegalStateException("ffprobe exited with status " + result.exitCode());
        }
        if (result.stdoutTruncated()) {
            // Never attempt to parse a truncated JSON document - fail explicitly instead of
            // risking a misleading parse error or, worse, a partially-successful parse.
            throw new IllegalStateException("ffprobe output exceeded the maximum accepted size");
        }

        JsonNode root;
        try {
            root = OBJECT_MAPPER.readTree(result.stdout());
        } catch (IOException e) {
            throw new IllegalStateException("ffprobe output could not be parsed as JSON", e);
        }

        double durationValue = parseDuration(root);
        if (!Double.isFinite(durationValue) || durationValue <= 0) {
            throw new IllegalStateException("ffprobe reported an invalid duration: " + durationValue);
        }
        int durationSeconds = (int) Math.round(durationValue);

        int[] dimensions = parseVideoDimensions(root);
        int width = dimensions[0];
        int height = dimensions[1];
        if (width <= 0 || height <= 0) {
            throw new IllegalStateException("ffprobe reported invalid video dimensions: " + width + "x" + height);
        }

        if (durationSeconds > limits.maxDurationSeconds()) {
            throw new IllegalStateException("video duration " + durationSeconds
                    + "s exceeds configured limit " + limits.maxDurationSeconds() + "s");
        }
        if (width > limits.maxWidth() || height > limits.maxHeight()) {
            throw new IllegalStateException("video dimensions " + width + "x" + height
                    + " exceed configured limit " + limits.maxWidth() + "x" + limits.maxHeight());
        }

        return new VideoMetadata(durationSeconds, width, height);
    }

    private double parseDuration(JsonNode root) {
        JsonNode durationNode = root.path("format").path("duration");
        if (!durationNode.isTextual() && !durationNode.isNumber()) {
            throw new IllegalStateException("ffprobe output is missing format.duration");
        }
        try {
            return Double.parseDouble(durationNode.asText());
        } catch (NumberFormatException e) {
            throw new IllegalStateException("ffprobe reported a non-numeric duration: " + durationNode.asText());
        }
    }

    private int[] parseVideoDimensions(JsonNode root) {
        JsonNode streams = root.path("streams");
        if (!streams.isArray()) {
            throw new IllegalStateException("ffprobe output is missing streams[]");
        }
        for (JsonNode stream : streams) {
            if ("video".equals(stream.path("codec_type").asText(null))) {
                JsonNode widthNode = stream.path("width");
                JsonNode heightNode = stream.path("height");
                if (!widthNode.isInt() || !heightNode.isInt()) {
                    throw new IllegalStateException("ffprobe video stream is missing integer width/height");
                }
                return new int[]{widthNode.asInt(), heightNode.asInt()};
            }
        }
        throw new IllegalStateException("ffprobe output contains no video stream");
    }

    // ==================== ffmpeg: single-frame thumbnail ====================

    private Path extractThumbnail(Path videoFile, ProcessingLimits limits) {
        Path thumbnail;
        try {
            // Server-generated, inside videoFile's own parent directory - never derived from
            // storageKey, original filename, URL, or caption.
            thumbnail = Files.createTempFile(videoFile.getParent(), "thumbnail-", ".jpg");
        } catch (IOException e) {
            throw new IllegalStateException("failed to create a server-generated thumbnail output file", e);
        }

        // Comma inside the filtergraph expression is escaped for ffmpeg's OWN -vf grammar
        // (not shell escaping - there is no shell in this invocation model at all).
        String scaleFilter = "scale=w=min(iw\\,%d):h=min(ih\\,%d):force_original_aspect_ratio=decrease"
                .formatted(limits.thumbnailMaxWidth(), limits.thumbnailMaxHeight());

        List<String> command = List.of(ffmpegPath,
                "-y",
                "-v", "quiet",
                "-i", videoFile.toString(),
                "-frames:v", "1",
                "-vf", scaleFilter,
                thumbnail.toString());

        ProcessResult result = runProcess(command, FFMPEG_TIMEOUT, 0);
        if (result.exitCode() != 0) {
            throw new IllegalStateException("ffmpeg exited with status " + result.exitCode());
        }

        if (!Files.exists(thumbnail)) {
            throw new IllegalStateException("ffmpeg reported success but produced no thumbnail file");
        }
        long thumbnailSize;
        try {
            thumbnailSize = Files.size(thumbnail);
        } catch (IOException e) {
            throw new IllegalStateException("failed to verify generated thumbnail file", e);
        }
        if (thumbnailSize == 0) {
            throw new IllegalStateException("ffmpeg produced an empty thumbnail file");
        }

        return thumbnail;
    }

    // ==================== bounded, timeout-guarded process execution ====================

    private record ProcessResult(int exitCode, byte[] stdout, boolean stdoutTruncated) {}

    /**
     * Starts command, drains stdout/stderr concurrently (bounded, never
     * unbounded accumulation - a pipe that fills up without being read would
     * otherwise deadlock the child), waits up to timeout, and on timeout
     * forcibly destroys the process and CONFIRMS termination before
     * returning - destroyForcibly() alone is a request, not a guarantee.
     */
    private ProcessResult runProcess(List<String> command, Duration timeout, int maxStdoutBytes) {
        Process process;
        try {
            process = new ProcessBuilder(command).start();
        } catch (IOException e) {
            throw new IllegalStateException("failed to start external process: " + command.get(0), e);
        }

        BoundedDrain stdoutDrain = new BoundedDrain(process.getInputStream(), Math.max(maxStdoutBytes, 1));
        BoundedDrain stderrDrain = new BoundedDrain(process.getErrorStream(), MAX_STDERR_CAPTURE_BYTES);
        Thread stdoutThread = new Thread(stdoutDrain, "ffmpeg-stdout-drain");
        Thread stderrThread = new Thread(stderrDrain, "ffmpeg-stderr-drain");
        stdoutThread.setDaemon(true);
        stderrThread.setDaemon(true);
        stdoutThread.start();
        stderrThread.start();

        boolean finished;
        try {
            finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            throw new IllegalStateException("interrupted while waiting for external process: " + command.get(0), e);
        }

        if (!finished) {
            process.destroyForcibly();
            boolean terminated = waitForTermination(process, TERMINATION_GRACE);
            joinQuietly(stdoutThread, stderrThread);
            if (!terminated) {
                log.error("FfmpegVideoProcessor: {} did not terminate after destroyForcibly() within {}",
                        command.get(0), TERMINATION_GRACE);
            }
            throw new IllegalStateException("external process timed out after " + timeout.toSeconds() + "s: " + command.get(0));
        }

        joinQuietly(stdoutThread, stderrThread);

        int exitCode = process.exitValue();
        if (exitCode != 0) {
            log.warn("FfmpegVideoProcessor: {} exited with status {} (stderr, capped at {} bytes: {})",
                    command.get(0), exitCode, MAX_STDERR_CAPTURE_BYTES,
                    new String(stderrDrain.captured(), StandardCharsets.UTF_8));
        }
        return new ProcessResult(exitCode, stdoutDrain.captured(), stdoutDrain.wasTruncated());
    }

    private boolean waitForTermination(Process process, Duration grace) {
        try {
            return process.waitFor(grace.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return !process.isAlive();
        }
    }

    private void joinQuietly(Thread... threads) {
        for (Thread t : threads) {
            try {
                t.join(2_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static long sizeQuietly(Path path) {
        try {
            return Files.size(path);
        } catch (IOException e) {
            return -1;
        }
    }

    /** Drains an InputStream fully without blocking the writer, retaining at most maxBytes. */
    private static final class BoundedDrain implements Runnable {
        private final InputStream input;
        private final int maxBytes;
        private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        private volatile boolean truncated = false;

        BoundedDrain(InputStream input, int maxBytes) {
            this.input = input;
            this.maxBytes = maxBytes;
        }

        @Override
        public void run() {
            byte[] chunk = new byte[8192];
            try {
                int read;
                while ((read = input.read(chunk)) != -1) {
                    int remaining = maxBytes - buffer.size();
                    if (remaining > 0) {
                        buffer.write(chunk, 0, Math.min(remaining, read));
                    }
                    if (read > remaining) {
                        truncated = true;
                    }
                }
            } catch (IOException ignored) {
                // Process ended / stream closed - nothing more to drain.
            }
        }

        byte[] captured() {
            return buffer.toByteArray();
        }

        boolean wasTruncated() {
            return truncated;
        }
    }
}
