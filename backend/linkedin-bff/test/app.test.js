'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const http = require('node:http');
const crypto = require('node:crypto');
const { createApp, sha256Base64Url, EXCHANGE_TTL_MS, LINKEDIN_TOKEN_ENDPOINT } = require('../src/app');

const SECRET = 'bi-mat-cua-server-khong-duoc-lo';
const APP_REDIRECT = 'com.lttbdd.socialagent://oauth/linkedin/callback';
const STATE = 'state_hop_le_0123456789abcdef';
const VERIFIER = 'verifier-hop-le-0123456789-0123456789-0123456789-abcdef';
const CHALLENGE = sha256Base64Url(VERIFIER);

/** Dựng server thật trên cổng ngẫu nhiên, LinkedIn giả và đồng hồ điều khiển được. */
async function setup({ linkedIn, trustedProxyHops } = {}) {
  const clock = { t: 1_000_000 };
  const calls = [];
  const fetchImpl = async (url, init) => {
    calls.push({ url, init });
    return (linkedIn || (async () => ({ ok: true, json: async () => ({ access_token: 'AQtoken', expires_in: 5184000, scope: 'openid,profile,w_member_social', refresh_token: 'KHONG-DUOC-CHUYEN-TIEP' }) })))();
  };
  const handler = createApp({
    config: { clientId: 'client_id_cong_khai', clientSecret: SECRET, publicUrl: 'https://bff.example.com/', appRedirectUri: APP_REDIRECT, trustedProxyHops },
    fetchImpl,
    now: () => clock.t,
  });
  const server = http.createServer(handler);
  await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve));
  const base = `http://127.0.0.1:${server.address().port}`;
  const post = (path, body, headers = {}) =>
    fetch(base + path, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', ...headers },
      body: typeof body === 'string' ? body : JSON.stringify(body),
    });
  const get = (path, headers = {}) => fetch(base + path, { redirect: 'manual', headers });
  return { server, post, get, clock, calls, close: () => new Promise((r) => server.close(r)) };
}

async function startSession(ctx, state = STATE, challenge = CHALLENGE) {
  const res = await ctx.post('/auth/linkedin/start', { state, code_challenge: challenge });
  assert.equal(res.status, 200);
}

function parseLocation(res) {
  assert.equal(res.status, 302);
  const location = res.headers.get('location');
  assert.ok(location.startsWith(`${APP_REDIRECT}?`), `Location sai: ${location}`);
  return new URL(location.replace(APP_REDIRECT, 'https://app.invalid/cb')).searchParams;
}

test('luồng đầy đủ: start → callback → token trả đúng hạn thật và scope thật', async () => {
  const ctx = await setup();
  try {
    await startSession(ctx);
    const cb = await ctx.get(`/auth/linkedin/callback?code=ma_code_linkedin&state=${STATE}`);
    const params = parseLocation(cb);
    assert.equal(params.get('state'), STATE);

    const tokenRes = await ctx.post('/auth/linkedin/token', {
      exchange_code: params.get('exchange_code'),
      state: STATE,
      code_verifier: VERIFIER,
    });
    assert.equal(tokenRes.status, 200);
    assert.equal(tokenRes.headers.get('cache-control'), 'no-store');
    const body = await tokenRes.json();
    assert.equal(body.access_token, 'AQtoken');
    assert.equal(body.expires_in, 5184000);
    assert.equal(body.scope, 'openid,profile,w_member_social');
    assert.equal(body.token_type, 'bearer');
    assert.ok(!JSON.stringify(body).includes('KHONG-DUOC-CHUYEN-TIEP'), 'không chuyển tiếp refresh_token');
  } finally {
    await ctx.close();
  }
});

test('đổi code gửi đúng client_secret, redirect_uri của BFF và không lộ secret ra ngoài', async () => {
  const ctx = await setup();
  try {
    await startSession(ctx);
    const cb = await ctx.get(`/auth/linkedin/callback?code=ma_code_linkedin&state=${STATE}`);
    const location = cb.headers.get('location');
    assert.ok(!location.includes(SECRET));

    assert.equal(ctx.calls.length, 1);
    assert.equal(ctx.calls[0].url, LINKEDIN_TOKEN_ENDPOINT);
    const sent = new URLSearchParams(ctx.calls[0].init.body);
    assert.equal(sent.get('grant_type'), 'authorization_code');
    assert.equal(sent.get('code'), 'ma_code_linkedin');
    assert.equal(sent.get('client_secret'), SECRET);
    assert.equal(sent.get('client_id'), 'client_id_cong_khai');
    assert.equal(sent.get('redirect_uri'), 'https://bff.example.com/auth/linkedin/callback');
  } finally {
    await ctx.close();
  }
});

