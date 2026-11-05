// FSD 감속 — 테슬라 목적지 중계 Worker (Cloudflare Workers, 무료 플랜)
//
// 하는 일
//  1) 테슬라가 요구하는 공개키를 /.well-known/appspecific/com.tesla.3p.public-key.pem 에 올린다(도메인 확인용).
//  2) /setup/register?key=<ADMIN_SECRET> : 이 도메인을 테슬라 Fleet API(북미·아시아태평양 지역)에 등록한다.
//     비밀 ADMIN_SECRET 이 없으면 꺼져 있다(404). 등록은 처음 한 번(또는 공개키를 바꿨을 때)만 필요하다.
//  3) /auth/start → 테슬라 로그인 → /auth/callback : 로그인 정보(refresh token)를 KV에 보관하고 앱에 세션 키를 돌려준다.
//     state는 KV에 쓰지 않고 HMAC 서명(10분 유효)으로 확인한다 — 아무나 /auth/start를 눌러도 KV 쓰기가 생기지 않는다.
//  4) /dest (헤더 Authorization: Bearer <세션 키>) : 차의 현재 내비 목적지 이름·좌표를 돌려준다(차가 깨어 있을 때만).
//     차를 깨우지 않는다. 호출 1번 = vehicle_data 1번(약 $0.002).
//  5) POST /auth/logout (같은 헤더) : 그 세션의 테슬라 토큰(tok:)과 차 VIN(vin:)을 KV에서 지운다.
//  - 토큰(tok:)·VIN(vin:)은 90일 동안 안 쓰면 KV에서 저절로 지워진다(토큰을 갱신할 때마다 90일 연장).
//
// 설정(Cloudflare 대시보드 → 이 Worker → 설정)
//  - 비밀(Secret)  CLIENT_SECRET : 테슬라 개발자 대시보드의 "고객 비밀번호"
//  - 비밀(Secret)  STATE_SECRET  : (선택) 로그인 state 서명용 아무 긴 무작위 문자열. 없으면 CLIENT_SECRET으로 서명
//  - 비밀(Secret)  ADMIN_SECRET  : (선택) /setup/register를 쓸 때만 잠깐 넣는다. 평소엔 없음 = 등록 꺼짐
//  - KV 바인딩     TESLA         : 아무 이름의 KV 네임스페이스를 만들어 변수 이름 TESLA로 연결
// 테슬라 토큰은 KV에만 있고 앱에는 가지 않는다. 앱은 무작위 세션 키만 가진다.

const CLIENT_ID = "0841322c-6a94-4ae9-bb67-13cafcbe518c";
const DOMAIN = "fsd-tesla.jsbeth.workers.dev";
const ORIGIN = "https://" + DOMAIN;
const REDIRECT_URI = ORIGIN + "/auth/callback";
const AUDIENCE = "https://fleet-api.prd.na.vn.cloud.tesla.com"; // 한국 = 북미·아시아태평양
const AUTH_URL = "https://auth.tesla.com/oauth2/v3/authorize";
const TOKEN_URL = "https://fleet-auth.prd.vn.cloud.tesla.com/oauth2/v3/token";
const SCOPES = "openid offline_access vehicle_device_data vehicle_location";
const APP_DEEPLINK = "fsdslow://tesla"; // 앱이 받는 주소(로그인 끝나면 세션 키를 붙여 연다)
const KEEP_SEC = 90 * 24 * 3600; // tok:·vin: 보관 기간(안 쓰면 삭제). 테슬라 refresh token 수명(약 3개월)과 맞춤
const STATE_SEC = 600;           // 로그인 state 유효 시간

const PUBLIC_KEY = `-----BEGIN PUBLIC KEY-----
MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAED2ESn7L//mvc47euAyBq3Io17q/o
mDav56PLrYsMrTFdZgcI/MtecUuLUwg2pLyGH2BlDSy9axskwUFGFVR2ig==
-----END PUBLIC KEY-----
`;

export default {
  async fetch(req, env) {
    const url = new URL(req.url);
    try {
      switch (url.pathname) {
        case "/.well-known/appspecific/com.tesla.3p.public-key.pem":
          return new Response(PUBLIC_KEY, { headers: { "content-type": "application/x-pem-file" } });
        case "/":
          return html("FSD 감속 — 테슬라 중계", `<p>동작 중입니다.</p>`);
        case "/setup/register":
          if (!env.ADMIN_SECRET || !safeEq(url.searchParams.get("key") || "", env.ADMIN_SECRET)) return notFound();
          return await register(env);
        case "/auth/start":
          return await authStart(env);
        case "/auth/callback":
          return await authCallback(url, env);
        case "/auth/logout":
          if (req.method !== "POST") return json({ error: "POST only" }, 405);
          return await logout(req, env);
        case "/dest":
          return await dest(req, env);
        default:
          return notFound();
      }
    } catch (e) {
      return json({ error: String(e && e.message || e) }, 500);
    }
  },
};

