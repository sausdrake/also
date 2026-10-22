/**
 * DIAG-02: how a diagnostic template item is answered. Source of truth: backend
 * DiagnosticRequestService.isFilled (completion gate) and the item flags of InspectionItem.
 *
 * - The field type decides what the item REQUIRES; a missing type is TEXT, exactly as the backend reads it.
 * - hasRating / hasPhoto add an optional rating / photos next to that answer (the seeded template items are
 *   TEXT with both flags). They never make an item required - the backend gate does not either.
 */
export interface InspectionItemShape {
  fieldType?: string | null;
  hasRating?: boolean;
  hasPhoto?: boolean;
}

export interface InspectionAnswerShape {
  rating?: number | null;
  comment?: string | null;
  photoUrls?: string[] | null;
  skipped?: boolean;
  isSkipped?: boolean;
}

export const primaryFieldType = (item: InspectionItemShape): string => item.fieldType || 'TEXT';

/** Mirrors the backend completion gate: a skipped or missing answer is never filled. */
export function isAnswerFilled(item: InspectionItemShape, ans?: InspectionAnswerShape | null): boolean {
  if (!ans || ans.skipped || ans.isSkipped) return false;
  switch (primaryFieldType(item)) {
    case 'NUMBER': return ans.rating != null && ans.rating > 0;
    case 'PHOTO': return (ans.photoUrls?.length ?? 0) > 0;
    case 'CHECKBOX': return ans.comment === 'true' || ans.comment === 'false';
    default: return (ans.comment?.trim().length ?? 0) > 0;
  }
}

/** An optional rating next to the main answer (the main answer is not a rating already). */
export const hasExtraRating = (item: InspectionItemShape): boolean =>
  !!item.hasRating && primaryFieldType(item) !== 'NUMBER';

/** Optional photos next to the main answer (the main answer is not photos already). */
export const hasExtraPhotos = (item: InspectionItemShape): boolean =>
  !!item.hasPhoto && primaryFieldType(item) !== 'PHOTO';

/** For read-only views: a saved rating outside a NUMBER item is still shown. */
export const savedExtraRating = (item: InspectionItemShape, ans?: InspectionAnswerShape | null): number | null =>
  ans && !ans.skipped && primaryFieldType(item) !== 'NUMBER' && ans.rating != null && ans.rating > 0 ? ans.rating : null;

/** For read-only views: saved photos outside a PHOTO item are still shown. */
export const savedExtraPhotos = (item: InspectionItemShape, ans?: InspectionAnswerShape | null): string[] =>
  ans && !ans.skipped && primaryFieldType(item) !== 'PHOTO' ? (ans.photoUrls ?? []) : [];

export const RATING_LABELS = ['', 'Плохо', 'Удовлетворительно', 'Нормально', 'Хорошо', 'Отлично'];
