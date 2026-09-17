import { useEffect, useMemo } from 'react';
import { useCameraStream } from '../../hooks/useCameraStream';
import { useVideoRecorder } from '../../hooks/useVideoRecorder';
import { useMediaUpload } from '../../hooks/useMediaUpload';
import { useMediaProcessing, computeItemReadiness, type ItemReadiness } from '../../hooks/useMediaProcessing';
import { DiagnosticVideoRecorder } from './DiagnosticVideoRecorder';
import { DiagnosticMediaStatus } from './DiagnosticMediaStatus';

export type { ItemReadiness };

export interface DiagnosticMediaSectionProps {
  requestId: number;
  itemId: number;
  hasVideo: boolean;
  onReadinessChange?: (itemId: number, readiness: ItemReadiness) => void;
}

/**
 * Composes the video-capture feature for ONE InspectionItem: camera lifecycle
 * (useCameraStream), recording (useVideoRecorder), two independent upload
 * orchestrators (video + snapshot - never sharing attempt state, so a failed
 * snapshot can never affect the video or vice versa), and server media
 * fetch/polling (useMediaProcessing). This component owns no camera/API/
 * polling logic itself - it only wires the hooks together and renders the
 * two presentational pieces. Readiness is reported upward via a callback so
 * ReportForm can aggregate it without owning any media state itself.
 */
export function DiagnosticMediaSection({
  requestId,
  itemId,
  hasVideo,
  onReadinessChange,
}: DiagnosticMediaSectionProps) {
  const camera = useCameraStream();
  const recorder = useVideoRecorder();
  const videoUpload = useMediaUpload();
  const snapshotUpload = useMediaUpload(); // independent instance - see file-level note above
  const processing = useMediaProcessing(requestId, itemId);

  const readiness = useMemo(
    () => computeItemReadiness(hasVideo, processing.status, processing.mediaList),
    [hasVideo, processing.status, processing.mediaList],
  );

  useEffect(() => {
    onReadinessChange?.(itemId, readiness);
  }, [itemId, readiness, onReadinessChange]);

  // Hand a finished recording off to its own upload attempt, then return the
  // recorder to idle so a new recording can begin immediately (multiple
  // videos per item are explicitly allowed).
  useEffect(() => {
    if (recorder.state === 'blob_ready' && recorder.blob && recorder.mimeType) {
      void videoUpload.start({
        requestId,
        itemId,
        mediaType: 'VIDEO',
        blob: recorder.blob,
        mimeType: recorder.mimeType,
      });
      recorder.reset();
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [recorder.state]);

  // Refresh the server media list once either upload attempt finalizes -
  // the freshly-finalized row (and its processingStatus) can only be
  // observed by re-fetching, since finalize()'s own response is not kept
  // as this section's source of truth for readiness.
  useEffect(() => {
    if (videoUpload.state === 'done' || snapshotUpload.state === 'done') {
      processing.refetch();
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [videoUpload.state, snapshotUpload.state]);

  if (!hasVideo) return null;

  const handleSnapshot = (blob: Blob) => {
    void snapshotUpload.start({ requestId, itemId, mediaType: 'PHOTO', blob, mimeType: 'image/jpeg' });
  };

  return (
    <div className="mt-3 rounded-lg border border-gray-200 p-3">
      <DiagnosticVideoRecorder camera={camera} recorder={recorder} onSnapshot={handleSnapshot} />
      <DiagnosticMediaStatus
        readiness={readiness}
        mediaList={processing.mediaList}
        videoUpload={videoUpload}
        snapshotUpload={snapshotUpload}
        fetchStatus={processing.status}
        onRetryFetch={processing.refetch}
      />
    </div>
  );
}