test('callback với state chưa đăng ký bị từ chối và không gọi LinkedIn', async () => {
  const ctx = await setup();
  try {
    const cb = await ctx.get('/auth/linkedin/callback?code=x&state=state_la_0123456789abcdef');
    assert.equal(cb.status, 400);
    assert.equal(ctx.calls.length, 0);
    const missing = await ctx.get('/auth/linkedin/callback?code=x');
    assert.equal(missing.status, 400);
  } finally {
    await ctx.close();
  }
});

test('state chỉ dùng được một lần ở callback', async () => {
  const ctx = await setup();
  try {
    await startSession(ctx);
    assert.equal((await ctx.get(`/auth/linkedin/callback?code=a&state=${STATE}`)).status, 302);
    assert.equal((await ctx.get(`/auth/linkedin/callback?code=a&state=${STATE}`)).status, 400);
    assert.equal(ctx.calls.length, 1);
  } finally {
    await ctx.close();
  }
});

test('phiên đăng nhập hết hạn sau 10 phút', async () => {
  const ctx = await setup();
  try {
    await startSession(ctx);
    ctx.clock.t += 10 * 60 * 1000 + 1;
    assert.equal((await ctx.get(`/auth/linkedin/callback?code=a&state=${STATE}`)).status, 400);
  } finally {
    await ctx.close();
  }
});

test('người dùng hủy ở LinkedIn: chuyển về app với access_denied, không có exchange_code', async () => {
  const ctx = await setup();
  try {
    await startSession(ctx);
    const params = parseLocation(await ctx.get(`/auth/linkedin/callback?error=user_cancelled_login&state=${STATE}`));
    assert.equal(params.get('error'), 'access_denied');
    assert.equal(params.get('exchange_code'), null);
    assert.equal(ctx.calls.length, 0);
  } finally {
    await ctx.close();
  }
});

test('LinkedIn báo lỗi OAuth khác: oauth_error', async () => {
  const ctx = await setup();
  try {
    await startSession(ctx);
    const params = parseLocation(await ctx.get(`/auth/linkedin/callback?error=invalid_scope&state=${STATE}`));
    assert.equal(params.get('error'), 'oauth_error');
  } finally {
    await ctx.close();
  }
});

test('LinkedIn từ chối đổi code hoặc ném lỗi: exchange_failed, không lộ chi tiết', async () => {
  for (const linkedIn of [
    async () => ({ ok: false, json: async () => ({ error: 'invalid_client', secret: SECRET }) }),
    async () => { throw new Error(`mạng hỏng ${SECRET}`); },
    async () => ({ ok: true, json: async () => ({}) }),
  ]) {
    const ctx = await setup({ linkedIn });
    try {
      await startSession(ctx);
      const res = await ctx.get(`/auth/linkedin/callback?code=a&state=${STATE}`);
      const params = parseLocation(res);
      assert.equal(params.get('error'), 'exchange_failed');
      assert.ok(!res.headers.get('location').includes(SECRET));
    } finally {
      await ctx.close();
    }
  }
});

async function getExchangeCode(ctx) {
  await startSession(ctx);
  return parseLocation(await ctx.get(`/auth/linkedin/callback?code=a&state=${STATE}`)).get('exchange_code');
}

test('exchange_code dùng một lần: lần hai bị từ chối', async () => {
  const ctx = await setup();
  try {
    const exchange_code = await getExchangeCode(ctx);
    const payload = { exchange_code, state: STATE, code_verifier: VERIFIER };
    assert.equal((await ctx.post('/auth/linkedin/token', payload)).status, 200);
    const again = await ctx.post('/auth/linkedin/token', payload);
    assert.equal(again.status, 400);
    assert.equal((await again.json()).error, 'invalid_grant');
  } finally {
    await ctx.close();
  }
});

