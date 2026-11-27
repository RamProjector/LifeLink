/* LifeLink Admin Supabase MFA transport.
 * Tokens are supplied by the caller and remain in memory; this module never persists them.
 */
(() => {
  "use strict";

  const normalizeBaseUrl = value => String(value || "").replace(/\/+$/, "");

  async function authRequest({ baseUrl, publishableKey, accessToken, path, method = "GET", body }) {
    if (!accessToken) throw new Error("An authenticated session is required.");
    if (!publishableKey || !baseUrl || !path || path.startsWith("/") || path.includes("..")) {
      throw new Error("Invalid Supabase Auth request configuration.");
    }
    const response = await fetch(normalizeBaseUrl(baseUrl) + "/auth/v1/" + path, {
      method,
      headers: {
        apikey: publishableKey,
        Authorization: "Bearer " + accessToken,
        "Content-Type": "application/json"
      },
      ...(body === undefined ? {} : { body: JSON.stringify(body) })
    });
    const payload = await response.json().catch(() => ({}));
    if (!response.ok) {
      const error = new Error("Supabase Auth request failed.");
      error.status = response.status;
      error.code = typeof payload.error_code === "string" ? payload.error_code : "";
      throw error;
    }
    return payload;
  }

  async function listFactors(config) {
    const user = await authRequest({ ...config, path: "user" });
    if (!user || !Array.isArray(user.factors)) return [];
    return user.factors.filter(factor =>
      factor && factor.factor_type === "totp" &&
      typeof factor.id === "string" &&
      (factor.status === "verified" || factor.status === "unverified")
    );
  }

  function verifiedFactors(factors) {
    return (Array.isArray(factors) ? factors : []).filter(factor =>
      factor && factor.factor_type === "totp" && factor.status === "verified"
    );
  }

  async function enrollTotp(config, friendlyName, issuer = "LifeLink") {
    const name = String(friendlyName || "").trim();
    if (!name || name.length > 64) throw new Error("Enter a valid authenticator name.");
    const result = await authRequest({
      ...config,
      path: "factors",
      method: "POST",
      body: { factor_type: "totp", friendly_name: name, issuer }
    });
    if (!result || typeof result.id !== "string" || !result.totp ||
        typeof result.totp.secret !== "string" || typeof result.totp.qr_code !== "string") {
      throw new Error("Supabase returned an incomplete authenticator enrollment response.");
    }
    return {
      id: result.id,
      friendlyName: typeof result.friendly_name === "string" ? result.friendly_name : name,
      secret: result.totp.secret,
      qrCode: result.totp.qr_code,
      uri: typeof result.totp.uri === "string" ? result.totp.uri : ""
    };
  }

  async function challenge(config, factorId) {
    if (typeof factorId !== "string" || !factorId) throw new Error("Choose an authenticator.");
    const result = await authRequest({
      ...config,
      path: "factors/" + encodeURIComponent(factorId) + "/challenge",
      method: "POST",
      body: {}
    });
    if (!result || typeof result.id !== "string" || !result.id) {
      throw new Error("Supabase did not return a valid authenticator challenge.");
    }
    return result.id;
  }

  async function verify(config, factorId, challengeId, code) {
    if (!/^[0-9]{6}$/.test(String(code || ""))) {
      throw new Error("Enter the six-digit code from your authenticator.");
    }
    const result = await authRequest({
      ...config,
      path: "factors/" + encodeURIComponent(factorId) + "/verify",
      method: "POST",
      body: { factor_id: factorId, challenge_id: challengeId, code: String(code) }
    });
    if (!result || typeof result.access_token !== "string" ||
        typeof result.refresh_token !== "string") {
      throw new Error("Supabase did not return a complete refreshed session.");
    }
    return {
      accessToken: result.access_token,
      refreshToken: result.refresh_token,
      tokenType: typeof result.token_type === "string" ? result.token_type : "bearer",
      expiresIn: Number.isFinite(Number(result.expires_in)) ? Number(result.expires_in) : null
    };
  }

  async function unenroll(config, factorId) {
    if (typeof factorId !== "string" || !factorId) throw new Error("Choose an authenticator.");
    return authRequest({
      ...config,
      path: "factors/" + encodeURIComponent(factorId),
      method: "DELETE"
    });
  }

  async function refreshSession({ baseUrl, publishableKey, refreshToken }) {
    if (!refreshToken) throw new Error("The sign-in session has expired. Sign in again.");
    const response = await fetch(normalizeBaseUrl(baseUrl) + "/auth/v1/token?grant_type=refresh_token", {
      method: "POST",
      headers: { apikey: publishableKey, "Content-Type": "application/json" },
      body: JSON.stringify({ refresh_token: refreshToken })
    });
    const payload = await response.json().catch(() => ({}));
    if (!response.ok || typeof payload.access_token !== "string" ||
        typeof payload.refresh_token !== "string") {
      const error = new Error("The sign-in session could not be refreshed.");
      error.status = response.status;
      throw error;
    }
    return {
      accessToken: payload.access_token,
      refreshToken: payload.refresh_token,
      tokenType: typeof payload.token_type === "string" ? payload.token_type : "bearer",
      expiresIn: Number.isFinite(Number(payload.expires_in)) ? Number(payload.expires_in) : null
    };
  }

  function assuranceLevel(accessToken) {
    try {
      const part = String(accessToken || "").split(".")[1];
      if (!part) return "unknown";
      const normalized = part.replace(/-/g, "+").replace(/_/g, "/");
      const payload = JSON.parse(atob(normalized.padEnd(Math.ceil(normalized.length / 4) * 4, "=")));
      if (!Object.prototype.hasOwnProperty.call(payload, "aal")) return "aal1";
      return payload.aal === "aal1" || payload.aal === "aal2" ? payload.aal : "unknown";
    } catch {
      return "unknown";
    }
  }

  function qrImageSource(qrCode) {
    const value = String(qrCode || "");
    if (value.startsWith("data:image/svg+xml")) return value;
    if (!value.startsWith("<svg") || value.length > 100000) return "";
    return "data:image/svg+xml;utf-8," + encodeURIComponent(value);
  }

  async function signOut(config) {
    return authRequest({ ...config, path: "logout", method: "POST" });
  }

  function isTotpCode(value) {
    return /^[0-9]{6}$/.test(String(value || ""));
  }

  window.LifeLinkAdminMFA = Object.freeze({
    authRequest,
    listFactors,
    verifiedFactors,
    enrollTotp,
    challenge,
    verify,
    unenroll,
    refreshSession,
    assuranceLevel,
    qrImageSource,
    signOut,
    isTotpCode
  });
})();
