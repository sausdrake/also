import { useState, useEffect, useMemo, useCallback } from 'react';
import { Routes, Route, NavLink, Navigate } from 'react-router-dom';
import React from 'react';
import axios from 'axios';
import api from '../api/client';
import { formatDate } from '../utils/formatDate';
import { useAuthStore } from '../store/authStore';
import { DiagnosticMediaSection, type ItemReadiness } from '../components/diagnostic/DiagnosticMediaSection';
import { TemplateBinding, type TemplateOption } from '../components/diagnostic/TemplateBinding';
import { SidebarLayout } from '../components/layout/SidebarLayout';
import { RoleAreaFooter } from '../components/layout/RoleAreaFooter';
import { NotificationBell } from '../components/notifications/NotificationBell';
import { BrandLogo } from '../components/brand/BrandLogo';
import { apiErrorMessage, parseApiError } from '../api/errors';
import { labelOf } from '../domain/parse';
import type { DiagnosticRequestStatus } from '../domain/diagnosticRequest';
import {
  DIAGNOSTIC_STATUS_LABELS, diagnosticianCanEdit, needsTemplate, parseDiagnosticRequestStatus,
} from '../domain/diagnosticRequest';
import {
  RATING_LABELS, hasExtraPhotos, hasExtraRating, isAnswerFilled, primaryFieldType, savedExtraPhotos, savedExtraRating,
} from '../domain/inspectionItem';

const BASE_URL = import.meta.env.VITE_API_URL || 'http://localhost:8081';

// ─── Types ───────────────────────────────────────────────────────────────────

interface DiagRequest {
  id: number;
  brand: string;
  model: string;
  year: number;
  vin: string;
  status: string;
  createdAt: string;
  ownerName?: string;
  /** BE DiagnosticRequestResponse.rejectedReason (FE-CONTRACT-01: was read as `rejectionComment`, never sent). */
  rejectedReason?: string;
}

interface Item {
  id: number;
  title: string;
  description?: string;
  fieldType: string;
  isRequired: boolean;
  hasRating: boolean;
  hasPhoto: boolean;
  hasVideo: boolean;
}

interface Group {
  id: number;
  name: string;
  sortOrder: number;
  items: Item[];
}

interface Template {
  id: number;
  name: string;
  vehicleType: string;
  groups: Group[];
}

interface Answer {
  itemId: number;
  rating: number | null;
  comment: string;
  photoUrls: string[];
  videoUrls: string[];
  isSkipped: boolean;
}

// FE-CONTRACT-01: labels come from the domain model; there is no DRAFT status in the backend
// (DiagnosticRequestStatus), so the "Черновик" label, filter and count are gone. Only colours here.
const STATUS_COLOR: Record<DiagnosticRequestStatus, string> = {
  PENDING:     'bg-yellow-100 text-yellow-700',
  IN_PROGRESS: 'bg-blue-100 text-blue-700',
  COMPLETED:   'bg-purple-100 text-purple-700',
  APPROVED:    'bg-green-100 text-green-700',
  REJECTED:    'bg-red-100 text-red-700',
};

/** FE-DIAG-01: the report is opened only for an accepted request (IN_PROGRESS); REJECTED goes through "rework" first. */
const canOpenReport = (status: string) => {
  const s = parseDiagnosticRequestStatus(status);
  return s.known && s.value === 'IN_PROGRESS';
};

// ─── PhotoCapture ─────────────────────────────────────────────────────────────

function PhotoCapture({ ans, onChange, hasError }: {
  ans: Answer;
  onChange: (field: keyof Answer, value: any) => void;
  hasError: boolean;
}) {
  const [uploading, setUploading] = useState(false);
  const [uploadError, setUploadError] = useState('');

  const handleCapture = async (e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0];
    if (!file) return;
    setUploading(true);
    setUploadError('');
    try {
      const fd = new FormData();
      fd.append('file', file);
      const token = localStorage.getItem('accessToken');
      const res = await axios.post(`${BASE_URL}/api/media/upload`, fd, {
        headers: token ? { Authorization: `Bearer ${token}` } : {},
      });
      onChange('photoUrls', [...(ans.photoUrls || []), res.data.publicUrl as string]);
    } catch (err: any) {
      setUploadError(err.response?.data?.error || 'Ошибка загрузки фото');
    } finally {
      setUploading(false);
      e.target.value = '';
    }
  };

  return (
    <div className="space-y-2">
      {(ans.photoUrls || []).length > 0 && (
        <div className="flex flex-wrap gap-2">
          {ans.photoUrls.map((url, i) => (
            <div key={i} className="relative w-20 h-20">
              <img src={url} alt="" className="w-full h-full object-cover rounded-lg border" />
              <button type="button"
                onClick={() => onChange('photoUrls', ans.photoUrls.filter((_, j) => j !== i))}
                className="absolute -top-1.5 -right-1.5 bg-red-500 text-white rounded-full w-5 h-5 text-xs flex items-center justify-center">✕</button>
            </div>
          ))}
        </div>
      )}
      <label className={`flex items-center gap-2 cursor-pointer px-3 py-2 rounded-lg border text-sm w-fit transition-colors ${
        hasError && (ans.photoUrls || []).length === 0 ? 'border-red-400 bg-red-50' : 'border-gray-200 hover:bg-gray-50'
      } ${uploading ? 'opacity-50 pointer-events-none' : ''}`}>
        <svg className="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
          <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2}
            d="M3 9a2 2 0 012-2h.93a2 2 0 001.664-.89l.812-1.22A2 2 0 0110.07 4h3.86a2 2 0 011.664.89l.812 1.22A2 2 0 0018.07 7H19a2 2 0 012 2v9a2 2 0 01-2 2H5a2 2 0 01-2-2V9z" />
          <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M15 13a3 3 0 11-6 0 3 3 0 016 0z" />
        </svg>
        <span>{uploading ? 'Загрузка...' : 'Сделать фото'}</span>
        <input type="file" accept="image/*" capture="environment" className="hidden" onChange={handleCapture} disabled={uploading} />
      </label>
      <label className="flex items-center gap-2 cursor-pointer px-3 py-2 rounded-lg border border-gray-200 text-sm w-fit hover:bg-gray-50">
        <svg className="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
          <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2}
            d="M4 16l4.586-4.586a2 2 0 012.828 0L16 16m-2-2l1.586-1.586a2 2 0 012.828 0L20 14m-6-6h.01M6 20h12a2 2 0 002-2V6a2 2 0 00-2-2H6a2 2 0 00-2 2v12a2 2 0 002 2z" />
        </svg>
        <span>Из галереи</span>
        <input type="file" accept="image/*" className="hidden" onChange={handleCapture} disabled={uploading} />
      </label>
      {uploadError && <p className="text-xs text-red-500">{uploadError}</p>}
    </div>
  );
}

