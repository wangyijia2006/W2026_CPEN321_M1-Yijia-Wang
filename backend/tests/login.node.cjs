const test = require('node:test');
const assert = require('node:assert/strict');
const { generateKeyPairSync, sign } = require('node:crypto');
const { once } = require('node:events');
const { OAuth2Client } = require('google-auth-library');
const { createApp, formatServerTime } = require('../dist/app.js');
const { createGoogleVerifier } = require('../dist/auth.js');

const audience = 'test-client.apps.googleusercontent.com';
const config = {
  port: 3000, googleClientId: audience, serverPublicIp: '136.66.38.82',
  developerFirstName: 'Yijia', developerLastName: 'Wang',
};
// Sign test JWTs locally, but verify them through the real Google library.
// Only certificate retrieval is replaced; no authentication bypass is shipped.
const keys = generateKeyPairSync('rsa', { modulusLength: 2048 });
const client = new OAuth2Client();
client.getFederatedSignonCertsAsync = async () => ({
  certs: { 'test-key': keys.publicKey.export({ type: 'spki', format: 'pem' }) },
  format: 'PEM',
});
const verifyToken = createGoogleVerifier(audience, client);
function token(changes = {}) {
  const now = Math.floor(Date.now() / 1000);
  const encode = (value) => Buffer.from(JSON.stringify(value)).toString('base64url');
  const data = encode({ alg: 'RS256', kid: 'test-key', typ: 'JWT' }) + '.' + encode({
    iss: 'https://accounts.google.com', aud: audience, sub: 'test-user',
    iat: now, exp: now + 3600, given_name: 'Alice', family_name: 'Example',
    name: 'Alice Example', ...changes,
  });
  return data + '.' + sign('RSA-SHA256', Buffer.from(data), keys.privateKey).toString('base64url');
}

async function withServer(overrides, callback) {
  const server = createApp({ config, verifyToken, ...overrides }).listen(0, '127.0.0.1');
  await once(server, 'listening');
  const base = 'http://127.0.0.1:' + server.address().port;
  try { await callback(base); }
  finally { await new Promise((resolve) => server.close(resolve)); }
}

test('health stays public; login and all three APIs require a Google token', async () => {
  await withServer({}, async (base) => {
    assert.deepEqual(await (await fetch(base + '/health')).json(), { status: 'ok' });
    for (const path of ['/api/auth/google', '/api/server/ip', '/api/server/time', '/api/developer']) {
      const response = await fetch(base + path, { method: path.endsWith('google') ? 'POST' : 'GET' });
      assert.equal(response.status, 401, path);
      assert.equal(response.headers.get('cache-control'), 'no-store');
    }
  });
});

test('valid signature yields verified names and three independent API results', async () => {
  await withServer({}, async (base) => {
    const headers = { Authorization: 'Bearer ' + token(), 'X-Forwarded-For': '203.0.113.25' };
    const login = await fetch(base + '/api/auth/google', { method: 'POST', headers });
    assert.equal(login.status, 200);
    assert.deepEqual((await login.json()).user, {
      id: 'test-user', firstName: 'Alice', lastName: 'Example', displayName: 'Alice Example',
    });
    const ip = await fetch(base + '/api/server/ip', { headers });
    assert.equal(ip.status, 200);
    assert.deepEqual(await ip.json(), { serverIp: '136.66.38.82', clientIp: '203.0.113.25' });
    const time = await fetch(base + '/api/server/time', { headers });
    assert.equal(time.status, 200);
    assert.match((await time.json()).serverTime, /^\d{2}:\d{2}:\d{2} GMT[+-]\d{2}:\d{2}$/);
    const name = await fetch(base + '/api/developer', { headers });
    assert.equal(name.status, 200);
    assert.deepEqual(await name.json(), { firstName: 'Yijia', lastName: 'Wang' });
  });
});

test('wrong audience, issuer, expiry, signature and malformed tokens are rejected', async () => {
  const good = token();
  const parts = good.split('.');
  parts[1] = Buffer.from(JSON.stringify({ given_name: 'Impersonated' })).toString('base64url');
  const invalid = [
    token({ aud: 'another-client' }), token({ iss: 'https://attacker.example' }),
    token({ iat: 1, exp: 2 }), parts.join('.'), 'not-a-jwt',
  ];
  await withServer({}, async (base) => {
    for (const value of invalid) {
      const response = await fetch(base + '/api/auth/google', {
        method: 'POST', headers: { Authorization: 'Bearer ' + value },
      });
      assert.equal(response.status, 401);
      assert.ok(!(await response.text()).includes(value));
    }
  });
});

test('missing Google names are not invented from a display name', async () => {
  const user = await verifyToken(token({ given_name: undefined, family_name: undefined, name: 'Single' }));
  assert.equal(user.firstName, '');
  assert.equal(user.lastName, '');
  assert.equal(user.displayName, 'Single');
});

test('missing server configuration fails clearly instead of returning invented values', async () => {
  await withServer({ config: { ...config, googleClientId: '' } }, async (base) => {
    assert.equal((await fetch(base + '/health')).status, 200);
    assert.equal((await fetch(base + '/api/server/ip')).status, 503);
  });
  await withServer({ config: { ...config, serverPublicIp: '', developerFirstName: '' } }, async (base) => {
    const headers = { Authorization: 'Bearer ' + token() };
    assert.equal((await fetch(base + '/api/server/ip', { headers })).status, 503);
    assert.equal((await fetch(base + '/api/developer', { headers })).status, 503);
  });
});

test('time includes midnight, GMT zero, positive fractional and negative offsets', () => {
  const previous = process.env.TZ;
  try {
    for (const [zone, expected] of [
      ['UTC', '00:04:05 GMT+00:00'], ['Asia/Kolkata', '05:34:05 GMT+05:30'],
      ['America/St_Johns', '20:34:05 GMT-03:30'],
    ]) {
      process.env.TZ = zone;
      assert.equal(formatServerTime(new Date('2026-01-15T00:04:05Z')), expected);
    }
  } finally {
    if (previous === undefined) delete process.env.TZ;
    else process.env.TZ = previous;
  }
});