// ---- 1회 등록: partner token → POST /api/1/partner_accounts
async function register(env) {
  need(env);
  const r = await fetch(TOKEN_URL, {
    method: "POST",
    headers: { "content-type": "application/x-www-form-urlencoded" },
    body: new URLSearchParams({
      grant_type: "client_credentials", client_id: CLIENT_ID, client_secret: env.CLIENT_SECRET,
      scope: "openid vehicle_device_data vehicle_location", audience: AUDIENCE,
    }),
  });
  const tok = await r.json();
  if (!tok.access_token) return html("등록 실패", `<p>파트너 토큰을 못 받았습니다.</p><pre>${esc(JSON.stringify(tok, null, 2))}</pre>`);
  const reg = await fetch(AUDIENCE + "/api/1/partner_accounts", {
    method: "POST",
    headers: { "content-type": "application/json", authorization: "Bearer " + tok.access_token },
    body: JSON.stringify({ domain: DOMAIN }),
  });
  const body = await reg.text();
  const ok = reg.status >= 200 && reg.status < 300;
  return html(ok ? "등록 완료" : "등록 실패",
    `<p>${ok ? "테슬라에 이 주소를 등록했습니다. 이제 앱에서 '테슬라 연결'을 하면 됩니다." : "응답을 캡처해서 보내 주세요."}</p><p>HTTP ${reg.status}</p><pre>${esc(body)}</pre>`);
}

// ---- 로그인 시작
async function authStart(env) {
  need(env);
  const state = await makeState(env);
  const u = new URL(AUTH_URL);
  u.search = new URLSearchParams({
    response_type: "code", client_id: CLIENT_ID, redirect_uri: REDIRECT_URI, scope: SCOPES, state,
    locale: "ko-KR", prompt_missing_scopes: "true",
  }).toString();
  return Response.redirect(u.toString(), 302);
}

// ---- 로그인 끝: code → token, KV에 저장, 앱으로 세션 키 전달
async function authCallback(url, env) {
  need(env);
  const code = url.searchParams.get("code");
  const state = url.searchParams.get("state");
  if (url.searchParams.get("error")) return html("로그인 취소", `<pre>${esc(url.search)}</pre>`);
  if (!code || !state || !(await checkState(env, state))) return html("로그인 실패", "<p>만료됐거나 잘못된 요청입니다. 앱에서 다시 시도하세요.</p>");
  const r = await fetch(TOKEN_URL, {
    method: "POST",
    headers: { "content-type": "application/x-www-form-urlencoded" },
    body: new URLSearchParams({
      grant_type: "authorization_code", client_id: CLIENT_ID, client_secret: env.CLIENT_SECRET,
      code, audience: AUDIENCE, redirect_uri: REDIRECT_URI,
    }),
  });
  const tok = await r.json();
  if (!tok.refresh_token) return html("로그인 실패", `<pre>${esc(JSON.stringify(tok, null, 2))}</pre>`);
  const session = rand(24);
  await saveTokens(env, session, tok);
  const link = APP_DEEPLINK + "?session=" + session;
  return html("테슬라 연결 완료",
    `<p>연결됐습니다. 아래 버튼을 누르면 앱으로 돌아갑니다.</p>
     <p><a style="font-size:20px" href="${link}">앱으로 돌아가기</a></p>
     <p style="color:#777">버튼이 안 되면 이 키를 앱의 '세션 키' 칸에 붙여 넣으세요:<br><code>${session}</code></p>
     <script>setTimeout(function(){ location.href = ${JSON.stringify(link)}; }, 600);</script>`);
}

// ---- 연결 끊기: 그 세션의 토큰·VIN 삭제(없어도 200 — 여러 번 불러도 같음)
async function logout(req, env) {
  need(env);
  const session = bearer(req);
  if (!session) return json({ error: "no session" }, 401);
  await env.TESLA.delete("tok:" + session);
  await env.TESLA.delete("vin:" + session);
  return json({ ok: true });
}

// ---- 현재 목적지
async function dest(req, env) {
  need(env);
  const session = bearer(req);
  if (!session) return json({ error: "no session" }, 401);
  const access = await accessToken(env, session);
  if (!access) return json({ error: "login required" }, 401);

  let vin = await env.TESLA.get("vin:" + session);
  if (!vin) {
    const v = await api(access, "/api/1/vehicles");
    if (v.status !== 200) return json({ error: "vehicles " + v.status, detail: v.body }, 502);
    const list = (v.json && v.json.response) || [];
    if (!list.length) return json({ error: "no vehicles" }, 404);
    vin = list[0].vin;
    await env.TESLA.put("vin:" + session, vin, { expirationTtl: KEEP_SEC });
  }
  const d = await api(access, `/api/1/vehicles/${vin}/vehicle_data?endpoints=${encodeURIComponent("drive_state;location_data")}`);
  if (d.status === 408) return json({ online: false });            // 차가 자는 중(깨우지 않음)
  if (d.status !== 200) return json({ error: "vehicle_data " + d.status, detail: d.body }, 502);
  const ds = (d.json && d.json.response && d.json.response.drive_state) || {};
  const has = typeof ds.active_route_latitude === "number" && typeof ds.active_route_longitude === "number";
  return json({
    online: true,
    destination: has ? {
      name: ds.active_route_destination || "",
      lat: ds.active_route_latitude,
      lon: ds.active_route_longitude,
      minutes: ds.active_route_minutes_to_arrival ?? null,
      km: typeof ds.active_route_miles_to_arrival === "number" ? Math.round(ds.active_route_miles_to_arrival * 1609.344) / 1000 : null,
    } : null,
  });
}

