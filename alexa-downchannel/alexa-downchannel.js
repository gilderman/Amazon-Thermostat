#!/usr/bin/env node
/**
 * Alexa Downchannel Server
 *
 * Connects to Alexa's HTTP/2 downchannel, listens for directives, fetches thermostat
 * state via GraphQL when changes occur, and pushes updates to Hubitat.
 *
 * Cookie: Fetches from COOKIE_SERVER_URL only (Hubitat /cookie or Echo Speaks /cookieData).
 */

const http = require('http');
const https = require('https');
const http2 = require('http2');
const { URL } = require('url');

const PORT = parseInt(process.env.PORT || '3099', 10);
const LOG_LEVEL = (process.env.LOG_LEVEL || 'info').toLowerCase();
const ALEXA_REGION = (process.env.ALEXA_REGION || 'na').toLowerCase();
const BOB_HOST = ALEXA_REGION === 'eu' ? 'bob-dispatch-prod-eu.amazon.com' : 'bob-dispatch-prod-na.amazon.com';
const DIRECTIVES_PATH = '/v20160207/directives';

const COOKIE_SERVER_URL = (process.env.COOKIE_SERVER_URL || '').replace(/\/$/, '');
const HUBITAT_URL = (process.env.HUBITAT_URL || '').replace(/\/$/, '');
const HUBITAT_APP_ID = process.env.HUBITAT_APP_ID || '';
const HUBITAT_ACCESS_TOKEN = process.env.HUBITAT_ACCESS_TOKEN || '';
const THERMOSTAT_NAMES = (process.env.THERMOSTAT_NAMES || '').split(',').map(s => s.trim()).filter(Boolean);

let cookieData = null;
let csrf = '';
let bearerToken = '';
let downchannelClient = null;
let downchannelStream = null;
let reconnectTimer = null;
let isShuttingDown = false;
// Thermostat endpoints cached at startup (and refreshed on discovery directives).
// This avoids calling listEndpoints (all 439 devices) on every downchannel event.
let cachedThermostats = null;
const COOKIE_REFRESH_INTERVAL_MS = 12 * 60 * 60 * 1000; // 12 hours
let cookieRefreshTimer = null;
// Last-known state per endpoint ID ΓÇö used to suppress no-op pushes to Hubitat.
const lastKnownState = new Map();

// Returns only thermostats whose state changed since last push, and updates the cache.
function filterChanged(payload) {
  const changed = [];
  for (const t of payload) {
    const prev = lastKnownState.get(t.endpointId);
    if (!prev ||
        prev.mode !== t.mode ||
        prev.currentTemp !== t.currentTemp ||
        prev.lowerSetpoint !== t.lowerSetpoint ||
        prev.upperSetpoint !== t.upperSetpoint) {
      lastKnownState.set(t.endpointId, { mode: t.mode, currentTemp: t.currentTemp, lowerSetpoint: t.lowerSetpoint, upperSetpoint: t.upperSetpoint });
      changed.push(t);
    }
  }
  return changed;
}

function log(level, ...args) {
  const levels = { debug: 0, info: 1, warn: 2, error: 3 };
  const current = levels[LOG_LEVEL] ?? 1;
  const msg = levels[level];
  if ((msg ?? 1) >= current) {
    const prefix = `[${new Date().toISOString()}] [${level.toUpperCase()}]`;
    console.log(prefix, ...args);
  }
}

function extractBearerToken(cookieStr) {
  const m = cookieStr.match(/(?:^|;\s*)at-main=([^;]+)/i);
  return m ? decodeURIComponent(m[1]) : '';
}

function extractCsrf(cookieStr) {
  const m = cookieStr.match(/(?:^|;\s*)csrf=([^;]+)/i);
  return m ? decodeURIComponent(m[1]) : '';
}

