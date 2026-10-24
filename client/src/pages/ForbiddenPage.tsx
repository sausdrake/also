import { useNavigate } from 'react-router-dom';
import { useBackOr } from '../navigation/useBackOr';

export const ForbiddenPage = () => {
  const navigate = useNavigate();
  // G1: opened directly (first entry of the tab) -> "Назад" goes home instead of leaving the app
  const back = useBackOr('/');
  return (
    <div className="min-h-screen flex items-center justify-center bg-gray-50">
      <div className="text-center">
        <div className="text-6xl mb-4">🚫</div>
        <h1 className="text-3xl font-bold text-gray-900 mb-2">Доступ запрещён</h1>
        <p className="text-gray-500 mb-6">У вас недостаточно прав для просмотра этой страницы.</p>
        <button
          onClick={back}
          className="mr-3 px-5 py-2 border border-gray-300 rounded-lg text-gray-700 hover:bg-gray-100"
        >
          Назад
        </button>
        <button
          onClick={() => navigate('/')}
          className="px-5 py-2 bg-blue-600 text-white rounded-lg hover:bg-blue-700"
        >
          На главную
        </button>
      </div>
    </div>
  );
};