test('sai code_verifier bị từ chối và làm mã mất hiệu lực', async () => {
  const ctx = await setup();
  try {
    const exchange_code = await getExchangeCode(ctx);
    const wrong = 'verifier-cua-ke-tan-cong-0123456789-0123456789-0123456789';
    const bad = await ctx.post('/auth/linkedin/token', { exchange_code, state: STATE, code_verifier: wrong });
    assert.equal(bad.status, 400);
    const good = await ctx.post('/auth/linkedin/token', { exchange_code, state: STATE, code_verifier: VERIFIER });
    assert.equal(good.status, 400, 'mã đã bị hủy sau lần thử sai');
  } finally {
    await ctx.close();
  }
});

test('sai state ở bước token bị từ chối', async () => {
  const ctx = await setup();
  try {
    const exchange_code = await getExchangeCode(ctx);
    const res = await ctx.post('/auth/linkedin/token', { exchange_code, state: 'state_khac_0123456789abcdef', code_verifier: VERIFIER });
    assert.equal(res.status, 400);
  } finally {
    await ctx.close();
  }
});

test('exchange_code hết hạn sau 60 giây', async () => {
  const ctx = await setup();
  try {
    const exchange_code = await getExchangeCode(ctx);
    ctx.clock.t += EXCHANGE_TTL_MS + 1;
    const res = await ctx.post('/auth/linkedin/token', { exchange_code, state: STATE, code_verifier: VERIFIER });
    assert.equal(res.status, 400);
  } finally {
    await ctx.close();
  }
});

test('start từ chối dữ liệu sai định dạng', async () => {
  const ctx = await setup();
  try {
    const bodies = [
      { state: 'ngan', code_challenge: CHALLENGE },
      { state: STATE, code_challenge: 'khong-phai-sha256' },
      { state: STATE },
      {},
      [],
    ];
    for (const body of bodies) {
      assert.equal((await ctx.post('/auth/linkedin/start', body)).status, 400, JSON.stringify(body));
    }
    assert.equal((await ctx.post('/auth/linkedin/start', '{khong json', { 'Content-Type': 'application/json' })).status, 400);
    assert.equal((await ctx.post('/auth/linkedin/start', { state: STATE, code_challenge: CHALLENGE }, { 'Content-Type': 'text/plain' })).status, 400);
    assert.equal((await ctx.post('/auth/linkedin/start', 'x'.repeat(5000))).status, 413);
  } finally {
    await ctx.close();
  }
});

test('token từ chối trường thiếu hoặc sai định dạng', async () => {
  const ctx = await setup();
  try {
    for (const body of [{}, { exchange_code: 'ngan', state: STATE, code_verifier: VERIFIER }, { exchange_code: 'a'.repeat(43), state: STATE }]) {
      assert.equal((await ctx.post('/auth/linkedin/token', body)).status, 400);
    }
  } finally {
    await ctx.close();
  }
});

test('địa chỉ chuyển hướng về app cố định theo cấu hình, không nhận từ request', async () => {
  const ctx = await setup();
  try {
    await startSession(ctx);
    const res = await ctx.get(`/auth/linkedin/callback?code=a&state=${STATE}&redirect_uri=https://ke-tan-cong.example&app_redirect_uri=https://ke-tan-cong.example`);
    assert.ok(res.headers.get('location').startsWith(`${APP_REDIRECT}?`));
  } finally {
    await ctx.close();
  }
});

test('thiếu cấu hình thì không khởi tạo được', () => {
  assert.throws(() => createApp({ config: { clientId: 'a', publicUrl: 'https://x', appRedirectUri: 'y' } }), /clientSecret/);
});

test('đường dẫn lạ trả 404; healthz trả ok', async () => {
  const ctx = await setup();
  try {
    assert.equal((await ctx.get('/')).status, 404);
    assert.equal((await ctx.get('/auth/linkedin/token')).status, 404);
    assert.equal((await ctx.get('/healthz')).status, 200);
  } finally {
    await ctx.close();
  }
});

test('sha256Base64Url khớp vector RFC 7636 Phụ lục B', () => {
  assert.equal(sha256Base64Url('dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk'), 'E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM');
  assert.equal(crypto.createHash('sha256').update('').digest('hex').length, 64);
});

// ---- Giới hạn tần suất và kho đầy (theo review của PR BFF) ----

