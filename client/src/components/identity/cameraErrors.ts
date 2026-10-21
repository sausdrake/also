/**
 * CAMERA-01: why the KYC selfie camera could not start, told apart so the user gets the real reason.
 * Browsers hide navigator.mediaDevices on a page that is not a secure context (plain http:// on a LAN
 * address, which is how a phone opens the dev stand) - that is not "this browser has no camera".
 */
export type CameraErrorKind = 'INSECURE_CONTEXT' | 'UNSUPPORTED' | 'PERMISSION_DENIED' | 'NO_CAMERA' | 'CAMERA_BUSY' | 'UNKNOWN';

export const CAMERA_ERROR_MESSAGES: Record<CameraErrorKind, string> = {
  INSECURE_CONTEXT: 'Камера работает только на защищённой странице (адрес начинается с https://). '
    + 'Откройте AlSo по защищённому адресу и включите камеру снова.',
  UNSUPPORTED: 'Этот браузер не даёт доступа к камере. Откройте страницу в актуальной версии Chrome, Edge или Safari.',
  PERMISSION_DENIED: 'Доступ к камере запрещён. Разрешите доступ к камере в настройках браузера и попробуйте снова.',
  NO_CAMERA: 'Камера не найдена. Подключите камеру или откройте страницу на телефоне.',
  CAMERA_BUSY: 'Камера занята другим приложением. Закройте его и попробуйте снова.',
  UNKNOWN: 'Не удалось включить камеру. Попробуйте ещё раз.',
};

/** Before calling getUserMedia: null when the camera API is available on this page. */
export function cameraUnavailableKind(win: { isSecureContext?: boolean; navigator: Navigator }): CameraErrorKind | null {
  if (win.isSecureContext === false) return 'INSECURE_CONTEXT';
  if (!win.navigator.mediaDevices?.getUserMedia) return 'UNSUPPORTED';
  return null;
}

/** A getUserMedia rejection, by its DOMException name (current and legacy Chrome names). */
export function cameraErrorKind(error: unknown): CameraErrorKind {
  const name = error instanceof Error || (typeof error === 'object' && error !== null && 'name' in error)
    ? String((error as { name: unknown }).name) : '';
  switch (name) {
    case 'NotAllowedError':
    case 'PermissionDeniedError':
    case 'SecurityError':
      return 'PERMISSION_DENIED';
    case 'NotFoundError':
    case 'DevicesNotFoundError':
    case 'OverconstrainedError':
      return 'NO_CAMERA';
    case 'NotReadableError':
    case 'TrackStartError':
    case 'AbortError':
      return 'CAMERA_BUSY';
    default:
      return 'UNKNOWN';
  }
}
