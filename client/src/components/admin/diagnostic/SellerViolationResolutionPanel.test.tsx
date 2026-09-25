import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { SellerViolationResolutionPanel, type ViolationResolution } from './SellerViolationResolutionPanel';
import api from '../../../api/client';

/**
 * Phase 92: dedicated regression coverage for SellerViolationResolutionPanel - the one
 * financial-mutation frontend component this session had not yet tested. The panel itself has
 * no internal race to protect against (the only button wired to the POST is unconditionally
 * disabled while a request is in flight on every reachable render path), so this suite does not
 * include a generation/race test - see Phase 92 PLAN/VERIFY for that analysis.
 */

vi.mock('../../../api/client', () => ({
  default: { get: vi.fn(), post: vi.fn() },
}));

function deferred<T>() {
  let resolve!: (value: T) => void;
  let reject!: (reason?: unknown) => void;
  const promise = new Promise<T>((res, rej) => {
    resolve = res;
    reject = rej;
  });
  return { promise, resolve, reject };
}

const apiGet = api.get as unknown as ReturnType<typeof vi.fn>;
const apiPost = api.post as unknown as ReturnType<typeof vi.fn>;

const violationDefinitions = [
  { id: 1, code: 'MISSING_VIN_PHOTO', description: 'Отсутствует фото VIN' },
  { id: 2, code: 'FALSE_MILEAGE', description: 'Недостоверный пробег' },
];

function violationResolutionFixture(): ViolationResolution {
  return {
    sellerDiagnosticCommitmentId: 7,
    violationDefinitionIds: [2],
    rawPenaltyAmount: 5000,
    appliedPenaltyAmount: 3000,
    refundAmount: 1000,
    resolvedAt: '2026-01-15T10:00:00Z',
  };
}

beforeEach(() => {
  apiGet.mockReset();
  apiPost.mockReset();
});

afterEach(() => {
  vi.restoreAllMocks();
});

