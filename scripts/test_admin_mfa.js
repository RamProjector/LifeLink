"use strict";

const assert = require("node:assert/strict");

global.window = global;
require("../admin-web/mfa.js");

const mfa = global.LifeLinkAdminMFA;
assert.ok(mfa, "MFA transport should be exposed");

const calls = [];
let nextResponse = { ok: true, status: 200, json: async () => ({}) };
global.fetch = async (url, options = {}) => {
  calls.push({ url, options });
  return nextResponse;
};

const config = {
  baseUrl: "https://example.supabase.co/",
  publishableKey: "sb_publishable_test",
  accessToken: "access-token"
};

(async () => {
  nextResponse = { ok: true, status: 200, json: async () => ({
    id: "factor-1",
    friendly_name: "Primary device",
    totp: { secret: "never-log-this", qr_code: "<svg></svg>", uri: "otpauth://totp/test" }
  }) };
  const enrolled = await mfa.enrollTotp(config, "Primary device");
  assert.equal(calls.at(-1).url, "https://example.supabase.co/auth/v1/factors");
  assert.equal(calls.at(-1).options.method, "POST");
  assert.deepEqual(JSON.parse(calls.at(-1).options.body), {
    factor_type: "totp", friendly_name: "Primary device", issuer: "LifeLink"
  });
  assert.equal(enrolled.id, "factor-1");
  assert.equal(enrolled.secret, "never-log-this");

  nextResponse = { ok: true, status: 200, json: async () => ({
    factors: [
      { id: "verified", factor_type: "totp", status: "verified" },
      { id: "pending", factor_type: "totp", status: "unverified" },
      { id: "phone", factor_type: "phone", status: "verified" },
      { id: "bad", factor_type: "totp", status: "unknown" }
    ]
  }) };
  const factors = await mfa.listFactors(config);
  assert.deepEqual(factors.map(item => item.id), ["verified", "pending"]);
  assert.deepEqual(mfa.verifiedFactors(factors).map(item => item.id), ["verified"]);

  nextResponse = { ok: true, status: 200, json: async () => ({ id: "challenge-1" }) };
  assert.equal(await mfa.challenge(config, "factor/one"), "challenge-1");
  assert.ok(calls.at(-1).url.endsWith("/factors/factor%2Fone/challenge"));
  assert.equal(calls.at(-1).options.headers.Authorization, "Bearer access-token");

  const beforeInvalid = calls.length;
  await assert.rejects(() => mfa.verify(config, "factor-1", "challenge-1", "12x456"));
  assert.equal(calls.length, beforeInvalid, "invalid codes must not reach Supabase");

  nextResponse = { ok: true, status: 200, json: async () => ({
    access_token: "new-access", refresh_token: "new-refresh", expires_in: 3600
  }) };
  const session = await mfa.verify(config, "factor-1", "challenge-1", "123456");
  assert.deepEqual(session, {
    accessToken: "new-access", refreshToken: "new-refresh", tokenType: "bearer", expiresIn: 3600
  });
  assert.deepEqual(JSON.parse(calls.at(-1).options.body), {
    factor_id: "factor-1", challenge_id: "challenge-1", code: "123456"
  });

  nextResponse = { ok: true, status: 200, json: async () => ({ access_token: "rotated", refresh_token: "rotated-refresh" }) };
  const refreshed = await mfa.refreshSession({
    baseUrl: config.baseUrl, publishableKey: config.publishableKey, refreshToken: "old-refresh"
  });
  assert.equal(refreshed.accessToken, "rotated");
  assert.equal(calls.at(-1).url, "https://example.supabase.co/auth/v1/token?grant_type=refresh_token");
  assert.equal(calls.at(-1).options.headers.Authorization, undefined);

  nextResponse = { ok: true, status: 200, json: async () => ({ id: "factor-1" }) };
  await mfa.unenroll(config, "factor-1");
  assert.equal(calls.at(-1).options.method, "DELETE");
  assert.ok(calls.at(-1).url.endsWith("/factors/factor-1"));

  const payload = Buffer.from(JSON.stringify({ aal: "aal2" })).toString("base64url");
  assert.equal(mfa.assuranceLevel("header." + payload + ".signature"), "aal2");
  const legacyPayload = Buffer.from(JSON.stringify({ sub: "admin-user" })).toString("base64url");
  assert.equal(mfa.assuranceLevel("header." + legacyPayload + ".signature"), "aal1");
  const malformedPayload = Buffer.from(JSON.stringify({ aal: null })).toString("base64url");
  assert.equal(mfa.assuranceLevel("header." + malformedPayload + ".signature"), "unknown");
  assert.equal(mfa.assuranceLevel("not-a-jwt"), "unknown");
  assert.equal(mfa.isTotpCode("012345"), true);
  assert.equal(mfa.isTotpCode("12345x"), false);
  assert.equal(mfa.qrImageSource("<svg></svg>").startsWith("data:image/svg+xml;utf-8,"), true);
  assert.equal(mfa.qrImageSource("javascript:alert(1)"), "");

  nextResponse = { ok: false, status: 429, json: async () => ({ error_code: "over_request_rate_limit" }) };
  await assert.rejects(
    () => mfa.authRequest({ ...config, path: "user" }),
    error => error.status === 429 && error.code === "over_request_rate_limit"
  );
  await assert.rejects(() => mfa.authRequest({ ...config, accessToken: null, path: "user" }));
  await assert.rejects(() => mfa.authRequest({ ...config, path: "../logout" }));

  nextResponse = { ok: true, status: 204, json: async () => ({}) };
  await mfa.signOut(config);
  assert.equal(calls.at(-1).options.method, "POST");
  assert.ok(calls.at(-1).url.endsWith("/auth/v1/logout"));

  console.log("Admin MFA transport tests passed.");
})().catch(error => {
  console.error(error);
  process.exitCode = 1;
});
