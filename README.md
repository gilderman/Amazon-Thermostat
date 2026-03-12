# Amazon-Thermostat

Alexa thermostat integration for Hubitat. Uses the **direct Alexa Smart Home API**.

📖 **[API.md](API.md)** – Full API reference: Alexa GraphQL, downchannel, Hubitat endpoints, payloads.

## How it works

- **Amazon AC Manager** polls for thermostat state on a schedule (1–15 min, or 30s fast poll), or receives real-time push updates via the downchannel server
- **Cookie** sourced from manual paste or a local cookie server (e.g. Echo Speaks Docker)
- **Commands** (set temp, mode) sent from Hubitat to Alexa via the downchannel server proxy

### Polling vs real-time (downchannel)

| Mode | Latency | Requires |
|------|---------|----------|
| **Polling via proxy** | 1–15 min (or 30s fast poll) | Downchannel server + `Downchannel server URL` set in app |
| **Real-time push** | Instant | Downchannel server (Docker/Node) |

> **Why the proxy?** Hubitat's Java HTTP client cannot complete TLS handshakes with `alexa.amazon.com` on some hub firmware versions, causing immediate 408 errors. All Alexa API calls are routed through the local [Alexa Downchannel Server](alexa-downchannel/) (Node.js/Docker), which handles HTTPS without issue.

## Setup

### 1. Run the downchannel server

The downchannel server handles all communication with the Alexa API. See [alexa-downchannel/README.md](alexa-downchannel/README.md) for full setup. Quick start:

```bash
docker run -d --name alexa-downchannel \
  -e COOKIE_SERVER_URL="http://YOUR_ECHO_SPEAKS_HOST:8091/cookieData" \
  -e HUBITAT_URL="http://YOUR_HUBITAT_IP" \
  -e HUBITAT_APP_ID="YOUR_APP_ID" \
  -e HUBITAT_ACCESS_TOKEN="YOUR_TOKEN" \
  -p 3099:3099 \
  ghcr.io/gilderman/alexa-downchannel
```

### 2. Install the Hubitat app

Install **Amazon AC Manager** and **Amazon Thermostat** driver from this repo into Hubitat.

### 3. Configure the app

1. **Cookie server URL** – e.g. `http://YOUR_ECHO_SPEAKS_HOST:8091/cookieData`
2. **Downchannel server URL** – e.g. `http://YOUR_DOWNCHANNEL_HOST:3099` (routes poll and commands through the local proxy)
3. **Thermostat names** – comma-separated, exactly as they appear in the Alexa app
4. Click **Test Cookie Fetch** to verify connectivity
5. Click **Create Devices** to create child devices
6. Click **Poll Now** to verify state is fetched correctly

### 4. Verify

Check Hubitat Live Logs. A successful poll looks like:

```
[Poll] Using downchannel proxy: http://192.168.21.100:3099/poll (timeout 30s)
[Poll] Proxy callback: HTTP 200
```

## Architecture

```
Alexa API (alexa.amazon.com)
        │  HTTP/2 downchannel          │  GraphQL (poll / commands)
        ▼                              ▼
Alexa Downchannel Server  ◄────────  Hubitat (Amazon AC Manager)
  (Node.js, port 3099)                    calls /poll, /command
        │
        │  POST /statusreportfromtheapp
        ▼
  Hubitat (real-time push)
```

- **Real-time**: Alexa pushes directives over HTTP/2 → Node.js server fetches state → pushes to Hubitat
- **Polling**: Hubitat calls `/poll` on the Node.js server → server queries Alexa → returns state
- **Commands**: Hubitat calls `/command` on the Node.js server → server sends GraphQL mutation to Alexa
