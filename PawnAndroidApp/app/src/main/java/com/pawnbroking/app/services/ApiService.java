package com.pawnbroking.app.services;

import android.content.Context;
import android.content.SharedPreferences;

import com.pawnbroking.app.config.AppConfig;
import com.pawnbroking.app.models.Bill;
import com.pawnbroking.app.models.Company;
import com.pawnbroking.app.models.User;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * Network layer for the pawnbroking-cloud-api (devpawn.magizhchi.academy).
 * The cloud only exposes /v1/auth, /v1/data, /v1/devices, /v1/sync. All
 * tenant data is served as generic projection rows under
 * /v1/data/{table_name}; we adapt those rows back into the legacy shapes
 * the existing screens consume so no UI rewrites are needed.
 *
 * Auth is JWT — minted at /v1/auth/mobile, attached as Bearer on every
 * subsequent call. The cloud is read-only from the mobile app's
 * perspective; write-side flows (billing.save, calculate, etc.) return
 * an error since they live on the desktop side of the sync pipeline.
 */
public class ApiService {
    /**
     * OkHttp client tuned for unstable mobile connections.
     *  - retryOnConnectionFailure(true): default but worth pinning so it
     *    survives an OS-level connection-pool refresh.
     *  - ConnectionPool(0, 1, NANOS): effectively disables keep-alive reuse,
     *    so the carrier/Wi-Fi handoff can't hand us a half-dead socket
     *    that throws "Software caused connection abort" mid-request.
     *  - Tighter read timeout (20 s) so a hung request gives up quickly
     *    instead of staring at a spinner forever.
     */
    private static final OkHttpClient CLIENT = new OkHttpClient.Builder()
            .connectTimeout(java.time.Duration.ofSeconds(15))
            .readTimeout   (java.time.Duration.ofSeconds(20))
            .writeTimeout  (java.time.Duration.ofSeconds(20))
            .retryOnConnectionFailure(true)
            .connectionPool(new okhttp3.ConnectionPool(0, 1, java.util.concurrent.TimeUnit.NANOSECONDS))
            .build();
    /** Long-running uploads (none right now from mobile) and image downloads
     *  use this beefier timeout. */
    private static final OkHttpClient LONG_CLIENT = new OkHttpClient.Builder()
            .connectTimeout(java.time.Duration.ofSeconds(20))
            .readTimeout   (java.time.Duration.ofSeconds(60))
            .writeTimeout  (java.time.Duration.ofSeconds(60))
            .retryOnConnectionFailure(true)
            .build();
    /** Auth calls (OTP send/verify, password login). The box's OTP email
     *  can take 20-30s, which was surfacing as "Server didn't respond in
     *  time" on the 20s CLIENT. This client waits up to 45s. */
    private static final OkHttpClient AUTH_CLIENT = new OkHttpClient.Builder()
            .connectTimeout(java.time.Duration.ofSeconds(15))
            .readTimeout   (java.time.Duration.ofSeconds(45))
            .writeTimeout  (java.time.Duration.ofSeconds(45))
            .retryOnConnectionFailure(true)
            .connectionPool(new okhttp3.ConnectionPool(0, 1, java.util.concurrent.TimeUnit.NANOSECONDS))
            .build();
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    private static final ExecutorService EXEC = Executors.newCachedThreadPool();
    private static final String PREFS = "pawn_prefs";

    public interface Callback<T> {
        void onSuccess(T result);
        void onError(String message);
    }

    // ── Auth ──────────────────────────────────────────────────────────────────

    public static void login(Context ctx, String username, String password, Callback<User> cb) {
        EXEC.execute(() -> {
            try {
                JSONObject body = new JSONObject();
                body.put("shop_id",  AppConfig.SHOP_ID);
                body.put("username", username);
                body.put("password", password);
                Request req = new Request.Builder()
                    .url(AppConfig.LOGIN)
                    .post(RequestBody.create(body.toString(), JSON))
                    .build();
                try (Response res = CLIENT.newCall(req).execute()) {
                    String raw = res.body() != null ? res.body().string() : "";
                    if (res.isSuccessful()) {
                        JSONObject data = new JSONObject(raw);
                        User user = User.fromLogin(data, username);
                        SharedPreferences.Editor ed = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit();
                        ed.putString("token", user.token);
                        ed.putString("userName", user.userName);
                        ed.putString("employeeName", user.employeeName);
                        ed.putString("shopId", AppConfig.SHOP_ID);
                        ed.apply();
                        cb.onSuccess(user);
                    } else {
                        cb.onError(extractError(raw, res.code(), "Login failed"));
                    }
                }
            } catch (Exception e) {
                cb.onError(e.getMessage());
            }
        });
    }

