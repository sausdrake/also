import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import {
  createPrivacyRequest, LEGAL_NAMES, LEGAL_SLUGS, myAcceptances, myPrivacyRequests,
  PRIVACY_REQUEST_STATUSES, PRIVACY_REQUEST_TYPES, type Acceptance, type PrivacyRequest, type PrivacyRequestType,
} from '../../api/legal';
import { formatDateTime } from '../../utils/formatDate';

const KIND_TEXT: Record<string, string> = {
  ACCEPTANCE: 'принято',
  ACKNOWLEDGEMENT: 'ознакомлен(а)',
  CONSENT: 'согласие',
};

/**
 * LEGAL-DATA-01 / PRIVACY-REQ-01: which document versions the user accepted, and requests about their personal
 * data. Requests are answered by an administrator by hand - the page says so and promises no deadline.
 */
export function PrivacyTab() {
  const [acceptances, setAcceptances] = useState<Acceptance[] | null>(null);
  const [requests, setRequests] = useState<PrivacyRequest[] | null>(null);
  const [type, setType] = useState<PrivacyRequestType | ''>('');
  const [message, setMessage] = useState('');
  const [sending, setSending] = useState(false);
  const [notice, setNotice] = useState<{ text: string; ok: boolean } | null>(null);

  const loadRequests = () => myPrivacyRequests().then(setRequests).catch(() => setRequests([]));

  useEffect(() => {
    myAcceptances().then(setAcceptances).catch(() => setAcceptances([]));
    loadRequests();
  }, []);

  const submit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!type || !message.trim()) return;
    setSending(true);
    setNotice(null);
    try {
      const r = await createPrivacyRequest(type, message.trim());
      setNotice({ text: `Обращение #${r.id} отправлено`, ok: true });
      setType('');
      setMessage('');
      loadRequests();
    } catch (err: any) {
      setNotice({ text: err?.response?.data?.message || 'Не удалось отправить обращение', ok: false });
    } finally {
      setSending(false);
    }
  };

  return (
    <div className="space-y-6">
      <section className="bg-white border border-gray-100 rounded-2xl p-5">
        <h2 className="font-semibold text-gray-900">Принятые документы</h2>
        {acceptances === null ? <p className="text-sm text-gray-400 mt-2">Загрузка…</p>
          : acceptances.length === 0 ? <p className="text-sm text-gray-500 mt-2">Записей нет.</p>
          : (
            <ul className="mt-3 divide-y divide-gray-100 text-sm">
              {acceptances.map(a => (
                <li key={a.id} className="py-2 flex flex-wrap justify-between gap-2">
                  <Link to={`/legal/${LEGAL_SLUGS[a.documentType]}`} className="text-blue-600 hover:underline">
                    {LEGAL_NAMES[a.documentType]}
                  </Link>
                  <span className="text-gray-500">
                    редакция {a.documentVersion} · {KIND_TEXT[a.kind] ?? a.kind}
                    {a.status === 'WITHDRAWN' ? ' · отозвано' : ''} · {formatDateTime(a.acceptedAt)}
                  </span>
                </li>
              ))}
            </ul>
          )}
      </section>

      <section className="bg-white border border-gray-100 rounded-2xl p-5 space-y-3">
        <h2 className="font-semibold text-gray-900">Обращение по персональным данным</h2>
        <p className="text-sm text-gray-500">
          Запрос рассматривает администратор вручную; ответ появится здесь. Отправка обращения сама по себе
          ничего не удаляет и не меняет.
        </p>
        <form onSubmit={submit} className="space-y-3">
          <label className="block text-sm text-gray-700">
            Тема
            <select value={type} onChange={e => setType(e.target.value as PrivacyRequestType)}
              className="mt-1 block w-full border border-gray-200 rounded-lg px-3 py-2 text-sm">
              <option value="">Выберите…</option>
              {(Object.keys(PRIVACY_REQUEST_TYPES) as PrivacyRequestType[]).map(t => (
                <option key={t} value={t}>{PRIVACY_REQUEST_TYPES[t]}</option>
              ))}
            </select>
          </label>
          <label className="block text-sm text-gray-700">
            Текст обращения
            <textarea value={message} onChange={e => setMessage(e.target.value)} maxLength={4000} rows={4}
              className="mt-1 block w-full border border-gray-200 rounded-lg px-3 py-2 text-sm" />
          </label>
          {notice && <p className={`text-sm ${notice.ok ? 'text-green-700' : 'text-red-600'}`}>{notice.text}</p>}
          <button type="submit" disabled={!type || !message.trim() || sending}
            className="px-4 py-2 bg-blue-600 text-white rounded-xl text-sm font-medium disabled:opacity-50">
            {sending ? 'Отправка…' : 'Отправить'}
          </button>
        </form>

        {requests && requests.length > 0 && (
          <ul className="mt-4 divide-y divide-gray-100 text-sm">
            {requests.map(r => (
              <li key={r.id} className="py-3 space-y-1">
                <div className="flex flex-wrap justify-between gap-2">
                  <span className="font-medium text-gray-800">#{r.id} · {PRIVACY_REQUEST_TYPES[r.type]}</span>
                  <span className="text-gray-500">{PRIVACY_REQUEST_STATUSES[r.status]} · {formatDateTime(r.createdAt)}</span>
                </div>
                <p className="text-gray-600 whitespace-pre-wrap break-words">{r.message}</p>
                {r.answer && (
                  <p className="text-gray-800 bg-gray-50 rounded-lg p-2 whitespace-pre-wrap break-words">
                    Ответ{r.answeredAt ? ` (${formatDateTime(r.answeredAt)})` : ''}: {r.answer}
                  </p>
                )}
              </li>
            ))}
          </ul>
        )}
      </section>
    </div>
  );
}