const stateN = (i) => `state_nhieu_phien_${String(i).padStart(6, '0')}_abcdef`;
const ipN = (i) => `10.1.${i >> 8}.${i & 255}`;

test('/start bị giới hạn 20 lần mỗi phút cho mỗi IP rồi trả 429, sang phút mới dùng lại được', async () => {
  const ctx = await setup();
  try {
    for (let i = 0; i < 20; i += 1) {
      const res = await ctx.post('/auth/linkedin/start', { state: stateN(i), code_challenge: CHALLENGE });
      assert.equal(res.status, 200, `lần ${i + 1}`);
    }
    const blocked = await ctx.post('/auth/linkedin/start', { state: stateN(99), code_challenge: CHALLENGE });
    assert.equal(blocked.status, 429);
    assert.equal(blocked.headers.get('retry-after'), '60');

    ctx.clock.t += 61 * 1000;
    // Cửa sổ mới, nhưng IP này vẫn đang giữ 20 phiên chờ nên bị chặn theo hạn mức phiên chờ.
    const stillBlocked = await ctx.post('/auth/linkedin/start', { state: stateN(100), code_challenge: CHALLENGE });
    assert.equal(stillBlocked.status, 429);

    ctx.clock.t += 10 * 60 * 1000;
    const again = await ctx.post('/auth/linkedin/start', { state: stateN(101), code_challenge: CHALLENGE });
    assert.equal(again.status, 200);
  } finally {
    await ctx.close();
  }
});

test('một IP chiếm hạn mức không làm IP khác bị chặn (tin X-Forwarded-For phía proxy)', async () => {
  const ctx = await setup({ trustedProxyHops: 1 });
  try {
    for (let i = 0; i < 21; i += 1) {
      await ctx.post('/auth/linkedin/start', { state: stateN(i), code_challenge: CHALLENGE }, { 'X-Forwarded-For': '203.0.113.7' });
    }
    const attacker = await ctx.post('/auth/linkedin/start', { state: stateN(50), code_challenge: CHALLENGE }, { 'X-Forwarded-For': '203.0.113.7' });
    assert.equal(attacker.status, 429);

    const other = await ctx.post('/auth/linkedin/start', { state: stateN(51), code_challenge: CHALLENGE }, { 'X-Forwarded-For': '198.51.100.9' });
    assert.equal(other.status, 200);
  } finally {
    await ctx.close();
  }
});

test('giả mạo phần tử bên trái X-Forwarded-For không né được giới hạn', async () => {
  const ctx = await setup({ trustedProxyHops: 1 });
  try {
    for (let i = 0; i < 20; i += 1) {
      const res = await ctx.post(
        '/auth/linkedin/start',
        { state: stateN(i), code_challenge: CHALLENGE },
        { 'X-Forwarded-For': `1.2.3.${i}, 203.0.113.7` },
      );
      assert.equal(res.status, 200);
    }
    const spoofed = await ctx.post(
      '/auth/linkedin/start',
      { state: stateN(60), code_challenge: CHALLENGE },
      { 'X-Forwarded-For': '9.9.9.9, 203.0.113.7' },
    );
    assert.equal(spoofed.status, 429);
  } finally {
    await ctx.close();
  }
});

test('kho mã đổi đầy: callback chuyển về app với error=busy, không có exchange_code', async () => {
  const ctx = await setup({ trustedProxyHops: 1 });
  try {
    for (let i = 0; i < 1000; i += 1) {
      const headers = { 'X-Forwarded-For': ipN(i) };
      const st = await ctx.post('/auth/linkedin/start', { state: stateN(i), code_challenge: CHALLENGE }, headers);
      assert.equal(st.status, 200, `start ${i}`);
      const cb = await ctx.get(`/auth/linkedin/callback?code=c${i}&state=${stateN(i)}`, headers);
      assert.ok(parseLocation(cb).get('exchange_code'), `callback ${i}`);
    }
    const headers = { 'X-Forwarded-For': ipN(2000) };
    await ctx.post('/auth/linkedin/start', { state: stateN(2000), code_challenge: CHALLENGE }, headers);
    const cb = await ctx.get(`/auth/linkedin/callback?code=cuoi&state=${stateN(2000)}`, headers);
    const params = parseLocation(cb);
    assert.equal(params.get('error'), 'busy');
    assert.equal(params.get('state'), stateN(2000));
    assert.equal(params.get('exchange_code'), null);
  } finally {
    await ctx.close();
  }
});