async function fetchJson(url, options = {}) {
  return new Promise((resolve, reject) => {
    const u = new URL(url);
    const lib = u.protocol === 'https:' ? https : http;
    const opts = {
      method: options.method || 'GET',
      headers: options.headers || {}
    };
    if (options.body) {
      opts.headers['Content-Length'] = Buffer.byteLength(options.body);
      if (!opts.headers['Content-Type']) opts.headers['Content-Type'] = 'application/json';
    }
    const req = lib.request(url, opts, (res) => {
      let data = '';
      res.on('data', (ch) => { data += ch; });
      res.on('end', () => {
        if (res.statusCode && res.statusCode >= 400) {
          const err = new Error(`HTTP ${res.statusCode}: ${data.slice(0, 200)}`);
          err.status = res.statusCode;
          reject(err);
          return;
        }
        try {
          resolve(JSON.parse(data || '{}'));
        } catch {
          log('warn', 'fetchJson: non-JSON response:', data.slice(0, 200));
          resolve({});
        }
      });
    });
    req.on('error', reject);
    if (options.body) req.write(options.body);
    req.end();
  });
}

async function fetchCookieFromCookieServer() {
  if (!COOKIE_SERVER_URL) throw new Error('Set COOKIE_SERVER_URL (Hubitat /cookie or Echo Speaks /cookieData)');
  const res = await fetchJson(COOKIE_SERVER_URL);
  const cd = res.cookieData || res;
  const cookie = cd.localCookie || cd.cookie;
  const csrfVal = cd.csrf || '';
  if (!cookie) throw new Error('Cookie server returned no cookie or localCookie');
  return { cookie, csrf: csrfVal };
}

async function refreshCookie() {
  try {
    const data = await fetchCookieFromCookieServer();
    cookieData = data.cookie;
    csrf = data.csrf || extractCsrf(cookieData);
    bearerToken = extractBearerToken(cookieData);
    if (!bearerToken) throw new Error('Could not extract Bearer token (at-main) from cookie');
    log('info', 'Cookie refreshed successfully');
    return true;
  } catch (e) {
    log('error', 'Cookie refresh failed:', e.message);
    return false;
  }
}

// Wraps fetchJson for Alexa API calls: rebuilds auth headers and retries once on 401.
async function fetchAlexaJson(url, body) {
  const makeOpts = () => {
    const hdrs = alexaHeaders();
    return { method: 'POST', headers: { ...hdrs, 'Content-Length': Buffer.byteLength(body) }, body };
  };
  try {
    return await fetchJson(url, makeOpts());
  } catch (e) {
    if (e.status === 401) {
      log('info', 'Alexa API returned 401 ΓÇö refreshing cookie and retrying');
      const ok = await refreshCookie();
      if (!ok) throw new Error('Cookie refresh failed after 401');
      return await fetchJson(url, makeOpts());
    }
    throw e;
  }
}

function scheduleCookieRefresh() {
  if (cookieRefreshTimer) clearInterval(cookieRefreshTimer);
  cookieRefreshTimer = setInterval(async () => {
    log('info', 'Periodic cookie refresh');
    const ok = await refreshCookie();
    // If cookie changed, reconnect downchannel so it uses the new bearer token.
    if (ok && downchannelStream) {
      log('info', 'Cookie refreshed ΓÇö reconnecting downchannel with new token');
      connectDownchannel();
    }
  }, COOKIE_REFRESH_INTERVAL_MS);
}

function alexaHeaders() {
  return {
    'Cookie': cookieData,
    'csrf': csrf,
    'Accept': 'application/json',
    'Content-Type': 'application/json',
    'User-Agent': 'Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36',
    'Referer': 'https://alexa.amazon.com/',
    'Origin': 'https://alexa.amazon.com'
  };
}

