import fs from 'node:fs'
import { defineConfig, type ProxyOptions } from 'vite'
import react from '@vitejs/plugin-react'

// CAMERA-01: optional HTTPS for opening the dev server from a phone on the LAN. Browsers expose the camera
// (navigator.mediaDevices) only on a secure page, and http://<lan-ip>:5173 is not one. Opt-in through
// DEV_HTTPS_CERT / DEV_HTTPS_KEY (paths to a locally generated certificate - never committed); without them
// nothing changes. Over HTTPS the API must be reached through this same origin (an https page cannot call
// http://<lan-ip>:8081), so /api, /uploads and the WebSocket are proxied to DEV_API_PROXY_TARGET and
// VITE_API_URL is set to this server's own https address. Dev-only: `vite build` never reads this block.
const cert = process.env.DEV_HTTPS_CERT
const key = process.env.DEV_HTTPS_KEY
const https = cert && key ? { cert: fs.readFileSync(cert), key: fs.readFileSync(key) } : undefined
const target = process.env.DEV_API_PROXY_TARGET || 'http://127.0.0.1:8081'

// Through the proxy the browser talks to one origin; dropping its Origin header keeps the backend's own
// CORS / WebSocket origin lists unchanged (they only ever see same-origin, proxied requests).
const toBackend = (ws = false): ProxyOptions => ({
  target,
  ws,
  changeOrigin: true,
  configure: proxy => {
    proxy.on('proxyReq', req => req.removeHeader('origin'))
    proxy.on('proxyReqWs', req => req.removeHeader('origin'))
  },
})

export default defineConfig({
  plugins: [react()],
  server: {
    host: '0.0.0.0',
    port: 5173,
    https,
    proxy: https ? { '/api': toBackend(), '/uploads': toBackend(), '/ws-auction': toBackend(true) } : undefined,
  },
})
