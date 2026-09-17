package com.example.autoauction.diagnostic.media.application;

/**
 * Basic magic-byte signature matching only - JPEG (FFD8FF), PNG
 * (89504E47 0D0A1A0A), MP4/ISO-BMFF (ftyp box at offset 4), WebM/EBML
 * (1A45DFA3 at offset 0). Deliberately NOT
 * full container/codec validation, which would require a media-processing
 * library this project does not have; MEDIA-3.2 explicitly does not add one.
 * Sufficient to distinguish the three supported types and reject content
 * whose actual bytes don't match any of them - closing the
 * "trust the client's declared Content-Type" gap without a heavy dependency.
 * Shared by DiagnosticMediaService.finalize() and MediaReconciliationService
 * so both apply the exact same validation, not two independently-maintained
 * copies.
 */
final class MediaContentValidator {

    private MediaContentValidator() {}

    static final int PROBE_LENGTH = 16;

    static String detectMimeType(byte[] b) {
        if (b.length >= 3 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8 && (b[2] & 0xFF) == 0xFF) {
            return "image/jpeg";
        }
        if (b.length >= 8 && (b[0] & 0xFF) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G'
                && b[4] == 0x0D && b[5] == 0x0A && b[6] == 0x1A && b[7] == 0x0A) {
            return "image/png";
        }
        if (b.length >= 8 && b[4] == 'f' && b[5] == 't' && b[6] == 'y' && b[7] == 'p') {
            return "video/mp4";
        }
        if (b.length >= 4 && (b[0] & 0xFF) == 0x1A && (b[1] & 0xFF) == 0x45
                && (b[2] & 0xFF) == 0xDF && (b[3] & 0xFF) == 0xA3) {
            return "video/webm";
        }
        return null;
    }
}