// Discover which endpoints are thermostats and cache them.
// Called once at startup and whenever Alexa pushes a Discovery/AddOrUpdateReport directive.
async function refreshThermostatEndpoints() {
  if (!cookieData || !bearerToken) {
    log('warn', 'refreshThermostatEndpoints: cookie not loaded');
    return;
  }
  const body = JSON.stringify({
    query: '{ listEndpoints(listEndpointsInput: {}) { endpoints { id friendlyName displayCategories { primary { value } } } } }'
  });
  const listRes = await fetchAlexaJson('https://alexa.amazon.com/nexus/v1/graphql', body);
  const endpoints = listRes?.data?.listEndpoints?.endpoints || [];
  log('debug', `listEndpoints returned ${endpoints.length} endpoint(s):`,
    endpoints.map(ep => `"${ep.friendlyName}" [${ep.displayCategories?.primary?.value || 'NO_CAT'}]`).join(', ') || '(none)');
  const thermoCategories = ['THERMOSTAT', 'TEMPERATURE_SENSOR'];
  let thermostats;
  if (THERMOSTAT_NAMES.length) {
    // When names are explicitly configured, match by name across ALL endpoints ΓÇö
    // don't require a THERMOSTAT category (Alexa sometimes reports these as INTERIOR_BLIND etc.)
    // Trim names to handle trailing spaces in Alexa's friendly names.
    // When multiple endpoints share the same name, prefer THERMOSTAT/TEMPERATURE_SENSOR category.
    const targetNames = THERMOSTAT_NAMES.map(n => n.trim().toLowerCase());
    const matched = endpoints.filter(ep => targetNames.includes((ep.friendlyName || '').trim().toLowerCase()));
    const deduped = new Map();
    for (const ep of matched) {
      const key = (ep.friendlyName || '').trim().toLowerCase();
      const cat = (ep.displayCategories?.primary?.value || '').toUpperCase();
      const existing = deduped.get(key);
      const existingCat = existing ? (existing.displayCategories?.primary?.value || '').toUpperCase() : '';
      if (!existing || (thermoCategories.includes(cat) && !thermoCategories.includes(existingCat))) {
        deduped.set(key, ep);
      }
    }
    thermostats = [...deduped.values()];
    log('debug', `THERMOSTAT_NAMES filter on all endpoints: found ${thermostats.length} of ${targetNames.length} configured names`);
    if (!thermostats.length) {
      log('warn', `No endpoints matched THERMOSTAT_NAMES=[${THERMOSTAT_NAMES.join(', ')}]. Falling back to category filter.`);
      thermostats = endpoints.filter(ep =>
        thermoCategories.includes((ep.displayCategories?.primary?.value || '').toUpperCase()));
      log('warn', `Fallback category filter: ${thermostats.length} thermostat(s)`);
    }
  } else {
    thermostats = endpoints.filter(ep => {
      const cat = (ep.displayCategories?.primary?.value || '').toUpperCase();
      const name = (ep.friendlyName || '').toLowerCase();
      return thermoCategories.includes(cat) || name.includes('thermostat');
    });
    log('debug', `Category/name filter: ${thermostats.length} thermostat(s)`);
  }
  cachedThermostats = thermostats;
  log('info', `Cached ${thermostats.length} thermostat endpoint(s): ${thermostats.map(t => t.friendlyName).join(', ') || '(none)'}`);
}

