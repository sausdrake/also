import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { IdentityUploadForm } from './IdentityUploadForm';
import { requestUploadUrl, submitIdentity, uploadToPresignedUrl } from '../../api/identityApi';

/**
 * MVP-004: a KYC submission needs the document image AND a live selfie from SelfieCamera; both
 * get their own upload slot and both keys are submitted.
 */

vi.mock('../../api/identityApi', () => ({
  requestUploadUrl: vi.fn(),
  uploadToPresignedUrl: vi.fn(),
  submitIdentity: vi.fn(),
}));

const selfieJpeg = new Blob(['selfie'], { type: 'image/jpeg' });

// SelfieCamera has its own tests; here only its contract (onCapture(blob)) matters.
vi.mock('./SelfieCamera', () => ({
  SelfieCamera: ({ onCapture }: { onCapture: (b: Blob | null) => void }) => (
    <button type="button" onClick={() => onCapture(selfieJpeg)}>mock-take-selfie</button>
  ),
}));

const mockedRequestUploadUrl = requestUploadUrl as unknown as ReturnType<typeof vi.fn>;
const mockedUpload = uploadToPresignedUrl as unknown as ReturnType<typeof vi.fn>;
const mockedSubmit = submitIdentity as unknown as ReturnType<typeof vi.fn>;

beforeEach(() => {
  mockedRequestUploadUrl.mockReset();
  mockedUpload.mockReset().mockResolvedValue(undefined);
  mockedSubmit.mockReset().mockResolvedValue({});
  mockedRequestUploadUrl
    .mockResolvedValueOnce({ storageKey: 'identity-documents/doc', uploadUrl: 'https://upload/doc', expiresAt: '' })
    .mockResolvedValueOnce({ storageKey: 'identity-documents/selfie', uploadUrl: 'https://upload/selfie', expiresAt: '' });
});

afterEach(() => {
  vi.restoreAllMocks();
});

function fillDocument() {
  fireEvent.change(screen.getByRole('combobox'), { target: { value: 'NATIONAL_ID' } });
  fireEvent.change(screen.getByPlaceholderText('Номер документа'), { target: { value: 'AN1234567' } });
  const documentFile = new File(['doc'], 'passport.png', { type: 'image/png' });
  fireEvent.change(screen.getByLabelText('Файл документа'), { target: { files: [documentFile] } });
  return documentFile;
}

describe('IdentityUploadForm (MVP-004 document + live selfie)', () => {
  it('cannot be submitted without a live selfie', () => {
    render(<IdentityUploadForm onSubmitted={vi.fn()} />);
    fillDocument();
    expect(screen.getByText('Отправить на проверку')).toBeDisabled();
  });

  it('the only file input is the document - the selfie has no file picker', () => {
    const { container } = render(<IdentityUploadForm onSubmitted={vi.fn()} />);
    expect(container.querySelectorAll('input[type="file"]')).toHaveLength(1);
  });

  it('uploads the document and the selfie to separate slots and submits both keys', async () => {
    const onSubmitted = vi.fn();
    render(<IdentityUploadForm onSubmitted={onSubmitted} />);
    const documentFile = fillDocument();
    fireEvent.click(screen.getByText('mock-take-selfie'));

    const submit = screen.getByText('Отправить на проверку');
    expect(submit).not.toBeDisabled();
    fireEvent.click(submit);

    await waitFor(() => expect(mockedSubmit).toHaveBeenCalledTimes(1));
    expect(mockedRequestUploadUrl).toHaveBeenNthCalledWith(1, 'image/png');
    expect(mockedRequestUploadUrl).toHaveBeenNthCalledWith(2, 'image/jpeg');
    expect(mockedUpload).toHaveBeenNthCalledWith(1, 'https://upload/doc', documentFile, 'image/png');
    expect(mockedUpload).toHaveBeenNthCalledWith(2, 'https://upload/selfie', selfieJpeg, 'image/jpeg');
    expect(mockedSubmit).toHaveBeenCalledWith('NATIONAL_ID', 'AN1234567', 'identity-documents/doc', 'identity-documents/selfie');
    expect(onSubmitted).toHaveBeenCalled();
  });
});
