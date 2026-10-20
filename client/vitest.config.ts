import { defineConfig } from 'vitest/config';
import react from '@vitejs/plugin-react';

export default defineConfig({
  plugins: [react()],
  // FE-STATE-01: the domain contract test reads the backend enum sources (../src/main/java) via ?raw;
  // tests only - the application build (vite.config.ts) is unaffected.
  server: { fs: { allow: ['..'] } },
  test: {
    environment: 'jsdom',
    setupFiles: ['./src/test/setup.ts'],
  },
});
