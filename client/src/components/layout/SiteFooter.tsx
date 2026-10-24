import { Link } from 'react-router-dom';
import { LEGAL_NAMES, LEGAL_SLUGS, type LegalDocumentType } from '../../api/legal';

/** LEGAL-DATA-01: the platform's documents, reachable from every public page. */
const FOOTER_DOCUMENTS: LegalDocumentType[] = [
  'TERMS_OF_USE', 'PRIVACY_POLICY', 'AUCTION_RULES', 'KYC_POLICY', 'DIAGNOSTIC_RULES', 'FINANCIAL_RULES',
];

export function SiteFooter() {
  return (
    <footer className="border-t border-gray-100 bg-white mt-8">
      <div className="max-w-6xl mx-auto px-4 py-5 flex flex-col gap-3 text-xs text-gray-500">
        <nav aria-label="Документы платформы" className="flex flex-wrap gap-x-4 gap-y-2">
          {FOOTER_DOCUMENTS.map(t => (
            <Link key={t} to={`/legal/${LEGAL_SLUGS[t]}`} className="hover:text-gray-800 hover:underline">{LEGAL_NAMES[t]}</Link>
          ))}
        </nav>
        <p>© AlSo — автомобильные аукционы</p>
      </div>
    </footer>
  );
}
