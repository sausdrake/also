import { describe, expect, it } from 'vitest';
import { CAMERA_ERROR_MESSAGES, cameraErrorKind, cameraUnavailableKind } from './cameraErrors';

/** CAMERA-01: classification of the selfie camera failures. */

const nav = (getUserMedia?: unknown) => ({ mediaDevices: getUserMedia ? { getUserMedia } : undefined } as unknown as Navigator);

describe('cameraUnavailableKind', () => {
  it('an insecure page wins over the missing API (that is why the API is missing)', () => {
    expect(cameraUnavailableKind({ isSecureContext: false, navigator: nav() })).toBe('INSECURE_CONTEXT');
    expect(cameraUnavailableKind({ isSecureContext: false, navigator: nav(() => null) })).toBe('INSECURE_CONTEXT');
  });
  it('a secure page without the API is an unsupported browser', () => {
    expect(cameraUnavailableKind({ isSecureContext: true, navigator: nav() })).toBe('UNSUPPORTED');
  });
  it('a secure page with the API is fine; an unknown isSecureContext is not treated as insecure', () => {
    expect(cameraUnavailableKind({ isSecureContext: true, navigator: nav(() => null) })).toBeNull();
    expect(cameraUnavailableKind({ navigator: nav(() => null) })).toBeNull();
  });
});

describe('cameraErrorKind', () => {
  it.each([
    ['NotAllowedError', 'PERMISSION_DENIED'], ['PermissionDeniedError', 'PERMISSION_DENIED'], ['SecurityError', 'PERMISSION_DENIED'],
    ['NotFoundError', 'NO_CAMERA'], ['DevicesNotFoundError', 'NO_CAMERA'], ['OverconstrainedError', 'NO_CAMERA'],
    ['NotReadableError', 'CAMERA_BUSY'], ['TrackStartError', 'CAMERA_BUSY'], ['AbortError', 'CAMERA_BUSY'],
    ['TypeError', 'UNKNOWN'], ['Whatever', 'UNKNOWN'],
  ])('%s -> %s', (name, kind) => {
    expect(cameraErrorKind(new DOMException('x', name))).toBe(kind);
  });
  it('non-error values are UNKNOWN', () => {
    expect(cameraErrorKind(undefined)).toBe('UNKNOWN');
    expect(cameraErrorKind('boom')).toBe('UNKNOWN');
    expect(cameraErrorKind({ name: 'NotAllowedError' })).toBe('PERMISSION_DENIED');
  });
  it('every kind has a message; the insecure one never claims the browser has no camera', () => {
    for (const m of Object.values(CAMERA_ERROR_MESSAGES)) expect(m.length).toBeGreaterThan(20);
    expect(CAMERA_ERROR_MESSAGES.INSECURE_CONTEXT).toMatch(/https:\/\//);
    expect(CAMERA_ERROR_MESSAGES.INSECURE_CONTEXT).not.toMatch(/в этом браузере/);
  });
});
