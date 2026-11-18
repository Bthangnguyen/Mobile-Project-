'use strict';

/**
 * Số proxy tin cậy đứng trước BFF (dùng để lấy IP thật từ X-Forwarded-For).
 * Chuỗi rỗng, toàn khoảng trắng, số âm hoặc không phải số nguyên được coi là chưa đặt và dùng mặc định:
 * 1 khi chạy trên Render (biến RENDER có giá trị), 0 ở nơi khác.
 */
function parseTrustedProxyHops(raw, onRender) {
  const fallback = onRender ? 1 : 0;
  if (raw === undefined || raw === null) return fallback;
  const text = String(raw).trim();
  if (!/^\d+$/.test(text)) return fallback;
  return Number(text);
}

module.exports = { parseTrustedProxyHops };
