#!/usr/bin/env node
/**
 * Alexa Thermostat Polling Server
 *
 * Periodically fetches thermostat state via Alexa's GraphQL API and pushes
 * updates to Hubitat. Cookie is fetched from COOKIE_SERVER_URL (Hubitat /cookie
 * or Echo Speaks /cookieData).
 */

const http = require('http');
const https = require('https');
const { URL } = require('url');

const PORT = parseInt(process.env.PORT || '3099', 10);
const LOG_LEVEL = (process.env.LOG_LEVEL || 'info').toLowerCase();
const COOKIE_SERVER_URL = (process.env.COOKIE_SERVER_URL || '').replace(/\/$/, '');
const HUBITAT_URL = (process.env.HUBITAT_URL || '').replace(/\/$/, '');
const HUBITAT_APP_ID = process.env.HUBITAT_APP_ID || '';
const HUBITAT_ACCESS_TOKEN = process.env.HUBITAT_ACCESS_TOKEN || '';
const THERMOSTAT_NAMES = (process.env.THERMOSTAT_NAMES || '').split(',').map(s => s.trim()).filter(Boolean);
const POLL_INTERVAL_MS = parseInt(process.env.POLL_INTERVAL_MS || '0', 10); // 0 = disabled

let cookieData = null;
let csrf = '';
let bearerToken = '';
let isShuttingDown = false;
// Thermostat endpoints cached at startup and refreshed periodically.
let cachedThermostats = null;
const COOKIE_REFRESH_INTERVAL_MS = 12 * 60 * 60 * 1000; // 12 hours
let cookieRefreshTimer = null;
// Last-known state per endpoint ID — used to suppress no-op pushes to Hubitat.
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
      log('info', 'Alexa API returned 401 — refreshing cookie and retrying');
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
    await refreshCookie();
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
// Called once at startup and on /poll if the cache is empty.
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
  if (!endpoints.length) {
    log('warn', 'listEndpoints returned 0 endpoints — cookie may be expired. Keeping existing cache:', cachedThermostats?.map(t => t.friendlyName).join(', ') || '(none)');
    return;
  }
  log('debug', `listEndpoints returned ${endpoints.length} endpoint(s):`,
    endpoints.map(ep => `"${ep.friendlyName}" [${ep.displayCategories?.primary?.value || 'NO_CAT'}]`).join(', ') || '(none)');
  const thermoCategories = ['THERMOSTAT', 'TEMPERATURE_SENSOR'];
  let thermostats;
  if (THERMOSTAT_NAMES.length) {
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

// Cached inline fragments for the state query, built via schema introspection.
let stateQueryFragments = null;

// Introspect ThermostatMode, Setpoint, TemperatureSensor to discover the exact
// field names for each concrete type. Builds and caches the inline fragments
// so the state query is always schema-correct.
async function buildStateQueryFragments() {
  log('info', 'Introspecting GraphQL schema for thermostat property types...');
  const typeNames = ['ThermostatMode', 'Setpoint', 'TemperatureSensor'];
  const aliases = typeNames.map((n, i) => `t${i}: __type(name: "${n}") { fields { name type { kind name ofType { name kind ofType { name kind } } } } }`).join(' ');
  const introspectBody = JSON.stringify({ query: `{ ${aliases} }` });
  const res = await fetchAlexaJson('https://alexa.amazon.com/nexus/v1/graphql', introspectBody);
  if (!res?.data) {
    log('warn', 'Schema introspection failed:', JSON.stringify(res).slice(0, 300));
    return null;
  }

  const SKIP = new Set(['name', 'error', 'accuracy', 'type']);
  function unwrap(t) { return (!t || t.kind === 'NON_NULL' || t.kind === 'LIST') ? unwrap(t?.ofType) : t; }
  const nestedTypeNames = new Set();
  typeNames.forEach((_, i) => {
    for (const f of res.data[`t${i}`]?.fields || []) {
      if (SKIP.has(f.name)) continue;
      const base = unwrap(f.type);
      if (base?.kind === 'OBJECT' && base.name) nestedTypeNames.add(base.name);
    }
  });

  const nestedSchema = {};
  if (nestedTypeNames.size) {
    const nested = [...nestedTypeNames];
    const nestedAliases = nested.map((n, i) => `n${i}: __type(name: "${n}") { fields { name } }`).join(' ');
    const nestedRes = await fetchAlexaJson('https://alexa.amazon.com/nexus/v1/graphql', JSON.stringify({ query: `{ ${nestedAliases} }` }));
    nested.forEach((n, i) => { nestedSchema[n] = nestedRes?.data?.[`n${i}`]; });
  }

  const fragments = [];
  typeNames.forEach((typeName, i) => {
    const typeInfo = res.data[`t${i}`];
    if (!typeInfo?.fields) return;
    const fields = typeInfo.fields
      .filter(f => !SKIP.has(f.name))
      .map(f => {
        const base = unwrap(f.type);
        if (base?.kind === 'OBJECT' && base.name) {
          const subFields = (nestedSchema[base.name]?.fields || []).map(nf => nf.name);
          return subFields.length ? `${f.name} { ${subFields.join(' ')} }` : `${f.name} { value scale }`;
        }
        return f.name;
      });
    if (fields.length) fragments.push(`... on ${typeName} { ${fields.join(' ')} }`);
  });

  log('info', 'State query fragments:', fragments.join(' | ') || '(none)');
  return fragments.length ? fragments : null;
}

// Fetch current state for the cached thermostat endpoints.
async function fetchThermostatState() {
  if (!cookieData || !bearerToken) {
    log('warn', 'fetchThermostatState: cookie not loaded, returning empty. Check COOKIE_SERVER_URL and /ping.');
    return [];
  }
  if (!cachedThermostats || !cachedThermostats.length) {
    log('info', 'Thermostat endpoints not cached yet — running discovery now');
    await refreshThermostatEndpoints();
  }
  const thermostats = cachedThermostats || [];
  if (!thermostats.length) return [];
  const ids = thermostats.map(t => `"${t.id}"`).join(', ');
  if (!stateQueryFragments) {
    stateQueryFragments = await buildStateQueryFragments().catch(e => {
      log('warn', 'Fragment build failed, using fallback:', e.message);
      return null;
    });
  }
  const fragments = stateQueryFragments || ['... on ThermostatMode { value }', '... on Setpoint { value { value scale } }', '... on TemperatureSensor { value { value scale } }'];
  const stateBody = JSON.stringify({
    query: `{ listEndpoints(listEndpointsInput: { endpointIds: [${ids}] }) { endpoints { id features { name properties { name ${fragments.join(' ')} } } } } }`
  });
  const stateRes = await fetchAlexaJson('https://alexa.amazon.com/nexus/v1/graphql', stateBody);
  const stateEndpoints = stateRes?.data?.listEndpoints?.endpoints || [];
  if (!stateEndpoints.length) {
    log('warn', 'State query returned 0 endpoints. Raw response:', JSON.stringify(stateRes).slice(0, 500));
  } else {
    log('debug', 'Raw state response:', JSON.stringify(stateRes?.data?.listEndpoints?.endpoints).slice(0, 2000));
  }
  const payload = [];
  for (const ep of stateEndpoints) {
    const state = { mode: 'off', currentTemp: null, target: null, lowerSetpoint: null, upperSetpoint: null };
    log('debug', `Raw properties for endpoint ${ep.id}:`, JSON.stringify(ep.features?.flatMap(f => f.properties || []) || []));
    for (const feat of ep.features || []) {
      for (const prop of feat.properties || []) {
        const v = prop.value;
        const val = v && typeof v === 'object' && 'value' in v ? v.value : v;
        const scale = v && typeof v === 'object' ? v.scale : null;
        const numVal = typeof val === 'number' ? val : (val != null ? parseFloat(String(val).replace(/[^\d.]/g, '')) : null);
        const display = val != null ? (scale ? `${val} ${(scale || '').slice(0, 1)}` : `${val}`) : null;
        switch (prop.name) {
          case 'thermostatMode': state.mode = (prop.thermostatModeValue || val || 'OFF').toString().toLowerCase(); break;
          case 'temperature': state.currentTemp = display; break;
          case 'targetSetpoint': state.target = display; if (numVal != null) { state.lowerSetpoint = numVal; state.upperSetpoint = numVal; } break;
          case 'lowerSetpoint': state.lowerSetpoint = numVal; break;
          case 'upperSetpoint': state.upperSetpoint = numVal; break;
        }
      }
    }
    if (state.lowerSetpoint != null && state.upperSetpoint != null) state.target = `${state.lowerSetpoint}-${state.upperSetpoint}`;
    const t = thermostats.find(x => x.id === ep.id);
    if (t) payload.push({ name: t.friendlyName, endpointId: ep.id, ...state });
  }
  return payload;
}

async function pushToHubitat(payload) {
  if (!HUBITAT_URL || !HUBITAT_APP_ID || !HUBITAT_ACCESS_TOKEN || !payload.length) return;
  const url = `${HUBITAT_URL}/apps/api/${HUBITAT_APP_ID}/statusreportfromtheapp?access_token=${HUBITAT_ACCESS_TOKEN}`;
  const body = JSON.stringify(payload);
  log('info', `Hubitat push → POST ${HUBITAT_URL}/apps/api/${HUBITAT_APP_ID}/statusreportfromtheapp`);
  log('debug', 'Hubitat push payload:', body);
  return new Promise((resolve, reject) => {
    const u = new URL(url);
    const lib = u.protocol === 'https:' ? https : http;
    const req = lib.request(url, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', 'Content-Length': Buffer.byteLength(body) }
    }, (res) => {
      let data = '';
      res.on('data', (ch) => { data += ch; });
      res.on('end', () => {
        log('debug', `Hubitat response: HTTP ${res.statusCode} — ${data.slice(0, 500) || '(empty body)'}`);
        resolve();
      });
    });
    req.on('error', (e) => { log('error', 'Hubitat callback failed:', e.message); reject(e); });
    req.write(body);
    req.end();
  });
}

