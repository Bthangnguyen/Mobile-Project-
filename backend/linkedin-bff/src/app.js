'use strict';

/**
 * BFF tối giản cho đăng nhập LinkedIn (Standard OAuth 2.0, docs/research-social-api.md — Luồng B).
 *
 *   POST /auth/linkedin/start     app đăng ký (state, code_challenge) trước khi mở trình duyệt
 *   GET  /auth/linkedin/callback  LinkedIn chuyển về đây; BFF đổi code lấy token bằng client_secret
 *                                 rồi chuyển hướng về app kèm exchange_code dùng một lần
 *   POST /auth/linkedin/token     app đổi exchange_code + code_verifier lấy token (một lần)
 *
 * Nguyên tắc an toàn:
 *  - client_secret chỉ đọc từ cấu hình server, không bao giờ nằm trong phản hồi hay log.
 *  - Token chỉ nằm trong bộ nhớ tối đa EXCHANGE_TTL_MS và bị xóa ngay lần đổi đầu tiên (dù đúng hay sai verifier).
 *  - Địa chỉ chuyển hướng về app lấy từ cấu hình, không từ request (không có open redirect).
 *  - Không chuyển tiếp refresh_token; không ghi log code, token, state hay verifier.
 */

const crypto = require('node:crypto');
const net = require('node:net');

const AUTHORIZATION_TTL_MS = 10 * 60 * 1000;
const EXCHANGE_TTL_MS = 60 * 1000;
const MAX_PENDING = 1000;
// Giới hạn theo IP để một người không làm đầy kho phiên của mọi người (theo review của PR BFF; lưu ý #22 là issue, không phải PR).
const MAX_PENDING_PER_IP = 20;
const UPSTREAM_TIMEOUT_MS = 10 * 1000;
const START_LIMIT_PER_MINUTE = 20;
const TOKEN_LIMIT_PER_MINUTE = 60;
const RATE_WINDOW_MS = 60 * 1000;
const MAX_TRACKED_IPS = 10000;
const MAX_BODY_BYTES = 4096;
const LINKEDIN_TOKEN_ENDPOINT = 'https://www.linkedin.com/oauth/v2/accessToken';

const STATE_PATTERN = /^[A-Za-z0-9._~-]{16,128}$/;
const CHALLENGE_PATTERN = /^[A-Za-z0-9_-]{43}$/;
const VERIFIER_PATTERN = /^[A-Za-z0-9._~-]{43,128}$/;
const EXCHANGE_CODE_PATTERN = /^[A-Za-z0-9_-]{32,128}$/;

function sha256Base64Url(value) {
  return crypto.createHash('sha256').update(value, 'ascii').digest('base64url');
}

/** Mở rộng địa chỉ IPv6 thành 8 nhóm 16 bit; trả null nếu không hợp lệ. */
function expandIpv6(address) {
  let text = address.split('%')[0].toLowerCase();
  const v4 = text.match(/(\d+\.\d+\.\d+\.\d+)$/);
  if (v4) {
    if (net.isIPv4(v4[1]) === false) return null;
    const [a, b, c, d] = v4[1].split('.').map(Number);
    text = text.slice(0, -v4[1].length) + `${((a << 8) | b).toString(16)}:${((c << 8) | d).toString(16)}`;
  }
  const halves = text.split('::');
  if (halves.length > 2) return null;
  const head = halves[0] ? halves[0].split(':') : [];
  const tail = halves.length === 2 && halves[1] ? halves[1].split(':') : [];
  const missing = 8 - head.length - tail.length;
  if (halves.length === 1 ? missing !== 0 : missing < 1) return null;
  const groups = [...head, ...Array(halves.length === 2 ? missing : 0).fill('0'), ...tail];
  return groups.length === 8 && groups.every((g) => /^[0-9a-f]{1,4}$/.test(g)) ? groups.map((g) => g.padStart(4, '0')) : null;
}

/**
 * Khóa dùng để giới hạn theo người gọi. IPv4 giữ nguyên; IPv6 gom theo /64 vì một máy thường có cả dải /64
 * để đổi địa chỉ; IPv4-mapped (::ffff:a.b.c.d) quy về IPv4.
 */
