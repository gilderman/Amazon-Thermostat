# Alexa Thermostat Server

Polls Alexa's GraphQL API for thermostat state and pushes updates to Hubitat. Used as a proxy when Hubitat cannot reach Alexa directly (TLS issues).

## How to Run

### With Node.js

```bash
cd alexa-downchannel
node alexa-downchannel.js
```

### With Docker

**Option A: Pull from GHCR**

```bash
echo $GITHUB_TOKEN | docker login ghcr.io -u USERNAME --password-stdin
docker pull ghcr.io/gilderman/amazon-thermostat:latest
docker run -d --name alexa-thermostat \
  -e COOKIE_SERVER_URL="..." \
  -e HUBITAT_URL="..." \
  -e HUBITAT_APP_ID="..." \
  -e HUBITAT_ACCESS_TOKEN="..." \
  -p 3099:3099 \
  ghcr.io/gilderman/amazon-thermostat:latest
```

**Option B: Build locally**

```bash
docker build -t alexa-thermostat ./alexa-downchannel
docker run -d --name alexa-thermostat \
  -e COOKIE_SERVER_URL="..." \
  -e HUBITAT_URL="..." \
  -e HUBITAT_APP_ID="..." \
  -e HUBITAT_ACCESS_TOKEN="..." \
  -p 3099:3099 \
  alexa-thermostat
```

**Option C: Windows with .env**

```powershell
# 1. Login to GHCR (one time)
$env:GITHUB_TOKEN | docker login ghcr.io -u gilderman --password-stdin

# 2. Create .env (copy from .env.example, fill in values)
# 3. Run
cd PS
.\run-with-env.ps1
```

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
