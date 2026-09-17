import { useCallback, useEffect, useRef, useState } from 'react';

export type RecordingState = 'idle' | 'recording' | 'finalizing_blob' | 'blob_ready' | 'failed';

/**
 * Candidate order for MediaRecorder.isTypeSupported negotiation. video/mp4
 * first (Safari's native output), then WebM variants (Chrome/Firefox/most
 * Android). The backend (DiagnosticMediaService.VIDEO_MIME_TYPES) accepts
 * exactly "video/mp4" and "video/webm" - this module never sends anything
 * else; codec-qualified strings are normalized to their bare container type
 * by diagnosticMediaApi.normalizeMimeForBackend before any network call.
 */
const MIME_CANDIDATES = [
  'video/mp4',
  'video/webm;codecs=vp9,opus',
  'video/webm;codecs=vp8,opus',
  'video/webm',
];

export function selectSupportedMimeType(): string | null {
  if (typeof MediaRecorder === 'undefined' || typeof MediaRecorder.isTypeSupported !== 'function') {
    return null;
  }
  return MIME_CANDIDATES.find((candidate) => MediaRecorder.isTypeSupported(candidate)) ?? null;
}

const MAX_RECORDING_SECONDS = 120;

export interface UseVideoRecorderResult {
  state: RecordingState;
  elapsedSeconds: number;
  blob: Blob | null;
  mimeType: string | null;
  start: (stream: MediaStream) => void;
  stop: () => void;
  reset: () => void;
}

/**
 * Owns MediaRecorder, chunk buffering, and the recording state machine only.
 * No Axios, no diagnostic media IDs, no upload/finalize/polling - the
 * resulting Blob is handed to the caller via `blob`/`mimeType` once
 * state === 'blob_ready'; everything downstream of that is useMediaUpload's
 * responsibility, not this hook's.
 */
export function useVideoRecorder(): UseVideoRecorderResult {
  const [state, setState] = useState<RecordingState>('idle');
  const [elapsedSeconds, setElapsedSeconds] = useState(0);
  const [blob, setBlob] = useState<Blob | null>(null);
  const [mimeType, setMimeType] = useState<string | null>(null);

  const recorderRef = useRef<MediaRecorder | null>(null);
  const chunksRef = useRef<Blob[]>([]);
  const timerRef = useRef<ReturnType<typeof setInterval> | null>(null);
  const stoppingRef = useRef(false); // guards duplicate stop() calls (manual + 120s auto-stop race)
  const erroredRef = useRef(false); // guards onstop from overwriting a state already set to 'failed' by onerror

  const clearTimer = useCallback(() => {
    if (timerRef.current) {
      clearInterval(timerRef.current);
      timerRef.current = null;
    }
  }, []);

  const stop = useCallback(() => {
    const recorder = recorderRef.current;
    if (!recorder || recorder.state !== 'recording' || stoppingRef.current) return; // duplicate-stop guard
    stoppingRef.current = true;
    clearTimer();
    setState('finalizing_blob');
    recorder.stop();
  }, [clearTimer]);

  const start = useCallback((stream: MediaStream) => {
    if (recorderRef.current) return; // duplicate-start guard - a recorder is already active
    const candidate = selectSupportedMimeType();
    if (!candidate) {
      setState('failed');
      return;
    }

    let recorder: MediaRecorder;
    try {
      recorder = new MediaRecorder(stream, { mimeType: candidate });
    } catch {
      setState('failed');
      return;
    }

    chunksRef.current = [];
    stoppingRef.current = false;
    erroredRef.current = false;
    setBlob(null);
    setMimeType(recorder.mimeType || candidate);

    recorder.ondataavailable = (event: BlobEvent) => {
      if (event.data && event.data.size > 0) {
        chunksRef.current.push(event.data);
      }
    };

    recorder.onerror = () => {
      erroredRef.current = true;
      clearTimer();
      recorderRef.current = null;
      chunksRef.current = [];
      setState('failed');
    };

    recorder.onstop = () => {
      // onerror may fire before onstop for the same recorder in some browsers -
      // if it already did, 'failed' is authoritative and must not be overwritten.
      if (erroredRef.current) return;
      const finalMimeType = recorder.mimeType || candidate;
      const finalBlob = new Blob(chunksRef.current, { type: finalMimeType });
      chunksRef.current = []; // release chunk references now that they're consolidated into finalBlob
      recorderRef.current = null;
      if (finalBlob.size === 0) {
        setState('failed');
      } else {
        setBlob(finalBlob);
        setState('blob_ready');
      }
    };

    recorderRef.current = recorder;
    setElapsedSeconds(0);
    recorder.start();
    setState('recording');

    timerRef.current = setInterval(() => {
      setElapsedSeconds((previous) => {
        const next = previous + 1;
        if (next >= MAX_RECORDING_SECONDS) {
          stop(); // 120s ceiling: same guarded path as a manual stop, never a second recorder.stop() call
        }
        return next;
      });
    }, 1000);
  }, [clearTimer, stop]);

  const reset = useCallback(() => {
    setState('idle');
    setBlob(null);
    setMimeType(null);
    setElapsedSeconds(0);
  }, []);

  // Guaranteed cleanup on unmount/navigation: stop an active recorder rather
  // than leaving it referencing tracks the owning useCameraStream may release.
  useEffect(() => {
    return () => {
      clearTimer();
      const recorder = recorderRef.current;
      if (recorder && recorder.state === 'recording') {
        try {
          recorder.stop();
        } catch {
          // best-effort on unmount - nothing left to update afterward
        }
      }
      recorderRef.current = null;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  return { state, elapsedSeconds, blob, mimeType, start, stop, reset };
}