// ---- Các nhánh bổ sung theo review ----

const {
  RateLimiter, rateKey, MAX_PENDING, MAX_TRACKED_IPS, TOKEN_LIMIT_PER_MINUTE, START_LIMIT_PER_MINUTE,
  AUTHORIZATION_TTL_MS, UPSTREAM_TIMEOUT_MS,
} = require('../src/app');
const { parseTrustedProxyHops } = require('../src/config');

test('/token bị giới hạn 60 lần mỗi phút cho mỗi IP rồi trả 429, sang phút mới dùng lại được', async () => {
  const ctx = await setup();
  try {
    const body = { exchange_code: 'x'.repeat(40), state: STATE, code_verifier: VERIFIER };
    for (let i = 0; i < TOKEN_LIMIT_PER_MINUTE; i += 1) {
      const res = await ctx.post('/auth/linkedin/token', body);
      assert.equal(res.status, 400, `lần ${i + 1}`);
    }
    const blocked = await ctx.post('/auth/linkedin/token', body);
    assert.equal(blocked.status, 429);
    assert.equal(blocked.headers.get('retry-after'), '60');

    ctx.clock.t += 61 * 1000;
    assert.equal((await ctx.post('/auth/linkedin/token', body)).status, 400);
  } finally {
    await ctx.close();
  }
});

test('kho phiên chờ đầy: /start trả 503 busy; có chỗ trống sau khi phiên hết hạn', async () => {
  const ctx = await setup({ trustedProxyHops: 1 });
  try {
    for (let i = 0; i < MAX_PENDING; i += 1) {
      const res = await ctx.post('/auth/linkedin/start', { state: stateN(i), code_challenge: CHALLENGE }, { 'X-Forwarded-For': ipN(i) });
      assert.equal(res.status, 200, `start ${i}`);
    }
    const full = await ctx.post('/auth/linkedin/start', { state: stateN(5000), code_challenge: CHALLENGE }, { 'X-Forwarded-For': ipN(5000) });
    assert.equal(full.status, 503);
    assert.deepEqual(await full.json(), { error: 'busy' });

    ctx.clock.t += AUTHORIZATION_TTL_MS + 1;
    const again = await ctx.post('/auth/linkedin/start', { state: stateN(5001), code_challenge: CHALLENGE }, { 'X-Forwarded-For': ipN(5001) });
    assert.equal(again.status, 200);
  } finally {
    await ctx.close();
  }
});

test('/start với state đang chờ bị từ chối (409) và không ghi đè challenge của phiên cũ', async () => {
  const ctx = await setup();
  try {
    assert.equal((await ctx.post('/auth/linkedin/start', { state: STATE, code_challenge: CHALLENGE })).status, 200);
    const otherChallenge = sha256Base64Url('verifier-cua-ke-tan-cong-0123456789-0123456789-abcdef');
    const dup = await ctx.post('/auth/linkedin/start', { state: STATE, code_challenge: otherChallenge });
    assert.equal(dup.status, 409);
    assert.deepEqual(await dup.json(), { error: 'state_in_use' });

    // Phiên gốc vẫn dùng được với verifier gốc.
    const cb = await ctx.get(`/auth/linkedin/callback?code=abc&state=${STATE}`);
    const exchangeCode = parseLocation(cb).get('exchange_code');
    assert.ok(exchangeCode);
    const token = await ctx.post('/auth/linkedin/token', { exchange_code: exchangeCode, state: STATE, code_verifier: VERIFIER });
    assert.equal(token.status, 200);
  } finally {
    await ctx.close();
  }
});