function rateKey(ip) {
  if (net.isIPv4(ip)) return ip;
  if (net.isIPv6(ip)) {
    const groups = expandIpv6(ip);
    if (groups) {
      if (groups.slice(0, 5).every((g) => g === '0000') && groups[5] === 'ffff') {
        const hi = parseInt(groups[6], 16);
        const lo = parseInt(groups[7], 16);
        return `${hi >> 8}.${hi & 255}.${lo >> 8}.${lo & 255}`;
      }
      return `${groups.slice(0, 4).join(':')}::/64`;
    }
  }
  return ip;
}

function safeEqual(a, b) {
  const x = Buffer.from(a);
  const y = Buffer.from(b);
  return x.length === y.length && crypto.timingSafeEqual(x, y);
}

/** Kho tạm trong bộ nhớ: mỗi mục có hạn; lấy ra là xóa (dùng một lần). */
class ExpiringStore {
  constructor(now, ttlMs) {
    this.now = now;
    this.ttlMs = ttlMs;
    this.items = new Map();
  }

  put(key, value) {
    this.sweep();
    if (this.items.size >= MAX_PENDING) return false;
    this.items.set(key, { value, expiresAt: this.now() + this.ttlMs });
    return true;
  }

  /** Khóa còn hạn trong kho không (không lấy ra). */
  has(key) {
    this.sweep();
    return this.items.has(key);
  }

  take(key) {
    const entry = this.items.get(key);
    if (!entry) return null;
    this.items.delete(key);
    return entry.expiresAt > this.now() ? entry.value : null;
  }

  /** Số mục còn hạn thỏa điều kiện [predicate]. */
  countWhere(predicate) {
    this.sweep();
    let n = 0;
    for (const entry of this.items.values()) {
      if (predicate(entry.value)) n += 1;
    }
    return n;
  }

  sweep() {
    const t = this.now();
    for (const [key, entry] of this.items) {
      if (entry.expiresAt <= t) this.items.delete(key);
    }
  }
}

/** Giới hạn tần suất theo khóa (IP) bằng cửa sổ cố định; bộ nhớ bị chặn trên bởi MAX_TRACKED_IPS. */
class RateLimiter {
  constructor(now, limit, windowMs) {
    this.now = now;
    this.limit = limit;
    this.windowMs = windowMs;
    this.hits = new Map();
  }

  /** Trả true nếu lần gọi này còn trong hạn mức. */
  allow(key) {
    const t = this.now();
    if (this.hits.size >= MAX_TRACKED_IPS) {
      for (const [k, entry] of this.hits) {
        if (entry.resetAt <= t) this.hits.delete(k);
      }
      // Vẫn đầy sau khi dọn: từ chối khóa mới thay vì phình bộ nhớ.
      if (this.hits.size >= MAX_TRACKED_IPS && !this.hits.has(key)) return false;
    }
    const entry = this.hits.get(key);
    if (!entry || entry.resetAt <= t) {
      this.hits.set(key, { count: 1, resetAt: t + this.windowMs });
      return true;
    }
    entry.count += 1;
    return entry.count <= this.limit;
  }
}

