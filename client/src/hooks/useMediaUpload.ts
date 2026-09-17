import { useCallback, useRef, useState } from 'react';
import {
  createIntent,
  uploadToPresignedUrl,
  finalize,
  type MediaType,
  type MediaResponse,
} from '../api/diagnosticMediaApi';

export type UploadState =
  | 'not_started'
  | 'creating_intent'
  | 'uploading'
  | 'upload_failed'
  | 'finalizing'
  | 'finalize_failed'
  | 'done';

export interface UploadProgress {
  loaded: number;
  total: number | null;
}

export interface StartUploadParams {
  requestId: number;
  itemId: number;
  mediaType: MediaType;
  blob: Blob;
  mimeType: string;
}

export interface UseMediaUploadResult {
  state: UploadState;
  progress: UploadProgress | null;
  mediaId: number | null;
  result: MediaResponse | null;
  error: string | null;
  start: (params: StartUploadParams) => Promise<void>;
  retryFinalize: () => Promise<void>;
}

/**
 * Orchestrates exactly one intent -> presigned PUT -> finalize attempt.
 * Owns neither MediaStream nor MediaRecorder - a Blob arrives already
 * finished. Calling start() again (e.g. for a new recording of the same
 * item) begins a new attempt in the SAME hook instance; an attempt-token
 * guard ensures a slow, superseded async completion from an earlier attempt
 * can never overwrite state belonging to a newer one.
 */
export function useMediaUpload(): UseMediaUploadResult {
  const [state, setState] = useState<UploadState>('not_started');
  const [progress, setProgress] = useState<UploadProgress | null>(null);
  const [mediaId, setMediaId] = useState<number | null>(null);
  const [result, setResult] = useState<MediaResponse | null>(null);
  const [error, setError] = useState<string | null>(null);

  const attemptTokenRef = useRef(0);

  const start = useCallback(async (params: StartUploadParams) => {
    const myToken = ++attemptTokenRef.current;
    setError(null);
    setResult(null);
    setMediaId(null);
    setProgress(null);
    setState('creating_intent');

    let intent;
    try {
      intent = await createIntent(params.requestId, params.itemId, params.mediaType, params.mimeType, params.blob.size);
    } catch {
      if (attemptTokenRef.current !== myToken) return; // superseded by a newer attempt - discard
      setError('Не удалось начать загрузку');
      setState('upload_failed');
      return;
    }
    if (attemptTokenRef.current !== myToken) return;
    setMediaId(intent.mediaId);
    setState('uploading');

    try {
      await uploadToPresignedUrl(intent.uploadUrl, params.blob, params.mimeType, (event) => {
        if (attemptTokenRef.current !== myToken) return;
        setProgress({ loaded: event.loaded, total: event.total ?? null });
      });
    } catch {
      if (attemptTokenRef.current !== myToken) return;
      setError('Не удалось загрузить файл');
      setState('upload_failed');
      return;
    }
    if (attemptTokenRef.current !== myToken) return;
    setState('finalizing');

    try {
      const media = await finalize(intent.mediaId);
      if (attemptTokenRef.current !== myToken) return;
      setResult(media);
      setState('done');
    } catch {
      if (attemptTokenRef.current !== myToken) return;
      setError('Не удалось завершить загрузку');
      setState('finalize_failed');
    }
  }, []);

  // Safe to retry: DiagnosticMediaService.finalize() is idempotent by design -
  // an already-READY row is returned unchanged, and a still-PENDING_UPLOAD
  // row is simply re-validated. Verified against the current backend
  // implementation, not assumed.
  const retryFinalize = useCallback(async () => {
    if (mediaId == null) return;
    const myToken = attemptTokenRef.current; // continuing the same attempt, not starting a new one
    setState('finalizing');
    setError(null);
    try {
      const media = await finalize(mediaId);
      if (attemptTokenRef.current !== myToken) return;
      setResult(media);
      setState('done');
    } catch {
      if (attemptTokenRef.current !== myToken) return;
      setError('Не удалось завершить загрузку');
      setState('finalize_failed');
    }
  }, [mediaId]);

  return { state, progress, mediaId, result, error, start, retryFinalize };
}
