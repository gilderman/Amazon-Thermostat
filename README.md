# Amazon-Thermostat

Alexa thermostat integration for Hubitat. Uses the **direct Alexa Smart Home API**.

📖 **[API.md](API.md)** – API reference: Alexa GraphQL, Hubitat endpoints, payloads.

## How it works

- **Amazon AC Manager** (Hubitat app) polls for thermostat state on a schedule (1–15 min, or 30s fast poll)
- **Alexa Thermostat Server** (Node.js/Docker) acts as a proxy when Hubitat cannot reach Alexa directly (TLS issues)
- **Cookie** sourced from manual paste or a cookie server (Hubitat app `/cookie` or Echo Speaks `/cookieData`)
- **Commands** (set temp, mode) sent from Hubitat directly to Alexa

### Polling via proxy

Hubitat's Java HTTP client cannot complete TLS handshakes with `alexa.amazon.com` on some hub firmware versions, causing 408 errors. The [Alexa Thermostat Server](alexa-downchannel/) handles HTTPS and proxies the poll request.

## Setup

### 1. Run the thermostat server

The server handles communication with the Alexa API. See [alexa-downchannel/README.md](alexa-downchannel/README.md) for full setup. Quick start:

```bash
docker run -d --name alexa-thermostat \
  -e COOKIE_SERVER_URL="http://YOUR_ECHO_SPEAKS_HOST:8091/cookieData" \
  -e HUBITAT_URL="http://YOUR_HUBITAT_IP" \
  -e HUBITAT_APP_ID="YOUR_APP_ID" \
  -e HUBITAT_ACCESS_TOKEN="YOUR_TOKEN" \
  -p 3099:3099 \
  ghcr.io/gilderman/amazon-thermostat
```

### 2. Install the Hubitat app

Install **Amazon AC Manager** and **Amazon Thermostat** driver from this repo into Hubitat.

### 3. Configure the app

1. **Cookie server URL** – e.g. `http://YOUR_ECHO_SPEAKS_HOST:8091/cookieData` (or omit to use Hubitat app `/cookie`)
2. **Downchannel server URL** – e.g. `http://YOUR_SERVER_HOST:3099` (routes poll through the proxy when Hubitat can't reach Alexa)
3. **Thermostat names** – comma-separated, exactly as they appear in the Alexa app
4. Click **Test Cookie Fetch** to verify connectivity
5. Click **Create Devices** to create child devices
6. Click **Poll Now** to verify state is fetched correctly

### 4. Verify

Check Hubitat Live Logs. A successful poll looks like:

```
[Poll] Using proxy: http://192.168.21.100:3099/poll (timeout 30s)
[Poll] Proxy callback: HTTP 200
```

## Architecture

```
Alexa API (alexa.amazon.com)
    ▲           │  GraphQL poll
    │           ▼
    │    Alexa Thermostat Server  ◄────  Hubitat (calls /poll)
    │      (Node.js, port 3099)              │
    │             │                          │  POST status
    │             ▼                          ▼
    │       Hubitat (state updates)     Commands go direct
```
