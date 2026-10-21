/**
 * BL-24: a list that failed to load must not look like an empty list. Same look as the withdrawal
 * list's load error in ProfilePage.
 */
export function LoadErrorAlert({ message, onRetry }: { message: string; onRetry: () => void }) {
  return (
    <div role="alert" className="rounded-xl border border-red-200 bg-red-50 p-3 text-sm text-red-700 space-y-2">
      <p>{message}</p>
      <button onClick={onRetry} className="px-3 py-1.5 bg-white border border-red-300 rounded-lg text-xs font-medium">
        Повторить
      </button>
    </div>
  );
}