let autoPollTimer = null;

function scheduleAutoPoll() {
  if (!POLL_INTERVAL_MS || isShuttingDown) return;
  autoPollTimer = setTimeout(async () => {
    autoPollTimer = null;
    try {
      const payload = await fetchThermostatState();
      const changed = filterChanged(payload);
      log('info', `Auto-poll: ${payload.length} thermostat(s), ${changed.length} changed`);
      if (changed.length) await pushToHubitat(changed).catch(e => log('warn', 'Hubitat push failed:', e.message));
    } catch (e) {
      log('warn', 'Auto-poll failed:', e.message);
    }
    scheduleAutoPoll();
  }, POLL_INTERVAL_MS);
}

async function start() {
  const ok = await refreshCookie();
  if (!ok) {
    log('error', 'Cannot start without valid cookie. Check COOKIE_SERVER_URL.');
    return;
  }
  await refreshThermostatEndpoints().catch(e => log('warn', 'Initial endpoint discovery failed:', e.message));
  stateQueryFragments = await buildStateQueryFragments().catch(e => { log('warn', 'Initial schema introspection failed:', e.message); return null; });
  scheduleCookieRefresh();
  if (POLL_INTERVAL_MS) {
    log('info', `Auto-poll enabled every ${POLL_INTERVAL_MS / 1000}s`);
    scheduleAutoPoll();
  }
}

