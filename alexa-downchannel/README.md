# Alexa Downchannel Server

Connects to Alexa's HTTP/2 downchannel, listens for thermostat changes, and pushes updates to Hubitat in real time.

## How to Run

### With Node.js

```bash
cd alexa-downchannel
npm install   # optional - no deps, uses stdlib only
node alexa-downchannel.js
```

### With Docker

```bash
docker build -t alexa-downchannel ./alexa-downchannel
docker run -d --name alexa-downchannel \
  -e COOKIE_SERVER_URL="..." \
  -e HUBITAT_URL="..." \
  -e HUBITAT_APP_ID="..." \
  -e HUBITAT_ACCESS_TOKEN="..." \
  -p 3099:3099 \
  alexa-downchannel
```

### Docker Compose

```yaml
services:
  alexa-downchannel:
    build: ./alexa-downchannel
    environment:
      - COOKIE_SERVER_URL=${COOKIE_SERVER_URL}
      - HUBITAT_URL=${HUBITAT_URL}
      - HUBITAT_APP_ID=${HUBITAT_APP_ID}
      - HUBITAT_ACCESS_TOKEN=${HUBITAT_ACCESS_TOKEN}
      - THERMOSTAT_NAMES=${THERMOSTAT_NAMES}
      - ALEXA_REGION=na
      - PORT=3099
    ports:
      - "3099:3099"
    restart: unless-stopped
```

---

## Environment Variables

| Variable | Required | Description |
|----------|----------|-------------|
| `COOKIE_SERVER_URL` | **Yes** | URL that returns Alexa cookie. Use Hubitat app `/cookie` endpoint. |
| `HUBITAT_URL` | **Yes** | Hubitat base URL (e.g. `https://xxx.hubitat.cloud` or `http://192.168.1.50`). |
| `HUBITAT_APP_ID` | **Yes** | Amazon AC Manager app instance ID. |
| `HUBITAT_ACCESS_TOKEN` | **Yes** | OAuth access token from the app (create via app settings). |
| `THERMOSTAT_NAMES` | No | Comma-separated thermostat names to filter (as in Alexa app). Empty = all thermostats. |
| `ALEXA_REGION` | No | `na` (default) or `eu` for bob-dispatch. |
| `PORT` | No | Server port. Default `3099`. |
| `LOG_LEVEL` | No | `debug`, `info`, `warn`, `error`. Default `info`. |

### Cookie Server URL

Use the Hubitat Amazon AC Manager app's cookie endpoint:

```
https://your-hub-id.hubitat.cloud/apps/api/YOUR_APP_ID/cookie?access_token=YOUR_TOKEN
```

Must return JSON with `cookie` and optionally `csrf`.

---

## Payload Sent to Hubitat

When a thermostat change is detected, the server POSTs this JSON to:

```
POST {HUBITAT_URL}/apps/api/{HUBITAT_APP_ID}/statusreportfromtheapp?access_token={HUBITAT_ACCESS_TOKEN}
Content-Type: application/json
```

**Request body** – array of thermostat objects:

```json
[
  {
    "name": "Living Room Thermostat",
    "endpointId": "amzn1.ask.account.xxx...",
    "mode": "cool",
    "currentTemp": "74° F",
    "target": "72",
    "lowerSetpoint": 68,
    "upperSetpoint": 76
  }
]
```

| Field | Type | Description |
|-------|------|-------------|
| `name` | string | Friendly name from Alexa. |
| `endpointId` | string | Alexa device ID. |
| `mode` | string | `off`, `heat`, `cool`, `auto`, `eco`. |
| `currentTemp` | string | Current temperature (e.g. `"74° F"`). |
| `target` | string | Single setpoint for heat/cool, or `"68°–76°"` for auto. |
| `lowerSetpoint` | number | Lower temp (auto mode). |
| `upperSetpoint` | number | Upper temp (auto mode). |

---

## HTTP Endpoints

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/ping` or `/health` | Health check. Returns `{status, downchannel, cookie}`. |
| `GET` | `/refresh` | Re-fetch the Alexa cookie from `COOKIE_SERVER_URL`. |
| `GET` | `/poll` | Fetch current thermostat state from Alexa, return as JSON, and push to Hubitat if `HUBITAT_*` vars are set. Used by the Hubitat app when **Downchannel server URL** is configured. |

### `/poll` response

```json
{
  "ok": true,
  "thermostats": [
    {
      "name": "Living Room Thermostat",
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

See the project’s [API.md](../API.md) for full API documentation (Alexa GraphQL, Hubitat endpoints, payloads).
