# Dev server over HTTPS for a phone on the LAN (camera / KYC selfie)

Browsers expose the camera (`navigator.mediaDevices`) only on a secure page: `https://…` or `http://localhost`.
A phone opening the dev server as `http://<lan-ip>:5173` is not on a secure page, so the KYC selfie camera
cannot start there; the page says so ("Камера работает только на защищённой странице…").

The Vite dev server can serve HTTPS for this case. It is dev-only and opt-in: without the variables below
nothing changes, and `vite build` (production) never reads it.

## 1. Create a local certificate (once)

The private key stays on this machine — never put it in the repository.

```bash
# any folder outside the repository, e.g. D:\AlSo-demo\cert
printf '[req]\ndistinguished_name=dn\nprompt=no\n[dn]\nCN=AlSo dev (local only)\n[ext]\nsubjectAltName=IP:<lan-ip>,IP:127.0.0.1,DNS:localhost\nbasicConstraints=CA:FALSE\nkeyUsage=digitalSignature,keyEncipherment\nextendedKeyUsage=serverAuth\n' > openssl.cnf
openssl req -x509 -newkey rsa:2048 -nodes -days 365 -config openssl.cnf -extensions ext -keyout dev.key -out dev.crt
```

Replace `<lan-ip>` with the laptop's Wi-Fi address (`ipconfig`). A new address needs a new certificate.

## 2. Start the frontend over HTTPS (PowerShell)

```powershell
$env:DEV_HTTPS_CERT = 'D:\AlSo-demo\cert\dev.crt'
$env:DEV_HTTPS_KEY  = 'D:\AlSo-demo\cert\dev.key'
$env:VITE_API_URL   = 'https://<lan-ip>:5173'     # the API is reached through this same https origin
cd client; npm run dev
```

An https page cannot call `http://<lan-ip>:8081`, so in this mode Vite proxies `/api`, `/uploads` and the
WebSocket (`/ws-auction`) to the backend (`DEV_API_PROXY_TARGET`, default `http://127.0.0.1:8081`). The proxy
drops the browser's `Origin` header, so the backend's CORS / WebSocket origin lists stay as they are.

## 3. On the phone

Open `https://<lan-ip>:5173`. The certificate is self-signed: accept the browser warning once
(iOS Safari: "Show details" → "visit this website"). The camera then works like on `localhost`.

## Known limits

- Media that was uploaded while the backend's `MEDIA_BASE_URL` pointed at `http://…:8081/uploads` keeps that
  absolute `http://` address; on an https page some phone browsers block such images as mixed content.
  New uploads get https addresses when the backend runs with `MEDIA_BASE_URL=https://<lan-ip>:5173/uploads`.
- Production must be served over real HTTPS with a trusted certificate; this setup is for local testing only.
