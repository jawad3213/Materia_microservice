#!/usr/bin/env node
// =====================================================================================================
// Security tests of the API's authentication and authorization, run by the staging CD after every deploy
// (next to the OWASP ZAP scan). Node 22, no dependencies.
//
//   BASE_URL                      where the application is served (no trailing /); Keycloak under /auth
//   TEST_EMAIL                    a valid account (the CD uses a dedicated staging account)
//   TEST_PASSWORD
//   KEYCLOAK_TEST_CLIENT_SECRET   the secret of the realm's materia-test-runner client (password grant)
//   RESTRICTED_EMAIL              optional: an account WITHOUT the admin permissions, to check that it is refused
//   RESTRICTED_PASSWORD           admin-only routes (403)
//
// Keycloak signs users in (docs/architecture/keycloak-migration.md): sessions are opened at its token endpoint.
// It changes no data. It does use the sign-in quota of the runner's address for a minute (the last test
// checks that repeated sign-ins are throttled).
//
// Local run:  BASE_URL=http://localhost TEST_EMAIL=... TEST_PASSWORD=... KEYCLOAK_TEST_CLIENT_SECRET=... \
//               node tests/security/auth-security.mjs
// =====================================================================================================
import { createHmac, randomBytes } from 'node:crypto';
import { appendFileSync } from 'node:fs';

const BASE_URL = (process.env.BASE_URL || 'http://localhost').replace(/\/+$/, '');
const { TEST_EMAIL, TEST_PASSWORD, RESTRICTED_EMAIL, RESTRICTED_PASSWORD, KEYCLOAK_TEST_CLIENT_SECRET } = process.env;
if (!TEST_EMAIL || !TEST_PASSWORD || !KEYCLOAK_TEST_CLIENT_SECRET) {
  console.error('TEST_EMAIL, TEST_PASSWORD and KEYCLOAK_TEST_CLIENT_SECRET are required');
  process.exit(2);
}
const REALM = '/auth/realms/materia/protocol/openid-connect';
const CLIENT = { client_id: 'materia-test-runner', client_secret: KEYCLOAK_TEST_CLIENT_SECRET };

// Routes that need a session; a few of each context
const PROTECTED = [
  ['GET', '/api/v1/auth/me'],
  ['GET', '/api/v1/dashboard'],
  ['GET', '/api/v1/purchase-orders'],
  ['GET', '/api/v1/purchase-requisitions'],
  ['GET', '/api/v1/masterdata/suppliers'],
  ['GET', '/api/v1/masterdata/materials'],
  ['GET', '/api/v1/invoices'],
  ['GET', '/api/v1/payments'],
  ['GET', '/api/v1/employees'],
  ['GET', '/api/v1/audit'],
  ['GET', '/api/v1/settings'],
  ['POST', '/api/v1/purchase-orders'],
];
// Admin-only routes, refused to a restricted account
const ADMIN_ONLY = [
  ['GET', '/api/v1/employees'],
  ['GET', '/api/v1/audit'],
];

const results = [];
let current = '';

function section(name) {
  current = name;
}

function record(name, ok, detail = '') {
  results.push({ section: current, name, ok, detail });
  console.log(`${ok ? 'PASS' : 'FAIL'}  [${current}] ${name}${detail ? ` - ${detail}` : ''}`);
}

async function http(method, path, { token, body, form, headers = {}, raw } = {}) {
  const init = { method, headers: { ...headers }, redirect: 'manual' };
  if (token) init.headers.Authorization = `Bearer ${token}`;
  if (form !== undefined) {
    init.body = new URLSearchParams(form).toString();
    init.headers['Content-Type'] = 'application/x-www-form-urlencoded';
  } else if (raw !== undefined) {
    init.body = raw;
    init.headers['Content-Type'] ??= 'application/json';
  } else if (body !== undefined) {
    init.body = JSON.stringify(body);
    init.headers['Content-Type'] = 'application/json';
  }
  const res = await fetch(`${BASE_URL}${path}`, init);
  const text = await res.text();
  let json;
  try {
    json = JSON.parse(text);
  } catch {
    json = undefined;
  }
  return { status: res.status, headers: res.headers, text, json };
}

const b64url = (value) => Buffer.from(typeof value === 'string' ? value : JSON.stringify(value)).toString('base64url');

function signHs512(header, payload, key) {
  const unsigned = `${b64url(header)}.${b64url(payload)}`;
  return `${unsigned}.${createHmac('sha512', key).update(unsigned).digest('base64url')}`;
}

function decodePayload(jwt) {
  return JSON.parse(Buffer.from(jwt.split('.')[1], 'base64url').toString());
}

