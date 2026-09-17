import { useCallback, useEffect, useRef, useState } from 'react';
import { listMedia, type MediaResponse } from '../api/diagnosticMediaApi';

/**
 * NOT_LOADED != LOADED_EMPTY: 'not_loaded' and 'fetch_failed' are distinct
 * from 'loaded' with an empty/non-qualifying list. Callers computing
 * readiness must never treat the former two as "confirmed no video".
 */
export type MediaListStatus = 'not_loaded' | 'loading' | 'loaded' | 'fetch_failed';

const POLL_INTERVAL_MS = 10_000;

function hasPendingProcessing(mediaList: MediaResponse[]): boolean {
  return mediaList.some((m) => m.processingStatus === 'PENDING' || m.processingStatus === 'PROCESSING');
}

/**
 * Item-level readiness, derived ENTIRELY from server-confirmed state.
 * 'unknown' (not 'missing') is returned whenever the list hasn't successfully
 * loaded yet - satisfying NOT_LOADED != LOADED_EMPTY. Local Blob/recording/
 * upload state never participates in this calculation; only a fetched
 * MediaResponse row with uploadStatus=READY AND processingStatus=READY
 * counts as satisfying a mandatory video requirement.
 */
export type ItemReadiness = 'not_required' | 'unknown' | 'missing' | 'ready';

function isQualifyingVideo(m: MediaResponse): boolean {
  return m.mediaType === 'VIDEO' && m.uploadStatus === 'READY' && m.processingStatus === 'READY';
}

export function computeItemReadiness(
  hasVideo: boolean,
  status: MediaListStatus,
  mediaList: MediaResponse[],
): ItemReadiness {
  if (!hasVideo) return 'not_required';
  if (status !== 'loaded') return 'unknown'; // not_loaded / loading / fetch_failed - never "missing"
  return mediaList.some(isQualifyingVideo) ? 'ready' : 'missing';
}

export interface UseMediaProcessingResult {
  status: MediaListStatus;
  mediaList: MediaResponse[];
  refetch: () => void;
}

/**
 * Fetches, and while necessary polls, one InspectionItem's media list.
 * Polling runs ONLY while at least one row is PENDING/PROCESSING and stops
 * itself the instant every row has settled to READY/FAILED - no global
 * polling service, one interval per mounted instance. Knows nothing about
 * camera or MediaRecorder.
 */
export function useMediaProcessing(requestId: number, itemId: number): UseMediaProcessingResult {
  const [status, setStatus] = useState<MediaListStatus>('not_loaded');
  const [mediaList, setMediaList] = useState<MediaResponse[]>([]);

  const intervalRef = useRef<ReturnType<typeof setInterval> | null>(null);
  const mountedRef = useRef(true);
  const fetchTokenRef = useRef(0);
  const hasLoadedOnceRef = useRef(false);

  const clearPoll = useCallback(() => {
    if (intervalRef.current) {
      clearInterval(intervalRef.current);
      intervalRef.current = null;
    }
  }, []);

  const fetchOnce = useCallback(async () => {
    const myToken = ++fetchTokenRef.current;
    if (!hasLoadedOnceRef.current) {
      setStatus('loading');
    }
    try {
      const data = await listMedia(requestId, itemId);
      if (!mountedRef.current || fetchTokenRef.current !== myToken) return; // superseded - discard
      hasLoadedOnceRef.current = true;
      setMediaList(data);
      setStatus('loaded');
    } catch {
      if (!mountedRef.current || fetchTokenRef.current !== myToken) return;
      // A poll-tick failure after we already have a last-good list must NOT
      // regress to fetch_failed - that would misrepresent already-confirmed
      // server state as unverified. Only the initial/explicit fetch surfaces
      // fetch_failed; a background poll tick silently retries next interval.
      if (!hasLoadedOnceRef.current) {
        setStatus('fetch_failed');
      }
    }
  }, [requestId, itemId]);

  // resetAndFetch() is a plain function CALLED from the effect below, rather
  // than inlining setState directly in the effect body - matching this
  // codebase's own existing convention for mount-time fetches (e.g.
  // useNotifications.ts calling its own `load()` the same way).
  const resetAndFetch = useCallback(() => {
    mountedRef.current = true;
    hasLoadedOnceRef.current = false;
    setStatus('not_loaded');
    setMediaList([]);
    void fetchOnce();
  }, [fetchOnce]);

  useEffect(() => {
    // Intentional fetch-on-mount/item-change reset, matching this app's
    // existing load-on-mount convention (e.g. useNotifications.ts calling
    // its own `load()` from an effect); not a cascading-render bug.
    // eslint-disable-next-line react-hooks/set-state-in-effect
    resetAndFetch();
    return () => {
      mountedRef.current = false;
      clearPoll();
    };
  }, [resetAndFetch, clearPoll]);

  useEffect(() => {
    const shouldPoll = status === 'loaded' && hasPendingProcessing(mediaList);
    if (shouldPoll && !intervalRef.current) {
      intervalRef.current = setInterval(fetchOnce, POLL_INTERVAL_MS);
    } else if (!shouldPoll) {
      clearPoll();
    }
    return clearPoll;
  }, [status, mediaList, fetchOnce, clearPoll]);

  return { status, mediaList, refetch: fetchOnce };
}
