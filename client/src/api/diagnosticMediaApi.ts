import axios from 'axios';
import api from './client';

export type MediaType = 'PHOTO' | 'VIDEO';
export type UploadStatus = 'PENDING_UPLOAD' | 'READY' | 'FAILED' | 'EXPIRED' | 'DELETED';
export type ProcessingStatus = 'NOT_REQUIRED' | 'PENDING' | 'PROCESSING' | 'READY' | 'FAILED';

export interface MediaIntentResponse {
  mediaId: number;
  uploadUrl: string;
  uploadUrlExpiresAt: string;
  mediaType: MediaType;
  uploadStatus: UploadStatus;
}

export interface MediaResponse {
  id: number;
  mediaType: MediaType;
  mimeType: string | null;
  sizeBytes: number | null;
  sortOrder: number;
  caption: string | null;
  uploadStatus: UploadStatus;
  processingStatus: ProcessingStatus;
  durationSeconds: number | null;
  width: number | null;
  height: number | null;
  thumbnailAvailable: boolean;
}

export interface MediaReadUrlResponse {
  url: string;
  expiresAt: string;
}

/**
 * Strips any codec parameter (e.g. "video/webm;codecs=vp9,opus" -> "video/webm").
 * The backend allow-list (DiagnosticMediaService.VIDEO_MIME_TYPES) only ever
 * compares the bare container MIME type - this is a pure string transform,
 * never a change to what the backend accepts.
 */
export function normalizeMimeForBackend(mime: string): string {
  return mime.split(';')[0].trim();
}

export async function createIntent(
  requestId: number,
  itemId: number,
  mediaType: MediaType,
  mimeType: string,
  sizeBytes: number,
): Promise<MediaIntentResponse> {
  const { data } = await api.post<MediaIntentResponse>(
    `/diagnostic/work/${requestId}/items/${itemId}/media/intents`,
    { mediaType, mimeType: normalizeMimeForBackend(mimeType), sizeBytes },
  );
  return data;
}

/**
 * Uploads directly to the presigned storage URL - deliberately via the raw
 * `axios` import, never the app's `api` instance, so the application's own
 * bearer token is never attached to a request the storage provider's
 * presigned-URL signature already authorizes on its own.
 */
export async function uploadToPresignedUrl(
  uploadUrl: string,
  blob: Blob,
  mimeType: string,
  onUploadProgress?: (progress: { loaded: number; total?: number }) => void,
): Promise<void> {
  await axios.put(uploadUrl, blob, {
    headers: { 'Content-Type': normalizeMimeForBackend(mimeType) },
    onUploadProgress: onUploadProgress
      ? (event) => onUploadProgress({ loaded: event.loaded, total: event.total })
      : undefined,
  });
}

export async function finalize(mediaId: number): Promise<MediaResponse> {
  const { data } = await api.post<MediaResponse>(`/diagnostic/media/${mediaId}/finalize`);
  return data;
}

export async function listMedia(requestId: number, itemId: number): Promise<MediaResponse[]> {
  const { data } = await api.get<MediaResponse[]>(`/diagnostic/work/${requestId}/items/${itemId}/media`);
  return data;
}

export async function getReadUrl(mediaId: number): Promise<MediaReadUrlResponse> {
  const { data } = await api.get<MediaReadUrlResponse>(`/diagnostic/media/${mediaId}/url`);
  return data;
}
