package com.example.autoauction.diagnostic.media.infrastructure.video;

import com.example.autoauction.diagnostic.media.domain.port.VideoProcessor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * MEDIA-3.4 Phase 4: proves the VideoProcessor port contract and
 * FakeVideoProcessor's double, independent of any real ffmpeg binary.
 */
@DisplayName("MEDIA-3.4: VideoProcessor port / FakeVideoProcessor")
class FakeVideoProcessorTest {

    private Path createVideoFileFixture() throws IOException {
        Path dir = Files.createTempDirectory("video-processor-test-");
        Path video = dir.resolve("input.mp4");
        Files.write(video, new byte[]{0x00, 0x00, 0x00, 0x18, 'f', 't', 'y', 'p'});
        return video;
    }

    private VideoProcessor.ProcessingLimits defaultLimits() {
        return new VideoProcessor.ProcessingLimits(120, 1920, 1080, 320, 240);
    }

    @Test
    @DisplayName("Настроенный успешный результат детерминирован")
    void configuredSuccess_ReturnsDeterministicMetadata() throws IOException {
        FakeVideoProcessor processor = new FakeVideoProcessor();
        processor.configureSuccess(42, 1280, 720);
        Path video = createVideoFileFixture();

        VideoProcessor.ProcessingResult result = processor.process(video, defaultLimits());

        assertThat(result.durationSeconds()).isEqualTo(42);
        assertThat(result.width()).isEqualTo(1280);
        assertThat(result.height()).isEqualTo(720);
    }

    @Test
    @DisplayName("Путь к миниатюре — реальный файл в той же директории, что и видео")
    void thumbnailPath_IsRealFileInVideoParentDirectory() throws IOException {
        FakeVideoProcessor processor = new FakeVideoProcessor();
        Path video = createVideoFileFixture();

        VideoProcessor.ProcessingResult result = processor.process(video, defaultLimits());

        assertThat(result.thumbnailFile()).exists();
        assertThat(Files.size(result.thumbnailFile())).isGreaterThan(0);
        assertThat(result.thumbnailFile().getParent()).isEqualTo(video.getParent());
    }

    @Test
    @DisplayName("Настроенный сбой корректно распространяется (one-shot)")
    void configuredFailure_PropagatesThenResets() throws IOException {
        FakeVideoProcessor processor = new FakeVideoProcessor();
        RuntimeException simulated = new RuntimeException("simulated processing failure");
        processor.configureFailure(simulated);
        Path video = createVideoFileFixture();

        assertThatThrownBy(() -> processor.process(video, defaultLimits()))
                .isSameAs(simulated);

        // one-shot: the next call succeeds normally again
        VideoProcessor.ProcessingResult result = processor.process(video, defaultLimits());
        assertThat(result.thumbnailFile()).exists();
    }

    @Test
    @DisplayName("ProcessingLimits — обычный value object, никакой FFmpeg-специфики в контракте")
    void processingLimits_CarriesExactValues() {
        VideoProcessor.ProcessingLimits limits = new VideoProcessor.ProcessingLimits(60, 1920, 1080, 320, 240);

        assertThat(limits.maxDurationSeconds()).isEqualTo(60);
        assertThat(limits.maxWidth()).isEqualTo(1920);
        assertThat(limits.maxHeight()).isEqualTo(1080);
        assertThat(limits.thumbnailMaxWidth()).isEqualTo(320);
        assertThat(limits.thumbnailMaxHeight()).isEqualTo(240);
    }
}
