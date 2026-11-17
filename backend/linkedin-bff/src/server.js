'use strict';

const http = require('node:http');
const { createApp } = require('./app');

const config = {
  clientId: process.env.LINKEDIN_MEMBER_CLIENT_ID,
  clientSecret: process.env.LINKEDIN_MEMBER_CLIENT_SECRET,
  publicUrl: process.env.BFF_PUBLIC_URL,
  appRedirectUri: process.env.APP_REDIRECT_URI || 'com.lttbdd.socialagent://oauth/linkedin/callback',
  // Số proxy tin cậy đứng trước BFF. Render đặt biến RENDER=true và có proxy phía trước nên mặc định 1; 0 = không tin X-Forwarded-For.
  trustedProxyHops: Number.isInteger(Number(process.env.TRUSTED_PROXY_HOPS)) && process.env.TRUSTED_PROXY_HOPS !== undefined
    ? Number(process.env.TRUSTED_PROXY_HOPS)
    : process.env.RENDER ? 1 : 0,
};

let handler;
try {
  handler = createApp({ config });
} catch (error) {
  console.error(error.message);
  process.exit(1);
}

const port = Number(process.env.PORT) || 8787;
http.createServer(handler).listen(port, () => console.log(`LinkedIn BFF đang chạy ở cổng ${port}`));