    public static void logout(Context ctx) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply();
    }

    // ── Box-OTP login (passwordless) ─────────────────────────────────────────

    /**
     * Asks the cloud-api to send an OTP for {@code email} via Magizhchi Share.
     * No shop_id needed — the cloud accepts the email if it's in
     * public.user_shop_access for at least one active shop.
     */
    public static void requestOtp(String email, Callback<Void> cb) {
        EXEC.execute(() -> {
            try {
                JSONObject body = new JSONObject();
                body.put("email", email);
                Request req = new Request.Builder()
                    .url(AppConfig.BOX_SEND_OTP)
                    .post(RequestBody.create(body.toString(), JSON))
                    .build();
                try (Response res = executeWithRetry(req, 2)) {
                    String raw = res.body() != null ? res.body().string() : "";
                    if (res.isSuccessful()) { cb.onSuccess(null); return; }
                    if (res.code() == 429) {
                        int wait = parseRetryAfterSec(res.header("Retry-After"));
                        if (wait <= 0) wait = 60;
                        cb.onError("RATE_LIMIT:" + wait);
                        return;
                    }
                    cb.onError(extractError(raw, res.code(), "Could not send OTP"));
                }
            } catch (Exception e) { cb.onError(friendlyNetError(e)); }
        });
    }

    /** Reads Retry-After (seconds-form or HTTP-date); returns 0 if missing/bad. */
    private static int parseRetryAfterSec(String header) {
        if (header == null || header.isEmpty()) return 0;
        try { return Integer.parseInt(header.trim()); } catch (Exception ignored) {}
        try {
            long when = java.util.Date.parse(header);   // HTTP-date form
            long now  = System.currentTimeMillis();
            return (int) Math.max(0, (when - now) / 1000);
        } catch (Exception ignored) {}
        return 0;
    }

    /**
     * Result of OTP verification.
     *  - SINGLE_SHOP: cloud minted a full access token → user is logged in
     *    and can go to Home.
     *  - MULTI_SHOP : cloud returned a selector token + list of shops →
     *    LoginActivity launches the shop picker; ShopPickerActivity calls
     *    {@link #selectShop} when the user taps a shop.
     */
    public static final class VerifyResult {
        public enum Kind { SINGLE_SHOP, MULTI_SHOP }
        public final Kind   kind;
        public final User   user;            // populated for SINGLE_SHOP
        public final String selectorToken;   // populated for MULTI_SHOP
        public final JSONArray shops;        // populated for MULTI_SHOP
        public final String email;
        private VerifyResult(Kind k, User u, String sel, JSONArray sh, String em) {
            this.kind = k; this.user = u; this.selectorToken = sel;
            this.shops = sh; this.email = em;
        }
        static VerifyResult single(User u, String em) {
            return new VerifyResult(Kind.SINGLE_SHOP, u, null, null, em);
        }
        static VerifyResult multi(String sel, JSONArray sh, String em) {
            return new VerifyResult(Kind.MULTI_SHOP, null, sel, sh, em);
        }
    }

    /**
     * Verifies the OTP. Handles BOTH the single-shop (access token minted
     * immediately) and multi-shop (selector token + shops list) cloud
     * responses. On single-shop success, JWT is persisted to prefs and the
     * user is fully logged in. On multi-shop, prefs are NOT touched — the
     * caller must navigate to the picker and call {@link #selectShop}.
     */
    public static void verifyOtpAndLogin(Context ctx, String email, String code,
                                          Callback<VerifyResult> cb) {
        EXEC.execute(() -> {
            try {
                JSONObject body = new JSONObject();
                body.put("email", email);
                body.put("code",  code);
                Request req = new Request.Builder()
                    .url(AppConfig.BOX_VERIFY)
                    .post(RequestBody.create(body.toString(), JSON))
                    .build();
                try (Response res = executeWithRetry(req, 2)) {
                    String raw = res.body() != null ? res.body().string() : "";
                    if (!res.isSuccessful()) {
                        cb.onError(extractError(raw, res.code(), "OTP verification failed"));
                        return;
                    }
                    JSONObject data = new JSONObject(raw);

                    // Multi-shop branch — cloud returns a selector + the list.
                    if (data.has("selector_token") && data.has("shops")) {
                        cb.onSuccess(VerifyResult.multi(
                            data.optString("selector_token", ""),
                            data.optJSONArray("shops"),
                            email));
                        return;
                    }

                    // Single-shop branch — full access token minted.
                    User user = User.fromLogin(data, email);
                    persistSession(ctx, user, data.optString("shop_id", ""));
                    cb.onSuccess(VerifyResult.single(user, email));
                }
            } catch (Exception e) { cb.onError(friendlyNetError(e)); }
        });
    }

    /**
     * Password login — same single/multi-shop result shape as OTP verify,
     * but no OTP round-trip. Returns VerifyResult so LoginActivity can
     * reuse the exact same navigation.
     */
    public static void passwordLogin(Context ctx, String email, String password,
                                      Callback<VerifyResult> cb) {
        EXEC.execute(() -> {
            try {
                JSONObject body = new JSONObject();
                body.put("email", email);
                body.put("password", password);
                Request req = new Request.Builder()
                    .url(AppConfig.BOX_PASSWORD_LOGIN)
                    .post(RequestBody.create(body.toString(), JSON))
                    .build();
                try (Response res = executeWithRetry(req, 2)) {
                    String raw = res.body() != null ? res.body().string() : "";
                    if (!res.isSuccessful()) {
                        cb.onError(extractError(raw, res.code(), "Login failed"));
                        return;
                    }
                    JSONObject data = new JSONObject(raw);
                    if (data.has("selector_token") && data.has("shops")) {
                        cb.onSuccess(VerifyResult.multi(
                            data.optString("selector_token", ""),
                            data.optJSONArray("shops"), email));
                        return;
                    }
                    User user = User.fromLogin(data, email);
                    persistSession(ctx, user, data.optString("shop_id", ""));
                    cb.onSuccess(VerifyResult.single(user, email));
                }
            } catch (Exception e) { cb.onError(friendlyNetError(e)); }
        });
    }

    /**
     * Sets/changes the password for an email. Requires a fresh OTP code
     * (proves inbox ownership). After this the user can use passwordLogin.
     */
    public static void setPassword(String email, String otpCode, String newPassword,
                                    Callback<String> cb) {
        EXEC.execute(() -> {
            try {
                JSONObject body = new JSONObject();
                body.put("email", email);
                body.put("code", otpCode);
                body.put("password", newPassword);
                Request req = new Request.Builder()
                    .url(AppConfig.BOX_SET_PASSWORD)
                    .post(RequestBody.create(body.toString(), JSON))
                    .build();
                try (Response res = executeWithRetry(req, 2)) {
                    String raw = res.body() != null ? res.body().string() : "";
                    if (!res.isSuccessful()) {
                        cb.onError(extractError(raw, res.code(), "Could not set password"));
                        return;
                    }
                    cb.onSuccess(new JSONObject(raw).optString("message", "Password set."));
                }
            } catch (Exception e) { cb.onError(friendlyNetError(e)); }
        });
    }

    /**
     * Exchanges a token + chosen shop_id for a full access token. The
     * "token" can be either a selector token (post-OTP first login) OR
     * the live access token (Switch-Shop from Home). If null, falls back
     * to whatever JWT is stored in prefs.
     */
    public static void selectShop(Context ctx, String selectorOrAccessToken, String shopId,
                                   String email, Callback<User> cb) {
        EXEC.execute(() -> {
            try {
                String bearer = (selectorOrAccessToken == null || selectorOrAccessToken.isEmpty())
                        ? token(ctx) : selectorOrAccessToken;
                if (bearer == null || bearer.isEmpty()) {
                    cb.onError("No active session — please sign in again.");
                    return;
                }
                JSONObject body = new JSONObject().put("shop_id", shopId);
                Request req = new Request.Builder()
                    .url(AppConfig.BOX_SELECT_SHOP)
                    .header("Authorization", "Bearer " + bearer)
                    .post(RequestBody.create(body.toString(), JSON))
                    .build();
                try (Response res = executeWithRetry(req, 2)) {
                    String raw = res.body() != null ? res.body().string() : "";
                    if (!res.isSuccessful()) {
                        cb.onError(extractError(raw, res.code(), "Shop selection failed"));
                        return;
                    }
                    JSONObject data = new JSONObject(raw);
                    User user = User.fromLogin(data, email);
                    persistSession(ctx, user, data.optString("shop_id", shopId));
                    cb.onSuccess(user);
                }
            } catch (Exception e) { cb.onError(friendlyNetError(e)); }
        });
    }

    /**
     * Lists every shop the currently-signed-in email can switch to. Used by
     * the Home screen's "Switch Shop" menu. Works with the active access
     * token (no re-OTP needed).
     */
    public static void getMyShops(Callback<JSONArray> cb) {
        EXEC.execute(() -> {
            try {
                Request req = new Request.Builder()
                    .url(AppConfig.BOX_MY_SHOPS)
                    .header("Authorization", "Bearer " + token(resolveCtx()))
                    .get()
                    .build();
                try (Response res = CLIENT.newCall(req).execute()) {
                    String raw = res.body() != null ? res.body().string() : "{}";
                    if (!res.isSuccessful()) {
                        cb.onError(extractError(raw, res.code(), "Shop list failed"));
                        return;
                    }
                    cb.onSuccess(new JSONObject(raw).optJSONArray("shops"));
                }
            } catch (Exception e) { cb.onError(friendlyNetError(e)); }
        });
    }

    /** Persists JWT + shop_id + display fields to SharedPreferences. */
    private static void persistSession(Context ctx, User user, String shopId) {
        SharedPreferences.Editor ed = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit();
        ed.putString("token",        user.token);
        ed.putString("userName",     user.userName);
        ed.putString("employeeName", user.employeeName);
        // shop_id from cloud → used everywhere the app needs to identify the
        // active tenant. Falls back to AppConfig.SHOP_ID for older builds.
        ed.putString("shopId",       (shopId == null || shopId.isEmpty())
                                          ? AppConfig.SHOP_ID : shopId);
        ed.apply();
    }

    /** Returns the active shop_id (from JWT login) — replaces the
     *  hardcoded AppConfig.SHOP_ID for runtime decisions. */
    public static String getCurrentShopId(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                  .getString("shopId", AppConfig.SHOP_ID);
    }

    /**
     * The active shop_id for the logged-in session, resolved without a
     * caller-supplied Context. THE single source of truth for "which tenant
     * am I" at runtime — reads the shop_id the cloud returned at login
     * (stored in prefs), NOT the build-time AppConfig.SHOP_ID. That's what
     * makes this one APK universal: install once, and whoever signs in sees
     * their own shop's records. Falls back to AppConfig.SHOP_ID only when
     * there's no Application context yet (pre-login, before any data call).
     */
    private static String currentShop() {
        Context c = resolveCtx();
        return c == null ? AppConfig.SHOP_ID : getCurrentShopId(c);
    }

    /** Execute with up to {@code attempts} tries on IOException (covers
     *  SocketException "connection abort", "broken pipe", read timeouts). */
    private static Response executeWithRetry(Request req, int attempts) throws java.io.IOException {
        java.io.IOException last = null;
        for (int i = 0; i < attempts; i++) {
            try {
                // AUTH_CLIENT: longer read timeout so a slow OTP email
                // doesn't trip "Server didn't respond in time".
                return AUTH_CLIENT.newCall(req).execute();
            } catch (java.io.IOException ioe) {
                last = ioe;
                android.util.Log.w("ApiService",
                    "attempt " + (i+1) + " failed: " + ioe + " — retrying");
                try { Thread.sleep(400L * (i + 1)); }
                catch (InterruptedException ie) { Thread.currentThread().interrupt(); break; }
            }
        }
        throw last == null ? new java.io.IOException("no attempts") : last;
    }

    /** Turn OkHttp/Socket exception jargon into a sentence a user can act on. */
    private static String friendlyNetError(Exception e) {
        String m = e == null || e.getMessage() == null ? "" : e.getMessage();
        String lc = m.toLowerCase();
        if (lc.contains("connection abort") || lc.contains("broken pipe")
            || lc.contains("connection reset") || lc.contains("eof"))
            return "Network dropped mid-request — please tap Send OTP again.";
        if (lc.contains("timeout") || lc.contains("timed out"))
            return "Server didn't respond in time — please try again.";
        if (lc.contains("unknown host") || lc.contains("unable to resolve host"))
            return "No internet connection — check Wi-Fi or mobile data.";
        if (lc.contains("sslhandshake"))
            return "Secure connection failed — check device date/time and retry.";
        return m.isEmpty() ? "Network error — please try again." : m;
    }

    public static boolean isLoggedIn(Context ctx) {
        // contains("token") alone returns true for an empty/null value, which
        // would leave the splash routing to Home with no real JWT and every
        // data call 401'ing. Require a non-empty token to be considered
        // logged in.
        String t = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("token", null);
        return t != null && !t.isEmpty();
    }

    public static String getSavedEmployeeName(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                  .getString("employeeName", "");
    }

    /** Holder for the JWT minted by the most recent login. */
    private static String token(Context ctx) {
        return ctx == null ? null
            : ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("token", null);
    }

    private static Context appCtx; // set from PawnApp.onCreate (primary path)
    public static void bindContext(Context ctx) { appCtx = ctx == null ? null : ctx.getApplicationContext(); }

    /**
     * Returns the Application context for SharedPreferences access. Tries the
     * bound context first; if {@link #bindContext} hasn't been called yet
     * (e.g. PawnApp.onCreate hasn't run, or this is invoked from a background
     * worker before any activity), falls back to the running Application via
     * ActivityThread reflection. Without this fallback, the JWT can't be read
     * and every authenticated request goes out without a Bearer header.
     */
    private static Context resolveCtx() {
        if (appCtx != null) return appCtx;
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Object app = at.getMethod("currentApplication").invoke(null);
            if (app instanceof Context c) {
                appCtx = c;
                android.util.Log.w("ApiService",
                    "bindContext was not called — recovered Application via ActivityThread");
                return c;
            }
        } catch (Throwable ignored) {}
        android.util.Log.e("ApiService",
            "no Application context available — outgoing requests will be unauthenticated");
        return null;
    }

    private static Request.Builder authed(HttpUrl url) {
        Request.Builder b = new Request.Builder().url(url);
        String t = token(resolveCtx());
        if (t != null && !t.isEmpty()) {
            b.header("Authorization", "Bearer " + t);
        } else {
            android.util.Log.w("ApiService",
                "no JWT in prefs — request to " + url.encodedPath() + " will be unauthenticated");
        }
        return b;
    }

    // ── Companies (synthesised — cloud has only one tenant bound at build time) ─

    public static void getCompanies(Callback<List<Company>> cb) {
        EXEC.execute(() -> {
            try {
                JSONArray rows = fetchTableSync(AppConfig.TBL_COMPANY, null);
                List<Company> list = new ArrayList<>();
                for (int i = 0; i < rows.length(); i++) {
                    JSONObject body = unwrapPayload(rows.getJSONObject(i));
                    sanitizeNulls(body);
                    list.add(Company.fromJson(body));
                }
                // Sort by name so the dropdown is stable across reloads.
                list.sort((a, b) -> {
                    String an = a.name == null ? "" : a.name;
                    String bn = b.name == null ? "" : b.name;
                    return an.compareToIgnoreCase(bn);
                });
                // Tenant has zero `company` rows → fall back to a synthesized
                // entry so the spinner never goes empty + Home can still open.
                // Uses the LOGGED-IN shop, not the build-time default, so the
                // single universal APK shows the right shop after sign-in.
                if (list.isEmpty()) {
                    String shop = currentShop();
                    list.add(new Company(shop, capitalize(shop),
                                         "", null, null, null, "ACTIVE"));
                }
                cb.onSuccess(list);
            } catch (Exception e) { cb.onError(e.getMessage()); }
        });
    }

    // ── Bills ─────────────────────────────────────────────────────────────────

    public static void getBills(String companyId, String type, String status,
                                String search, int page, int size,
                                Callback<BillsResult> cb) {
        EXEC.execute(() -> {
            try {
                String table = AppConfig.TBL_BILL_OPENING;
                HttpUrl.Builder b = HttpUrl.parse(AppConfig.DATA_BASE + "/" + table).newBuilder()
                    .addQueryParameter("limit", String.valueOf(Math.max(size, 1)));
                if (search != null && !search.isEmpty()) b.addQueryParameter("q", search);
                try (Response res = CLIENT.newCall(authed(b.build()).get().build()).execute()) {
                    String raw = res.body() != null ? res.body().string() : "[]";
                    checkStatus(res, raw);
                    JSONArray arr = new JSONArray(raw);
                    List<Bill> bills = new ArrayList<>();
                    for (int i = 0; i < arr.length(); i++) {
                        JSONObject row = arr.getJSONObject(i);
                        JSONObject body = row.optJSONObject("payload");
                        if (body == null) body = row;
                        if (companyId != null && !companyId.isEmpty()
                            && !"ALL".equalsIgnoreCase(companyId)
                            && !companyId.equalsIgnoreCase(currentShop())
                            && !companyId.equalsIgnoreCase(body.optString("company_id", "")))
                            continue;
                        Bill bill = Bill.fromJson(row);
                        if (!matchesFilter(bill, type, status)) continue;
                        bills.add(bill);
                    }
                    // client-side pagination since cloud /v1/data is just a top-N list
                    int from = Math.max(page * size, 0);
                    int to   = Math.min(from + size, bills.size());
                    List<Bill> pageSlice = from < bills.size()
                        ? new ArrayList<>(bills.subList(from, to))
                        : new ArrayList<>();
                    cb.onSuccess(new BillsResult(pageSlice, bills.size()));
                }
            } catch (Exception e) { cb.onError(e.getMessage()); }
        });
    }

    public static void getBillDetail(String companyId, String billNumber, String type,
                                     Callback<JSONObject> cb) {
        EXEC.execute(() -> {
            try {
                // Bills are projected with composite row_pk =
                // "<company_id>|<jewel_material_type>|<bill_number>". The
                // mobile-side companyId is the SHOP_ID (e.g. "mylocal"), not
                // the desktop's company id (e.g. "CMP1"), so we can't compose
                // the row_pk directly. Use the cloud's ILIKE search on the
                // JSONB payload and pick the row whose bill_number matches
                // exactly (and material, when supplied).
                JSONArray rows = fetchTableSync(AppConfig.TBL_BILL_OPENING, billNumber);
                JSONObject match = null;
                for (int i = 0; i < rows.length(); i++) {
                    JSONObject body = unwrapPayload(rows.getJSONObject(i));
                    if (!billNumber.equalsIgnoreCase(body.optString("bill_number", ""))) continue;
                    if (type != null && !type.isEmpty()
                        && !"ALL".equalsIgnoreCase(type)
                        && !type.equalsIgnoreCase(body.optString("jewel_material_type", ""))) continue;
                    // Multi-company tenant: don't return CMP2's E18444 when
                    // the user is browsing CMP1.
                    if (companyId != null && !companyId.isEmpty()
                        && !"ALL".equalsIgnoreCase(companyId)
                        && !companyId.equalsIgnoreCase(currentShop())
                        && !companyId.equalsIgnoreCase(body.optString("company_id", "")))
                        continue;
                    match = body;
                    break;
                }
                if (match == null) { cb.onError("Bill " + billNumber + " not found"); return; }
                sanitizeNulls(match);
                addBillAliases(match);
                mergeRepledgeInto(match);
                cb.onSuccess(match);
            } catch (Exception e) { cb.onError(e.getMessage()); }
        });
    }

    /**
     * If the bill is linked to a repledge (company_billing.repledge_bill_id
     * non-empty), look up that row in repledge_billing and copy its fields
     * into the response with a `repl_` prefix so the Billing screen's
     * REPLEDGE INFORMATION block — and any future repledge-opening/closing
     * sub-sections — render with real data.
     */
    private static void mergeRepledgeInto(JSONObject bill) {
        String repId = bill.optString("repledge_bill_id", "").trim();
        if (repId.isEmpty()) return;
        try {
            JSONArray rows = fetchTableSync(AppConfig.TBL_REPLEDGE, repId, null);
            JSONObject rb = null;
            for (int i = 0; i < rows.length(); i++) {
                JSONObject body = unwrapPayload(rows.getJSONObject(i));
                if (repId.equalsIgnoreCase(body.optString("repledge_bill_id", ""))) {
                    rb = body; break;
                }
            }
            if (rb == null) return;
            sanitizeNulls(rb);
            // Defaults for fields that are commonly null in real data so the
            // screen never shows blank labels next to populated rows.
            if (rb.optString("interest_type", "").isEmpty()) {
                String fallback = bill.optString("interest_type", "MONTH");
                rb.put("interest_type", fallback.isEmpty() ? "MONTH" : fallback);
            }
            // Legacy field names the existing layout reads.
            bill.put("repl_name",          rb.optString("repledge_name", ""));
            bill.put("repl_bill_number",   rb.optString("repledge_bill_number", ""));
            bill.put("repl_company_bill",  rb.optString("company_bill_number", ""));
            bill.put("repl_opening_date",  rb.optString("opening_date", ""));
            bill.put("repl_amount",        rb.optDouble("amount", 0));
            bill.put("repl_status",        rb.optString("status", ""));
            // Make every repledge field accessible to a richer sub-section
            // later — prefixed so it never collides with the parent bill.
            java.util.Iterator<String> it = rb.keys();
            while (it.hasNext()) {
                String k = it.next();
                String pk = "repl_" + k;
                if (!bill.has(pk)) bill.put(pk, rb.opt(k));
            }
        } catch (Exception ignored) { /* repledge linkage is optional */ }
    }

    /**
     * The BillingActivity layout was written for the old REST server's field
     * names. The desktop's actual columns differ — copy the values across so
     * the screen renders without changing the layout XML.
     */
    private static JSONObject addBillAliases(JSONObject b) {
        try {
            // material_type ← jewel_material_type
            if (!b.has("material_type") && b.has("jewel_material_type"))
                b.put("material_type", b.optString("jewel_material_type"));
            // taken_amount ← open_taken_amount
            if (!b.has("taken_amount") && b.has("open_taken_amount"))
                b.put("taken_amount", b.opt("open_taken_amount"));
            // to_give_amount ← togive_amount
            if (!b.has("to_give_amount") && b.has("togive_amount"))
                b.put("to_give_amount", b.opt("togive_amount"));
            // close_interest_type ← interest_type
            if (!b.has("close_interest_type") && b.has("interest_type"))
                b.put("close_interest_type", b.optString("interest_type"));
        } catch (Exception ignored) {}
        return b;
    }

    // ── Dashboard ─────────────────────────────────────────────────────────────

    public static void getDashboard(String companyId, String date, Callback<JSONObject> cb) {
        EXEC.execute(() -> {
            try {
                HttpUrl url = HttpUrl.parse(AppConfig.DATA_DASHBOARD).newBuilder().build();
                try (Response res = CLIENT.newCall(authed(url).get().build()).execute()) {
                    String raw = res.body() != null ? res.body().string() : "{}";
                    checkStatus(res, raw);
                    cb.onSuccess(new JSONObject(raw));
                }
            } catch (Exception e) { cb.onError(e.getMessage()); }
        });
    }

    // ── Today's Account ───────────────────────────────────────────────────────

    /**
     * Resolves the "last" account date — the {@code todays_date} value of
     * the {@code company_todays_account} row marked {@code ref_mark='L'}
     * for the given company. Used as the Today's Account screen's default
     * date instead of the device's current date (most shops are running a
     * day or more behind today's calendar).
     */
    public static void getLastAccountDate(String companyId, Callback<String> cb) {
        EXEC.execute(() -> {
            try {
                // Use the cloud's dedicated refMark filter (JSONB-keyed) — the
                // previous q="ref_mark":"L" substring trick failed when the
                // payload was stored as `json` with whitespace ("ref_mark":
                // "L" instead of "ref_mark":"L"). We probe without companyId
                // so the diagnostic can list mismatched company_ids if the
                // sync stored a different spelling than the picker uses.
                JSONArray rows = fetchTodaysAccountRows(/*companyId*/ null,
                        /*todaysDate*/ null, /*refMark*/ "L", "todays_date:desc");

                String best = null;
                java.util.TreeMap<String,String> bestPerCompany = new java.util.TreeMap<>();
                for (int i = 0; i < rows.length(); i++) {
                    JSONObject body = unwrapPayload(rows.getJSONObject(i));
                    if (!"L".equalsIgnoreCase(body.optString("ref_mark", ""))) continue;
                    String d = body.optString("todays_date", "");
                    if (d.length() >= 10) d = d.substring(0, 10);
                    if (d.isEmpty()) continue;
                    String rowCompany = body.optString("company_id", "");
                    if (rowCompany.isEmpty()) rowCompany = "(empty)";
                    String prev = bestPerCompany.get(rowCompany);
                    if (prev == null || d.compareTo(prev) > 0) bestPerCompany.put(rowCompany, d);

                    boolean matches = companyId == null || companyId.isEmpty()
                            || "ALL".equalsIgnoreCase(companyId)
                            || companyId.equalsIgnoreCase(currentShop())
                            || companyId.equalsIgnoreCase(body.optString("company_id", ""));
                    if (!matches) continue;
                    if (best == null || d.compareTo(best) > 0) best = d;
                }

                if (best != null) { cb.onSuccess(best); return; }

                // No match for the requested company — tell the caller WHY.
                if (bestPerCompany.isEmpty()) {
                    cb.onError("Cloud has 0 L-marker rows in company_todays_account "
                            + "(sync agent may not have pushed this table yet)");
                } else {
                    StringBuilder sb = new StringBuilder("No L row for ")
                        .append(companyId).append(". Cloud has L rows for: ");
                    int n = 0;
                    for (java.util.Map.Entry<String,String> e : bestPerCompany.entrySet()) {
                        if (n++ > 0) sb.append(", ");
                        sb.append(e.getKey()).append("→").append(e.getValue());
                        if (n >= 4) { sb.append(", …"); break; }
                    }
                    cb.onError(sb.toString());
                }
            } catch (Exception e) { cb.onError(e.getMessage()); }
        });
    }

    public static void getTodaysAccount(String companyId, String date, Callback<JSONObject> cb) {
        EXEC.execute(() -> {
            try {
                // Use the cloud's dedicated todaysDate filter (JSONB-keyed)
                // instead of the brittle q-substring trick. companyId is
                // dropped from the server filter so diagnostics can list
                // mismatched IDs client-side if the lookup still fails.
                JSONArray rows = fetchTodaysAccountRows(/*companyId*/ null,
                        date, /*refMark*/ null, "todays_date:desc");
                // Two-pass: collect all matching rows for (companyId, date),
                // then prefer the one with ref_mark='L' (the canonical "locked"
                // day-end) over interim "S" save snapshots.
                JSONObject acct = null;
                JSONObject anyMatch = null;
                java.util.TreeSet<String> companiesSeenOnDate = new java.util.TreeSet<>();
                for (int i = 0; i < rows.length(); i++) {
                    JSONObject body = unwrapPayload(rows.getJSONObject(i));
                    String rowDate = body.optString("todays_date", "");
                    if (rowDate.length() >= 10) rowDate = rowDate.substring(0, 10);
                    String rowCompany = body.optString("company_id", "");
                    if (date != null && !date.isEmpty() && !date.equals(rowDate)) continue;
                    companiesSeenOnDate.add(rowCompany.isEmpty() ? "(empty)" : rowCompany);
                    if (companyId != null && !companyId.isEmpty()
                            && !"ALL".equalsIgnoreCase(companyId)
                            && !companyId.equalsIgnoreCase(currentShop())
                            && !companyId.equalsIgnoreCase(rowCompany)) continue;
                    if (anyMatch == null) anyMatch = body;
                    if ("L".equalsIgnoreCase(body.optString("ref_mark", ""))) {
                        acct = body;
                        break;
                    }
                }
                if (acct == null) acct = anyMatch;
                // If still nothing, log what companies DID have rows on this
                // date so the diagnostic chip can show the mismatch.
                if (acct == null && !companiesSeenOnDate.isEmpty()) {
                    android.util.Log.w("ApiService",
                        "todays_account: no row for " + companyId + " on " + date
                        + " — date has rows for companies: " + companiesSeenOnDate);
                }

                JSONObject out = new JSONObject();
                out.put("date", date == null ? "" : date);
                if (acct != null) {
                    sanitizeNulls(acct);
                    String preDate = acct.optString("pre_date", "");
                    if (preDate.length() >= 10) preDate = preDate.substring(0, 10);
                    out.put("preDate",             preDate);
                    out.put("preActualBalance",    acct.optDouble("pre_actual_amount",       0));
                    out.put("preAvailableBalance", acct.optDouble("pre_available_amount",    0));
                    out.put("preDeficit",          acct.optDouble("pre_deficit_amount",     0));
                    out.put("preNote",             acct.optString("pre_note",                ""));
                    out.put("actualBalance",       acct.optDouble("todays_actual_amount",   0));
                    out.put("availableBalance",    acct.optDouble("todays_available_amount",0));
                    out.put("deficit",             acct.optDouble("todays_deficit_amount",  0));
                    out.put("todaysNote",          acct.optString("todays_note",             ""));
                    // ref_mark='L' means day-end is locked/closed.
                    out.put("accountStatus",
                            "L".equalsIgnoreCase(acct.optString("ref_mark","")) ? "CLOSED" : "OPEN");
                }
                // Operations breakdown (debit/credit by op type) requires
                // aggregating multiple source tables — return empty for now;
                // activity falls back to 0 rows gracefully.
                out.put("totalDebit",  0);
                out.put("totalCredit", 0);
                out.put("operations",  new JSONArray());
                cb.onSuccess(out);
            } catch (Exception e) { cb.onError(e.getMessage()); }
        });
    }

    /**
     * Operations breakdown for the Today's Account screen — debit/credit by
     * op type (bill openings/closings, repledge, advance). Cloud aggregates
     * server-side so the mobile app only renders the table.
     */
    public static void getTodaysAccountOps(String companyId, String date,
                                            Callback<JSONObject> cb) {
        EXEC.execute(() -> {
            try {
                HttpUrl.Builder b = HttpUrl.parse(AppConfig.DATA_BASE + "/todays-account-ops")
                        .newBuilder()
                        .addQueryParameter("date", date == null ? "" : date);
                if (companyId != null && !companyId.isEmpty()
                        && !"ALL".equalsIgnoreCase(companyId)
                        && !companyId.equalsIgnoreCase(currentShop()))
                    b.addQueryParameter("companyId", companyId);
                try (Response res = CLIENT.newCall(authed(b.build()).get().build()).execute()) {
                    String raw = res.body() != null ? res.body().string() : "{}";
                    if (!res.isSuccessful()) {
                        cb.onError(extractError(raw, res.code(), "Operations lookup failed"));
                        return;
                    }
                    cb.onSuccess(new JSONObject(raw));
                }
            } catch (Exception e) { cb.onError(friendlyNetError(e)); }
        });
    }

    /**
     * Drill-down for an Operations row on Today's Account. Dispatches on
     * the type (GOLD_OPENING, GOLD_CLOSING, SILVER_*, REPLEDGE_*, EXPENSES,
     * INCOMES, *_ADVANCE), pulls the right table from cloud, filters by
     * date + material client-side, and returns the activity's expected
     * shape: {headers, rows, count}.
     */
    public static void getTodaysAccountDetails(String companyId, String date, String type,
                                               Callback<JSONObject> cb) {
        EXEC.execute(() -> {
            try {
                String t = type == null ? "" : type.toUpperCase();
                String material = t.startsWith("GOLD") ? "GOLD"
                              : t.startsWith("SILVER") ? "SILVER" : null;
                JSONObject out;
                if (t.endsWith("_OPENING") && material != null) {
                    out = buildBillOpeningDetail(companyId, date, material);
                } else if (t.endsWith("_CLOSING") && material != null) {
                    out = buildBillClosingDetail(companyId, date, material);
                } else if (t.endsWith("_ADVANCE") && material != null) {
                    out = buildAdvanceDetail(companyId, date, material);
                } else if ("REPLEDGE_OPENING".equals(t)) {
                    out = buildRepledgeDetail(companyId, date, "opening_date", "REPLEDGE OPENING");
                } else if ("REPLEDGE_CLOSING".equals(t)) {
                    out = buildRepledgeDetail(companyId, date, "closing_date", "REPLEDGE CLOSING");
                } else if ("EXPENSES".equals(t)) {
                    out = buildExpenseIncomeDetailMerged(companyId, date, "EXPENSE");
                } else if ("INCOMES".equals(t)) {
                    out = buildExpenseIncomeDetailMerged(companyId, date, "INCOME");
                } else {
                    out = new JSONObject().put("headers", new JSONArray())
                                          .put("rows",    new JSONArray())
                                          .put("count",   0);
                }
                cb.onSuccess(out);
            } catch (Exception e) { cb.onError(friendlyNetError(e)); }
        });
    }

    // ── detail-table builders ────────────────────────────────────────────

    private static JSONObject buildBillOpeningDetail(String companyId, String date, String material)
            throws Exception {
        JSONArray raw = fetchTableSync(AppConfig.TBL_BILL_OPENING, null,
                "opening_date:desc", companyId, material,
                /*statuses*/ null, /*repledged*/ null,
                date, date, null, null, null);
        JSONArray rows = new JSONArray();
        int count = 0;
        for (int i = 0; i < raw.length(); i++) {
            JSONObject b = unwrapPayload(raw.getJSONObject(i));
            if (!startsWith(b.optString("opening_date",""), date)) continue;
            String status = b.optString("status","").toUpperCase();
            if (status.equals("CANCELED") || status.equals("CANCELLED")) continue;
            JSONArray r = new JSONArray();
            r.put(idx(i+1));
            r.put(b.optString("bill_number",""));
            r.put(b.optString("customer_name",""));
            r.put(money(b.optDouble("amount", 0)));
            r.put(money(b.optDouble("document_charge", 0)));
            r.put(money(b.optDouble("open_taken_amount", 0)));
            rows.put(r);
            count++;
        }
        return new JSONObject()
                .put("headers", arr("#","Bill No","Customer","Amount","Doc","Taken"))
                .put("rows",    rows)
                .put("count",   count);
    }

    private static JSONObject buildBillClosingDetail(String companyId, String date, String material)
            throws Exception {
        JSONArray raw = fetchTableSync(AppConfig.TBL_BILL_CLOSING, null,
                "closing_date:desc", companyId, material,
                /*statuses*/ null, /*repledged*/ null,
                null, null, null, null, null);
        java.util.Set<String> closedSet = new java.util.HashSet<>(java.util.Arrays.asList(
                "CLOSED","DELIVERED","REBILLED","REBILLED-ADDED","REBILLED-REMOVED","REBILLED-MULTIPLE"));
        JSONArray rows = new JSONArray();
        int count = 0;
        for (int i = 0; i < raw.length(); i++) {
            JSONObject b = unwrapPayload(raw.getJSONObject(i));
            if (!startsWith(b.optString("closing_date",""), date)) continue;
            String status = b.optString("status","").toUpperCase();
            if (!closedSet.contains(status)) continue;
            double amt  = b.optDouble("amount",                    0);
            double intr = b.optDouble("close_taken_amount",        0);
            double fine = b.optDouble("total_other_charges",       0);
            double less = b.optDouble("discount_amount",           0);
            double adv  = b.optDouble("total_advance_amount_paid", 0);
            JSONArray r = new JSONArray();
            r.put(idx(i+1));
            r.put(b.optString("bill_number",""));
            r.put(b.optString("customer_name",""));
            r.put(money(amt));
            r.put(money(intr));
            r.put(money(fine));
            r.put(money(less));
            r.put(money(amt + intr + fine - less + adv));
            rows.put(r);
            count++;
        }
        return new JSONObject()
                .put("headers", arr("#","Bill No","Customer","Amount","Intr","Fine","Less","Total"))
                .put("rows",    rows)
                .put("count",   count);
    }

    private static JSONObject buildAdvanceDetail(String companyId, String date, String material)
            throws Exception {
        // Advance rows carry bill_number. We pull all advances for the date
        // then look up each bill's material client-side to filter.
        JSONArray raw = fetchTableSync(AppConfig.TBL_ADVANCE, null);
        JSONArray rows = new JSONArray();
        int count = 0;
        for (int i = 0; i < raw.length(); i++) {
            JSONObject a = unwrapPayload(raw.getJSONObject(i));
            if (!startsWith(a.optString("advance_date",""), date)) continue;
            if (companyId != null && !companyId.isEmpty()
                    && !"ALL".equalsIgnoreCase(companyId)
                    && !companyId.equalsIgnoreCase(currentShop())
                    && !companyId.equalsIgnoreCase(a.optString("company_id","")))
                continue;
            // Material is on the parent bill — skip filtering if absent.
            // (For now we don't make a per-row bill lookup; if you need
            // strict material filtering here we can add a bulk fetch.)
            JSONArray r = new JSONArray();
            r.put(idx(i+1));
            r.put(a.optString("bill_number",""));
            r.put(a.optString("advance_date","").substring(0, Math.min(10, a.optString("advance_date","").length())));
            r.put(money(a.optDouble("paid_amount", 0)));
            rows.put(r);
            count++;
        }
        return new JSONObject()
                .put("headers", arr("#","Bill No","Date","Amount"))
                .put("rows",    rows)
                .put("count",   count);
    }

    private static JSONObject buildRepledgeDetail(String companyId, String date,
                                                   String dateField, String title) throws Exception {
        JSONArray raw = fetchTableSync(AppConfig.TBL_REPLEDGE, null,
                dateField + ":desc", companyId, null);
        JSONArray rows = new JSONArray();
        int count = 0;
        for (int i = 0; i < raw.length(); i++) {
            JSONObject b = unwrapPayload(raw.getJSONObject(i));
            if (!startsWith(b.optString(dateField,""), date)) continue;
            JSONArray r = new JSONArray();
            r.put(idx(i+1));
            r.put(b.optString("repledge_bill_number", b.optString("repledge_bill_id","")));
            r.put(b.optString("repledge_name",""));
            r.put(money(b.optDouble("amount", 0)));
            r.put(money(b.optDouble("document_charge", 0)));
            rows.put(r);
            count++;
        }
        return new JSONObject()
                .put("headers", arr("#","Repledge No","Financier","Amount","Doc"))
                .put("rows",    rows)
                .put("count",   count);
    }

    /**
     * EXPENSES / INCOMES drill-down. The desktop's Today's Account sums EIGHT
     * debit tables (expenses) and SIX credit tables (incomes) — not just
     * company_other_*. Rather than fetch 8 tables from the phone, we hit the
     * cloud's /todays-account-ei-detail which merges them server-side with the
     * same type labels + descriptions the desktop uses. kind = EXPENSE|INCOME.
     */
    private static JSONObject buildExpenseIncomeDetailMerged(String companyId, String date,
                                                             String kind) throws Exception {
        HttpUrl.Builder b = HttpUrl.parse(AppConfig.DATA_BASE + "/todays-account-ei-detail")
                .newBuilder()
                .addQueryParameter("date", date == null ? "" : date)
                .addQueryParameter("kind", kind);
        if (companyId != null && !companyId.isEmpty()
                && !"ALL".equalsIgnoreCase(companyId)
                && !companyId.equalsIgnoreCase(currentShop()))
            b.addQueryParameter("companyId", companyId);

        JSONArray rows = new JSONArray();
        int count = 0;
        try (Response res = CLIENT.newCall(authed(b.build()).get().build()).execute()) {
            String raw = res.body() != null ? res.body().string() : "{}";
            if (res.isSuccessful()) {
                JSONArray src = new JSONObject(raw).optJSONArray("rows");
                if (src != null) {
                    for (int i = 0; i < src.length(); i++) {
                        JSONObject o = src.getJSONObject(i);
                        JSONArray r = new JSONArray();
                        r.put(idx(count + 1));
                        r.put(o.optString("type", ""));
                        r.put(o.optString("details", ""));
                        r.put(money(o.optDouble("amount", 0)));
                        rows.put(r);
                        count++;
                    }
                }
            }
        }
        return new JSONObject()
                .put("headers", arr("#","Type","Details","Amount"))
                .put("rows",    rows)
                .put("count",   count);
    }

    // ── small detail-builder helpers ────────────────────────────────────

    private static JSONArray arr(String... values) {
        JSONArray a = new JSONArray();
        for (String v : values) a.put(v);
        return a;
    }
    private static String idx(int n) { return Integer.toString(n); }
    private static boolean startsWith(String s, String prefix) {
        return s != null && prefix != null && s.startsWith(prefix);
    }
    private static String money(double v) {
        java.text.NumberFormat f = java.text.NumberFormat.getNumberInstance(new java.util.Locale("en","IN"));
        f.setMinimumFractionDigits(2);
        f.setMaximumFractionDigits(2);
        return f.format(v);
    }

    // ── Customers ─────────────────────────────────────────────────────────────

    public static void searchCustomers(String companyId, String query, Callback<JSONArray> cb) {
        EXEC.execute(() -> {
            try {
                JSONArray rows = fetchTableSync(AppConfig.TBL_CUSTOMER, query);
                JSONArray flat = new JSONArray();
                for (int i = 0; i < rows.length(); i++) {
                    JSONObject body = unwrapPayload(rows.getJSONObject(i));
                    sanitizeNulls(body);
                    if (companyId != null && !companyId.isEmpty()
                        && !"ALL".equalsIgnoreCase(companyId)
                        && !companyId.equalsIgnoreCase(currentShop())
                        && !companyId.equalsIgnoreCase(body.optString("company_id", "")))
                        continue;
                    flat.put(body);
                }
                cb.onSuccess(flat);
            } catch (Exception e) { cb.onError(e.getMessage()); }
        });
    }

    // ── Stock ─────────────────────────────────────────────────────────────────

    /** Company Alone: OPENED + LOCKED bills that are NOT placed in repledge. */
    public static void getStock(String companyId, String materialType, String search,
                                String from, String to,
                                String customerName, String amountFrom, String amountTo,
                                int page, int size,
                                Callback<JSONObject> cb) {
        fetchStock(AppConfig.TBL_STOCK, companyId, materialType, search, page, size,
                   from, to, customerName, amountFrom, amountTo,
                   null, null, null,
                   /*statuses*/ "OPENED,LOCKED", /*repledged*/ "false", cb);
    }

    /** Repledge Alone: matches desktop StockDetailsDBOperation.getRepAloneAllDetailsValues —
     *  RB.STATUS IN ('OPENED','GIVEN','SUSPENSE') filtered by company + material.
     *  These are the bills the shop has *currently* placed in repledge with
     *  another holder (i.e. still outstanding). LOCKED / DELIVERED rows are
     *  excluded because they're no longer in active repledge. */
    public static void getRepledgeStock(String companyId, String materialType, String search,
                                        String repledgeName,
                                        String repledgeDateFrom, String repledgeDateTo,
                                        int page, int size, Callback<JSONObject> cb) {
        fetchStock(AppConfig.TBL_REPLEDGE, companyId, materialType, search, page, size,
                   null, null, null, null, null,
                   repledgeName, repledgeDateFrom, repledgeDateTo,
                   /*statuses*/ "OPENED,GIVEN,SUSPENSE", /*repledged*/ null, cb);
    }

    /** All Details: every OPENED + LOCKED bill regardless of repledge status. */
    public static void getAllStock(String companyId, String materialType, String search,
                                   String compDateFrom, String compDateTo,
                                   String customerName, String amountFrom, String amountTo,
                                   String repledgeName,
                                   String repledgeDateFrom, String repledgeDateTo,
                                   int page, int size,
                                   Callback<JSONObject> cb) {
        fetchStock(AppConfig.TBL_STOCK, companyId, materialType, search, page, size,
                   compDateFrom, compDateTo, customerName, amountFrom, amountTo,
                   repledgeName, repledgeDateFrom, repledgeDateTo,
                   /*statuses*/ "OPENED,LOCKED", /*repledged*/ null, cb);
    }

    /**
     * Applies every active filter client-side. The cloud's /v1/data endpoint
     * only honours `q` (free-text ILIKE) and `limit`; everything else
     * (material, date range, amount range, customer/repledge name) is
     * narrowed here before paging the slice back to the UI.
     */
    private static void fetchStock(String table, String companyId,
                                   String materialType, String search,
                                   int page, int size,
                                   String compDateFrom, String compDateTo,
                                   String customerName, String amountFrom, String amountTo,
                                   String repledgeName,
                                   String repledgeDateFrom, String repledgeDateTo,
                                   String statuses, String repledged,
                                   Callback<JSONObject> cb) {
        EXEC.execute(() -> {
            try {
                JSONObject summary = fetchSummarySync(table, search, companyId,
                        materialType, /*status*/ null, statuses, repledged,
                        compDateFrom, compDateTo, customerName,
                        amountFrom, amountTo);
                JSONArray rows = fetchTableSync(table, search,
                        "opening_date:desc", companyId, materialType,
                        statuses, repledged,
                        compDateFrom, compDateTo, customerName,
                        amountFrom, amountTo);
                JSONArray filtered = new JSONArray();
                Double amtFrom = parseDoubleOrNull(amountFrom);
                Double amtTo   = parseDoubleOrNull(amountTo);
                String custLc  = customerName == null ? null : customerName.toLowerCase();
                String replLc  = repledgeName == null ? null : repledgeName.toLowerCase();
                for (int i = 0; i < rows.length(); i++) {
                    JSONObject row = rows.getJSONObject(i);
                    JSONObject payload = row.optJSONObject("payload");
                    JSONObject body = payload != null ? payload : row;
                    // Repledge fields (repledge_opening_date / repledge_name)
                    // can't be expressed server-side cheaply yet — keep their
                    // narrow client-side checks. companyId/material/opening_date/
                    // customerName/amount are now all server-side, so removed.
                    String replDate = body.optString("repledge_opening_date",
                                      body.optString("opening_date", ""));
                    if (repledgeDateFrom != null && !repledgeDateFrom.isEmpty()
                        && (replDate.isEmpty() || replDate.compareTo(repledgeDateFrom) < 0)) continue;
                    if (repledgeDateTo != null && !repledgeDateTo.isEmpty()
                        && (replDate.isEmpty() || replDate.compareTo(repledgeDateTo) > 0)) continue;
                    if (replLc != null && !replLc.isEmpty()
                        && !body.optString("repledge_name", "").toLowerCase().contains(replLc)) continue;
                    // (companyId, material, date, customer, amount filters
                    //  already enforced server-side — no need to re-check.)
                    double amt = body.optDouble("amount", 0);
                    // dummy ref so the var stays in scope for the rest of the
                    // loop without changing the structure below; amtFrom/amtTo
                    // are now passed to the server, so this is a no-op fallback.
                    if (amtFrom != null && amt < amtFrom) continue;
                    if (amtTo   != null && amt > amtTo  ) continue;
                    // org.json's optString returns the literal "null" for
                    // explicit JSON null values, which renders as the title
                    // text on bill cards. Strip nulls so default-fallbacks
                    // ("") actually fire in the adapter.
                    sanitizeNulls(body);
                    // Repledge cards show repledge_bill_id as the title. If
                    // that column is null/empty in source, fall back to the
                    // most useful identifier we do have so the card isn't
                    // headerless on screen.
                    if (AppConfig.TBL_REPLEDGE.equals(table)
                        && body.optString("repledge_bill_id", "").isEmpty()) {
                        String alt = body.optString("repledge_bill_number", "");
                        if (alt.isEmpty()) alt = body.optString("company_bill_number", "");
                        if (alt.isEmpty()) alt = body.optString("repledge_id", "");
                        if (!alt.isEmpty()) body.put("repledge_bill_id", alt);
                    }
                    filtered.put(body);
                }
                // Sort newest-first by opening_date. The cloud's
                // last_updated_at DESC order is meaningless because the
                // one-time replay stamped every row with the same instant.
                java.util.List<JSONObject> sortable = new java.util.ArrayList<>();
                for (int i = 0; i < filtered.length(); i++) sortable.add(filtered.getJSONObject(i));
                // Primary: opening_date DESC; tie-breaker: bill_number DESC
                // so same-day bills show newest-issued first.
                sortable.sort((a, b) -> {
                    int byDate = b.optString("opening_date", "")
                                  .compareTo(a.optString("opening_date", ""));
                    if (byDate != 0) return byDate;
                    String an = a.optString("bill_number", "");
                    String bn = b.optString("bill_number", "");
                    // Same length → lexicographic == numeric. Different
                    // length → longer string is the larger number.
                    if (an.length() != bn.length())
                        return Integer.compare(bn.length(), an.length());
                    return bn.compareTo(an);
                });
                int from = Math.max(page * size, 0);
                int to   = Math.min(from + size, sortable.size());
                JSONArray slice = new JSONArray();
                for (int i = from; i < to; i++) slice.put(sortable.get(i));
                JSONObject out = new JSONObject();
                // Summary numbers come from the server-side /summary call
                // (counts EVERY matching row, not just the 500 displayed).
                // If summary fetch failed, fall back to the slice we have.
                long  trueTotal     = summary.optLong  ("total",         sortable.size());
                double trueAmount   = summary.optDouble("totalAmount",   0);
                double trueInterest = summary.optDouble("totalInterest", 0);
                out.put("bills",         slice);
                out.put("total",         trueTotal);
                out.put("totalAmount",   trueAmount);
                out.put("totalInterest", trueInterest);
                cb.onSuccess(out);
            } catch (Exception e) { cb.onError(e.getMessage()); }
        });
    }

    // ── Reports (no cloud equivalent yet) ────────────────────────────────────

    /** Default = 6 months (used by Home charts). */
    public static void getMonthlyReport(String companyId, Callback<JSONObject> cb) {
        getMonthlyReport(companyId, 6, cb);
    }

    /** Caller-controlled window — MIS Report passes 12 for a 1-year view. */
    public static void getMonthlyReport(String companyId, int monthLimit, Callback<JSONObject> cb) {
        EXEC.execute(() -> {
            try {
                HttpUrl.Builder b = HttpUrl.parse(AppConfig.DATA_BASE + "/monthly-report").newBuilder()
                    .addQueryParameter("limit", Integer.toString(Math.max(1, monthLimit)));
                if (companyId != null && !companyId.isEmpty()
                    && !"ALL".equalsIgnoreCase(companyId)
                    && !companyId.equalsIgnoreCase(currentShop()))
                    b.addQueryParameter("companyId", companyId);
                try (Response res = CLIENT.newCall(authed(b.build()).get().build()).execute()) {
                    String raw = res.body() != null ? res.body().string() : "{}";
                    checkStatus(res, raw);
                    cb.onSuccess(new JSONObject(raw));
                }
            } catch (Exception e) { cb.onError(e.getMessage()); }
        });
    }

    /**
     * Full 16-column MIS report — one row per (month, jewel type) with the
     * complete desktop layout including repledge legs. Returns
     * { total, rows:[ {month, jwlType, pawnBills, pawnAmount, redeemBills,
     *   redeemAmount, interest, repledgeBills, repledgeAmount,
     *   repledgeRedeemBills, repledgeRedeemAmount, repledgeInterest,
     *   repledgeStockBills, repledgeStockAmount, stockBills, stockAmount} ] }.
     */
    public static void getMisReport(String companyId, Callback<JSONObject> cb) {
        EXEC.execute(() -> {
            try {
                HttpUrl.Builder b = HttpUrl.parse(AppConfig.DATA_BASE + "/mis-report").newBuilder();
                if (companyId != null && !companyId.isEmpty()
                    && !"ALL".equalsIgnoreCase(companyId)
                    && !companyId.equalsIgnoreCase(currentShop()))
                    b.addQueryParameter("companyId", companyId);
                try (Response res = CLIENT.newCall(authed(b.build()).get().build()).execute()) {
                    String raw = res.body() != null ? res.body().string() : "{}";
                    checkStatus(res, raw);
                    cb.onSuccess(new JSONObject(raw));
                }
            } catch (Exception e) { cb.onError(e.getMessage()); }
        });
    }

    public static void getTrialBalance(String companyId, String from, String to,
                                       Callback<JSONObject> cb) {
        cb.onError("Trial balance is not yet exposed by the cloud API.");
    }

    // ── Billing (write-side lives on the desktop) ────────────────────────────

    public static void findBill(String companyId, String billNumber, String materialType,
                                Callback<JSONObject> cb) {
        getBillDetail(companyId, billNumber, materialType, cb);
    }

    public static void getNextBillNumber(String companyId, String materialType,
                                         Callback<JSONObject> cb) {
        cb.onError("Bill numbers are issued by the desktop app, not the cloud.");
    }

    public static void calculateClosing(String companyId, String materialType,
                                         double amount, double interest, double documentCharge,
                                         String openingDate, double totalAdvancePaid,
                                         String closingDate,
                                         Callback<JSONObject> cb) {
        cb.onError("Closing calculation runs on the desktop, not the cloud.");
    }

    public static void calculateBilling(JSONObject body, Callback<JSONObject> cb) {
        cb.onError("Billing calculation runs on the desktop, not the cloud.");
    }

    public static void saveBill(JSONObject body, Callback<JSONObject> cb) {
        cb.onError("Bills must be saved on the desktop; the cloud is read-only from mobile.");
    }

    // ── Notifications ─────────────────────────────────────────────────────────

    /** Fetches the latest cloud notifications (newest first). */
    public static void getNotifications(int limit, Callback<JSONArray> cb) {
        EXEC.execute(() -> {
            try {
                HttpUrl url = HttpUrl.parse(AppConfig.DATA_NOTIFICATIONS).newBuilder()
                    .addQueryParameter("limit", String.valueOf(Math.max(limit, 1)))
                    .build();
                try (Response res = CLIENT.newCall(authed(url).get().build()).execute()) {
                    String raw = res.body() != null ? res.body().string() : "[]";
                    checkStatus(res, raw);
                    cb.onSuccess(new JSONArray(raw));
                }
            } catch (Exception e) { cb.onError(e.getMessage()); }
        });
    }

    /** Records the highest notif_id the user has seen so the bell badge
     *  only shows entries newer than this. */
    public static void markNotificationsRead(Context ctx, long lastNotifId) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
           .putLong("last_read_notif_id", lastNotifId).apply();
    }

    public static long getLastReadNotifId(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                  .getLong("last_read_notif_id", 0L);
    }

    // ── Internals ─────────────────────────────────────────────────────────────

    /**
     * Dedicated company_todays_account fetcher — uses the cloud's JSONB-keyed
     * refMark / todaysDate params instead of the q-ILIKE substring trick that
     * fails when the payload is stored as `json` (with whitespace) rather
     * than canonical-compact `jsonb`. Any of the filter args can be null.
     */
    private static JSONArray fetchTodaysAccountRows(String companyId,
                                                     String todaysDate,
                                                     String refMark,
                                                     String orderBy) throws Exception {
        HttpUrl.Builder b = HttpUrl.parse(AppConfig.DATA_BASE + "/company_todays_account")
                .newBuilder()
                .addQueryParameter("limit", "500");
        if (orderBy != null && !orderBy.isEmpty())
            b.addQueryParameter("order_by", orderBy);
        if (companyId != null && !companyId.isEmpty()
                && !"ALL".equalsIgnoreCase(companyId)
                && !companyId.equalsIgnoreCase(currentShop()))
            b.addQueryParameter("companyId", companyId);
        if (refMark != null && !refMark.isEmpty())
            b.addQueryParameter("refMark", refMark);
        if (todaysDate != null && !todaysDate.isEmpty())
            b.addQueryParameter("todaysDate", todaysDate);
        try (Response res = CLIENT.newCall(authed(b.build()).get().build()).execute()) {
            String raw = res.body() != null ? res.body().string() : "[]";
            checkStatus(res, raw);
            return new JSONArray(raw);
        }
    }

    private static JSONArray fetchTableSync(String table, String query) throws Exception {
        return fetchTableSync(table, query, null, null, null);
    }

    private static JSONArray fetchTableSync(String table, String query, String orderBy) throws Exception {
        return fetchTableSync(table, query, orderBy, null, null);
    }

    /**
     * @param orderBy   optional `field` / `field:desc` — pushed to cloud for
     *                  proper top-N slicing.
     * @param companyId optional payload->>'company_id' = ? filter (server-side)
     * @param material  optional payload->>'jewel_material_type' = ? filter
     */
    private static JSONArray fetchTableSync(String table, String query,
                                            String orderBy, String companyId,
                                            String material) throws Exception {
        return fetchTableSync(table, query, orderBy, companyId, material,
                              null, null, null, null, null, null, null);
    }

    /** Full filter set — list endpoint. */
    private static JSONArray fetchTableSync(String table, String query,
                                            String orderBy, String companyId,
                                            String material,
                                            String statuses, String repledged,
                                            String dateFrom, String dateTo,
                                            String customerName,
                                            String amountFrom, String amountTo) throws Exception {
        HttpUrl.Builder b = HttpUrl.parse(AppConfig.DATA_BASE + "/" + table).newBuilder()
            .addQueryParameter("limit", "500");
        appendCommonFilters(b, query, orderBy, companyId, material,
                            statuses, repledged,
                            dateFrom, dateTo, customerName, amountFrom, amountTo);
        try (Response res = CLIENT.newCall(authed(b.build()).get().build()).execute()) {
            String raw = res.body() != null ? res.body().string() : "[]";
            checkStatus(res, raw);
            return new JSONArray(raw);
        }
    }

    /** Aggregate {count, totalAmount, totalInterest} across the full set. */
    private static JSONObject fetchSummarySync(String table, String query,
                                                String companyId, String material, String status,
                                                String statuses, String repledged,
                                                String dateFrom, String dateTo,
                                                String customerName,
                                                String amountFrom, String amountTo) throws Exception {
        HttpUrl.Builder b = HttpUrl.parse(AppConfig.DATA_BASE + "/" + table + "/summary").newBuilder();
        appendCommonFilters(b, query, /*orderBy*/ null, companyId, material,
                            statuses, repledged,
                            dateFrom, dateTo, customerName, amountFrom, amountTo);
        if (status != null && !status.isEmpty() && !"ALL".equalsIgnoreCase(status))
            b.addQueryParameter("status", status);
        try (Response res = CLIENT.newCall(authed(b.build()).get().build()).execute()) {
            String raw = res.body() != null ? res.body().string() : "{}";
            checkStatus(res, raw);
            return new JSONObject(raw);
        }
    }

    private static void appendCommonFilters(HttpUrl.Builder b, String query, String orderBy,
                                             String companyId, String material,
                                             String statuses, String repledged,
                                             String dateFrom, String dateTo,
                                             String customerName,
                                             String amountFrom, String amountTo) {
        if (query     != null && !query.isEmpty())     b.addQueryParameter("q",        query);
        if (orderBy   != null && !orderBy.isEmpty())   b.addQueryParameter("order_by", orderBy);
        if (companyId != null && !companyId.isEmpty()
                && !"ALL".equalsIgnoreCase(companyId)
                && !companyId.equalsIgnoreCase(currentShop()))
            b.addQueryParameter("companyId", companyId);
        if (material != null && !material.isEmpty()
                && !"ALL".equalsIgnoreCase(material))
            b.addQueryParameter("material", material);
        if (statuses != null && !statuses.isEmpty()
                && !"ALL".equalsIgnoreCase(statuses))
            b.addQueryParameter("statuses", statuses);
        if (repledged != null && !repledged.isEmpty())
            b.addQueryParameter("repledged", repledged);
        if (dateFrom != null && !dateFrom.isEmpty()) b.addQueryParameter("dateFrom", dateFrom);
        if (dateTo   != null && !dateTo.isEmpty())   b.addQueryParameter("dateTo",   dateTo);
        if (customerName != null && !customerName.isEmpty())
            b.addQueryParameter("customerName", customerName);
        if (amountFrom != null && !amountFrom.isEmpty()) b.addQueryParameter("amountFrom", amountFrom);
        if (amountTo   != null && !amountTo.isEmpty())   b.addQueryParameter("amountTo",   amountTo);
    }

    private static JSONArray filterByDate(JSONArray rows, String dateField, String date)
            throws org.json.JSONException {
        if (date == null || date.isEmpty()) return rows;
        JSONArray out = new JSONArray();
        for (int i = 0; i < rows.length(); i++) {
            JSONObject row = rows.getJSONObject(i);
            JSONObject body = row.optJSONObject("payload");
            if (body == null) body = row;
            String v = body.optString(dateField, "");
            if (v != null && v.startsWith(date)) out.put(row);
        }
        return out;
    }

    private static JSONObject unwrapPayload(JSONObject row) {
        JSONObject p = row.optJSONObject("payload");
        return p != null ? p : row;
    }

    private static boolean matchesFilter(Bill bill, String type, String status) {
        if (type != null && !type.isEmpty()
            && !"ALL".equalsIgnoreCase(type)
            && !type.equalsIgnoreCase(bill.materialType)) return false;
        if (status != null && !status.isEmpty()
            && !"ALL".equalsIgnoreCase(status)
            && !status.equalsIgnoreCase(bill.status)) return false;
        return true;
    }

    private static String extractError(String raw, int code, String fallback) {
        try {
            JSONObject err = new JSONObject(raw);
            String msg = err.optString("error", null);
            if (msg == null) msg = err.optString("message", null);
            if (msg != null) return msg;
        } catch (Exception ignored) {}
        return fallback + " (HTTP " + code + ")";
    }

    private static void checkStatus(Response res, String raw) throws Exception {
        if (res.code() == 401) {
            // The saved JWT is missing, expired, or signed with an old
            // secret — purge it so the next app launch routes back to
            // Login instead of looping on dead requests.
            Context c = resolveCtx();
            if (c != null) {
                c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply();
            }
            throw new Exception("Session expired — please sign in again");
        }
        if (!res.isSuccessful())
            throw new Exception("API error " + res.code() + ": " + raw);
    }

    private static String capitalize(String s) {
        return s == null || s.isEmpty() ? s
            : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private static Double parseDoubleOrNull(String s) {
        if (s == null || s.isEmpty()) return null;
        try { return Double.parseDouble(s); } catch (Exception e) { return null; }
    }

    /** Drop keys whose value is the JSON null sentinel so optString returns
     *  the default ("") instead of the literal text "null". */
    private static void sanitizeNulls(JSONObject o) {
        java.util.List<String> drop = new java.util.ArrayList<>();
        java.util.Iterator<String> it = o.keys();
        while (it.hasNext()) {
            String k = it.next();
            if (o.isNull(k)) drop.add(k);
        }
        for (String k : drop) o.remove(k);
    }

    public static class BillsResult {
        public final List<Bill> bills;
        public final int total;
        public BillsResult(List<Bill> bills, int total) {
            this.bills = bills; this.total = total;
        }
    }
}
