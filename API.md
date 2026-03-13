# Alexa API Reference

How this integration uses the Alexa Smart Home GraphQL API.

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

## 2. Thermostat Server (Node.js)

The integration uses a Node.js server that polls Alexa's GraphQL API and pushes state to Hubitat. Hubitat's Java HTTP client cannot complete TLS handshakes with `alexa.amazon.com` on some hub firmware versions, so the server acts as a proxy.