// ---- 토큰 관리: 만료 2분 전이면 refresh(새 refresh token 저장)
async function accessToken(env, session) {
  const raw = await env.TESLA.get("tok:" + session);
  if (!raw) return null;
  const t = JSON.parse(raw);
  if (t.access && t.exp - 120 > now()) return t.access;
  const r = await fetch(TOKEN_URL, {
    method: "POST",
    headers: { "content-type": "application/x-www-form-urlencoded" },
    body: new URLSearchParams({ grant_type: "refresh_token", client_id: CLIENT_ID, refresh_token: t.refresh }),
  });
  let tok = {}; try { tok = await r.json(); } catch (_) {}
  if (!tok.access_token) {
    // 로그인 무효(400/401: invalid_grant 등)만 재로그인. 테슬라 쪽 일시 오류는 다음에 다시 시도하게 502
    if (r.status === 400 || r.status === 401) return null;
    throw new Error("token refresh failed " + r.status);
  }
  // 응답에 새 refresh token이 없으면 기존 것을 그대로 둔다
  if (!tok.refresh_token) tok.refresh_token = t.refresh;
  try { await saveTokens(env, session, tok); }
  catch (e) { await saveTokens(env, session, tok); } // KV 쓰기 1회 재시도(실패하면 회전된 refresh token을 잃는다)
  return tok.access_token;
}

async function saveTokens(env, session, tok) {
  await env.TESLA.put("tok:" + session, JSON.stringify({
    access: tok.access_token, refresh: tok.refresh_token, exp: now() + (tok.expires_in || 3600),
  }), { expirationTtl: KEEP_SEC });
}

// ---- 로그인 state: "만든시각.무작위.서명" (HMAC-SHA256, KV 안 씀)
async function makeState(env) {
  const body = now() + "." + rand(12);
  return body + "." + await hmac(env, body);
}
async function checkState(env, state) {
  const p = state.split(".");
  if (p.length !== 3) return false;
  const t = Number(p[0]);
  if (!Number.isFinite(t) || now() - t > STATE_SEC || t - now() > 60) return false;
  return safeEq(p[2], await hmac(env, p[0] + "." + p[1]));
}
async function hmac(env, text) {
  const enc = new TextEncoder();
  const key = await crypto.subtle.importKey("raw", enc.encode("state:" + (env.STATE_SECRET || env.CLIENT_SECRET)),
    { name: "HMAC", hash: "SHA-256" }, false, ["sign"]);
  const sig = new Uint8Array(await crypto.subtle.sign("HMAC", key, enc.encode(text)));
  return [...sig].map(b => b.toString(16).padStart(2, "0")).join("");
}
// 길이가 같으면 끝까지 비교(시간으로 맞춰 보기 방지)
function safeEq(a, b) {
  a = String(a); b = String(b);
  if (a.length !== b.length) return false;
  let d = 0;
  for (let i = 0; i < a.length; i++) d |= a.charCodeAt(i) ^ b.charCodeAt(i);
  return d === 0;
}

// Authorization: Bearer <세션 키> — 앱과 같은 형식(영숫자 16~128자)만 받는다(쓰레기 키로 KV 읽기 방지)
function bearer(req) {
  const s = (req.headers.get("authorization") || "").replace(/^Bearer\s+/i, "").trim();
  return /^[A-Za-z0-9]{16,128}$/.test(s) ? s : "";
}

async function api(access, path) {
  const r = await fetch(AUDIENCE + path, { headers: { authorization: "Bearer " + access } });
  const body = await r.text();
  let j = null; try { j = JSON.parse(body); } catch (_) {}
  return { status: r.status, json: j, body: body.slice(0, 500) };
}

function need(env) {
  if (!env.CLIENT_SECRET) throw new Error("CLIENT_SECRET 비밀이 설정되지 않았습니다");
  if (!env.TESLA) throw new Error("KV 바인딩 TESLA가 연결되지 않았습니다");
}
const now = () => Math.floor(Date.now() / 1000);
function rand(n) { const a = new Uint8Array(n); crypto.getRandomValues(a); return [...a].map(b => b.toString(16).padStart(2, "0")).join(""); }
function notFound() { return new Response("not found", { status: 404 }); }
function esc(s) { return String(s).replace(/[&<>"]/g, c => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;" }[c])); }
function json(o, status = 200) { return new Response(JSON.stringify(o), { status, headers: { "content-type": "application/json; charset=utf-8" } }); }
function html(title, body) {
  return new Response(`<!doctype html><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>${esc(title)}</title><body style="font-family:system-ui,sans-serif;max-width:640px;margin:32px auto;padding:0 16px"><h2>${esc(title)}</h2>${body}</body>`,
    { headers: { "content-type": "text/html; charset=utf-8" } });
}