const leaksInternals = (text) => /Exception|\tat [a-z]+\.|at com\.materia|org\.springframework|SQLState|stack/i.test(text);

/** Keycloak's token endpoint, password grant: { access_token, refresh_token } or { error }. */
async function login(email, password, extraHeaders = {}) {
  return http('POST', `${REALM}/token`, {
    form: { ...CLIENT, grant_type: 'password', username: email, password, scope: 'openid' },
    headers: extraHeaders,
  });
}

// -----------------------------------------------------------------------------------------------------
section('no session');
for (const [method, path] of PROTECTED) {
  const res = await http(method, path, method === 'POST' ? { body: {} } : {});
  record(`${method} ${path} without a token is refused`, res.status === 401, `HTTP ${res.status}`);
}
for (const path of ['/actuator/env', '/actuator/prometheus', '/actuator/heapdump', '/actuator/loggers']) {
  const res = await http('GET', path);
  const exposed = res.status === 200 && !String(res.headers.get('content-type')).includes('text/html');
  record(`${path} is not reachable from outside`, !exposed, `HTTP ${res.status} ${res.headers.get('content-type')}`);
}

// -----------------------------------------------------------------------------------------------------
section('sign-in');
const session = await login(TEST_EMAIL, TEST_PASSWORD);
const accessToken = session.json?.access_token;
const refreshToken = session.json?.refresh_token;
record('valid credentials open a session', session.status === 200 && !!accessToken, `HTTP ${session.status}`);
if (!accessToken) {
  console.error('Cannot continue without a session:', session.text.slice(0, 300));
  process.exit(1);
}

const me = await http('GET', '/api/v1/auth/me', { token: accessToken });
record('the access token opens the API', me.status === 200, `HTTP ${me.status}`);

const issued = decodePayload(accessToken);
record('the access token is short-lived (15 minutes at most)', issued.exp - issued.iat <= 900,
  `${issued.exp - issued.iat} s`);
record('the access token is meant for the API', [].concat(issued.aud).includes('materia-api'), `aud: ${issued.aud}`);
const oldSignIn = await http('POST', '/api/v1/auth/login', { body: { email: TEST_EMAIL, password: TEST_PASSWORD } });
record("identity's old sign-in endpoint is gone", oldSignIn.status !== 200 && !oldSignIn.json?.accessToken,
  `HTTP ${oldSignIn.status}`);

const wrongPassword = await login(TEST_EMAIL, `${TEST_PASSWORD}-wrong`);
const unknownAccount = await login(`nobody-${randomBytes(6).toString('hex')}@example.com`, 'Whatever-1!');
record('a wrong password is refused', wrongPassword.status === 401, `HTTP ${wrongPassword.status}`);
record(
  'a wrong password and an unknown account get the same answer (no account enumeration)',
  wrongPassword.status === unknownAccount.status &&
    (wrongPassword.json?.error_description ?? '') === (unknownAccount.json?.error_description ?? ''),
  `${wrongPassword.status} "${wrongPassword.json?.error_description}" vs ${unknownAccount.status} "${unknownAccount.json?.error_description}"`,
);
const malformed = await http('POST', `${REALM}/token`, {
  raw: 'grant_type=password&username=', headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
});
record('a malformed request is refused without internal details',
  malformed.status >= 400 && malformed.status < 500 && !leaksInternals(malformed.text), `HTTP ${malformed.status}`);
const injection = await login("' OR '1'='1' --@example.com", "' OR '1'='1");
record('SQL injection in the sign-in form does not open a session', injection.status !== 200 && !injection.json?.access_token,
  `HTTP ${injection.status}`);

// -----------------------------------------------------------------------------------------------------
section('forged tokens');
const claims = decodePayload(accessToken);
const forged = {
  'a malformed token': 'not-a-jwt',
  'an unsigned token (alg "none")': `${b64url({ alg: 'none', typ: 'JWT' })}.${b64url({ ...claims, role: 'ADMIN' })}.`,
  'a token signed with another key': signHs512({ alg: 'HS512', typ: 'JWT' }, claims, randomBytes(64)),
  'a real token whose claims were edited': (() => {
    const [header, , signature] = accessToken.split('.');
    return `${header}.${b64url({ ...claims, role: 'ADMIN', exp: Math.floor(Date.now() / 1000) + 86400 })}.${signature}`;
  })(),
  'an expired token': signHs512({ alg: 'HS512', typ: 'JWT' }, { ...claims, exp: 1 }, randomBytes(64)),
};
if (refreshToken) forged['the refresh token used as an access token'] = refreshToken;
for (const [name, token] of Object.entries(forged)) {
  const res = await http('GET', '/api/v1/auth/me', { token });
  record(`${name} is refused`, res.status === 401, `HTTP ${res.status}`);
}

