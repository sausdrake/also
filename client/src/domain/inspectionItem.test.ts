import { describe, expect, it } from 'vitest';
import {
  hasExtraPhotos, hasExtraRating, isAnswerFilled, primaryFieldType, savedExtraPhotos, savedExtraRating,
} from './inspectionItem';

describe('DIAG-02 inspection item answering', () => {
  const seeded = { fieldType: 'TEXT', hasRating: true, hasPhoto: true };

  it('a missing field type is TEXT, as the backend reads it', () => {
    expect(primaryFieldType({ fieldType: null, hasRating: true })).toBe('TEXT');
    expect(isAnswerFilled({ fieldType: null, hasRating: true }, { rating: 4, comment: '' })).toBe(false);
    expect(isAnswerFilled({ fieldType: null }, { comment: 'ok' })).toBe(true);
  });

  it('seeded TEXT items offer an optional rating and photos', () => {
    expect(hasExtraRating(seeded)).toBe(true);
    expect(hasExtraPhotos(seeded)).toBe(true);
    expect(hasExtraRating({ fieldType: 'NUMBER', hasRating: true })).toBe(false);
    expect(hasExtraPhotos({ fieldType: 'PHOTO', hasPhoto: true })).toBe(false);
    expect(hasExtraRating({ fieldType: 'TEXT', hasRating: false })).toBe(false);
  });

  it('the extras never fill a required item - only the main answer does (backend gate)', () => {
    expect(isAnswerFilled(seeded, { rating: 5, photoUrls: ['/uploads/a.jpg'], comment: ' ' })).toBe(false);
    expect(isAnswerFilled(seeded, { comment: 'Без повреждений' })).toBe(true);
  });

  it('follows the gate for every field type, and a skipped answer is never filled', () => {
    expect(isAnswerFilled({ fieldType: 'NUMBER' }, { rating: 0 })).toBe(false);
    expect(isAnswerFilled({ fieldType: 'NUMBER' }, { rating: 3 })).toBe(true);
    expect(isAnswerFilled({ fieldType: 'PHOTO' }, { photoUrls: [] })).toBe(false);
    expect(isAnswerFilled({ fieldType: 'PHOTO' }, { photoUrls: ['/u.jpg'] })).toBe(true);
    expect(isAnswerFilled({ fieldType: 'CHECKBOX' }, { comment: 'false' })).toBe(true);
    expect(isAnswerFilled({ fieldType: 'CHECKBOX' }, { comment: 'maybe' })).toBe(false);
    expect(isAnswerFilled({ fieldType: 'TEXTAREA' }, { comment: 'x', skipped: true })).toBe(false);
    expect(isAnswerFilled({ fieldType: 'TEXTAREA' }, { comment: 'x', isSkipped: true })).toBe(false);
    expect(isAnswerFilled({ fieldType: 'TEXT' }, undefined)).toBe(false);
  });

  it('read-only views show a saved rating / photos outside NUMBER / PHOTO items, never for skipped ones', () => {
    expect(savedExtraRating(seeded, { rating: 4 })).toBe(4);
    expect(savedExtraRating({ fieldType: 'NUMBER' }, { rating: 4 })).toBeNull();
    expect(savedExtraRating(seeded, { rating: 4, skipped: true })).toBeNull();
    expect(savedExtraPhotos(seeded, { photoUrls: ['/a.jpg'] })).toEqual(['/a.jpg']);
    expect(savedExtraPhotos({ fieldType: 'PHOTO' }, { photoUrls: ['/a.jpg'] })).toEqual([]);
    expect(savedExtraPhotos(seeded, null)).toEqual([]);
  });
});
