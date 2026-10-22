// @vitest-environment node
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { ConfigEnv, UserConfig } from 'vite'

// PROD-FE-01: a deployable bundle must never fall back to http://localhost:8081.
const env: { VITE_API_URL?: string } = {}
vi.mock('vite', async importOriginal => ({
  ...(await importOriginal<typeof import('vite')>()),
  loadEnv: () => ({ ...env }),
}))

const config = async (command: ConfigEnv['command']) => {
  const factory = (await import('./vite.config')).default as unknown as (e: ConfigEnv) => UserConfig
  return factory({ command, mode: command === 'build' ? 'production' : 'development' })
}

describe('vite.config - VITE_API_URL guard', () => {
  beforeEach(() => {
    delete env.VITE_API_URL
  })

  it('refuses a build without VITE_API_URL', async () => {
    await expect(config('build')).rejects.toThrow('VITE_API_URL must be set')
  })

  it('builds when VITE_API_URL is set', async () => {
    env.VITE_API_URL = 'https://api.example.test'
    await expect(config('build')).resolves.toHaveProperty('plugins')
  })

  it('the dev server still starts without it', async () => {
    await expect(config('serve')).resolves.toHaveProperty('server.port', 5173)
  })
})