// ─── FieldInput ───────────────────────────────────────────────────────────────

function ReadOnlyPhotos({ urls }: { urls: string[] }) {
  if (urls.length === 0) return null;
  return (
    <div className="flex flex-wrap gap-2 mt-1">
      {urls.map((url, i) => (
        <a key={i} href={url} target="_blank" rel="noopener noreferrer">
          <img src={url} alt="" className="w-16 h-16 object-cover rounded border" />
        </a>
      ))}
    </div>
  );
}

function RatingInput({ value, onChange, optional }: {
  value: number | null;
  onChange: (value: number | null) => void;
  optional?: boolean;
}) {
  return (
    <div>
      <p className="text-xs text-gray-500 mb-1">{optional ? 'Оценка (1–5, необязательно)' : 'Оценка (1–5)'}</p>
      <div className="flex flex-wrap gap-1.5">
        {[1, 2, 3, 4, 5].map(star => (
          <button key={star} type="button"
            aria-label={`Оценка ${star}`} aria-pressed={value != null && value >= star}
            onClick={() => onChange(value === star ? null : star)}
            className={`w-11 h-11 rounded-lg text-sm font-semibold transition-colors ${
              value != null && value >= star
                ? 'bg-yellow-400 text-white' : 'bg-gray-100 text-gray-500 hover:bg-yellow-100'
            }`}>{star}</button>
        ))}
        {value ? <span className="self-center text-xs text-gray-400 ml-1">{RATING_LABELS[value]}</span> : null}
      </div>
    </div>
  );
}

/** DIAG-02: the main answer follows the field type; hasRating / hasPhoto add an optional rating / photos. */
function FieldInput({ item, ans, onChange, hasError }: {
  item: Item;
  ans: Answer;
  onChange: (field: keyof Answer, value: any) => void;
  hasError: boolean;
}) {
  return (
    <div className="space-y-3">
      <MainInput item={item} ans={ans} onChange={onChange} hasError={hasError} />
      {hasExtraRating(item) && (
        <RatingInput optional value={ans.rating} onChange={v => onChange('rating', v)} />
      )}
      {hasExtraPhotos(item) && (
        <div>
          <p className="text-xs text-gray-500 mb-1">Фото (необязательно)</p>
          <PhotoCapture ans={ans} onChange={onChange} hasError={false} />
        </div>
      )}
    </div>
  );
}

function MainInput({ item, ans, onChange, hasError }: {
  item: Item;
  ans: Answer;
  onChange: (field: keyof Answer, value: any) => void;
  hasError: boolean;
}) {
  const ft = primaryFieldType(item);
  const options = ft === 'SELECT' && item.description
    ? item.description.split('\n').filter(o => o.trim())
    : [];

  switch (ft) {
    case 'NUMBER':
      return <RatingInput value={ans.rating} onChange={v => onChange('rating', v)} />;
    case 'CHECKBOX':
      return (
        <label className="flex items-center gap-2 cursor-pointer select-none">
          <input type="checkbox"
            checked={ans.comment === 'true'}
            onChange={e => onChange('comment', e.target.checked ? 'true' : 'false')}
            className="w-4 h-4" />
          <span className="text-sm text-gray-700">{item.title}</span>
        </label>
      );
    case 'SELECT':
      return (
        <select value={ans.comment || ''}
          onChange={e => onChange('comment', e.target.value)}
          className={`w-full border rounded-lg px-3 py-2 text-sm focus:ring-2 focus:ring-blue-500 focus:outline-none ${hasError ? 'border-red-400' : 'border-gray-200'}`}>
          <option value="">— Выберите вариант —</option>
          {options.map(opt => <option key={opt} value={opt}>{opt}</option>)}
        </select>
      );
    case 'TEXT':
      return (
        <input type="text" value={ans.comment}
          onChange={e => onChange('comment', e.target.value)}
          placeholder={item.isRequired ? 'Ответ (обязателен)' : 'Ответ'}
          className={`w-full border rounded-lg px-3 py-2 text-sm focus:ring-2 focus:ring-blue-500 focus:outline-none ${hasError ? 'border-red-400' : 'border-gray-200'}`} />
      );
    case 'PHOTO':
      return <PhotoCapture ans={ans} onChange={onChange} hasError={hasError} />;
    default:
      return (
        <textarea value={ans.comment}
          onChange={e => onChange('comment', e.target.value)}
          placeholder={item.isRequired ? 'Комментарий (обязателен)' : 'Комментарий'}
          rows={2}
          className={`w-full border rounded-lg px-3 py-2 text-sm resize-none focus:ring-2 focus:ring-blue-500 focus:outline-none ${hasError ? 'border-red-400' : 'border-gray-200'}`} />
      );
  }
}

// ─── ReportForm ───────────────────────────────────────────────────────────────

