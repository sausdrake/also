import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { IdentityUploadForm } from './IdentityUploadForm';
import { getCaptureRequirements, requestUploadUrl, submitIdentity, uploadToPresignedUrl } from '../../api/identityApi';

/**
 * KYC-CAPTURE-01: the form has no file input; the document sides come from the backend requirements and are
 * photographed by DocumentCaptureFlow; every side and the live selfie get their own capture slot.
 */

vi.mock('../../api/identityApi', () => ({
  CAPTURE_CONTENT_TYPE: 'image/jpeg',
  getCaptureRequirements: vi.fn(),
  requestUploadUrl: vi.fn(),
  uploadToPresignedUrl: vi.fn(),
  submitIdentity: vi.fn(),
}));

const shot = (name: string) => new Blob([name], { type: 'image/jpeg' });
const selfieJpeg = shot('selfie');

// DocumentCaptureFlow and SelfieCamera have their own tests; here only their contracts matter.
vi.mock('./DocumentCaptureFlow', () => ({
  DocumentCaptureFlow: ({ sides, onChange }: { sides: string[]; onChange: (c: Record<string, Blob> | null) => void }) => (
    <div>
      <span>sides:{sides.join('+')}</span>
      <button type="button" onClick={() => onChange(Object.fromEntries(sides.map(s => [s, shot(s)])))}>mock-capture-document</button>
    </div>
  ),
}));
vi.mock('./SelfieCamera', () => ({
  SelfieCamera: ({ onCapture }: { onCapture: (b: Blob | null) => void }) => (
    <button type="button" onClick={() => onCapture(selfieJpeg)}>mock-take-selfie</button>
  ),
}));

const mockedRequirements = getCaptureRequirements as unknown as ReturnType<typeof vi.fn>;
const mockedRequestUploadUrl = requestUploadUrl as unknown as ReturnType<typeof vi.fn>;
const mockedUpload = uploadToPresignedUrl as unknown as ReturnType<typeof vi.fn>;
const mockedSubmit = submitIdentity as unknown as ReturnType<typeof vi.fn>;

const REQUIREMENTS = [
  { documentType: 'NATIONAL_ID', sides: ['FRONT', 'BACK'] },
  { documentType: 'INTERNATIONAL_PASSPORT', sides: ['DATA_PAGE'] },
  { documentType: 'DRIVER_LICENSE', sides: ['FRONT', 'BACK'] },
];

beforeEach(() => {
  mockedRequirements.mockReset().mockResolvedValue(REQUIREMENTS);
  mockedUpload.mockReset().mockResolvedValue(undefined);
  mockedSubmit.mockReset().mockResolvedValue({});
  mockedRequestUploadUrl.mockReset().mockImplementation((side: string) =>
    Promise.resolve({ storageKey: `identity-documents/${side}`, uploadUrl: `https://upload/${side}`, expiresAt: '' }));
});

afterEach(() => {
  vi.restoreAllMocks();
});

async function choose(type: string) {
  render(<IdentityUploadForm onSubmitted={vi.fn()} />);
  await waitFor(() => expect(mockedRequirements).toHaveBeenCalled());
  fireEvent.change(screen.getByLabelText('Тип документа'), { target: { value: type } });
  fireEvent.change(screen.getByPlaceholderText('Номер документа'), { target: { value: 'AN1234567' } });
}