function createApp({ config, fetchImpl = globalThis.fetch, now = Date.now, randomToken, upstreamTimeoutMs = UPSTREAM_TIMEOUT_MS } = {}) {
  for (const key of ['clientId', 'clientSecret', 'publicUrl', 'appRedirectUri']) {
    if (!config || !config[key]) throw new Error(`Thiếu cấu hình: ${key}`);
  }
  const makeToken = randomToken || (() => crypto.randomBytes(32).toString('base64url'));
  const callbackUrl = `${config.publicUrl.replace(/\/+$/, '')}/auth/linkedin/callback`;
  const pending = new ExpiringStore(now, AUTHORIZATION_TTL_MS);
  const exchanges = new ExpiringStore(now, EXCHANGE_TTL_MS);
  const startLimiter = new RateLimiter(now, START_LIMIT_PER_MINUTE, RATE_WINDOW_MS);
  const tokenLimiter = new RateLimiter(now, TOKEN_LIMIT_PER_MINUTE, RATE_WINDOW_MS);
  const proxyHops = Number.isInteger(config.trustedProxyHops) ? config.trustedProxyHops : 0;

  /**
   * IP của người gọi. Chỉ tin X-Forwarded-For khi đứng sau proxy tin cậy (proxyHops > 0): lấy phần tử
   * thứ proxyHops tính từ cuối, vì đó là giá trị do proxy của hosting thêm vào (các giá trị bên trái có thể bị giả mạo).
   */
  function clientIp(req) {
    if (proxyHops > 0) {
      const parts = String(req.headers['x-forwarded-for'] || '')
        .split(',')
        .map((p) => p.trim())
        .filter(Boolean);
      if (parts.length >= proxyHops) return parts[parts.length - proxyHops];
    }
    return (req.socket && req.socket.remoteAddress) || 'unknown';
  }

  /** Khóa giới hạn tần suất của người gọi (IPv6 gom theo /64). */
  function callerKey(req) {
    return rateKey(clientIp(req));
  }

  function sendJson(res, status, body) {
    const payload = JSON.stringify(body);
    res.writeHead(status, {
      'Content-Type': 'application/json; charset=utf-8',
      'Cache-Control': 'no-store',
      'Content-Length': Buffer.byteLength(payload),
    });
    res.end(payload);
  }

  function sendText(res, status, text) {
    res.writeHead(status, { 'Content-Type': 'text/plain; charset=utf-8', 'Cache-Control': 'no-store' });
    res.end(text);
  }

  function redirectToApp(res, params) {
    const target = `${config.appRedirectUri}?${new URLSearchParams(params).toString()}`;
    res.writeHead(302, { Location: target, 'Cache-Control': 'no-store' });
    res.end();
  }

  function readJson(req) {
    return new Promise((resolve, reject) => {
      const type = String(req.headers['content-type'] || '');
      if (!type.toLowerCase().startsWith('application/json')) return reject(new Error('content-type'));
      let size = 0;
      let tooLarge = false;
      const chunks = [];
      req.on('data', (chunk) => {
        size += chunk.length;
        if (size > MAX_BODY_BYTES) {
          // Không giữ thêm dữ liệu; phản hồi 413 khi nhận xong thay vì cắt kết nối giữa chừng.
          tooLarge = true;
          chunks.length = 0;
          return;
        }
        chunks.push(chunk);
      });
      req.on('end', () => {
        if (tooLarge) return reject(new Error('too-large'));
        try {
          const parsed = JSON.parse(Buffer.concat(chunks).toString('utf8'));
          if (parsed === null || typeof parsed !== 'object' || Array.isArray(parsed)) throw new Error('shape');
          resolve(parsed);
        } catch (_) {
          reject(new Error('json'));
        }
      });
      req.on('error', reject);
    });
  }

  async function handleStart(req, res) {
    const ip = callerKey(req);
    if (!startLimiter.allow(ip)) {
      res.setHeader('Retry-After', '60');
      return sendJson(res, 429, { error: 'rate_limited' });
    }
    let body;
    try {
      body = await readJson(req);
    } catch (error) {
      return sendJson(res, error.message === 'too-large' ? 413 : 400, { error: 'invalid_request' });
    }
    if (!STATE_PATTERN.test(String(body.state)) || !CHALLENGE_PATTERN.test(String(body.code_challenge))) {
      return sendJson(res, 400, { error: 'invalid_request' });
    }
    // State đã có phiên đang chờ thì từ chối, không ghi đè challenge của phiên cũ.
    if (pending.has(body.state)) return sendJson(res, 409, { error: 'state_in_use' });
    // Mỗi IP chỉ được giữ một số phiên đang chờ nhất định, để một người không chiếm hết MAX_PENDING.
    if (pending.countWhere((v) => v.ip === ip) >= MAX_PENDING_PER_IP) {
      res.setHeader('Retry-After', '60');
      return sendJson(res, 429, { error: 'rate_limited' });
    }
    if (!pending.put(body.state, { challenge: body.code_challenge, ip })) {
      return sendJson(res, 503, { error: 'busy' });
    }
    return sendJson(res, 200, { ok: true });
  }

  async function exchangeWithLinkedIn(code) {
    const form = new URLSearchParams({
      grant_type: 'authorization_code',
      code,
      redirect_uri: callbackUrl,
      client_id: config.clientId,
      client_secret: config.clientSecret,
    });
    const response = await fetchImpl(LINKEDIN_TOKEN_ENDPOINT, {
      method: 'POST',
      headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
      body: form.toString(),
      // LinkedIn treo thì không để callback (và trình duyệt người dùng) chờ vô hạn.
      signal: AbortSignal.timeout(upstreamTimeoutMs),
    });
    if (!response.ok) return null;
    const data = await response.json();
    if (!data || typeof data.access_token !== 'string' || !data.access_token) return null;
    const expiresIn = Number(data.expires_in);
    return {
      access_token: data.access_token,
      token_type: 'bearer',
      // Chuyển tiếp nguyên hạn thật của LinkedIn; thiếu thì không bịa.
      expires_in: Number.isFinite(expiresIn) && expiresIn > 0 ? Math.floor(expiresIn) : null,
      scope: typeof data.scope === 'string' && data.scope ? data.scope : null,
    };
  }

  async function handleCallback(url, res) {
    const state = url.searchParams.get('state') || '';
    // Lấy phiên ra (xóa luôn): mỗi state chỉ dùng được một lần, kể cả khi LinkedIn báo lỗi.
    const session = STATE_PATTERN.test(state) ? pending.take(state) : null;
    if (!session) return sendText(res, 400, 'Phiên đăng nhập không hợp lệ hoặc đã hết hạn.');

    const error = url.searchParams.get('error');
    if (error) {
      const cancelled = error === 'access_denied' || error.startsWith('user_cancelled');
      return redirectToApp(res, { error: cancelled ? 'access_denied' : 'oauth_error', state });
    }
    const code = url.searchParams.get('code');
    if (!code) return redirectToApp(res, { error: 'oauth_error', state });

    let token = null;
    try {
      token = await exchangeWithLinkedIn(code);
    } catch (_) {
      token = null;
    }
    if (!token) return redirectToApp(res, { error: 'exchange_failed', state });

    const exchangeCode = makeToken();
    // Kho mã đổi đầy thì không được chuyển exchange_code "ma" về app: báo busy để người dùng thử lại.
    if (!exchanges.put(exchangeCode, { state, challenge: session.challenge, token })) {
      return redirectToApp(res, { error: 'busy', state });
    }
    return redirectToApp(res, { exchange_code: exchangeCode, state });
  }

  async function handleToken(req, res) {
    if (!tokenLimiter.allow(callerKey(req))) {
      res.setHeader('Retry-After', '60');
      return sendJson(res, 429, { error: 'rate_limited' });
    }
    let body;
    try {
      body = await readJson(req);
    } catch (_) {
      return sendJson(res, 400, { error: 'invalid_request' });
    }
    const { exchange_code: exchangeCode, state, code_verifier: verifier } = body;
    if (
      !EXCHANGE_CODE_PATTERN.test(String(exchangeCode)) ||
      !STATE_PATTERN.test(String(state)) ||
      !VERIFIER_PATTERN.test(String(verifier))
    ) {
      return sendJson(res, 400, { error: 'invalid_request' });
    }
    // Lấy ra là xóa: thử sai verifier cũng làm mã mất hiệu lực, người dùng chỉ cần đăng nhập lại.
    const entry = exchanges.take(exchangeCode);
    if (!entry) return sendJson(res, 400, { error: 'invalid_grant' });
    if (!safeEqual(entry.state, state) || !safeEqual(entry.challenge, sha256Base64Url(verifier))) {
      return sendJson(res, 400, { error: 'invalid_grant' });
    }
    return sendJson(res, 200, entry.token);
  }

  return async function handler(req, res) {
    try {
      const url = new URL(req.url, 'http://localhost');
      if (req.method === 'POST' && url.pathname === '/auth/linkedin/start') return await handleStart(req, res);
      if (req.method === 'GET' && url.pathname === '/auth/linkedin/callback') return await handleCallback(url, res);
      if (req.method === 'POST' && url.pathname === '/auth/linkedin/token') return await handleToken(req, res);
      if (req.method === 'GET' && url.pathname === '/healthz') return sendText(res, 200, 'ok');
      return sendJson(res, 404, { error: 'not_found' });
    } catch (_) {
      // Không đưa chi tiết lỗi (có thể chứa dữ liệu nhạy cảm) ra ngoài hay vào log.
      if (!res.headersSent) sendJson(res, 500, { error: 'server_error' });
    }
  };
}

module.exports = { createApp, RateLimiter, rateKey, MAX_PENDING, MAX_PENDING_PER_IP, MAX_TRACKED_IPS, START_LIMIT_PER_MINUTE, TOKEN_LIMIT_PER_MINUTE, UPSTREAM_TIMEOUT_MS, sha256Base64Url, EXCHANGE_TTL_MS, AUTHORIZATION_TTL_MS, LINKEDIN_TOKEN_ENDPOINT };
