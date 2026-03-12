# Amazon-Thermostat

Alexa thermostat integration for Hubitat. Uses the **direct Alexa Smart Home API**.

📖 **[API.md](API.md)** – Full API reference: Alexa GraphQL, downchannel, Hubitat endpoints, payloads.

## How it works

- **Amazon AC Manager** polls the Alexa API (configurable 1–15 min) or receives push updates via the downchannel server
- **Cookie** from manual paste or cookie server URL (same as downchannel bridge)
- **Commands** (set temp, mode) go directly to the Alexa API from the app

### Polling vs real-time (downchannel)

| Mode | Latency | Requires |
|------|---------|----------|
| **Polling** | 1–15 min (or 30s fast poll) | Hubitat app only |
| **Real-time** | Instant | [Alexa Downchannel Server](alexa-downchannel/) (Docker/Node) |

## Setup

1. **Install the app** in Hubitat: Amazon AC Manager
2. **Cookie** – pick one:
   - **Manual cookie**: From browser DevTools (alexa.amazon.com → Application → Cookies), paste the full Cookie header.
   - **Cookie server URL**: Full URL, e.g. `http://YOUR_SERVER:8091/cookieData` for Echo Speaks Docker. Must include `/cookieData` path.
3. **Configure thermostat names** as they appear in the Alexa app (comma-separated).
4. **Create devices** via the Create Devices button.

Polling starts automatically. Commands (set temp, mode) go directly to the Alexa API.

### Optional: real-time updates (downchannel server)

Run the [Alexa Downchannel Server](alexa-downchannel/) in Docker. It connects to Alexa's HTTP/2 downchannel and pushes thermostat changes to Hubitat immediately. See [alexa-downchannel/README.md](alexa-downchannel/README.md) for setup.
