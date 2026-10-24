import { useEffect, useState } from 'react';
import api from '../../api/client';
import {
  LEGAL_NAMES, PRIVACY_REQUEST_STATUSES, PRIVACY_REQUEST_TYPES,
  type AcceptanceKind, type LegalDocumentType, type PrivacyRequest,
} from '../../api/legal';
import { formatDateTime } from '../../utils/formatDate';

interface AdminDocument {
  id: number;
  documentType: LegalDocumentType;
  version: string;
  title: string;
  acceptanceKind: AcceptanceKind;
  status: 'DRAFT' | 'PENDING_LEGAL_REVIEW' | 'APPROVED' | 'ACTIVE' | 'RETIRED';
  content: string;
  contentSha256: string;
  createdBy: number;
  createdAt: string;
  approvedBy: number | null;
  approvedAt: string | null;
  approvalNote: string | null;
  effectiveAt: string | null;
}

const STATUS_TEXT: Record<AdminDocument['status'], string> = {
  DRAFT: 'Черновик',
  PENDING_LEGAL_REVIEW: 'На юридической проверке',
  APPROVED: 'Утверждён, не действует',
  ACTIVE: 'Действует',
  RETIRED: 'Снят с действия',
};

const KIND_TEXT: Record<AcceptanceKind, string> = {
  ACCEPTANCE: 'Принятие (договор)',
  ACKNOWLEDGEMENT: 'Ознакомление (уведомление)',
  CONSENT: 'Согласие (можно отозвать)',
  INFORMATIONAL: 'Только для чтения',
};

/**
 * LEGAL-DATA-01: versioned legal documents. A text is edited only as a draft, frozen when sent to legal review,
 * approved by another administrator with a note of who reviewed it, then put in force (the previous edition is
 * retired). PRIVACY-REQ-01: the queue of users' requests about their data.
 */