function ReportForm({ request, onDone }: { request: DiagRequest; onDone: () => void }) {
  const requestId = request.id;
  const [data, setData] = useState<any>(null);
  const [needsBinding, setNeedsBinding] = useState(false);
  const [answers, setAnswers] = useState<Record<number, Answer>>({});
  const [openGroups, setOpenGroups] = useState<Set<number>>(new Set());
  const [msg, setMsg] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const [validationErrors, setValidationErrors] = useState<Set<number>>(new Set());
  const [missingFields, setMissingFields] = useState<string[]>([]);
  const [templates, setTemplates] = useState<TemplateOption[]>([]);
  const [templatesError, setTemplatesError] = useState('');
  const [itemReadiness, setItemReadiness] = useState<Record<number, ItemReadiness>>({});

  // Frontend readiness is advisory only - the backend completion gate remains
  // authoritative (submit() below still relies on the real POST/409 path).
  const handleReadinessChange = useCallback((itemId: number, readiness: ItemReadiness) => {
    setItemReadiness(prev => (prev[itemId] === readiness ? prev : { ...prev, [itemId]: readiness }));
  }, []);

  // FE-DIAG-01: the form is always the request's bound template (GET /diagnostic/work/{id}); the
  // backend ignores any ?templateId and refuses (409) an accepted request with no template bound yet.
  const loadForm = () => {
    setMsg('');
    api.get(`/diagnostic/work/${requestId}`).then(r => {
      setNeedsBinding(false);
      setData(r.data);
      const groups: Group[] = r.data?.template?.groups ?? [];
      setOpenGroups(new Set(groups.map((g: Group) => g.id)));
      const initial: Record<number, Answer> = {};
      (r.data.existingAnswers || []).forEach((a: any) => {
        initial[a.inspectionItemId] = {
          itemId: a.inspectionItemId,
          rating: a.rating,
          comment: a.comment || '',
          photoUrls: a.photoUrls || [],
          videoUrls: a.videoUrls || [],
          isSkipped: !!a.skipped,
        };
      });
      setAnswers(initial);
    }).catch(e => {
      const err = parseApiError(e, 'Не удалось загрузить заявку');
      // The response carries no templateId: for a request this diagnostician holds IN_PROGRESS,
      // a 409 here is getRequestWithTemplate's "no template bound yet" refusal.
      const status = parseDiagnosticRequestStatus(request.status);
      if (err.kind === 'conflict' && needsTemplate({ status, templateId: null })) {
        setNeedsBinding(true);
        return;
      }
      setNeedsBinding(false);
      setMsg(err.message);
    });
  };

  useEffect(() => {
    loadForm();
    api.get('/diagnostic/work/templates')
      .then(r => setTemplates(Array.isArray(r.data) ? r.data : []))
      .catch(e => setTemplatesError(apiErrorMessage(e, 'Не удалось загрузить шаблоны анкеты')));
  }, [requestId]);

  const setAnswer = (itemId: number, field: keyof Answer, value: any) => {
    setAnswers(prev => ({
      ...prev,
      [itemId]: {
        ...(prev[itemId] || { itemId, rating: null, comment: '', photoUrls: [], videoUrls: [], isSkipped: false }),
        [field]: value,
      },
    }));
    setValidationErrors(prev => { const s = new Set(prev); s.delete(itemId); return s; });
  };

  const toggleGroup = (id: number) => setOpenGroups(prev => {
    const s = new Set(prev);
    s.has(id) ? s.delete(id) : s.add(id);
    return s;
  });

  const validateForSubmit = (): boolean => {
    const template: Template = data?.template;
    if (!template) return true;
    const errors = new Set<number>();
    for (const group of template.groups) {
      for (const item of group.items) {
        if (item.isRequired && !isAnswerFilled(item, answers[item.id])) errors.add(item.id);
      }
    }
    setValidationErrors(errors);
    if (errors.size > 0) {
      const errGroups = new Set<number>();
      const missing: string[] = [];
      for (const group of template.groups) {
        if (group.items.some(i => errors.has(i.id))) errGroups.add(group.id);
        group.items.filter(i => errors.has(i.id)).forEach(i => missing.push(`${group.name}: ${i.title}`));
      }
      setOpenGroups(prev => new Set([...prev, ...errGroups]));
      setMissingFields(missing);
    }
    return errors.size === 0;
  };

  const submit = async (isSubmit: boolean) => {
    if (isSubmit && !validateForSubmit()) { setMsg(''); return; }
    setMissingFields([]);
    setSubmitting(true);
    try {
      // The server field is `skipped` (scenario 3 step 4); it clears a skipped item's content.
      const payload = Object.values(answers).map(({ isSkipped, ...a }) => ({ ...a, skipped: isSkipped }));
      await api.post(`/diagnostic/work/${requestId}/answers`, { answers: payload, submit: isSubmit });
      setMsg(isSubmit ? 'Отчёт отправлен на проверку!' : 'Черновик сохранён');
      if (isSubmit) setTimeout(onDone, 1500);
    } catch (e: any) {
      setMsg(apiErrorMessage(e, 'Ошибка сохранения'));
    } finally {
      setSubmitting(false);
    }
  };

  const breadcrumb = (vehicle: { brand?: string; model?: string; year?: number; vin?: string }) => (
    <div className="flex items-center gap-2 text-sm">
      <button onClick={onDone} className="flex items-center gap-1 text-gray-500 hover:text-gray-700">
        <svg className="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
          <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M15 19l-7-7 7-7" />
        </svg>
        Мои отчёты
      </button>
      <span className="text-gray-300">/</span>
      <span className="font-medium text-gray-900">{vehicle.brand} {vehicle.model} {vehicle.year}</span>
      <span className="text-gray-400 font-mono text-xs hidden sm:inline">{vehicle.vin}</span>
    </div>
  );

  if (needsBinding) return (
    <div className="p-4 sm:p-6 max-w-3xl space-y-4">
      {breadcrumb(request)}
      <div className="bg-white border rounded-xl p-4 shadow-sm">
        <TemplateBinding requestId={requestId} templates={templates} loadError={templatesError} onBound={loadForm} />
      </div>
    </div>
  );

  if (!data) return (
    <div className="flex items-center justify-center py-16 p-6">
      {msg ? <p className="text-red-500">{msg}</p> : <p className="text-gray-400">Загрузка отчёта...</p>}
    </div>
  );

  const template: Template = data.template;
  const groups: Group[] = template?.groups ?? [];
  const status = parseDiagnosticRequestStatus(data.status);
  const editable = diagnosticianCanEdit({ status, templateId: template?.id ?? null });

  const totalRequired = groups.flatMap(g => g.items).filter(i => i.isRequired).length;
  const filledRequired = groups.flatMap(g => g.items)
    .filter(i => i.isRequired && isAnswerFilled(i, answers[i.id])).length;
  const progress = totalRequired > 0 ? Math.round((filledRequired / totalRequired) * 100) : 100;

  // HYBRID submit UX: advisory only, never blocks the submit button (unlike
  // the existing progress<100 disable above, which is untouched). Distinct
  // from a genuine "confirmed missing" message when readiness is still
  // unknown (media list not yet loaded / failed to load) - NOT_LOADED must
  // never be presented to the user as "video missing".
  const videoRequiredItems = groups.flatMap(g => g.items).filter(i => i.hasVideo);
  const videoMissing = videoRequiredItems.filter(i => itemReadiness[i.id] === 'missing').map(i => i.title);
  const videoUnknown = videoRequiredItems.filter(i => {
    const r = itemReadiness[i.id];
    return r === undefined || r === 'unknown';
  }).map(i => i.title);

  return (
    <div className="p-4 sm:p-6 max-w-3xl space-y-4">
      {breadcrumb(data)}

      <div className="bg-white border rounded-xl p-4 shadow-sm space-y-3">
        {template && (
          <p className="text-sm text-gray-600">
            Шаблон анкеты: <strong>{template.name}</strong>{template.vehicleType ? ` (${template.vehicleType})` : ''}
          </p>
        )}
        {!template && (
          <div className="p-3 bg-yellow-50 border border-yellow-200 rounded-lg text-sm text-yellow-800">
            Нет активного шаблона анкеты. Обратитесь к администратору.
          </div>
        )}
        {!editable && (
          <div className="p-3 bg-gray-50 border border-gray-200 rounded-lg text-sm text-gray-600">
            Редактирование недоступно — статус: <strong>{labelOf(DIAGNOSTIC_STATUS_LABELS.diagnostician, status)}</strong>
          </div>
        )}
        {template && editable && (
          <div>
            <div className="flex justify-between text-xs text-gray-500 mb-1">
              <span>Прогресс: {filledRequired}/{totalRequired} обязательных полей</span>
              <span>{progress}%</span>
            </div>
            <div className="h-2 bg-gray-200 rounded-full overflow-hidden">
              <div className={`h-full rounded-full transition-all ${progress === 100 ? 'bg-green-500' : 'bg-blue-500'}`}
                style={{ width: `${progress}%` }} />
            </div>
          </div>
        )}
      </div>

      {missingFields.length > 0 && (
        <div className="p-3 rounded-xl bg-red-50 border border-red-200 text-sm text-red-700">
          <p className="font-medium mb-1">Нельзя отправить — не заполнены обязательные поля:</p>
          <ul className="list-disc pl-4 space-y-0.5">
            {missingFields.map((f, i) => <li key={i}>{f}</li>)}
          </ul>
        </div>
      )}

      {videoMissing.length > 0 && (
        <div className="p-3 rounded-xl bg-amber-50 border border-amber-200 text-sm text-amber-800">
          <p className="font-medium mb-1">Обязательное видео ещё не готово (отправка всё же возможна):</p>
          <ul className="list-disc pl-4 space-y-0.5">
            {videoMissing.map((f, i) => <li key={i}>{f}</li>)}
          </ul>
        </div>
      )}
      {videoMissing.length === 0 && videoUnknown.length > 0 && (
        <div className="p-3 rounded-xl bg-gray-50 border border-gray-200 text-sm text-gray-600">
          Статус обязательного видео пока не проверен: {videoUnknown.join(', ')}
        </div>
      )}

      {msg && (
        <div className={`p-3 rounded-xl text-sm border ${
          msg.includes('отправлен') ? 'bg-green-50 text-green-700 border-green-200'
          : msg.includes('сохранён') ? 'bg-blue-50 text-blue-700 border-blue-200'
          : 'bg-red-50 text-red-700 border-red-200'
        }`}>{msg}</div>
      )}

      {groups.map(group => (
        <div key={group.id} className="bg-white border rounded-xl overflow-hidden shadow-sm">
          <button onClick={() => toggleGroup(group.id)}
            className="w-full flex justify-between items-center px-4 py-3 hover:bg-gray-50 text-left">
            <span className="font-medium">{group.name}</span>
            <div className="flex items-center gap-2">
              <span className="text-xs text-gray-400">
                {group.items.filter(i =>
                  answers[i.id]?.rating != null || answers[i.id]?.comment || (answers[i.id]?.photoUrls?.length ?? 0) > 0
                ).length}/{group.items.length}
              </span>
              <svg className={`w-4 h-4 text-gray-400 transition-transform ${openGroups.has(group.id) ? 'rotate-180' : ''}`}
                fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M19 9l-7 7-7-7" />
              </svg>
            </div>
          </button>
          {openGroups.has(group.id) && (
            <div className="divide-y">
              {group.items.length === 0 && (
                <p className="px-4 py-3 text-sm text-gray-400">В этом разделе нет полей</p>
              )}
              {group.items.map(item => {
                const ans = answers[item.id] ?? { itemId: item.id, rating: null, comment: '', photoUrls: [], videoUrls: [], isSkipped: false };
                const hasError = validationErrors.has(item.id);
                return (
                  <div key={item.id} className={`p-4 space-y-2 ${hasError ? 'bg-red-50' : ''}`}>
                    {primaryFieldType(item) !== 'CHECKBOX' && (
                      <div>
                        <p className="font-medium text-sm">
                          {item.title}
                          {item.isRequired && <span className="text-red-500 ml-1">*</span>}
                        </p>
                        {item.description && primaryFieldType(item) !== 'SELECT' && (
                          <p className="text-xs text-gray-400 mt-0.5">{item.description}</p>
                        )}
                      </div>
                    )}
                    {editable && ans.isSkipped ? (
                      <div className="flex items-center justify-between text-sm text-gray-500 bg-gray-50 rounded-lg px-3 py-2 border border-dashed">
                        <span className="italic">Пункт пропущен</span>
                        <button type="button" onClick={() => setAnswer(item.id, 'isSkipped', false)}
                          aria-label={`Вернуть пункт ${item.title}`}
                          className="text-xs text-blue-600 hover:underline">Вернуть</button>
                      </div>
                    ) : editable ? (
                      <>
                        <FieldInput item={item} ans={ans}
                          onChange={(field, value) => setAnswer(item.id, field, value)}
                          hasError={hasError} />
                        {/* Scenario 3 step 4: only a non-required item may be skipped. */}
                        {!item.isRequired && (
                          <button type="button" onClick={() => setAnswer(item.id, 'isSkipped', true)}
                            aria-label={`Пропустить пункт ${item.title}`}
                            className="text-xs text-gray-500 hover:text-gray-700 underline">Пропустить</button>
                        )}
                      </>
                    ) : ans.isSkipped ? (
                      <div className="text-sm text-gray-500 italic bg-gray-50 rounded-lg px-3 py-2 border">Пропущен</div>
                    ) : (
                      <div className="text-sm text-gray-600 bg-gray-50 rounded-lg px-3 py-2 border">
                        {primaryFieldType(item) === 'NUMBER' && ans.rating ? `Оценка: ${ans.rating}/5` : null}
                        {['TEXT', 'TEXTAREA', 'SELECT', 'CHECKBOX'].includes(primaryFieldType(item)) && (ans.comment || '—')}
                        {savedExtraRating(item, ans) != null && <div>Оценка: {savedExtraRating(item, ans)}/5</div>}
                        <ReadOnlyPhotos urls={primaryFieldType(item) === 'PHOTO' ? ans.photoUrls ?? [] : savedExtraPhotos(item, ans)} />
                      </div>
                    )}
                    {hasError && <p className="text-xs text-red-500">Обязательное поле</p>}
                    {item.hasVideo && editable && (
                      <DiagnosticMediaSection
                        requestId={requestId}
                        itemId={item.id}
                        hasVideo={item.hasVideo}
                        onReadinessChange={handleReadinessChange}
                      />
                    )}
                  </div>
                );
              })}
            </div>
          )}
        </div>
      ))}

      {template && editable && (
        <div className="flex gap-3 pb-6">
          <button onClick={() => submit(false)} disabled={submitting}
            className="px-5 py-2.5 border rounded-xl text-sm hover:bg-gray-50 disabled:opacity-50">
            Сохранить черновик
          </button>
          <button onClick={() => submit(true)} disabled={submitting || progress < 100}
            title={progress < 100 ? 'Заполните все обязательные поля' : ''}
            className="px-5 py-2.5 bg-blue-600 text-white rounded-xl text-sm hover:bg-blue-700 disabled:opacity-50">
            Отправить на проверку
            {progress < 100 && <span className="ml-1 text-xs opacity-75">({progress}%)</span>}
          </button>
        </div>
      )}
    </div>
  );
}