// Fetch current state for the cached thermostat endpoints.
// Does NOT call listEndpoints for discovery ΓÇö that's done once at startup.
async function fetchThermostatState() {
  if (!cookieData || !bearerToken) {
    log('warn', 'fetchThermostatState: cookie not loaded, returning empty. Check COOKIE_SERVER_URL and /ping.');
    return [];
  }
  if (!cachedThermostats) {
    log('info', 'Thermostat endpoints not cached yet ΓÇö running discovery now');
    await refreshThermostatEndpoints();
  }
  const thermostats = cachedThermostats || [];
  if (!thermostats.length) return [];
  const ids = thermostats.map(t => `"${t.id}"`).join(', ');
  const stateBody = JSON.stringify({
    query: `{ listEndpoints(listEndpointsInput: { endpointIds: [${ids}] }) { endpoints { id features { name properties { name ... on ThermostatMode { value } ... on Setpoint { value { value scale } } ... on TemperatureSensor { value { value scale } } } } } } }`
  });
  const stateRes = await fetchAlexaJson('https://alexa.amazon.com/nexus/v1/graphql', stateBody);
  const stateEndpoints = stateRes?.data?.listEndpoints?.endpoints || [];
  const payload = [];
  for (const ep of stateEndpoints) {
    const state = { mode: 'off', currentTemp: null, target: null, lowerSetpoint: null, upperSetpoint: null };
    for (const feat of ep.features || []) {
      for (const prop of feat.properties || []) {
        const v = prop.value;
        const val = v && typeof v === 'object' && 'value' in v ? v.value : v;
        const scale = v && typeof v === 'object' ? v.scale : null;
        const numVal = typeof val === 'number' ? val : (val != null ? parseFloat(String(val).replace(/[^\d.]/g, '')) : null);
        const display = val != null ? (scale ? `${val}┬░ ${(scale || '').slice(0, 1)}` : `${val}`) : null;
        switch (prop.name) {
          case 'thermostatMode': state.mode = (val || 'OFF').toString().toLowerCase(); break;
          case 'temperature': state.currentTemp = display; break;
          case 'targetSetpoint': state.target = display; if (numVal != null) { state.lowerSetpoint = numVal; state.upperSetpoint = numVal; } break;
          case 'lowerSetpoint': state.lowerSetpoint = numVal; break;
          case 'upperSetpoint': state.upperSetpoint = numVal; break;
        }
      }
    }
    if (state.lowerSetpoint != null && state.upperSetpoint != null) state.target = `${state.lowerSetpoint}┬░ΓÇô${state.upperSetpoint}┬░`;
    const t = thermostats.find(x => x.id === ep.id);
    if (t) payload.push({ name: t.friendlyName, endpointId: ep.id, ...state });
  }
  return payload;
}

async function pushToHubitat(payload) {
  if (!HUBITAT_URL || !HUBITAT_APP_ID || !HUBITAT_ACCESS_TOKEN || !payload.length) return;
  const url = `${HUBITAT_URL}/apps/api/${HUBITAT_APP_ID}/statusreportfromtheapp?access_token=${HUBITAT_ACCESS_TOKEN}`;
  const body = JSON.stringify(payload);
  return new Promise((resolve, reject) => {
    const u = new URL(url);
    const lib = u.protocol === 'https:' ? https : http;
    const req = lib.request(url, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', 'Content-Length': Buffer.byteLength(body) }
    }, (res) => {
      let data = '';
      res.on('data', (ch) => { data += ch; });
      res.on('end', () => resolve());
    });
    req.on('error', (e) => { log('error', 'Hubitat callback failed:', e.message); reject(e); });
    req.write(body);
    req.end();
  });
}

// Namespaces (lowercased prefix match) that indicate thermostat state may have changed.
// Anything else (audio, notifications, speech, etc.) is ignored.
const THERMOSTAT_NAMESPACES = [
  'alexa.thermostatcontroller',
  'alexa.temperaturesensor',
  'alexa.endpointhealth',
  'alexa.connectedhome.control',
  'alexa.connectedhome.query',
  'alexa.connectedhome.discovery',
  'alexa.discovery',
];

function isThermostatDirective(directive) {
  const ns = (
    directive?.directive?.header?.namespace ||
    directive?.header?.namespace ||
    directive?.namespace || ''
  ).toLowerCase();

  // Always handle discovery/device-list changes
  if (/discovery|addorupdate/i.test(ns)) return true;

  // Namespace whitelist
  if (THERMOSTAT_NAMESPACES.some(n => ns.startsWith(n))) return true;

  // If we have cached endpoints, check whether this directive targets one of them
  const endpointId =
    directive?.directive?.endpoint?.endpointId ||
    directive?.endpoint?.endpointId || '';
  if (endpointId && cachedThermostats?.some(t => t.id === endpointId)) return true;

  // No namespace at all ΓÇö process to be safe (e.g. keepalive ping objects)
  if (!ns) return true;

  log('debug', `Skipping non-thermostat directive: ${ns}`);
  return false;
}