// -----------------------------------------------------------------------------------------------------
section('account');
if (RESTRICTED_EMAIL && RESTRICTED_PASSWORD) {
  const restricted = await login(RESTRICTED_EMAIL, RESTRICTED_PASSWORD);
  const restrictedToken = restricted.json?.access_token;
  record('the restricted account signs in', !!restrictedToken, `HTTP ${restricted.status}`);
  for (const [method, path] of ADMIN_ONLY) {
    const res = await http(method, path, { token: restrictedToken });
    record(`${method} ${path} is refused to a non-admin`, res.status === 403, `HTTP ${res.status}`);
  }
}

// -----------------------------------------------------------------------------------------------------
section('session end');
const refresh = (token) => http('POST', `${REALM}/token`, {
  form: { ...CLIENT, grant_type: 'refresh_token', refresh_token: token },
});
if (refreshToken) {
  const refreshed = await refresh(refreshToken);
  record('a refresh token gives a new session', refreshed.status === 200 && !!refreshed.json?.access_token,
    `HTTP ${refreshed.status}`);

  const replay = await refresh(refreshToken);
  record('a refresh token cannot be used twice', replay.status === 400 || replay.status === 401, `HTTP ${replay.status}`);

  const another = (await login(TEST_EMAIL, TEST_PASSWORD)).json?.refresh_token;
  if (another) {
    await http('POST', `${REALM}/logout`, { form: { ...CLIENT, refresh_token: another } });
    const afterLogout = await refresh(another);
    record('after sign-out the refresh token is dead', afterLogout.status === 400 || afterLogout.status === 401,
      `HTTP ${afterLogout.status}`);
  }
}

// -----------------------------------------------------------------------------------------------------
section('browser protections');
const evil = 'https://evil.example';
const preflight = await http('OPTIONS', '/api/v1/auth/me', {
  headers: { Origin: evil, 'Access-Control-Request-Method': 'GET', 'Access-Control-Request-Headers': 'authorization' },
});
const allowed = preflight.headers.get('access-control-allow-origin');
record('CORS does not allow another site', allowed !== evil && allowed !== '*', `allow-origin: ${allowed}`);

const api = await http('GET', '/api/v1/auth/me', { token: accessToken });
record('API responses are not sniffed (X-Content-Type-Options: nosniff)',
  /nosniff/i.test(api.headers.get('x-content-type-options') || ''));
record('API responses are not cached (Cache-Control: no-store)', /no-store/i.test(api.headers.get('cache-control') || ''),
  `cache-control: ${api.headers.get('cache-control')}`);
record('API responses cannot be framed (X-Frame-Options)', /DENY|SAMEORIGIN/i.test(api.headers.get('x-frame-options') || ''));
const notFound = await http('GET', `/api/v1/${randomBytes(4).toString('hex')}`, { token: accessToken });
record('errors do not reveal internals', !leaksInternals(notFound.text), `HTTP ${notFound.status}`);

// -----------------------------------------------------------------------------------------------------
// Last: it uses up the sign-in quota of this address for a minute.
section('brute force');
let throttledAt = 0;
for (let attempt = 1; attempt <= 40 && !throttledAt; attempt++) {
  // A different, invented X-Forwarded-For each time: the limit must hold anyway
  const res = await login(`nobody-${attempt}@example.com`, 'Wrong-Passw0rd!', {
    'X-Forwarded-For': `10.${attempt}.${attempt}.${attempt}`,
  });
  if (res.status === 429) throttledAt = attempt;
}
record('repeated sign-ins are throttled (429), even with an invented X-Forwarded-For', throttledAt > 0,
  throttledAt ? `throttled at attempt ${throttledAt}` : 'never throttled in 40 attempts');

// -----------------------------------------------------------------------------------------------------
const failed = results.filter((r) => !r.ok);
const summary = [
  '## Security tests: authentication and authorization',
  '',
  `Target: \`${BASE_URL}\` - ${results.length - failed.length}/${results.length} passed`,
  '',
  '| | Area | Check | Detail |',
  '| :-: | :--- | :--- | :--- |',
  ...results.map((r) => `| ${r.ok ? '✅' : '❌'} | ${r.section} | ${r.name} | ${r.detail.replace(/\|/g, '\\|')} |`),
  '',
].join('\n');
if (process.env.GITHUB_STEP_SUMMARY) appendFileSync(process.env.GITHUB_STEP_SUMMARY, summary);
console.log(`\n${results.length - failed.length}/${results.length} checks passed`);
process.exit(failed.length ? 1 : 0);
