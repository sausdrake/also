import { useEffect, useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { activeDocument, LEGAL_NAMES, LEGAL_SLUGS, typeBySlug, type LegalDocument, type LegalDocumentType } from '../api/legal';
import { formatDateTime } from '../utils/formatDate';

/**
 * LEGAL-DATA-01: a legal document as it is in force. Only the ACTIVE version is shown; until a version has been
 * approved and activated the page says so - a draft for legal review is never presented as the valid text.
 * The text is rendered as plain text (no HTML).
 */
export function LegalDocumentPage() {
  const { slug } = useParams();
  const type = typeBySlug(slug);
  const [doc, setDoc] = useState<LegalDocument | null>(null);
  const [state, setState] = useState<'loading' | 'ready' | 'none' | 'error'>('loading');

  useEffect(() => {
    if (!type) { setState('none'); return; }
    let cancelled = false;
    setState('loading');
    activeDocument(type)
      .then(d => { if (!cancelled) { setDoc(d); setState(d ? 'ready' : 'none'); } })
      .catch(() => { if (!cancelled) setState('error'); });
    return () => { cancelled = true; };
  }, [type]);

  const name = type ? LEGAL_NAMES[type] : 'Документ';

  return (
    <div className="max-w-3xl mx-auto px-4 py-8">
      <h1 className="text-2xl font-bold text-gray-900">{doc?.title ?? name}</h1>
      {state === 'loading' && <p className="text-sm text-gray-400 mt-4">Загрузка…</p>}
      {state === 'error' && <p className="text-sm text-red-600 mt-4">Не удалось загрузить документ. Попробуйте позже.</p>}
      {state === 'none' && (
        <div className="mt-4 bg-amber-50 border border-amber-200 rounded-xl p-4 text-sm text-amber-800">
          {type
            ? 'Документ готовится и проходит юридическую проверку. Действующей редакции пока нет.'
            : 'Такого документа нет.'}
        </div>
      )}
      {state === 'ready' && doc && (
        <>
          <p className="text-xs text-gray-500 mt-1">
            Редакция {doc.version}{doc.effectiveAt && ` · действует с ${formatDateTime(doc.effectiveAt)}`}
          </p>
          <div className="mt-6 bg-white border border-gray-100 rounded-2xl p-5 text-sm text-gray-800 whitespace-pre-wrap leading-relaxed break-words">
            {doc.content}
          </div>
        </>
      )}
      <LegalLinks current={type} />
    </div>
  );
}

function LegalLinks({ current }: { current: LegalDocumentType | null }) {
  return (
    <nav aria-label="Документы" className="mt-8 flex flex-wrap gap-x-4 gap-y-2 text-sm">
      {(Object.keys(LEGAL_SLUGS) as LegalDocumentType[]).filter(t => t !== current).map(t => (
        <Link key={t} to={`/legal/${LEGAL_SLUGS[t]}`} className="text-blue-600 hover:underline">{LEGAL_NAMES[t]}</Link>
      ))}
    </nav>
  );
}
