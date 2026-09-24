import '@testing-library/jest-dom/vitest';
import { afterEach } from 'vitest';
import { cleanup } from '@testing-library/react';

// @testing-library/react's automatic between-test cleanup relies on detecting a global
// afterEach; this project runs Vitest without `globals: true`, so it is wired explicitly here.
afterEach(() => {
  cleanup();
});