const httpServer = http.createServer((req, res) => {
  const u = new URL(req.url || '/', `http://localhost`);
  const path = u.pathname;
  res.setHeader('Content-Type', 'application/json');
  if (path === '/ping' || path === '/health') {
    res.statusCode = 200;
    res.end(JSON.stringify({ status: 'ok', cookie: !!cookieData }));
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
    fetchAlexaJson('https://alexa.amazon.com/nexus/v1/graphql', listBody).then(listRes => {
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
    log('info', 'Poll request received');
    if (!cookieData || !bearerToken) {
      res.statusCode = 503;
      res.end(JSON.stringify({ ok: false, error: 'Cookie not loaded. Call /refresh or check COOKIE_SERVER_URL.' }));
      return;
    }
    fetchThermostatState().then(async (payload) => {
      const changed = filterChanged(payload);
      log('info', `Poll: ${payload.length} thermostat(s) fetched, ${changed.length} changed, pushing to Hubitat: ${HUBITAT_URL ? 'yes' : 'no (HUBITAT_URL not set)'}`);
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
  log('info', `Alexa Thermostat Server listening on port ${PORT}`);
  log('info', `Ping: http://localhost:${PORT}/ping`);
  if (!COOKIE_SERVER_URL) log('warn', 'COOKIE_SERVER_URL not set - cookie fetch will fail');
  if (!HUBITAT_URL || !HUBITAT_APP_ID || !HUBITAT_ACCESS_TOKEN) log('warn', 'HUBITAT_* not set - callback push will fail');
  start();
});

process.on('SIGINT', () => {
  isShuttingDown = true;
  if (autoPollTimer) clearTimeout(autoPollTimer);
  if (cookieRefreshTimer) clearInterval(cookieRefreshTimer);
  httpServer.close();
  process.exit(0);
});