test('gọi LinkedIn có timeout: fetch treo thì callback chuyển về app với error=exchange_failed', async () => {
  const clock = { t: 1_000_000 };
  let sawSignal = false;
  const fetchImpl = (url, init) => new Promise((resolve, reject) => {
    sawSignal = init.signal instanceof AbortSignal;
    init.signal.addEventListener('abort', () => reject(init.signal.reason));
  });
  const handler = createApp({
    config: { clientId: 'client_id_cong_khai', clientSecret: SECRET, publicUrl: 'https://bff.example.com', appRedirectUri: APP_REDIRECT },
    fetchImpl,
    now: () => clock.t,
    upstreamTimeoutMs: 50,
  });
  const server = http.createServer(handler);
  await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve));
  const base = `http://127.0.0.1:${server.address().port}`;
  try {
    await fetch(`${base}/auth/linkedin/start`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ state: STATE, code_challenge: CHALLENGE }),
    });
    const started = Date.now();
    const cb = await fetch(`${base}/auth/linkedin/callback?code=abc&state=${STATE}`, { redirect: 'manual' });
    const params = new URL(cb.headers.get('location')).searchParams;
    assert.equal(params.get('error'), 'exchange_failed');
    assert.ok(sawSignal, 'phải truyền AbortSignal cho fetch');
    assert.ok(Date.now() - started < 5000, 'không được chờ lâu hơn timeout');
  } finally {
    await new Promise((r) => server.close(r));
  }
  assert.equal(UPSTREAM_TIMEOUT_MS, 10000);
});

test('RateLimiter đầy MAX_TRACKED_IPS: từ chối khóa mới, khóa cũ vẫn được tính, dọn mục hết hạn thì nhận lại', () => {
  let t = 0;
  const limiter = new RateLimiter(() => t, START_LIMIT_PER_MINUTE, 60_000);
  for (let i = 0; i < MAX_TRACKED_IPS; i += 1) assert.equal(limiter.allow(`k${i}`), true);
  assert.equal(limiter.allow('khoa-moi'), false);
  assert.equal(limiter.allow('k0'), true);

  t += 61_000;
  assert.equal(limiter.allow('khoa-moi'), true);
  assert.ok(limiter.hits.size <= MAX_TRACKED_IPS);
});

test('rateKey: IPv4 giữ nguyên, IPv6 gom theo /64, IPv4-mapped quy về IPv4', () => {
  assert.equal(rateKey('203.0.113.7'), '203.0.113.7');
  assert.equal(rateKey('2001:db8:1:2:aaaa:bbbb:cccc:dddd'), rateKey('2001:db8:1:2::1'));
  assert.equal(rateKey('2001:DB8:1:2::1'), rateKey('2001:db8:1:2:0:0:0:9'));
  assert.notEqual(rateKey('2001:db8:1:2::1'), rateKey('2001:db8:1:3::1'));
  assert.equal(rateKey('::ffff:203.0.113.7'), '203.0.113.7');
  assert.equal(rateKey('::1'), '0000:0000:0000:0000::/64');
  assert.equal(rateKey('khong-phai-ip'), 'khong-phai-ip');
});

test('đổi địa chỉ IPv6 trong cùng /64 không né được giới hạn /start', async () => {
  const ctx = await setup({ trustedProxyHops: 1 });
  try {
    for (let i = 0; i < 20; i += 1) {
      const res = await ctx.post(
        '/auth/linkedin/start',
        { state: stateN(i), code_challenge: CHALLENGE },
        { 'X-Forwarded-For': `2001:db8:abcd:12::${(i + 1).toString(16)}` },
      );
      assert.equal(res.status, 200);
    }
    const sameBlock = await ctx.post(
      '/auth/linkedin/start',
      { state: stateN(70), code_challenge: CHALLENGE },
      { 'X-Forwarded-For': '2001:db8:abcd:12:ffff:ffff:ffff:ffff' },
    );
    assert.equal(sameBlock.status, 429);
    const otherBlock = await ctx.post(
      '/auth/linkedin/start',
      { state: stateN(71), code_challenge: CHALLENGE },
      { 'X-Forwarded-For': '2001:db8:abcd:13::1' },
    );
    assert.equal(otherBlock.status, 200);
  } finally {
    await ctx.close();
  }
});

test('parseTrustedProxyHops: rỗng/khoảng trắng/âm/không phải số nguyên dùng mặc định theo Render', () => {
  for (const raw of [undefined, null, '', ' ', '   ', '-1', '1.5', 'abc', '1e2']) {
    assert.equal(parseTrustedProxyHops(raw, true), 1, `render, "${raw}"`);
    assert.equal(parseTrustedProxyHops(raw, false), 0, `local, "${raw}"`);
  }
  assert.equal(parseTrustedProxyHops('0', true), 0);
  assert.equal(parseTrustedProxyHops('2', false), 2);
  assert.equal(parseTrustedProxyHops(' 3 ', false), 3);
});
