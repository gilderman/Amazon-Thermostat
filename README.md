# Amazon-Thermostat

Alexa thermostat integration for Hubitat. Uses the **direct Alexa Smart Home API**.

📖 **[API.md](API.md)** – API reference: Alexa GraphQL, Hubitat endpoints, payloads.

## How it works

- **Amazon AC Manager** (Hubitat app) polls for thermostat state on a schedule (1–15 min, or 30s fast poll)
- **Alexa Downchannel Server (alexa-downchannel)** (Node.js/Docker) acts as a proxy when Hubitat cannot reach Alexa directly (TLS issues)
- **Cookie** sourced from manual paste or a cookie server (Hubitat app `/cookie` or Echo Speaks `/cookieData`)
- **Commands** (set temp, mode) sent from Hubitat directly to Alexa

### Polling via proxy

Hubitat's Java HTTP client cannot complete TLS handshakes with `alexa.amazon.com` on some hub firmware versions, causing 408 errors. The [Alexa Downchannel Server (alexa-downchannel)](alexa-downchannel/) handles HTTPS and proxies the poll request.

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

### 2. Install the Hubitat app, driver, and library

Install in this order (Helper first, since the app includes it):

1. **Helper (Library)** – In Hubitat: *Apps → Add an App → Library* → paste the contents of [AmazonACManagerHelper.groovy](https://raw.githubusercontent.com/gilderman/Amazon-Thermostat/main/AmazonACManagerHelper.groovy). Save as "AmazonACManagerHelper".

2. **Driver** – *Devices → Drivers → Add a Custom Driver* → paste the contents of [AmazonThermostatDriver.groovy](https://raw.githubusercontent.com/gilderman/Amazon-Thermostat/main/AmazonThermostatDriver.groovy). Save as "Amazon Thermostat".

3. **App** – *Apps → Add an App → Custom* → paste the contents of [AmazonACManager.groovy](https://raw.githubusercontent.com/gilderman/Amazon-Thermostat/main/AmazonACManager.groovy). Save as "Amazon AC Manager".

4. **Enable OAuth** – The app uses OAuth for its `/cookie` and `/statusreportfromtheapp` callbacks. In Hubitat: *Settings → OAuth*. For "Amazon AC Manager", enable OAuth.

**Where to find App ID and Access Token** (for `HUBITAT_APP_ID` and `HUBITAT_ACCESS_TOKEN` in the thermostat server):

- **App ID** – Open *Apps → Amazon AC Manager* (click the app name). The App ID is in the browser URL (e.g. `.../app/editor/12345678-xxxx`) or shown on the app’s OAuth/callback section.
- **Access Token** – After OAuth is enabled, go to *Settings → OAuth*. Under "Amazon AC Manager" you’ll see the access token. Or open the app → its OAuth section shows the token. Copy the full token (no spaces).

Use these values for the server’s `HUBITAT_APP_ID` and `HUBITAT_ACCESS_TOKEN` env vars (see step 1 above).

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
    │    Alexa Downchannel Server (alexa-downchannel)  ◄────  Hubitat (calls /poll)
    │      (Node.js, port 3099)              │
    │             │                          │  POST status
    │             ▼                          ▼
    │       Hubitat (state updates)     Commands go direct
```