export function AdminLegalPage() {
  const [docs, setDocs] = useState<AdminDocument[]>([]);
  const [requests, setRequests] = useState<PrivacyRequest[]>([]);
  const [msg, setMsg] = useState<{ text: string; ok: boolean } | null>(null);
  const [form, setForm] = useState({ documentType: 'TERMS_OF_USE' as LegalDocumentType, version: '', title: '',
    acceptanceKind: 'ACCEPTANCE' as AcceptanceKind, content: '' });
  const [openId, setOpenId] = useState<number | null>(null);
  const [answers, setAnswers] = useState<Record<number, string>>({});

  const load = () => {
    api.get<AdminDocument[]>('/admin/legal/documents').then(r => setDocs(r.data)).catch(() => setDocs([]));
    api.get<PrivacyRequest[]>('/admin/privacy/requests').then(r => setRequests(r.data)).catch(() => setRequests([]));
  };
  useEffect(load, []);

  const act = async (fn: () => Promise<unknown>, ok: string) => {
    setMsg(null);
    try { await fn(); setMsg({ text: ok, ok: true }); load(); }
    catch (e: any) { setMsg({ text: e?.response?.data?.message || 'Ошибка', ok: false }); }
  };

  const create = (e: React.FormEvent) => {
    e.preventDefault();
    act(() => api.post('/admin/legal/documents', form), 'Черновик создан')
      .then(() => setForm(f => ({ ...f, version: '', title: '', content: '' })));
  };

  return (
    <div className="max-w-5xl mx-auto py-8 px-4 space-y-6">
      <div>
        <h2 className="text-xl font-bold text-gray-900">Документы и обращения по данным</h2>
        <p className="text-sm text-gray-500 mt-0.5">
          Пользователи видят только действующую редакцию. Утверждает документ другой администратор после
          юридической проверки; действующий текст не редактируется — изменение оформляется новой редакцией.
        </p>
      </div>

      {msg && <div className={`text-sm rounded-lg px-3 py-2 ${msg.ok ? 'bg-green-50 text-green-700' : 'bg-red-50 text-red-700'}`}>{msg.text}</div>}

      <section className="bg-white border border-gray-100 rounded-2xl p-5 space-y-3">
        <h3 className="font-semibold text-gray-900">Новая редакция</h3>
        <form onSubmit={create} className="grid gap-3 sm:grid-cols-2">
          <label className="text-sm text-gray-700">Документ
            <select value={form.documentType} onChange={e => setForm({ ...form, documentType: e.target.value as LegalDocumentType })}
              className="mt-1 block w-full border border-gray-200 rounded-lg px-3 py-2 text-sm">
              {(Object.keys(LEGAL_NAMES) as LegalDocumentType[]).map(t => <option key={t} value={t}>{LEGAL_NAMES[t]}</option>)}
            </select>
          </label>
          <label className="text-sm text-gray-700">Что означает действие пользователя
            <select value={form.acceptanceKind} onChange={e => setForm({ ...form, acceptanceKind: e.target.value as AcceptanceKind })}
              className="mt-1 block w-full border border-gray-200 rounded-lg px-3 py-2 text-sm">
              {(Object.keys(KIND_TEXT) as AcceptanceKind[]).map(k => <option key={k} value={k}>{KIND_TEXT[k]}</option>)}
            </select>
          </label>
          <label className="text-sm text-gray-700">Редакция (номер)
            <input value={form.version} onChange={e => setForm({ ...form, version: e.target.value })} maxLength={40}
              className="mt-1 block w-full border border-gray-200 rounded-lg px-3 py-2 text-sm" placeholder="1.0" />
          </label>
          <label className="text-sm text-gray-700">Название
            <input value={form.title} onChange={e => setForm({ ...form, title: e.target.value })} maxLength={200}
              className="mt-1 block w-full border border-gray-200 rounded-lg px-3 py-2 text-sm" />
          </label>
          <label className="text-sm text-gray-700 sm:col-span-2">Текст
            <textarea value={form.content} onChange={e => setForm({ ...form, content: e.target.value })} rows={6}
              className="mt-1 block w-full border border-gray-200 rounded-lg px-3 py-2 text-sm font-mono" />
          </label>
          <div className="sm:col-span-2">
            <button type="submit" disabled={!form.version.trim() || !form.title.trim() || !form.content.trim()}
              className="px-4 py-2 bg-blue-600 text-white rounded-xl text-sm font-medium disabled:opacity-50">Создать черновик</button>
          </div>
        </form>
      </section>

      <section className="bg-white border border-gray-100 rounded-2xl overflow-hidden">
        <h3 className="font-semibold text-gray-900 px-5 pt-4">Редакции</h3>
        {docs.length === 0 ? <p className="text-sm text-gray-400 p-5">Документов пока нет.</p> : (
          <ul className="divide-y divide-gray-100 mt-2">
            {docs.map(d => (
              <li key={d.id} className="px-5 py-3 text-sm space-y-2">
                <div className="flex flex-wrap justify-between gap-2">
                  <span className="font-medium text-gray-800">{LEGAL_NAMES[d.documentType]} · редакция {d.version}</span>
                  <span className="text-xs px-2 py-0.5 rounded-full bg-gray-100 text-gray-700">{STATUS_TEXT[d.status]}</span>
                </div>
                <p className="text-xs text-gray-500">
                  {d.title} · {KIND_TEXT[d.acceptanceKind]} · создан {formatDateTime(d.createdAt)}
                  {d.approvedAt && ` · утверждён ${formatDateTime(d.approvedAt)} (${d.approvalNote})`}
                  {d.effectiveAt && ` · действует с ${formatDateTime(d.effectiveAt)}`}
                </p>
                <div className="flex flex-wrap gap-2">
                  <button onClick={() => setOpenId(openId === d.id ? null : d.id)} className="text-xs px-2.5 py-1.5 rounded-lg bg-gray-100 text-gray-700">
                    {openId === d.id ? 'Скрыть текст' : 'Текст'}
                  </button>
                  {d.status === 'DRAFT' && (
                    <button onClick={() => act(() => api.post(`/admin/legal/documents/${d.id}/submit`), 'Отправлено на юридическую проверку')}
                      className="text-xs px-2.5 py-1.5 rounded-lg bg-amber-50 text-amber-700 border border-amber-200">На юридическую проверку</button>
                  )}
                  {d.status === 'PENDING_LEGAL_REVIEW' && (
                    <button onClick={() => {
                      const note = window.prompt('Кто и когда проверил текст (юрист, владелец)?');
                      if (note && note.trim()) act(() => api.post(`/admin/legal/documents/${d.id}/approve`, { note }), 'Утверждено');
                    }} className="text-xs px-2.5 py-1.5 rounded-lg bg-green-50 text-green-700 border border-green-200">Утвердить</button>
                  )}
                  {d.status === 'APPROVED' && (
                    <button onClick={() => {
                      if (window.confirm('Ввести эту редакцию в действие? Пользователям будет предложено принять её.'))
                        act(() => api.post(`/admin/legal/documents/${d.id}/activate`), 'Редакция введена в действие');
                    }} className="text-xs px-2.5 py-1.5 rounded-lg bg-blue-600 text-white">Ввести в действие</button>
                  )}
                  {(d.status === 'ACTIVE' || d.status === 'APPROVED') && (
                    <button onClick={() => {
                      if (window.confirm('Снять редакцию с действия?')) act(() => api.post(`/admin/legal/documents/${d.id}/retire`), 'Снято с действия');
                    }} className="text-xs px-2.5 py-1.5 rounded-lg bg-red-50 text-red-600">Снять</button>
                  )}
                </div>
                {openId === d.id && (
                  <pre className="whitespace-pre-wrap break-words text-xs bg-gray-50 rounded-lg p-3 max-h-80 overflow-y-auto">{d.content}</pre>
                )}
              </li>
            ))}
          </ul>
        )}
      </section>

      <section className="bg-white border border-gray-100 rounded-2xl overflow-hidden">
        <h3 className="font-semibold text-gray-900 px-5 pt-4">Обращения пользователей</h3>
        {requests.length === 0 ? <p className="text-sm text-gray-400 p-5">Обращений нет.</p> : (
          <ul className="divide-y divide-gray-100 mt-2">
            {requests.map(r => (
              <li key={r.id} className="px-5 py-3 text-sm space-y-2">
                <div className="flex flex-wrap justify-between gap-2">
                  <span className="font-medium text-gray-800">#{r.id} · пользователь #{r.userId} · {PRIVACY_REQUEST_TYPES[r.type]}</span>
                  <span className="text-gray-500">{PRIVACY_REQUEST_STATUSES[r.status]} · {formatDateTime(r.createdAt)}</span>
                </div>
                <p className="text-gray-600 whitespace-pre-wrap break-words">{r.message}</p>
                {r.answer ? (
                  <p className="text-gray-800 bg-gray-50 rounded-lg p-2 whitespace-pre-wrap break-words">Ответ: {r.answer}</p>
                ) : (
                  <div className="space-y-2">
                    {r.status === 'OPEN' && (
                      <button onClick={() => act(() => api.post(`/admin/privacy/requests/${r.id}/take`), 'Взято в работу')}
                        className="text-xs px-2.5 py-1.5 rounded-lg bg-gray-100 text-gray-700">Взять в работу</button>
                    )}
                    <textarea value={answers[r.id] ?? ''} onChange={e => setAnswers(a => ({ ...a, [r.id]: e.target.value }))}
                      rows={2} maxLength={4000} placeholder="Ответ пользователю: что сделано или почему отказано"
                      className="block w-full border border-gray-200 rounded-lg px-3 py-2 text-sm" />
                    <div className="flex gap-2">
                      <button disabled={!answers[r.id]?.trim()}
                        onClick={() => act(() => api.post(`/admin/privacy/requests/${r.id}/answer`, { answer: answers[r.id], rejected: false }), 'Ответ отправлен')}
                        className="text-xs px-2.5 py-1.5 rounded-lg bg-blue-600 text-white disabled:opacity-50">Ответить</button>
                      <button disabled={!answers[r.id]?.trim()}
                        onClick={() => act(() => api.post(`/admin/privacy/requests/${r.id}/answer`, { answer: answers[r.id], rejected: true }), 'Отказ отправлен')}
                        className="text-xs px-2.5 py-1.5 rounded-lg bg-red-50 text-red-600 disabled:opacity-50">Отказать</button>
                    </div>
                  </div>
                )}
              </li>
            ))}
          </ul>
        )}
      </section>
    </div>
  );
}