async function onDirectiveReceived(directive) {
  if (!isThermostatDirective(directive)) return;

  // If Alexa is reporting a device list change, refresh our cached endpoint IDs.
  // State directives (mode/temp changes) only need the state query ΓÇö no re-discovery.
  const namespace = directive?.directive?.header?.namespace
    || directive?.header?.namespace
    || directive?.namespace
    || '';
  const isDiscovery = /discovery|addorupdate/i.test(namespace);
  if (isDiscovery) {
    log('info', `Discovery directive received (${namespace}) ΓÇö refreshing thermostat endpoint cache`);
    try { await refreshThermostatEndpoints(); } catch (e) { log('warn', 'Endpoint refresh failed:', e.message); }
  }
  log('debug', 'Directive received, fetching thermostat state');
  try {
    const payload = await fetchThermostatState();
    const changed = filterChanged(payload);
    if (changed.length) {
      await pushToHubitat(changed);
      log('info', `Pushed ${changed.length} thermostat(s) to Hubitat (${payload.length - changed.length} unchanged)`);
    } else {
      log('debug', 'No state changes ΓÇö skipping Hubitat push');
    }
  } catch (e) {
    log('warn', 'State fetch/push failed:', e.message);
  }
}

function connectDownchannel() {
  if (isShuttingDown || !bearerToken) return;
  if (downchannelClient) {
    try { downchannelClient.close(); } catch {}
    downchannelClient = null;
    downchannelStream = null;
  }
  log('info', `Connecting to Alexa downchannel (${BOB_HOST})...`);
  const client = http2.connect(`https://${BOB_HOST}`);
  downchannelClient = client;
  client.on('error', (err) => {
    log('warn', 'Downchannel client error:', err.message);
    scheduleReconnect();
  });
  client.on('close', () => {
    downchannelClient = null;
    downchannelStream = null;
    if (!isShuttingDown) scheduleReconnect();
  });
  const headers = {
    ':path': DIRECTIVES_PATH,
    ':method': 'GET',
    'authorization': `Bearer ${bearerToken}`,
    'user-agent': 'Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36',
    'accept': 'application/json'
  };
  const req = client.request(headers);
  downchannelStream = req;
  let buffer = '';
  req.on('response', (headers) => {
    const status = headers[':status'];
    if (status === 401) {
      log('warn', 'Downchannel got 401 ΓÇö refreshing cookie then reconnecting');
      refreshCookie().then((ok) => {
        if (ok) connectDownchannel();
        else scheduleReconnect();
      });
    } else if (status !== 200) {
      log('warn', 'Downchannel response status:', status);
    }
  });
  req.on('data', (chunk) => {
    buffer += chunk.toString();
    const lines = buffer.split(/\r?\n/);
    buffer = lines.pop() || '';
    for (const line of lines) {
      if (!line.trim()) continue;
      try {
        const obj = JSON.parse(line);
        if (obj) {
          log('debug', 'Directive:', JSON.stringify(obj).slice(0, 200));
          onDirectiveReceived(obj);
        }
      } catch {}
    }
  });
  req.on('end', () => {
    downchannelStream = null;
    if (!isShuttingDown) scheduleReconnect();
  });
  req.on('error', (err) => {
    log('warn', 'Downchannel stream error:', err.message);
    scheduleReconnect();
  });
  req.end();
}

function scheduleReconnect() {
  if (reconnectTimer) clearTimeout(reconnectTimer);
  reconnectTimer = setTimeout(() => {
    reconnectTimer = null;
    if (isShuttingDown) return;
    log('info', 'Reconnecting downchannel...');
    connectDownchannel();
  }, 10000);
}

async function startDownchannel() {
  const ok = await refreshCookie();
  if (!ok) {
    log('error', 'Cannot start without valid cookie. Check COOKIE_SERVER_URL.');
    return;
  }
  // Discover thermostats once at startup so the downchannel push path never
  // needs to call listEndpoints(all) again ΓÇö only the targeted state query runs
  // on each directive. The cache is refreshed automatically on Discovery directives.
  await refreshThermostatEndpoints().catch(e => log('warn', 'Initial endpoint discovery failed:', e.message));
  scheduleCookieRefresh();
  connectDownchannel();
}

