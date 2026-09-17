package com.example.autoauction.diagnostic.media.application;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure unit tests for MediaContentValidator.detectMimeType - no Spring context,
 * no I/O. Package-private access to the class under test (same package).
 */
@DisplayName("MediaContentValidator: определение MIME по magic bytes")
class MediaContentValidatorTest {

    @Test
    @DisplayName("Валидная сигнатура WebM/EBML -> video/webm")
    void validWebmSignature_DetectedAsVideoWebm() {
        byte[] webm = {(byte) 0x1A, 0x45, (byte) 0xDF, (byte) 0xA3, 0x01, 0x02, 0x03, 0x04};
        assertThat(MediaContentValidator.detectMimeType(webm)).isEqualTo("video/webm");
    }

    @Test
    @DisplayName("Сигнатура, отличающаяся на один байт -> null")
    void nearMissSignature_ReturnsNull() {
        byte[] nearMiss = {(byte) 0x1A, 0x45, (byte) 0xDF, (byte) 0xA2, 0x01, 0x02, 0x03, 0x04};
        assertThat(MediaContentValidator.detectMimeType(nearMiss)).isNull();
    }

    @Test
    @DisplayName("0 байт -> null, без исключения")
    void zeroBytes_ReturnsNullWithoutThrowing() {
        assertThat(MediaContentValidator.detectMimeType(new byte[0])).isNull();
    }

    @Test
    @DisplayName("1-3 байта -> null, без исключения")
    void shortInput_ReturnsNullWithoutThrowing() {
        assertThat(MediaContentValidator.detectMimeType(new byte[]{(byte) 0x1A})).isNull();
        assertThat(MediaContentValidator.detectMimeType(new byte[]{(byte) 0x1A, 0x45})).isNull();
        assertThat(MediaContentValidator.detectMimeType(new byte[]{(byte) 0x1A, 0x45, (byte) 0xDF})).isNull();
    }

    @Test
    @DisplayName("Существующая сигнатура JPEG не затронута")
    void existingJpegSignature_StillDetected() {
        byte[] jpeg = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0x01, 0x02, 0x03};
        assertThat(MediaContentValidator.detectMimeType(jpeg)).isEqualTo("image/jpeg");
    }

    @Test
    @DisplayName("Существующая сигнатура PNG не затронута")
    void existingPngSignature_StillDetected() {
        byte[] png = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};
        assertThat(MediaContentValidator.detectMimeType(png)).isEqualTo("image/png");
    }

    @Test
    @DisplayName("Существующая сигнатура MP4 (ftyp) не затронута")
    void existingMp4Signature_StillDetected() {
        byte[] mp4 = {0x00, 0x00, 0x00, 0x18, 'f', 't', 'y', 'p', 0x01};
        assertThat(MediaContentValidator.detectMimeType(mp4)).isEqualTo("video/mp4");
    }
}
