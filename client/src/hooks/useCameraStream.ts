import { useCallback, useEffect, useRef, useState } from 'react';

export type CameraState = 'idle' | 'requesting' | 'ready' | 'permission_denied' | 'unavailable' | 'error';

export interface UseCameraStreamResult {
  state: CameraState;
  stream: MediaStream | null;
  start: () => Promise<void>;
  stop: () => void;
}

/**
 * Owns a MediaStream's lifecycle only - permission request, the stream
 * itself, and guaranteed track cleanup. Knows nothing about recording,
 * uploads, or diagnostic media IDs. The stream is never stored outside this
 * hook's own state/refs (never Zustand, never a module-level variable).
 */
export function useCameraStream(): UseCameraStreamResult {
  const [state, setState] = useState<CameraState>('idle');
  const [stream, setStream] = useState<MediaStream | null>(null);
  const streamRef = useRef<MediaStream | null>(null);

  const releaseTracks = useCallback(() => {
    streamRef.current?.getTracks().forEach((track) => track.stop());
    streamRef.current = null;
  }, []);

  const stop = useCallback(() => {
    releaseTracks();
    setStream(null);
    setState('idle');
  }, [releaseTracks]);

  const start = useCallback(async () => {
    if (streamRef.current) return; // already active - no-op, not a new request
    setState('requesting');
    try {
      const mediaStream = await navigator.mediaDevices.getUserMedia({
        video: { width: { max: 1920 }, height: { max: 1080 } },
        audio: true,
      });
      streamRef.current = mediaStream;
      setStream(mediaStream);
      setState('ready');
    } catch (err: unknown) {
      const name = err instanceof DOMException ? err.name : '';
      if (name === 'NotAllowedError' || name === 'SecurityError') {
        setState('permission_denied');
      } else if (name === 'NotFoundError' || name === 'OverconstrainedError') {
        setState('unavailable');
      } else {
        setState('error');
      }
    }
  }, []);

  // Guaranteed cleanup on unmount, regardless of current state - never leaves
  // an active track behind. Runs once per mount (empty deps): the effect
  // reads streamRef, not stream, so it always sees the latest stream.
  useEffect(() => {
    return () => {
      releaseTracks();
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  return { state, stream, start, stop };
}
