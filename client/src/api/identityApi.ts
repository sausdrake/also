import axios from 'axios';
import api from './client';

export type IdentityStatus = 'PENDING_REVIEW' | 'VERIFIED' | 'REJECTED' | 'REVOKED';
export type DocumentType = 'NATIONAL_ID' | 'INTERNATIONAL_PASSPORT';
export type RevocationReasonCode = 'ADMIN_ERROR' | 'FRAUD_DISCOVERED' | 'DOCUMENT_INVALID' | 'IDENTITY_MISMATCH' | 'OTHER';

/** The only issuing country the backend accepts (IdentityVerificationService.SUPPORTED_ISSUING_COUNTRY) - never invented. */
export const SUPPORTED_ISSUING_COUNTRY = 'KG';

export interface IdentityStatusResponse {
  id: number;
  userId: number;
  status: IdentityStatus;
  issuingCountry: string;
  documentType: DocumentType;
  canonicalDocumentNumber: string;
  createdAt: string;
  updatedAt: string;
  /** MVP-004: false only for submissions made before the live selfie became mandatory. */
  selfieProvided: boolean;
}

export interface IdentityHistoryEventResponse {
  id: number;
  eventType: string;
  canonicalDocumentNumber: string;
  reviewerAdminId: number | null;
  revocationReasonCode: RevocationReasonCode | null;
  reasonText: string | null;
  occurredAt: string;
}

export interface IdentityUploadUrlResponse {
  storageKey: string;
  uploadUrl: string;
  expiresAt: string;
}

// ─────────────────── Self-service (USER) ───────────────────

export async function requestUploadUrl(contentType: string): Promise<IdentityUploadUrlResponse> {
  const { data } = await api.post<IdentityUploadUrlResponse>('/identity/documents/upload-url', { contentType });
  return data;
}

/**
 * Uploads directly to the presigned storage URL - deliberately via the raw `axios` import,
 * never the app's `api` instance, mirroring diagnosticMediaApi.ts's uploadToPresignedUrl (the
 * application's own bearer token must never be attached to a request the storage provider's
 * presigned-URL signature already authorizes on its own).
 */
export async function uploadToPresignedUrl(uploadUrl: string, blob: Blob, contentType: string): Promise<void> {
  await axios.put(uploadUrl, blob, { headers: { 'Content-Type': contentType } });
}

export async function submitIdentity(
  documentType: DocumentType,
  rawDocumentNumber: string,
  documentStorageKey: string,
  selfieStorageKey: string,
): Promise<IdentityStatusResponse> {
  const { data } = await api.post<IdentityStatusResponse>('/identity/submissions', {
    issuingCountry: SUPPORTED_ISSUING_COUNTRY,
    documentType,
    rawDocumentNumber,
    documentStorageKey,
    selfieStorageKey,
  });
  return data;
}

export async function getOwnStatus(): Promise<IdentityStatusResponse> {
  const { data } = await api.get<IdentityStatusResponse>('/identity/me');
  return data;
}

export async function getOwnHistory(): Promise<IdentityHistoryEventResponse[]> {
  const { data } = await api.get<IdentityHistoryEventResponse[]>('/identity/me/history');
  return data;
}

export async function getOwnDocumentUrl(): Promise<string> {
  const { data } = await api.get<{ url: string }>('/identity/me/document-url');
  return data.url;
}

// ─────────────────── Admin review ───────────────────

export async function getPending(): Promise<IdentityStatusResponse[]> {
  const { data } = await api.get<IdentityStatusResponse[]>('/admin/identity/pending');
  return data;
}

export async function getById(id: number): Promise<IdentityStatusResponse> {
  const { data } = await api.get<IdentityStatusResponse>(`/admin/identity/${id}`);
  return data;
}

export async function getDocumentUrlAdmin(id: number): Promise<string> {
  const { data } = await api.get<{ url: string }>(`/admin/identity/${id}/document-url`);
  return data.url;
}

/** MVP-004: the live selfie the administrator compares with the document. */
export async function getSelfieUrlAdmin(id: number): Promise<string> {
  const { data } = await api.get<{ url: string }>(`/admin/identity/${id}/selfie-url`);
  return data.url;
}

export async function getHistoryAdmin(id: number): Promise<IdentityHistoryEventResponse[]> {
  const { data } = await api.get<IdentityHistoryEventResponse[]>(`/admin/identity/${id}/history`);
  return data;
}

export async function approve(id: number): Promise<void> {
  await api.post(`/admin/identity/${id}/approve`);
}

export async function reject(id: number, reason: string): Promise<void> {
  await api.post(`/admin/identity/${id}/reject`, { reason });
}

export async function revoke(id: number, reasonCode: RevocationReasonCode, reasonText: string | null): Promise<void> {
  await api.post(`/admin/identity/${id}/revoke`, { reasonCode, reasonText: reasonText || undefined });
}
