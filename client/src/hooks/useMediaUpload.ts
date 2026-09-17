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
  /**
   * Called once THIS attempt reaches a terminal state (done or any failure),
   * even if a newer attempt has since become the one reflected in this
   * hook's public state. Lets the caller refresh server-derived truth (e.g.
   * item readiness) for an attempt that is no longer the one being displayed.
   */
  onSettled?: () => void;
}

export interface UseMediaUploadResult {
  state: UploadState;
  progress: UploadProgress | null;
  mediaId: number | null;
  result: MediaResponse | null;
  error: string | null;
  start: (params: StartUploadParams) => Promise<void>;
  retryFinalize: (onSettled?: () => void) => Promise<void>;
}

/**
 * Orchestrates upload attempts (intent -> presigned PUT -> finalize). Each
 * start() call is an independent attempt with its own stable id. An attempt
 * ALWAYS runs its pipeline to completion once begun - a newer attempt never
 * aborts an older one's PUT/finalize, since the older attempt may already
 * own a server-side media row that must not be abandoned merely because
 * recording started again (multiple videos per item are explicitly
 * allowed). Only the LATEST attempt's progress is reflected in this hook's
 * public state/progress/mediaId/result/error fields, so an older attempt
 * can never overwrite a newer one's displayed status; each attempt still
 * owns its own async lifecycle end to end.
 */
export function useMediaUpload(): UseMediaUploadResult {
  const [state, setState] = useState<UploadState>('not_started');
  const [progress, setProgress] = useState<UploadProgress | null>(null);
  const [mediaId, setMediaId] = useState<number | null>(null);
  const [result, setResult] = useState<MediaResponse | null>(null);
  const [error, setError] = useState<string | null>(null);

  const nextAttemptIdRef = useRef(0);
  const latestAttemptIdRef = useRef(0);

  const start = useCallback(async (params: StartUploadParams) => {
    const myId = ++nextAttemptIdRef.current;
    latestAttemptIdRef.current = myId;
    const isLatest = () => latestAttemptIdRef.current === myId;

    setError(null);
    setResult(null);
    setMediaId(null);
    setProgress(null);
    setState('creating_intent');

    let intent;
    try {
      intent = await createIntent(params.requestId, params.itemId, params.mediaType, params.mimeType, params.blob.size);
    } catch {
      if (isLatest()) {
        setError('Не удалось начать загрузку');
        setState('upload_failed');
      }
      params.onSettled?.();
      return;
    }
    if (isLatest()) {
      setMediaId(intent.mediaId);
      setState('uploading');
    }

    try {
      await uploadToPresignedUrl(intent.uploadUrl, params.blob, params.mimeType, (event) => {
        if (isLatest()) {
          setProgress({ loaded: event.loaded, total: event.total ?? null });
        }
      });
    } catch {
      if (isLatest()) {
        setError('Не удалось загрузить файл');
        setState('upload_failed');
      }
      params.onSettled?.();
      return;
    }
    if (isLatest()) {
      setState('finalizing');
    }

    try {
      // intent.mediaId - never params - so this attempt always finalizes the
      // media row it itself created, regardless of newer attempts.
      const media = await finalize(intent.mediaId);
      if (isLatest()) {
        setResult(media);
        setState('done');
      }
    } catch {
      if (isLatest()) {
        setError('Не удалось завершить загрузку');
        setState('finalize_failed');
      }
    } finally {
      params.onSettled?.();
    }
  }, []);

  // Safe to retry: DiagnosticMediaService.finalize() is idempotent by design -
  // an already-READY row is returned unchanged, and a still-PENDING_UPLOAD
  // row is re-validated via the SAME mediaId (never creates a new row).
  // Re-verified fresh against the current backend implementation.
  const retryFinalize = useCallback(async (onSettled?: () => void) => {
    if (mediaId == null) return;
    const myId = latestAttemptIdRef.current; // continuing the currently-displayed attempt, not starting a new one
    const isLatest = () => latestAttemptIdRef.current === myId;
    setState('finalizing');
    setError(null);
    try {
      const media = await finalize(mediaId);
      if (isLatest()) {
        setResult(media);
        setState('done');
      }
    } catch {
      if (isLatest()) {
        setError('Не удалось завершить загрузку');
        setState('finalize_failed');
      }
    } finally {
      onSettled?.();
    }
  }, [mediaId]);

  return { state, progress, mediaId, result, error, start, retryFinalize };
}
