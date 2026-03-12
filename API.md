# Alexa API Reference

How this integration uses the Alexa Smart Home and downchannel APIs.

---

## 1. Alexa GraphQL API

We use Amazon's Smart Home GraphQL API at `https://alexa.amazon.com/nexus/v1/graphql`. All requests require:

- **Cookie** – Full `Cookie` header from an authenticated alexa.amazon.com session (includes `at-main`, `csrf`, etc.).
- **Headers** – `Cookie`, `csrf`, `Accept: application/json`, `Content-Type: application/json`, `Referer: https://alexa.amazon.com/`, `Origin: https://alexa.amazon.com`.

### 1.1 List endpoints (discovery)

Discover devices and their metadata.

**Request:**

```http
POST https://alexa.amazon.com/nexus/v1/graphql
Content-Type: application/json
```

**Body:**

```json
{
  "query": "{ listEndpoints(listEndpointsInput: {}) { endpoints { id friendlyName displayCategories { primary { value } } } } }"
}
```

**Response:** `data.listEndpoints.endpoints` – array of devices with `id`, `friendlyName`, `displayCategories.primary.value` (e.g. `THERMOSTAT`, `TEMPERATURE_SENSOR`).

### 1.2 List endpoints (state)

Fetch thermostat state for specific endpoint IDs.

**Request:**

```http
POST https://alexa.amazon.com/nexus/v1/graphql
Content-Type: application/json
```

**Body:**

```json
{
  "query": "{ listEndpoints(listEndpointsInput: { endpointIds: [\"id1\", \"id2\"] }) { endpoints { id features { name properties { name value } } } } }"
}
```

**Response:** Each endpoint has `features` with `properties` such as:

- `thermostatMode` – `"HEAT"`, `"COOL"`, `"AUTO"`, `"OFF"`, etc.
- `temperature` – current temp
- `targetSetpoint` – single setpoint
- `lowerSetpoint` / `upperSetpoint` – auto mode range

Property `value` may be a primitive or `{ value, scale }`.

### 1.3 Set thermostat features (commands)

Send commands (set temp, mode) via a mutation.

**Request:**

```http
POST https://alexa.amazon.com/nexus/v1/graphql
Content-Type: application/json
```

**Body:**

```json
{
  "query": "mutation SetFeatures($input: SetEndpointFeaturesInput!) { setEndpointFeatures(setEndpointFeaturesInput: $input) { featureControlResponses { endpointId } errors { message } } }",
  "variables": {
    "input": {
      "featureControlRequests": [{
        "endpointId": "amzn1.ask.account.xxx...",
        "featureName": "thermostat",
        "featureOperationName": "setTargetSetpoint",
        "payload": { "targetSetpoint": { "value": 72, "scale": "FAHRENHEIT" } }
      }]
    }
  }
}
```

**Operations:**

| Command | featureOperationName | payload |
|---------|----------------------|---------|
| Set heating/cooling setpoint | `setTargetSetpoint` | `{ "targetSetpoint": { "value": 72, "scale": "FAHRENHEIT" } }` |
| Set mode | `setThermostatMode` | `{ "thermostatMode": "HEAT" }` (HEAT, COOL, AUTO, ECO, OFF) |

---

## 2. Alexa downchannel (HTTP/2)

For real-time push notifications, we use Amazon's HTTP/2 downchannel.

- **Host:** `bob-dispatch-prod-na.amazon.com` (NA) or `bob-dispatch-prod-eu.amazon.com` (EU)
- **Path:** `GET /v20160207/directives`
- **Auth:** `Authorization: Bearer {token}` (extract `at-main` from cookie, URL-decode)

The connection stays open. Amazon streams directives to the client when devices or account state changes.

### 2.1 What we get from the downchannel

The response body is a stream of **newline-delimited JSON** (NDJSON). Each line is a complete JSON object representing one directive. Amazon sends directives for various events: Smart Home device changes, voice interactions, account updates, etc.

**Transport format:** One JSON object per line, `\n`-separated. Partial lines may arrive; the client buffers and splits on newlines before parsing.

**Example stream:**

```
{"header":{"namespace":"Alexa.ConnectedHome.Discovery","name":"AddOrUpdateReport"}}
{"header":{"namespace":"Alexa","name":"Response"}}
```

### 2.2 Directive payload structure

Each directive is a JSON object. The structure typically includes:

| Field | Type | Description |
|-------|------|-------------|
| `header` | object | Directive metadata |
| `header.namespace` | string | e.g. `Alexa.ConnectedHome.Discovery`, `Alexa`, `Com Alexa.SmartHome` |
| `header.name` | string | Directive type, e.g. `AddOrUpdateReport`, `Response`, `StateReport` |
| `header.messageId` | string | Unique ID (optional) |
| `payload` | object | Directive-specific data (optional) |

**Example directive (Smart Home state change):**

```json
{
  "header": {
    "namespace": "Alexa.ConnectedHome.Control",
    "name": "StateReport",
    "messageId": "abc-123"
  },
  "payload": {
    "endpoints": []
  }
}
```

**Example directive (discovery/add device):**

```json
{
  "header": {
    "namespace": "Alexa.ConnectedHome.Discovery",
    "name": "AddOrUpdateReport"
  },
  "payload": {}
}
```

We do not parse thermostat data from the directive payload. Any received directive is treated as a signal that something may have changed. We then fetch full thermostat state via the GraphQL API (1.1 + 1.2) and push the result to Hubitat.

Node.js supports HTTP/2; Hubitat Groovy does not — hence the separate downchannel server.