// ─── DashboardPage ────────────────────────────────────────────────────────────

function DashboardPage() {
  const [stats, setStats] = useState({ pending: 0, inProgress: 0, submitted: 0, approved: 0 });
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    Promise.all([
      api.get('/diagnostic/work/pending').catch(() => ({ data: [] })),
      api.get('/diagnostic/work/my?size=200').catch(() => ({ data: { content: [] } })),
    ]).then(([pendingRes, myRes]) => {
      const reports: any[] = myRes.data?.content || [];
      setStats({
        pending: (pendingRes.data || []).length,
        inProgress: reports.filter(r => r.status === 'IN_PROGRESS').length,
        submitted: reports.filter(r => r.status === 'COMPLETED').length,
        approved: reports.filter(r => r.status === 'APPROVED').length,
      });
    }).finally(() => setLoading(false));
  }, []);

  const cards = [
    {
      title: 'Новые заявки',
      value: stats.pending,
      bg: 'bg-yellow-50', border: 'border-yellow-200', num: 'text-yellow-800', text: 'text-yellow-700',
      icon: (
        <svg className="w-6 h-6 text-yellow-600" fill="none" stroke="currentColor" viewBox="0 0 24 24">
          <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2}
            d="M9 5H7a2 2 0 00-2 2v12a2 2 0 002 2h10a2 2 0 002-2V7a2 2 0 00-2-2h-2M9 5a2 2 0 002 2h2a2 2 0 002-2M9 5a2 2 0 012-2h2a2 2 0 012 2" />
        </svg>
      ),
    },
    {
      title: 'Отчёты в работе',
      value: stats.inProgress,
      bg: 'bg-blue-50', border: 'border-blue-200', num: 'text-blue-800', text: 'text-blue-700',
      icon: (
        <svg className="w-6 h-6 text-blue-600" fill="none" stroke="currentColor" viewBox="0 0 24 24">
          <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2}
            d="M11 5H6a2 2 0 00-2 2v11a2 2 0 002 2h11a2 2 0 002-2v-5m-1.414-9.414a2 2 0 112.828 2.828L11.828 15H9v-2.828l8.586-8.586z" />
        </svg>
      ),
    },
    {
      title: 'Отчёты на проверке',
      value: stats.submitted,
      bg: 'bg-purple-50', border: 'border-purple-200', num: 'text-purple-800', text: 'text-purple-700',
      icon: (
        <svg className="w-6 h-6 text-purple-600" fill="none" stroke="currentColor" viewBox="0 0 24 24">
          <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2}
            d="M9 12l2 2 4-4m6 2a9 9 0 11-18 0 9 9 0 0118 0z" />
        </svg>
      ),
    },
    {
      title: 'Одобренные отчёты',
      value: stats.approved,
      bg: 'bg-green-50', border: 'border-green-200', num: 'text-green-800', text: 'text-green-700',
      icon: (
        <svg className="w-6 h-6 text-green-600" fill="none" stroke="currentColor" viewBox="0 0 24 24">
          <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M5 13l4 4L19 7" />
        </svg>
      ),
    },
  ];

  return (
    <div className="p-6">
      <h1 className="text-2xl font-bold text-gray-900 mb-1">Dashboard</h1>
      <p className="text-sm text-gray-400 mb-6">Сводка по вашим заявкам и отчётам</p>
      {loading ? (
        <div className="text-center py-16 text-gray-400">Загрузка...</div>
      ) : (
        <div className="grid grid-cols-1 sm:grid-cols-2 xl:grid-cols-4 gap-4">
          {cards.map(c => (
            <div key={c.title} className={`border rounded-xl p-5 ${c.bg} ${c.border}`}>
              <div className="w-10 h-10 rounded-lg bg-white shadow-sm flex items-center justify-center mb-3">
                {c.icon}
              </div>
              <p className={`text-3xl font-bold ${c.num}`}>{c.value}</p>
              <p className={`text-sm mt-1 font-medium ${c.text}`}>{c.title}</p>
            </div>
          ))}
        </div>
      )}
    </div>
  );
}