describe('IdentityUploadForm (KYC-CAPTURE-01 camera-only document)', () => {
  it('has no file input anywhere and offers the three document types', async () => {
    const { container } = render(<IdentityUploadForm onSubmitted={vi.fn()} />);
    await waitFor(() => expect(mockedRequirements).toHaveBeenCalled());
    expect(container.querySelector('input[type="file"]')).toBeNull();
    const options = Array.from(container.querySelectorAll('option')).map(o => o.getAttribute('value'));
    expect(options).toEqual(['', 'NATIONAL_ID', 'DRIVER_LICENSE', 'INTERNATIONAL_PASSPORT']);
  });

  it.each([
    ['NATIONAL_ID', 'sides:FRONT+BACK'],
    ['DRIVER_LICENSE', 'sides:FRONT+BACK'],
    ['INTERNATIONAL_PASSPORT', 'sides:DATA_PAGE'],
  ])('%s: the capture steps are the backend requirement (%s)', async (type, sides) => {
    await choose(type);
    expect(await screen.findByText(sides)).toBeInTheDocument();
  });

  it('cannot be submitted until the document is captured AND the selfie is taken', async () => {
    await choose('NATIONAL_ID');
    const submit = screen.getByText('Отправить на проверку');
    expect(submit).toBeDisabled();
    fireEvent.click(await screen.findByText('mock-take-selfie'));
    expect(submit).toBeDisabled();
    fireEvent.click(screen.getByText('mock-capture-document'));
    expect(submit).not.toBeDisabled();
  });

  it('ID card: FRONT, BACK and SELFIE get their own slots; the submission carries all three keys', async () => {
    await choose('NATIONAL_ID');
    fireEvent.click(await screen.findByText('mock-capture-document'));
    fireEvent.click(screen.getByText('mock-take-selfie'));
    fireEvent.click(screen.getByText('Отправить на проверку'));

    await waitFor(() => expect(mockedSubmit).toHaveBeenCalledTimes(1));
    expect(mockedRequestUploadUrl.mock.calls).toEqual([['FRONT', 'NATIONAL_ID'], ['BACK', 'NATIONAL_ID'], ['SELFIE', undefined]]);
    expect(mockedUpload).toHaveBeenNthCalledWith(3, 'https://upload/SELFIE', selfieJpeg, 'image/jpeg');
    expect(mockedUpload.mock.calls.every(c => c[2] === 'image/jpeg')).toBe(true);
    expect(mockedSubmit).toHaveBeenCalledWith('NATIONAL_ID', 'AN1234567', 'identity-documents/FRONT', 'identity-documents/BACK', 'identity-documents/SELFIE');
  });

  it('passport: only the data page is uploaded and no back key is sent', async () => {
    await choose('INTERNATIONAL_PASSPORT');
    fireEvent.click(await screen.findByText('mock-capture-document'));
    fireEvent.click(screen.getByText('mock-take-selfie'));
    fireEvent.click(screen.getByText('Отправить на проверку'));

    await waitFor(() => expect(mockedSubmit).toHaveBeenCalledTimes(1));
    expect(mockedRequestUploadUrl.mock.calls).toEqual([['DATA_PAGE', 'INTERNATIONAL_PASSPORT'], ['SELFIE', undefined]]);
    expect(mockedSubmit).toHaveBeenCalledWith('INTERNATIONAL_PASSPORT', 'AN1234567', 'identity-documents/DATA_PAGE', null, 'identity-documents/SELFIE');
  });

  it('changing the document type discards what was captured for the previous one', async () => {
    await choose('NATIONAL_ID');
    fireEvent.click(await screen.findByText('mock-capture-document'));
    fireEvent.click(screen.getByText('mock-take-selfie'));
    fireEvent.change(screen.getByLabelText('Тип документа'), { target: { value: 'INTERNATIONAL_PASSPORT' } });
    expect(screen.getByText('Отправить на проверку')).toBeDisabled();
  });

  it('a server refusal is shown as its message', async () => {
    mockedSubmit.mockRejectedValue(Object.assign(new Error('400'), { isAxiosError: true,
      response: { status: 400, data: { message: 'Срок действия снимка истёк. Сделайте новый снимок: лицевая сторона' } } }));
    await choose('NATIONAL_ID');
    fireEvent.click(await screen.findByText('mock-capture-document'));
    fireEvent.click(screen.getByText('mock-take-selfie'));
    fireEvent.click(screen.getByText('Отправить на проверку'));
    expect(await screen.findByText(/Срок действия снимка истёк/)).toBeInTheDocument();
  });

  it('requirements that fail to load: no capture step, a retry is offered', async () => {
    mockedRequirements.mockReset().mockRejectedValueOnce(new Error('network')).mockResolvedValue(REQUIREMENTS);
    await choose('NATIONAL_ID');
    expect(await screen.findByText(/Не удалось загрузить требования/)).toBeInTheDocument();
    expect(screen.queryByText(/sides:/)).toBeNull();
    fireEvent.click(screen.getByText('Повторить'));
    expect(await screen.findByText('sides:FRONT+BACK')).toBeInTheDocument();
  });
});
