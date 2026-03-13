# Alexa Downchannel Server

Polls Alexa's GraphQL API for thermostat state and pushes updates to Hubitat. Used as a proxy when Hubitat cannot reach Alexa directly (TLS issues).

**Docker image:** `ghcr.io/gilderman/amazon-thermostat:latest` — built automatically on push to `main`. Pull with `docker pull ghcr.io/gilderman/amazon-thermostat:latest` (login to `ghcr.io` first with a GitHub PAT that has `read:packages`).

## How to Run

### With Node.js

```bash
cd alexa-downchannel
node alexa-downchannel.js
```

### With Docker

**Image:** `ghcr.io/gilderman/amazon-thermostat:latest` (built on push to main)

**Option A: Pull from GHCR** (Linux/Mac)

```bash
# Login (one time; use a GitHub PAT with read:packages)
echo $GITHUB_TOKEN | docker login ghcr.io -u gilderman --password-stdin

docker pull ghcr.io/gilderman/amazon-thermostat:latest
docker run -d --name alexa-thermostat \
  -e COOKIE_SERVER_URL="http://YOUR_ECHO_SPEAKS:8091/cookieData" \
  -e HUBITAT_URL="http://YOUR_HUBITAT_IP:8080" \
  -e HUBITAT_APP_ID="YOUR_APP_ID" \
  -e HUBITAT_ACCESS_TOKEN="YOUR_TOKEN" \
  -e THERMOSTAT_NAMES="Office, Living Room" \
  -p 3099:3099 \
  --restart unless-stopped \
  ghcr.io/gilderman/amazon-thermostat:latest
```

**Option B: Windows (PowerShell)**

```powershell
# 1. Login to GHCR (one time; set $env:GITHUB_TOKEN to your PAT first)
$env:GITHUB_TOKEN | docker login ghcr.io -u gilderman --password-stdin

# 2. Pull and run
docker pull ghcr.io/gilderman/amazon-thermostat:latest
docker run -d --name alexa-thermostat `
  -e COOKIE_SERVER_URL="http://YOUR_ECHO_SPEAKS:8091/cookieData" `
  -e HUBITAT_URL="http://YOUR_HUBITAT_IP:8080" `
  -e HUBITAT_APP_ID="YOUR_APP_ID" `
  -e HUBITAT_ACCESS_TOKEN="YOUR_TOKEN" `
  -e THERMOSTAT_NAMES="Office" `
  -p 3099:3099 `
  --restart unless-stopped `
  ghcr.io/gilderman/amazon-thermostat:latest
```

**Option C: With .env file**

```bash
# Create .env (copy from .env.example in this directory, fill in values)
docker run -d --name alexa-thermostat --env-file .env -p 3099:3099 --restart unless-stopped ghcr.io/gilderman/amazon-thermostat:latest
```

**Option D: Build from source**

```bash
docker build -t alexa-thermostat .
docker run -d --name alexa-thermostat \
  -e COOKIE_SERVER_URL="..." \
  -e HUBITAT_URL="..." \
  -e HUBITAT_APP_ID="..." \
  -e HUBITAT_ACCESS_TOKEN="..." \
  -p 3099:3099 \
  alexa-thermostat
```

Verify: `curl http://localhost:3099/ping` or `Invoke-WebRequest -Uri http://localhost:3099/ping`

### Docker Compose

```yaml
services:
  alexa-thermostat:
    image: ghcr.io/gilderman/amazon-thermostat:latest
    environment:
      - COOKIE_SERVER_URL=${COOKIE_SERVER_URL}
      - HUBITAT_URL=${HUBITAT_URL}
      - HUBITAT_APP_ID=${HUBITAT_APP_ID}
      - HUBITAT_ACCESS_TOKEN=${HUBITAT_ACCESS_TOKEN}
      - THERMOSTAT_NAMES=${THERMOSTAT_NAMES}
      - PORT=3099
    ports:
      - "3099:3099"
    restart: unless-stopped
```

---

## Environment Variables

| Variable | Required | Description |
|----------|----------|-------------|
| `COOKIE_SERVER_URL` | No* | URL that returns Alexa cookie. Omit to use Hubitat app `/cookie` when `HUBITAT_*` are set. Echo Speaks: `http://host:8091/cookieData` |
| `HUBITAT_URL` | **Yes** | Hubitat base URL (e.g. `http://192.168.1.50:8080` for local, or cloud URL). |
| `HUBITAT_APP_ID` | **Yes** | Amazon AC Manager app instance ID. |
| `HUBITAT_ACCESS_TOKEN` | **Yes** | OAuth access token from the app. |
| `THERMOSTAT_NAMES` | No | Comma-separated names to filter (as in Alexa app). Empty = all thermostats. |
| `PORT` | No | Server port. Default `3099`. |
| `LOG_LEVEL` | No | `debug`, `info`, `warn`, `error`. Default `info`. |

---

## HTTP Endpoints

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/ping` or `/health` | Health check. Returns `{status, cookie}`. |
| `GET` | `/refresh` | Re-fetch the Alexa cookie. |
| `GET` | `/poll` | Fetch thermostat state from Alexa, push to Hubitat, return JSON. |

### `/poll` response

```json
{
  "ok": true,
  "thermostats": [
    {
      "name": "Living Room",
      "endpointId": "amzn1.ask.account.xxx...",
      "mode": "cool",
      "currentTemp": "74° F",
      "target": "72° F",
      "lowerSetpoint": null,
      "upperSetpoint": null
    }
  ]
}
```

---

## Health Check

```bash
curl http://localhost:3099/ping
```
