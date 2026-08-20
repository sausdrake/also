package com.example.autoauction.diagnostic.media.infrastructure.video;

import com.example.autoauction.diagnostic.media.domain.port.VideoProcessor;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * MEDIA-3.4 Phase 5: FfmpegVideoProcessor.
 *
 * This repository has no genuine playable MP4 fixture (confirmed during the
 * MEDIA-3.4 pre-flight review - the only .mp4 files anywhere are synthetic
 * magic-byte-only stubs from unrelated legacy-migration tests, not real
 * video). Rather than fabricate one, the ffmpeg-dependent tests below
 * generate a real, valid, known-duration/resolution MP4 AT TEST TIME using
 * ffmpeg's own built-in lavfi color source - a genuine video, not a stub,
 * and not a checked-in repository asset. Every ffmpeg-dependent test
 * self-skips via Assumptions if ffmpeg/ffprobe are not present in the
 * current environment (confirmed absent on this Windows dev machine - the
 * hardcoded /usr/bin paths are Linux/Alpine-specific, matching the target
 * container).
 *
 * The input-validation tests need neither ffmpeg nor a fixture - they prove
 * process() rejects bad input before ever starting a subprocess.
 */
@DisplayName("MEDIA-3.4: FfmpegVideoProcessor")
class FfmpegVideoProcessorTest {

    private static final String FFMPEG_PATH = "/usr/bin/ffmpeg";
    private static final String FFPROBE_PATH = "/usr/bin/ffprobe";

    private static void assumeFfmpegAvailable() {
        Assumptions.assumeTrue(Files.isExecutable(Path.of(FFMPEG_PATH)), "ffmpeg is not available in this environment");
        Assumptions.assumeTrue(Files.isExecutable(Path.of(FFPROBE_PATH)), "ffprobe is not available in this environment");
    }

    /** Generates a real, valid MP4 of the given duration/resolution using ffmpeg's own lavfi color source. */
    private Path generateRealTestVideo(Path dir, int durationSeconds, int width, int height) throws IOException, InterruptedException {
        Path output = dir.resolve("real-input.mp4");
        List<String> command = List.of(FFMPEG_PATH, "-y", "-v", "quiet",
                "-f", "lavfi", "-i", "color=c=blue:s=" + width + "x" + height + ":d=" + durationSeconds,
                "-frames:v", String.valueOf(durationSeconds * 5),
                output.toString());
        Process process = new ProcessBuilder(command).start();
        boolean finished = process.waitFor(30, TimeUnit.SECONDS);
        if (!finished || process.exitValue() != 0) {
            throw new IllegalStateException("failed to generate the synthetic test video fixture (exit="
                    + (finished ? process.exitValue() : "timeout") + ")");
        }
        return output;
    }

    private VideoProcessor.ProcessingLimits generousLimits() {
        return new VideoProcessor.ProcessingLimits(120, 1920, 1080, 320, 240);
    }

    // ==================== input validation - no ffmpeg needed ====================

    @Test
    @DisplayName("null videoFile -> IllegalArgumentException до запуска subprocess")
    void nullVideoFile_RejectedBeforeAnySubprocess() {
        FfmpegVideoProcessor processor = new FfmpegVideoProcessor();
        assertThatThrownBy(() -> processor.process(null, generousLimits()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("Несуществующий файл -> IllegalArgumentException")
    void nonExistentFile_Rejected() {
        FfmpegVideoProcessor processor = new FfmpegVideoProcessor();
        Path missing = Path.of(System.getProperty("java.io.tmpdir"), "definitely-does-not-exist-" + System.nanoTime() + ".mp4");
        assertThatThrownBy(() -> processor.process(missing, generousLimits()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("Директория вместо файла -> IllegalArgumentException")
    void directoryInsteadOfFile_Rejected() throws IOException {
        FfmpegVideoProcessor processor = new FfmpegVideoProcessor();
        Path dir = Files.createTempDirectory("ffmpeg-test-dir-");
        assertThatThrownBy(() -> processor.process(dir, generousLimits()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ==================== ffmpeg-dependent (environment-gated) ====================

    @Test
    @DisplayName("Валидное реальное MP4 -> метаданные и миниатюра корректны")
    void validRealMp4_ExtractsMetadataAndThumbnail() throws Exception {
        assumeFfmpegAvailable();
        Path dir = Files.createTempDirectory("ffmpeg-test-valid-");
        Path video = generateRealTestVideo(dir, 2, 320, 240);
        FfmpegVideoProcessor processor = new FfmpegVideoProcessor();

        VideoProcessor.ProcessingResult result = processor.process(video, generousLimits());

        assertThat(result.durationSeconds()).isBetween(1, 3); // rounding tolerance around the 2s source
        assertThat(result.width()).isEqualTo(320);
        assertThat(result.height()).isEqualTo(240);
        assertThat(result.thumbnailFile()).exists();
        assertThat(Files.size(result.thumbnailFile())).isGreaterThan(0);
        assertThat(result.thumbnailFile().getParent()).isEqualTo(video.getParent());
    }

    @Test
    @DisplayName("Повреждённый/не-видео вход -> ошибка, миниатюра не создаётся")
    void malformedNonVideoInput_Fails() throws Exception {
        assumeFfmpegAvailable();
        Path dir = Files.createTempDirectory("ffmpeg-test-malformed-");
        Path garbage = dir.resolve("garbage.mp4");
        Files.write(garbage, "this is not a video file, just plain text bytes".getBytes(StandardCharsets.UTF_8));
        FfmpegVideoProcessor processor = new FfmpegVideoProcessor();

        assertThatThrownBy(() -> processor.process(garbage, generousLimits()))
                .isInstanceOf(IllegalStateException.class);

        // no thumbnail file must have been left behind
        try (Stream<Path> files = Files.list(dir)) {
            assertThat(files).noneMatch(p -> p.getFileName().toString().startsWith("thumbnail-"));
        }
    }

    @Test
    @DisplayName("Длительность превышает лимит -> отклонено ДО извлечения миниатюры")
    void durationExceedsLimit_RejectedBeforeThumbnailExtraction() throws Exception {
        assumeFfmpegAvailable();
        Path dir = Files.createTempDirectory("ffmpeg-test-duration-limit-");
        Path video = generateRealTestVideo(dir, 2, 320, 240);
        FfmpegVideoProcessor processor = new FfmpegVideoProcessor();
        VideoProcessor.ProcessingLimits tooStrict = new VideoProcessor.ProcessingLimits(1, 1920, 1080, 320, 240);

        assertThatThrownBy(() -> processor.process(video, tooStrict))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("duration");

        try (Stream<Path> files = Files.list(dir)) {
            assertThat(files).noneMatch(p -> p.getFileName().toString().startsWith("thumbnail-"));
        }
    }

    @Test
    @DisplayName("Ширина превышает лимит -> отклонено ДО извлечения миниатюры")
    void widthExceedsLimit_RejectedBeforeThumbnailExtraction() throws Exception {
        assumeFfmpegAvailable();
        Path dir = Files.createTempDirectory("ffmpeg-test-width-limit-");
        Path video = generateRealTestVideo(dir, 2, 320, 240);
        FfmpegVideoProcessor processor = new FfmpegVideoProcessor();
        VideoProcessor.ProcessingLimits tooStrict = new VideoProcessor.ProcessingLimits(120, 100, 1080, 320, 240);

        assertThatThrownBy(() -> processor.process(video, tooStrict))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("dimensions");

        try (Stream<Path> files = Files.list(dir)) {
            assertThat(files).noneMatch(p -> p.getFileName().toString().startsWith("thumbnail-"));
        }
    }
}
