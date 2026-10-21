import { CameraCapture } from './CameraCapture';

/**
 * MVP-004: the live selfie required for KYC is taken ONLY through the device camera inside the
 * platform (CameraCapture: getUserMedia -> canvas -> JPEG). There is deliberately no file input and no
 * fallback to uploading an existing photo: if the camera is unavailable or permission is denied, the
 * user is told so and nothing can be submitted.
 *
 * KYC-CAPTURE-01: the selfie is reported via onCapture(blob) only after "Подтвердить", and onCapture(null)
 * whenever it is discarded ("Переснять"). Remount the component (change its `key`) to reset it.
 */
export function SelfieCamera({ onCapture, disabled = false }: {
  onCapture: (selfie: Blob | null) => void;
  disabled?: boolean;
}) {
  return (
    <div className="space-y-2">
      <p className="text-xs text-gray-500">
        Живое селфи: сделайте снимок камерой прямо сейчас. Загрузить готовое фото нельзя.
      </p>
      <CameraCapture facingMode="user" videoLabel="Камера для селфи" previewAlt="Снятое селфи"
        onConfirm={onCapture} onDiscard={() => onCapture(null)} disabled={disabled} />
    </div>
  );
}
