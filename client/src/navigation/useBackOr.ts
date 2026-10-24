import { useNavigate } from 'react-router-dom';

/**
 * G1: "Назад" that stays inside the app. React Router keeps the position in this tab's history as
 * `history.state.idx`; 0 means the page was opened directly (a link, a bookmark, a redirect that
 * replaced the first entry) - going back would leave the app, so the fallback is opened instead.
 */
export function useBackOr(fallback: string): () => void {
  const navigate = useNavigate();
  return () => {
    const idx = (window.history.state as { idx?: unknown } | null)?.idx;
    if (typeof idx === 'number' && idx > 0) navigate(-1);
    else navigate(fallback, { replace: true });
  };
}
