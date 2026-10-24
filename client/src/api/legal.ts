import api from './client';
import type { Acceptance, LegalDocument, LegalDocumentType, PrivacyRequest, PrivacyRequestType } from '../domain/legal';

export * from '../domain/legal';

/**
 * LEGAL-DATA-01: versioned legal documents and the user's acceptances; PRIVACY-REQ-01: requests about personal
 * data. Only versions in force (ACTIVE) are public - a draft prepared for legal review is never shown as if it
 * applied.
 */

export async function activeDocuments(): Promise<LegalDocument[]> {
  return (await api.get<LegalDocument[]>('/public/legal/documents')).data;
}

/** The version in force, or null when nothing is in force yet (404). */
export async function activeDocument(type: LegalDocumentType): Promise<LegalDocument | null> {
  try {
    return (await api.get<LegalDocument>(`/public/legal/documents/${type}`)).data;
  } catch (e: any) {
    if (e?.response?.status === 404) return null;
    throw e;
  }
}

export async function requiredAcceptances(): Promise<LegalDocument[]> {
  return (await api.get<LegalDocument[]>('/legal/acceptance/required')).data;
}

export async function accept(documentVersionIds: number[]): Promise<Acceptance[]> {
  return (await api.post<Acceptance[]>('/legal/acceptance', { documentVersionIds })).data;
}

export async function myAcceptances(): Promise<Acceptance[]> {
  return (await api.get<Acceptance[]>('/legal/acceptance/my')).data;
}

export async function createPrivacyRequest(type: PrivacyRequestType, message: string): Promise<PrivacyRequest> {
  return (await api.post<PrivacyRequest>('/privacy/requests', { type, message })).data;
}

export async function myPrivacyRequests(): Promise<PrivacyRequest[]> {
  return (await api.get<PrivacyRequest[]>('/privacy/requests/my')).data;
}