// ─── AvailablePage ────────────────────────────────────────────────────────────

function AvailablePage() {
  const [requests, setRequests] = useState<DiagRequest[]>([]);
  const [loading, setLoading] = useState(true);
  const [msg, setMsg] = useState('');
  const [msgOk, setMsgOk] = useState(false);
  const [search, setSearch] = useState('');
  const [taking, setTaking] = useState<number | null>(null);

  const load = () => {
    setLoading(true);
    api.get('/diagnostic/work/pending')
      .then(r => setRequests(r.data || []))
      .catch(() => setMsg('Не удалось загрузить заявки'))
      .finally(() => setLoading(false));
  };
  useEffect(load, []);

  const takeWork = async (id: number) => {
    setTaking(id);
    try {
      await api.post(`/diagnostic/work/${id}/assign`);
      setMsg(`Заявка #${id} принята в работу. Откройте «Мои отчёты», чтобы выбрать шаблон анкеты.`);
      setMsgOk(true);
      load();
    } catch (e: any) {
      setMsg(apiErrorMessage(e, 'Ошибка при принятии заявки'));
      setMsgOk(false);
    } finally {
      setTaking(null);
    }
  };

  const filtered = useMemo(() => {
    const q = search.trim().toLowerCase();
    if (!q) return requests;
    return requests.filter(r =>
      r.vin?.toLowerCase().includes(q) ||
      `${r.brand} ${r.model}`.toLowerCase().includes(q) ||
      r.ownerName?.toLowerCase().includes(q)
    );
  }, [requests, search]);

  return (
    <div className="p-4 sm:p-6">
      <div className="flex flex-wrap gap-3 justify-between items-center mb-6">
        <div>
          <h1 className="text-2xl font-bold text-gray-900">Доступные заявки</h1>
          <p className="text-sm text-gray-400 mt-0.5">Заявки, ожидающие назначения диагноста</p>
        </div>
        <button onClick={load} className="text-sm text-gray-500 hover:text-gray-700 flex items-center gap-1.5">
          <svg className="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
            <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2}
              d="M4 4v5h.582m15.356 2A8.001 8.001 0 004.582 9m0 0H9m11 11v-5h-.581m0 0a8.003 8.003 0 01-15.357-2m15.357 2H15" />
          </svg>
          Обновить
        </button>
      </div>

      {msg && (
        <div className={`mb-4 p-3 rounded-xl text-sm border ${
          msgOk ? 'bg-green-50 text-green-700 border-green-200' : 'bg-red-50 text-red-700 border-red-200'
        }`}>{msg}</div>
      )}

      <div className="mb-4 relative w-full sm:w-72">
        <svg className="absolute left-3 top-1/2 -translate-y-1/2 w-4 h-4 text-gray-400"
          fill="none" stroke="currentColor" viewBox="0 0 24 24">
          <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2}
            d="M21 21l-6-6m2-5a7 7 0 11-14 0 7 7 0 0114 0z" />
        </svg>
        <input type="text" value={search} onChange={e => setSearch(e.target.value)}
          placeholder="Поиск по VIN, авто, владельцу..."
          className="pl-9 pr-3 py-2 border rounded-lg text-sm w-full focus:ring-2 focus:ring-blue-500 focus:outline-none" />
      </div>

      {loading ? (
        <div className="text-center py-16 text-gray-400">Загрузка...</div>
      ) : filtered.length === 0 ? (
        <div className="bg-white border rounded-xl p-16 text-center text-gray-400">
          <svg className="w-12 h-12 mx-auto mb-3 opacity-30" fill="none" stroke="currentColor" viewBox="0 0 24 24">
            <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={1.5}
              d="M9 5H7a2 2 0 00-2 2v12a2 2 0 002 2h10a2 2 0 002-2V7a2 2 0 00-2-2h-2M9 5a2 2 0 002 2h2a2 2 0 002-2M9 5a2 2 0 012-2h2a2 2 0 012 2" />
          </svg>
          <p>{requests.length === 0 ? 'Нет доступных заявок' : 'Ничего не найдено'}</p>
        </div>
      ) : (
        <div className="bg-white border rounded-xl overflow-x-auto shadow-sm">
          <table className="w-full">
            <thead className="bg-gray-50 border-b">
              <tr>
                <th className="text-left px-4 py-3 text-xs font-semibold text-gray-500 uppercase tracking-wide">Автомобиль</th>
                <th className="hidden sm:table-cell text-left px-4 py-3 text-xs font-semibold text-gray-500 uppercase tracking-wide">VIN</th>
                <th className="hidden md:table-cell text-left px-4 py-3 text-xs font-semibold text-gray-500 uppercase tracking-wide">Владелец</th>
                <th className="hidden sm:table-cell text-left px-4 py-3 text-xs font-semibold text-gray-500 uppercase tracking-wide">Дата</th>
                <th className="px-4 py-3"></th>
              </tr>
            </thead>
            <tbody className="divide-y">
              {filtered.map(r => (
                <tr key={r.id} className="hover:bg-gray-50 transition-colors">
                  <td className="px-4 py-3">
                    <p className="font-medium text-gray-900">{r.brand} {r.model}</p>
                    <p className="text-xs text-gray-400">{r.year} г.</p>
                    {/* FE-MOBILE-01: the VIN column is hidden on a phone - keep the VIN with the car */}
                    <p className="sm:hidden font-mono text-xs text-gray-500 break-all">{r.vin || '—'}</p>
                  </td>
                  <td className="hidden sm:table-cell px-4 py-3">
                    <span className="font-mono text-sm text-gray-700">{r.vin || '—'}</span>
                  </td>
                  <td className="hidden md:table-cell px-4 py-3 text-sm text-gray-600">{r.ownerName || '—'}</td>
                  <td className="hidden sm:table-cell px-4 py-3 text-sm text-gray-500">{formatDate(r.createdAt)}</td>
                  <td className="px-4 py-3 text-right">
                    <button onClick={() => takeWork(r.id)} disabled={taking === r.id}
                      className="bg-blue-600 text-white px-4 py-1.5 rounded-lg text-sm hover:bg-blue-700 disabled:opacity-50 transition-colors whitespace-nowrap">
                      {taking === r.id ? 'Принятие...' : 'Принять заявку'}
                    </button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  );
}

// ─── MyReportsPage ────────────────────────────────────────────────────────────

function MyReportsPage() {
  const [requests, setRequests] = useState<DiagRequest[]>([]);
  const [loading, setLoading] = useState(true);
  const [activeReport, setActiveReport] = useState<DiagRequest | null>(null);
  const [statusFilter, setStatusFilter] = useState('ALL');
  const [search, setSearch] = useState('');
  const [actionMsg, setActionMsg] = useState('');
  const [actionOk, setActionOk] = useState(true);

  const load = () => {
    setLoading(true);
    api.get('/diagnostic/work/my?size=100')
      .then(r => setRequests(r.data.content || []))
      .catch(e => { setActionMsg(apiErrorMessage(e, 'Не удалось загрузить заявки')); setActionOk(false); })
      .finally(() => setLoading(false));
  };
  useEffect(load, []);

  const rework = async (id: number) => {
    try {
      await api.post(`/diagnostic/work/${id}/rework`);
      setActionMsg('Статус изменён — теперь можете исправить и повторно отправить отчёт');
      setActionOk(true);
      load();
    } catch (e) {
      setActionMsg(apiErrorMessage(e, 'Не удалось вернуть отчёт на исправление'));
      setActionOk(false);
    }
  };

  const filtered = useMemo(() => {
    let list = statusFilter === 'ALL' ? requests
      : requests.filter(r => {
          if (statusFilter === 'SUBMITTED') return r.status === 'COMPLETED';
          return r.status === statusFilter;
        });
    const q = search.trim().toLowerCase();
    if (q) list = list.filter(r =>
      r.vin?.toLowerCase().includes(q) ||
      `${r.brand} ${r.model}`.toLowerCase().includes(q)
    );
    return list;
  }, [requests, statusFilter, search]);

  const filterOptions = [
    { value: 'ALL',         label: 'Все' },
    { value: 'IN_PROGRESS', label: 'В работе' },
    { value: 'SUBMITTED',   label: 'На проверке' },
    { value: 'APPROVED',    label: 'Одобрены' },
    { value: 'REJECTED',    label: 'Отклонённые' },
  ];

  if (activeReport != null) {
    return <ReportForm request={activeReport} onDone={() => { setActiveReport(null); load(); }} />;
  }

  return (
    <div className="p-4 sm:p-6">
      <div className="flex flex-wrap gap-3 justify-between items-center mb-6">
        <div>
          <h1 className="text-2xl font-bold text-gray-900">Мои отчёты</h1>
          <p className="text-sm text-gray-400 mt-0.5">Все заявки, закреплённые за вами</p>
        </div>
        <button onClick={load} className="text-sm text-gray-500 hover:text-gray-700 flex items-center gap-1.5">
          <svg className="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
            <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2}
              d="M4 4v5h.582m15.356 2A8.001 8.001 0 004.582 9m0 0H9m11 11v-5h-.581m0 0a8.003 8.003 0 01-15.357-2m15.357 2H15" />
          </svg>
          Обновить
        </button>
      </div>

      {actionMsg && (
        <div className={`mb-4 p-3 rounded-xl text-sm border ${
          actionOk ? 'bg-blue-50 text-blue-700 border-blue-200' : 'bg-red-50 text-red-700 border-red-200'
        }`}>
          {actionMsg}
        </div>
      )}

      <div className="flex flex-col sm:flex-row gap-3 mb-4">
        <div className="flex gap-2 flex-wrap">
          {filterOptions.map(opt => (
            <button key={opt.value} onClick={() => setStatusFilter(opt.value)}
              className={`px-3 py-1.5 rounded-lg text-xs font-medium transition-colors ${
                statusFilter === opt.value
                  ? 'bg-blue-600 text-white'
                  : 'bg-gray-100 text-gray-600 hover:bg-gray-200'
              }`}>
              {opt.label}
            </button>
          ))}
        </div>
        <div className="sm:ml-auto relative w-full sm:w-64">
          <svg className="absolute left-3 top-1/2 -translate-y-1/2 w-4 h-4 text-gray-400"
            fill="none" stroke="currentColor" viewBox="0 0 24 24">
            <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2}
              d="M21 21l-6-6m2-5a7 7 0 11-14 0 7 7 0 0114 0z" />
          </svg>
          <input type="text" value={search} onChange={e => setSearch(e.target.value)}
            placeholder="VIN, авто..."
            className="pl-9 pr-3 py-2 border rounded-lg text-sm w-full focus:ring-2 focus:ring-blue-500 focus:outline-none" />
        </div>
      </div>

      {loading ? (
        <div className="text-center py-16 text-gray-400">Загрузка...</div>
      ) : filtered.length === 0 ? (
        <div className="bg-white border rounded-xl p-16 text-center text-gray-400">
          <svg className="w-12 h-12 mx-auto mb-3 opacity-30" fill="none" stroke="currentColor" viewBox="0 0 24 24">
            <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={1.5}
              d="M9 12h6m-6 4h6m2 5H7a2 2 0 01-2-2V5a2 2 0 012-2h5.586a1 1 0 01.707.293l5.414 5.414a1 1 0 01.293.707V19a2 2 0 01-2 2z" />
          </svg>
          <p>{requests.length === 0 ? 'У вас пока нет заявок. Перейдите в «Доступные заявки».' : 'Ничего не найдено'}</p>
        </div>
      ) : (
        <div className="bg-white border rounded-xl overflow-x-auto shadow-sm">
          <table className="w-full">
            <thead className="bg-gray-50 border-b">
              <tr>
                <th className="text-left px-4 py-3 text-xs font-semibold text-gray-500 uppercase tracking-wide">Автомобиль</th>
                <th className="hidden sm:table-cell text-left px-4 py-3 text-xs font-semibold text-gray-500 uppercase tracking-wide">VIN</th>
                <th className="hidden md:table-cell text-left px-4 py-3 text-xs font-semibold text-gray-500 uppercase tracking-wide">Дата</th>
                <th className="text-left px-4 py-3 text-xs font-semibold text-gray-500 uppercase tracking-wide">Статус</th>
                <th className="px-4 py-3"></th>
              </tr>
            </thead>
            <tbody className="divide-y">
              {filtered.map(r => {
                const parsed = parseDiagnosticRequestStatus(r.status);
                const s = { label: labelOf(DIAGNOSTIC_STATUS_LABELS.diagnostician, parsed),
                  color: parsed.known ? STATUS_COLOR[parsed.value] : 'bg-gray-100 text-gray-600' };
                const canOpen = canOpenReport(r.status);
                return (
                  <React.Fragment key={r.id}>
                    <tr className={`hover:bg-gray-50 transition-colors ${r.status === 'REJECTED' ? 'bg-red-50/40' : ''}`}>
                      <td className="px-4 py-3">
                        <p className="font-medium text-gray-900">{r.brand} {r.model}</p>
                        <p className="text-xs text-gray-400">{r.year} г.</p>
                        <p className="sm:hidden font-mono text-xs text-gray-500 break-all">{r.vin || '—'}</p>
                      </td>
                      <td className="hidden sm:table-cell px-4 py-3">
                        <span className="font-mono text-sm text-gray-700">{r.vin || '—'}</span>
                      </td>
                      <td className="hidden md:table-cell px-4 py-3 text-sm text-gray-500">{formatDate(r.createdAt)}</td>
                      <td className="px-4 py-3">
                        <span className={`inline-flex items-center px-2.5 py-1 rounded-full text-xs font-medium ${s.color}`}>
                          {s.label}
                        </span>
                      </td>
                      <td className="px-4 py-3 text-right sm:whitespace-nowrap">
                        {r.status === 'REJECTED' ? (
                          <button onClick={() => rework(r.id)}
                            className="bg-orange-500 text-white px-4 py-1.5 rounded-lg text-sm hover:bg-orange-600 transition-colors">
                            Исправить и отправить
                          </button>
                        ) : canOpen ? (
                          <button onClick={() => setActiveReport(r)}
                            className="bg-blue-600 text-white px-4 py-1.5 rounded-lg text-sm hover:bg-blue-700 transition-colors">
                            Заполнить
                          </button>
                        ) : r.status === 'COMPLETED' ? (
                          <span className="text-xs text-gray-400">Ожидает проверки</span>
                        ) : r.status === 'APPROVED' ? (
                          <span className="text-xs text-green-600 font-medium">Одобрен</span>
                        ) : null}
                      </td>
                    </tr>
                    {r.status === 'REJECTED' && r.rejectedReason && (
                      <tr className="bg-red-50">
                        <td colSpan={5} className="px-4 py-2 border-b border-red-100">
                          <div className="flex items-start gap-2 text-sm text-red-700">
                            <svg className="w-4 h-4 mt-0.5 shrink-0" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                              <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2}
                                d="M12 9v2m0 4h.01m-6.938 4h13.856c1.54 0 2.502-1.667 1.732-3L13.732 4c-.77-1.333-2.694-1.333-3.464 0L3.34 16c-.77 1.333.192 3 1.732 3z" />
                            </svg>
                            <span><strong>Причина отклонения:</strong> {r.rejectedReason}</span>
                          </div>
                        </td>
                      </tr>
                    )}
                  </React.Fragment>
                );
              })}
            </tbody>
          </table>
        </div>
      )}
    </div>
  );
}

