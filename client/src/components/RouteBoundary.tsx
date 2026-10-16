import { Component, Suspense, type ReactNode } from 'react';

/**
 * FE-04: the boundary around lazily loaded route chunks.
 * - while a page's chunk loads, a small loading indicator is shown (the header stays);
 * - if the chunk cannot be loaded (network error, or a deploy replaced the files the open tab
 *   still refers to), the user gets a clear message with "reload" instead of a blank page.
 * The parent keys this boundary by the path, so navigating elsewhere clears an earlier failure.
 */
export function PageLoader() {
  return (
    <div role="status" aria-live="polite" className="flex justify-center py-16 text-gray-500 text-sm">
      Загрузка…
    </div>
  );
}

interface State { failed: boolean }

export class RouteErrorBoundary extends Component<{ children: ReactNode }, State> {
  state: State = { failed: false };

  static getDerivedStateFromError(): State {
    return { failed: true };
  }

  componentDidCatch(error: unknown) {
    console.error('Route failed to load', error);
  }

  render() {
    if (!this.state.failed) {
      return this.props.children;
    }
    return (
      <div role="alert" className="max-w-md mx-auto my-16 p-6 bg-white border rounded-xl shadow-sm text-center space-y-4">
        <h2 className="text-lg font-semibold text-gray-900">Не удалось загрузить страницу</h2>
        <p className="text-sm text-gray-600">
          Проверьте подключение к интернету. Если сайт только что обновился, обновите страницу.
        </p>
        <div className="flex gap-2 justify-center">
          <button type="button" onClick={() => window.location.reload()}
            className="px-4 py-2 bg-blue-600 text-white rounded-lg text-sm hover:bg-blue-700">
            Обновить
          </button>
          <a href="/" className="px-4 py-2 border border-gray-200 rounded-lg text-sm hover:bg-gray-50">На главную</a>
        </div>
      </div>
    );
  }
}

export function RouteBoundary({ children }: { children: ReactNode }) {
  return (
    <RouteErrorBoundary>
      <Suspense fallback={<PageLoader />}>{children}</Suspense>
    </RouteErrorBoundary>
  );
}