const httpServer = http.createServer((req, res) => {
  const u = new URL(req.url || '/', `http://localhost`);
  const path = u.pathname;
  res.setHeader('Content-Type', 'application/json');
  if (path === '/ping' || path === '/health') {
    res.statusCode = 200;
    res.end(JSON.stringify({
      status: 'ok',
      downchannel: !!downchannelStream,
      cookie: !!cookieData
    }));
    return;
  }
  if (path === '/refresh') {
    refreshCookie().then((ok) => {
      res.statusCode = ok ? 200 : 500;
      res.end(JSON.stringify({ ok }));
    });
    return;
  }
  if (path === '/debug') {
    if (!cookieData || !bearerToken) {
      res.statusCode = 503;
      res.end(JSON.stringify({ ok: false, error: 'Cookie not loaded.' }));
      return;
    }
    const listBody = JSON.stringify({
      query: '{ listEndpoints(listEndpointsInput: {}) { endpoints { id friendlyName displayCategories { primary { value } } } } }'
    });
    const headers = {
      'Cookie': cookieData, 'csrf': csrf, 'Accept': 'application/json',
      'Content-Type': 'application/json',
      'User-Agent': 'Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36',
      'Referer': 'https://alexa.amazon.com/', 'Origin': 'https://alexa.amazon.com'
    };
    fetchJson('https://alexa.amazon.com/nexus/v1/graphql', {
      method: 'POST',
      headers: { ...headers, 'Content-Length': Buffer.byteLength(listBody) },
      body: listBody
    }).then(listRes => {
      const endpoints = listRes?.data?.listEndpoints?.endpoints || [];
      const thermoCategories = ['THERMOSTAT', 'TEMPERATURE_SENSOR'];
      res.statusCode = 200;
      res.end(JSON.stringify({
        ok: true,
        thermostat_names_env: THERMOSTAT_NAMES,
        total_endpoints: endpoints.length,
        endpoints: endpoints.map(ep => ({
          friendlyName: ep.friendlyName,
          category: ep.displayCategories?.primary?.value || null,
          matchesCategoryFilter: thermoCategories.includes((ep.displayCategories?.primary?.value || '').toUpperCase()),
          matchesNameFilter: (ep.friendlyName || '').toLowerCase().includes('thermostat')
        }))
      }));
    }).catch(e => {
      res.statusCode = 500;
      res.end(JSON.stringify({ ok: false, error: e.message }));
    });
    return;
  }
  if (path === '/poll') {
    if (!cookieData || !bearerToken) {
      res.statusCode = 503;
      res.end(JSON.stringify({ ok: false, error: 'Cookie not loaded. Call /refresh or check COOKIE_SERVER_URL.' }));
      return;
    }
    fetchThermostatState().then(async (payload) => {
      const changed = filterChanged(payload);
      if (changed.length) {
        pushToHubitat(changed).catch(e => log('warn', 'Hubitat push failed:', e.message));
      }
      res.statusCode = 200;
      res.end(JSON.stringify({ ok: true, thermostats: payload, pushed: changed.length }));
    }).catch(e => {
      log('error', 'Poll failed:', e.message);
      res.statusCode = 500;
      res.end(JSON.stringify({ error: e.message }));
    });
    return;
  }
  res.statusCode = 404;
  res.end(JSON.stringify({ error: 'Not found' }));
});

httpServer.listen(PORT, () => {
  log('info', `Alexa Downchannel Server listening on port ${PORT}`);
  log('info', `Ping: http://localhost:${PORT}/ping`);
  if (!COOKIE_SERVER_URL) log('warn', 'COOKIE_SERVER_URL not set - cookie fetch will fail');
  if (!HUBITAT_URL || !HUBITAT_APP_ID || !HUBITAT_ACCESS_TOKEN) log('warn', 'HUBITAT_* not set - callback push will fail');
  startDownchannel();
});

process.on('SIGINT', () => {
  isShuttingDown = true;
  if (reconnectTimer) clearTimeout(reconnectTimer);
  if (cookieRefreshTimer) clearInterval(cookieRefreshTimer);
  if (downchannelClient) downchannelClient.close();
  httpServer.close();
  process.exit(0);
});
