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
const SKIP_DOWNCHANNEL = /^(1|true|yes)$/i.test(process.env.SKIP_DOWNCHANNEL || '');
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
let downchannel403Count = 0;
let downchannel403Backoff = false;

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
        try {
          resolve(JSON.parse(data || '{}'));
        } catch {
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

async function fetchThermostatState() {
  if (!cookieData || !bearerToken) return [];
  const headers = {
    'Cookie': cookieData,
    'csrf': csrf,
    'Accept': 'application/json',
    'Content-Type': 'application/json',
    'User-Agent': 'Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36',
    'Referer': 'https://alexa.amazon.com/',
    'Origin': 'https://alexa.amazon.com'
  };
  const listBody = JSON.stringify({
    query: '{ listEndpoints(listEndpointsInput: {}) { endpoints { id friendlyName displayCategories { primary { value } } } } }'
  });
  const listRes = await fetchJson('https://alexa.amazon.com/nexus/v1/graphql', {
    method: 'POST',
    headers: { ...headers, 'Content-Length': Buffer.byteLength(listBody) },
    body: listBody
  });
  const endpoints = listRes?.data?.listEndpoints?.endpoints || [];
  const thermoCategories = ['THERMOSTAT', 'TEMPERATURE_SENSOR'];
  let thermostats = endpoints.filter(ep => {
    const cat = (ep.displayCategories?.primary?.value || '').toUpperCase();
    const name = (ep.friendlyName || '').toLowerCase();
    return thermoCategories.includes(cat) || name.includes('thermostat');
  });
  if (THERMOSTAT_NAMES.length) {
    const targetNames = THERMOSTAT_NAMES.map(n => n.toLowerCase());
    thermostats = thermostats.filter(t => targetNames.includes((t.friendlyName || '').toLowerCase()));
  }
  if (!thermostats.length) thermostats = endpoints.filter(ep =>
    thermoCategories.includes((ep.displayCategories?.primary?.value || '').toUpperCase()));
  if (!thermostats.length) return [];
  const ids = thermostats.map(t => `"${t.id}"`).join(', ');
  const stateBody = JSON.stringify({
    query: `{ listEndpoints(listEndpointsInput: { endpointIds: [${ids}] }) { endpoints { id features { name properties { name ... on ThermostatMode { value } ... on Setpoint { value { value scale } } ... on TemperatureSensor { value { value scale } } } } } } }`
  });
  const stateRes = await fetchJson('https://alexa.amazon.com/nexus/v1/graphql', {
    method: 'POST',
    headers: { ...headers, 'Content-Length': Buffer.byteLength(stateBody) },
    body: stateBody
  });
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
        const display = val != null ? (scale ? `${val}° ${(scale || '').slice(0, 1)}` : `${val}`) : null;
        switch (prop.name) {
          case 'thermostatMode': state.mode = (val || 'OFF').toString().toLowerCase(); break;
          case 'temperature': state.currentTemp = display; break;
          case 'targetSetpoint': state.target = display; if (numVal != null) { state.lowerSetpoint = numVal; state.upperSetpoint = numVal; } break;
          case 'lowerSetpoint': state.lowerSetpoint = numVal; break;
          case 'upperSetpoint': state.upperSetpoint = numVal; break;
        }
      }
    }
    if (state.lowerSetpoint != null && state.upperSetpoint != null) state.target = `${state.lowerSetpoint}°–${state.upperSetpoint}°`;
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

async function onDirectiveReceived() {
  log('debug', 'Directive received, fetching thermostat state');
  try {
    const payload = await fetchThermostatState();
    if (payload.length) {
      await pushToHubitat(payload);
      log('info', `Pushed ${payload.length} thermostat(s) to Hubitat`);
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
  let lastStatus = 0;
  let skipReconnectOnEnd = false;
  req.on('response', (headers) => {
    lastStatus = headers[':status'];
    if (lastStatus !== 200) {
      downchannel403Count++;
      if (lastStatus === 403 && downchannel403Count > 2) {
        log('warn', 'Downchannel 403 - Amazon may block this. Set SKIP_DOWNCHANNEL=1 to disable. Polling still works.');
        skipReconnectOnEnd = true;
        scheduleReconnect(300000);
      } else {
        log('warn', 'Downchannel response status:', lastStatus);
      }
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
          onDirectiveReceived();
        }
      } catch {}
    }
  });
  req.on('end', () => {
    downchannelStream = null;
    if (!isShuttingDown && !skipReconnectOnEnd) scheduleReconnect();
  });
  req.on('error', (err) => {
    log('warn', 'Downchannel stream error:', err.message);
    scheduleReconnect();
  });
  req.end();
}

function scheduleReconnect(ms = 10000) {
  if (reconnectTimer) clearTimeout(reconnectTimer);
  reconnectTimer = setTimeout(() => {
    reconnectTimer = null;
    if (isShuttingDown) return;
    log('info', 'Reconnecting downchannel...');
    connectDownchannel();
  }, ms);
}

async function startDownchannel() {
  const ok = await refreshCookie();
  if (!ok) {
    log('error', 'Cannot start without valid cookie. Check COOKIE_SERVER_URL.');
    return;
  }
  if (SKIP_DOWNCHANNEL) {
    log('info', 'Downchannel disabled (SKIP_DOWNCHANNEL=1). Polling only.');
    return;
  }
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
  if (path === '/poll') {
    fetchThermostatState().then(async (payload) => {
      if (payload.length) {
        pushToHubitat(payload).catch(e => log('warn', 'Hubitat push failed:', e.message));
      }
      res.statusCode = 200;
      res.end(JSON.stringify({ ok: true, thermostats: payload }));
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
  if (downchannelClient) downchannelClient.close();
  httpServer.close();
  process.exit(0);
});