// ─── Sidebar ──────────────────────────────────────────────────────────────────

function DiagSidebar({ onNavigate }: { onNavigate: () => void }) {
  const user = useAuthStore(s => s.user);

  const navItems = [
    {
      path: '/diagnostic/dashboard',
      label: 'Dashboard',
      icon: (
        <svg className="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
          <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2}
            d="M3 12l2-2m0 0l7-7 7 7M5 10v10a1 1 0 001 1h3m10-11l2 2m-2-2v10a1 1 0 01-1 1h-3m-6 0a1 1 0 001-1v-4a1 1 0 011-1h2a1 1 0 011 1v4a1 1 0 001 1m-6 0h6" />
        </svg>
      ),
    },
    {
      path: '/diagnostic/available',
      label: 'Доступные заявки',
      icon: (
        <svg className="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
          <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2}
            d="M9 5H7a2 2 0 00-2 2v12a2 2 0 002 2h10a2 2 0 002-2V7a2 2 0 00-2-2h-2M9 5a2 2 0 002 2h2a2 2 0 002-2M9 5a2 2 0 012-2h2a2 2 0 012 2" />
        </svg>
      ),
    },
    {
      path: '/diagnostic/reports',
      label: 'Мои отчёты',
      icon: (
        <svg className="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
          <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2}
            d="M9 12h6m-6 4h6m2 5H7a2 2 0 01-2-2V5a2 2 0 012-2h5.586a1 1 0 01.707.293l5.414 5.414a1 1 0 01.293.707V19a2 2 0 01-2 2z" />
        </svg>
      ),
    },
  ];

  return (
    <aside className="w-60 bg-white border-r border-gray-100 flex flex-col h-screen sticky top-0 shrink-0">
      <div className="p-4 border-b border-gray-100">
        <BrandLogo variant="full" className="h-9 w-auto" />
        <p className="font-semibold text-gray-900 text-sm leading-tight mt-1">Диагностика</p>
        <p className="text-xs text-gray-400 truncate">{user?.username}</p>
      </div>

      <nav aria-label="Разделы" className="flex-1 p-3 space-y-0.5 overflow-y-auto">
        {navItems.map(item => (
          <NavLink key={item.path} to={item.path} onClick={onNavigate}
            className={({ isActive }) =>
              `flex items-center gap-3 px-3 py-2.5 rounded-xl text-sm font-medium transition-colors ${
                isActive
                  ? 'bg-blue-50 text-blue-700'
                  : 'text-gray-700 hover:bg-gray-50 hover:text-gray-900'
              }`
            }>
            {item.icon}
            {item.label}
          </NavLink>
        ))}
      </nav>

      <RoleAreaFooter onNavigate={onNavigate} />
    </aside>
  );
}

// ─── Main ─────────────────────────────────────────────────────────────────────

export function DiagnosticDashboard() {
  return (
    <SidebarLayout title="Диагностика" sidebar={onNavigate => <DiagSidebar onNavigate={onNavigate} />}
      actions={<NotificationBell />}>
      <Routes>
        <Route index element={<Navigate to="/diagnostic/dashboard" replace />} />
        <Route path="dashboard" element={<DashboardPage />} />
        <Route path="available" element={<AvailablePage />} />
        <Route path="reports" element={<MyReportsPage />} />
        {/* G1: an unknown section is the area's start page, not an empty work area */}
        <Route path="*" element={<Navigate to="/diagnostic/dashboard" replace />} />
      </Routes>
    </SidebarLayout>
  );
}