describe('SellerViolationResolutionPanel', () => {
  describe('render branches', () => {
    it('renders nothing when commitmentStatus is not AWAITING_FINALIZATION', () => {
      const { container } = render(
        <SellerViolationResolutionPanel
          requestId={42}
          commitmentStatus="FROZEN"
          violationResolution={null}
          onDone={vi.fn()}
        />
      );

      expect(container).toBeEmptyDOMElement();
      expect(apiGet).not.toHaveBeenCalled();
    });

    it('renders nothing when commitmentStatus is null', () => {
      const { container } = render(
        <SellerViolationResolutionPanel
          requestId={42}
          commitmentStatus={null}
          violationResolution={null}
          onDone={vi.fn()}
        />
      );

      expect(container).toBeEmptyDOMElement();
      expect(apiGet).not.toHaveBeenCalled();
    });

    it('renders the read-only financial summary when violationResolution is present, without fetching the catalogue or showing the form', () => {
      render(
        <SellerViolationResolutionPanel
          requestId={42}
          commitmentStatus="AWAITING_FINALIZATION"
          violationResolution={violationResolutionFixture()}
          onDone={vi.fn()}
        />
      );

      expect(screen.getByText(/5000/)).toBeInTheDocument();
      expect(screen.getByText(/3000/)).toBeInTheDocument();
      expect(screen.getByText(/1000/)).toBeInTheDocument();
      expect(screen.queryByRole('checkbox')).not.toBeInTheDocument();
      expect(screen.queryByText('Разрешить нарушение')).not.toBeInTheDocument();
      expect(apiGet).not.toHaveBeenCalled();
    });

    it('fetches the catalogue and renders violation options when eligible and unresolved', async () => {
      apiGet.mockResolvedValue({ data: violationDefinitions });

      render(
        <SellerViolationResolutionPanel
          requestId={42}
          commitmentStatus="AWAITING_FINALIZATION"
          violationResolution={null}
          onDone={vi.fn()}
        />
      );

      expect(apiGet).toHaveBeenCalledWith('/admin/policy/violation-definitions');
      expect(await screen.findByRole('checkbox', { name: 'MISSING_VIN_PHOTO' })).toBeInTheDocument();
      expect(screen.getByRole('checkbox', { name: 'FALSE_MILEAGE' })).toBeInTheDocument();
    });
  });

  describe('selection and validation', () => {
    it('leaves the trigger disabled with zero selections and enables it once a violation is selected', async () => {
      apiGet.mockResolvedValue({ data: violationDefinitions });

      render(
        <SellerViolationResolutionPanel
          requestId={42}
          commitmentStatus="AWAITING_FINALIZATION"
          violationResolution={null}
          onDone={vi.fn()}
        />
      );

      const trigger = await screen.findByRole('button', { name: 'Разрешить нарушение' });
      expect(trigger).toBeDisabled();

      fireEvent.click(screen.getByRole('checkbox', { name: 'FALSE_MILEAGE' }));

      expect(trigger).toBeEnabled();
    });
  });

  describe('confirm/cancel flow', () => {
    it('enters confirmation state on trigger click and returns to the trigger on cancel, without submitting', async () => {
      apiGet.mockResolvedValue({ data: violationDefinitions });

      render(
        <SellerViolationResolutionPanel
          requestId={42}
          commitmentStatus="AWAITING_FINALIZATION"
          violationResolution={null}
          onDone={vi.fn()}
        />
      );

      fireEvent.click(await screen.findByRole('checkbox', { name: 'FALSE_MILEAGE' }));
      fireEvent.click(screen.getByRole('button', { name: 'Разрешить нарушение' }));

      expect(await screen.findByRole('button', { name: 'Подтвердить' })).toBeInTheDocument();
      expect(screen.getByRole('button', { name: 'Отмена' })).toBeInTheDocument();

      fireEvent.click(screen.getByRole('button', { name: 'Отмена' }));

      expect(await screen.findByRole('button', { name: 'Разрешить нарушение' })).toBeInTheDocument();
      expect(apiPost).not.toHaveBeenCalled();
    });
  });

  describe('successful mutation', () => {
    it('submits the exact selected violationDefinitionIds, notifies, and calls onDone exactly once', async () => {
      apiGet.mockResolvedValue({ data: violationDefinitions });
      apiPost.mockResolvedValue({ data: {} });
      const onDone = vi.fn();

      render(
        <SellerViolationResolutionPanel
          requestId={42}
          commitmentStatus="AWAITING_FINALIZATION"
          violationResolution={null}
          onDone={onDone}
        />
      );

      fireEvent.click(await screen.findByRole('checkbox', { name: 'FALSE_MILEAGE' }));
      fireEvent.click(screen.getByRole('button', { name: 'Разрешить нарушение' }));
      fireEvent.click(await screen.findByRole('button', { name: 'Подтвердить' }));

      await waitFor(() => expect(apiPost).toHaveBeenCalledTimes(1));
      expect(apiPost).toHaveBeenCalledWith(
        '/admin/diagnostic/reports/42/seller-violation-resolution',
        { violationDefinitionIds: [2] }
      );

      expect(await screen.findByText('Нарушение продавца разрешено')).toBeInTheDocument();
      expect(onDone).toHaveBeenCalledTimes(1);
    });

    it('submits multiple selected violationDefinitionIds in selection order', async () => {
      apiGet.mockResolvedValue({ data: violationDefinitions });
      apiPost.mockResolvedValue({ data: {} });

      render(
        <SellerViolationResolutionPanel
          requestId={99}
          commitmentStatus="AWAITING_FINALIZATION"
          violationResolution={null}
          onDone={vi.fn()}
        />
      );

      fireEvent.click(await screen.findByRole('checkbox', { name: 'MISSING_VIN_PHOTO' }));
      fireEvent.click(screen.getByRole('checkbox', { name: 'FALSE_MILEAGE' }));
      fireEvent.click(screen.getByRole('button', { name: 'Разрешить нарушение' }));
      fireEvent.click(await screen.findByRole('button', { name: 'Подтвердить' }));

      await waitFor(() => expect(apiPost).toHaveBeenCalledTimes(1));
      expect(apiPost).toHaveBeenCalledWith(
        '/admin/diagnostic/reports/99/seller-violation-resolution',
        { violationDefinitionIds: [1, 2] }
      );
    });
  });

  describe('pending / duplicate-submission protection', () => {
    it('keeps the submit button disabled while the request is pending, even if confirmation is re-entered, and issues exactly one POST', async () => {
      apiGet.mockResolvedValue({ data: violationDefinitions });
      const post = deferred<{ data: unknown }>();
      apiPost.mockReturnValue(post.promise);
      const onDone = vi.fn();

      render(
        <SellerViolationResolutionPanel
          requestId={42}
          commitmentStatus="AWAITING_FINALIZATION"
          violationResolution={null}
          onDone={onDone}
        />
      );

      fireEvent.click(await screen.findByRole('checkbox', { name: 'FALSE_MILEAGE' }));
      fireEvent.click(screen.getByRole('button', { name: 'Разрешить нарушение' }));
      fireEvent.click(await screen.findByRole('button', { name: 'Подтвердить' }));

      await waitFor(() => expect(apiPost).toHaveBeenCalledTimes(1));

      // submit() clears `confirming` immediately, so the trigger button re-appears while the
      // request is still pending. The actual protection is that re-entering confirmation shows
      // the submit button already disabled by `submitting` - it can never be clicked while a
      // request is in flight.
      fireEvent.click(screen.getByRole('button', { name: 'Разрешить нарушение' }));
      const pendingSubmitButton = await screen.findByRole('button', { name: '...' });
      expect(pendingSubmitButton).toBeDisabled();

      fireEvent.click(pendingSubmitButton);
      expect(apiPost).toHaveBeenCalledTimes(1);

      post.resolve({ data: {} });
      await waitFor(() => expect(onDone).toHaveBeenCalledTimes(1));
      expect(apiPost).toHaveBeenCalledTimes(1);
    });
  });

  describe('backend error handling', () => {
    it('shows the conflict-specific message on 409 and still calls onDone exactly once', async () => {
      apiGet.mockResolvedValue({ data: violationDefinitions });
      apiPost.mockRejectedValue({ response: { status: 409 } });
      const onDone = vi.fn();

      render(
        <SellerViolationResolutionPanel
          requestId={42}
          commitmentStatus="AWAITING_FINALIZATION"
          violationResolution={null}
          onDone={onDone}
        />
      );

      fireEvent.click(await screen.findByRole('checkbox', { name: 'FALSE_MILEAGE' }));
      fireEvent.click(screen.getByRole('button', { name: 'Разрешить нарушение' }));
      fireEvent.click(await screen.findByRole('button', { name: 'Подтвердить' }));

      expect(await screen.findByText('Уже разрешено (другим администратором или ранее) — статус обновлён'))
        .toBeInTheDocument();
      expect(onDone).toHaveBeenCalledTimes(1);
      expect(apiPost).toHaveBeenCalledTimes(1);
    });

    it('shows the server-provided message on a non-409 error and still calls onDone exactly once', async () => {
      apiGet.mockResolvedValue({ data: violationDefinitions });
      apiPost.mockRejectedValue({ response: { status: 500, data: { message: 'Внутренняя ошибка сервера' } } });
      const onDone = vi.fn();

      render(
        <SellerViolationResolutionPanel
          requestId={42}
          commitmentStatus="AWAITING_FINALIZATION"
          violationResolution={null}
          onDone={onDone}
        />
      );

      fireEvent.click(await screen.findByRole('checkbox', { name: 'FALSE_MILEAGE' }));
      fireEvent.click(screen.getByRole('button', { name: 'Разрешить нарушение' }));
      fireEvent.click(await screen.findByRole('button', { name: 'Подтвердить' }));

      expect(await screen.findByText('Внутренняя ошибка сервера')).toBeInTheDocument();
      expect(onDone).toHaveBeenCalledTimes(1);
      expect(apiPost).toHaveBeenCalledTimes(1);
    });
  });
});
