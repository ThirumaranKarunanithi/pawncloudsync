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
    private static final OkHttpClient CLIENT = new OkHttpClient.Builder()
            .connectTimeout(java.time.Duration.ofSeconds(30))
            .readTimeout   (java.time.Duration.ofSeconds(60))
            .writeTimeout  (java.time.Duration.ofSeconds(60))
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

    /** Asks the cloud-api to send an OTP for {@code email} via Magizhchi Share. */
    public static void requestOtp(String email, Callback<Void> cb) {
        EXEC.execute(() -> {
            try {
                JSONObject body = new JSONObject();
                body.put("email",   email);
                body.put("shop_id", AppConfig.SHOP_ID);
                Request req = new Request.Builder()
                    .url(AppConfig.BOX_SEND_OTP)
                    .post(RequestBody.create(body.toString(), JSON))
                    .build();
                try (Response res = CLIENT.newCall(req).execute()) {
                    String raw = res.body() != null ? res.body().string() : "";
                    if (res.isSuccessful()) cb.onSuccess(null);
                    else cb.onError(extractError(raw, res.code(), "Could not send OTP"));
                }
            } catch (Exception e) { cb.onError(e.getMessage()); }
        });
    }

    /** Verifies OTP with cloud-api, persists the minted JWT, returns the User. */
    public static void verifyOtpAndLogin(Context ctx, String email, String code, Callback<User> cb) {
        EXEC.execute(() -> {
            try {
                JSONObject body = new JSONObject();
                body.put("email",   email);
                body.put("code",    code);
                body.put("shop_id", AppConfig.SHOP_ID);
                Request req = new Request.Builder()
                    .url(AppConfig.BOX_VERIFY)
                    .post(RequestBody.create(body.toString(), JSON))
                    .build();
                try (Response res = CLIENT.newCall(req).execute()) {
                    String raw = res.body() != null ? res.body().string() : "";
                    if (!res.isSuccessful()) {
                        cb.onError(extractError(raw, res.code(), "OTP verification failed"));
                        return;
                    }
                    JSONObject data = new JSONObject(raw);
                    User user = User.fromLogin(data, email);
                    SharedPreferences.Editor ed = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit();
                    ed.putString("token",        user.token);
                    ed.putString("userName",     user.userName);
                    ed.putString("employeeName", user.employeeName);
                    ed.putString("shopId",       AppConfig.SHOP_ID);
                    ed.apply();
                    cb.onSuccess(user);
                }
            } catch (Exception e) { cb.onError(e.getMessage()); }
        });
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
                if (list.isEmpty()) {
                    list.add(new Company(AppConfig.SHOP_ID, capitalize(AppConfig.SHOP_ID),
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
                            && !companyId.equalsIgnoreCase(AppConfig.SHOP_ID)
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
                        && !companyId.equalsIgnoreCase(AppConfig.SHOP_ID)
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

    public static void getTodaysAccount(String companyId, String date, Callback<JSONObject> cb) {
        EXEC.execute(() -> {
            try {
                JSONArray openings = fetchTableSync(AppConfig.TBL_BILL_OPENING, null);
                JSONArray closings = fetchTableSync(AppConfig.TBL_BILL_CLOSING, null);
                JSONArray advances = fetchTableSync(AppConfig.TBL_ADVANCE,      null);
                JSONObject out = new JSONObject();
                out.put("date", date == null ? "" : date);
                out.put("openings", filterByDate(openings, "opening_date",  date));
                out.put("closings", filterByDate(closings, "closing_date",  date));
                out.put("advances", filterByDate(advances, "advance_date",  date));
                cb.onSuccess(out);
            } catch (Exception e) { cb.onError(e.getMessage()); }
        });
    }

    public static void getTodaysAccountDetails(String companyId, String date, String type,
                                               Callback<JSONObject> cb) {
        EXEC.execute(() -> {
            try {
                String table;
                String dateField;
                switch (type == null ? "" : type.toUpperCase()) {
                    case "CLOSING": table = AppConfig.TBL_BILL_CLOSING; dateField = "closing_date"; break;
                    case "ADVANCE": table = AppConfig.TBL_ADVANCE;      dateField = "advance_date"; break;
                    default:        table = AppConfig.TBL_BILL_OPENING; dateField = "opening_date"; break;
                }
                JSONArray rows = filterByDate(fetchTableSync(table, null), dateField, date);
                JSONObject out = new JSONObject();
                out.put("date", date == null ? "" : date);
                out.put("type", type == null ? "" : type);
                out.put("items", rows);
                cb.onSuccess(out);
            } catch (Exception e) { cb.onError(e.getMessage()); }
        });
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
                        && !companyId.equalsIgnoreCase(AppConfig.SHOP_ID)
                        && !companyId.equalsIgnoreCase(body.optString("company_id", "")))
                        continue;
                    flat.put(body);
                }
                cb.onSuccess(flat);
            } catch (Exception e) { cb.onError(e.getMessage()); }
        });
    }

    // ── Stock ─────────────────────────────────────────────────────────────────

    public static void getStock(String companyId, String materialType, String search,
                                String from, String to,
                                String customerName, String amountFrom, String amountTo,
                                int page, int size,
                                Callback<JSONObject> cb) {
        fetchStock(AppConfig.TBL_STOCK, companyId, materialType, search, page, size,
                   from, to, customerName, amountFrom, amountTo,
                   null, null, null, cb);
    }

    public static void getRepledgeStock(String companyId, String materialType, String search,
                                        String repledgeName,
                                        String repledgeDateFrom, String repledgeDateTo,
                                        int page, int size, Callback<JSONObject> cb) {
        fetchStock(AppConfig.TBL_REPLEDGE, companyId, materialType, search, page, size,
                   null, null, null, null, null,
                   repledgeName, repledgeDateFrom, repledgeDateTo, cb);
    }

    public static void getAllStock(String companyId, String materialType, String search,
                                   String compDateFrom, String compDateTo,
                                   String customerName, String amountFrom, String amountTo,
                                   String repledgeName,
                                   String repledgeDateFrom, String repledgeDateTo,
                                   int page, int size,
                                   Callback<JSONObject> cb) {
        fetchStock(AppConfig.TBL_STOCK, companyId, materialType, search, page, size,
                   compDateFrom, compDateTo, customerName, amountFrom, amountTo,
                   repledgeName, repledgeDateFrom, repledgeDateTo, cb);
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
                                   Callback<JSONObject> cb) {
        EXEC.execute(() -> {
            try {
                // Ask the cloud to slice the top 500 by opening_date DESC so
                // we get the most recent bills out of the full 39k, not an
                // arbitrary 500 ordered by last_updated_at.
                JSONArray rows = fetchTableSync(table, search, "opening_date:desc");
                JSONArray filtered = new JSONArray();
                double totalAmount = 0d;
                double totalInterest = 0d;
                Double amtFrom = parseDoubleOrNull(amountFrom);
                Double amtTo   = parseDoubleOrNull(amountTo);
                String custLc  = customerName == null ? null : customerName.toLowerCase();
                String replLc  = repledgeName == null ? null : repledgeName.toLowerCase();
                for (int i = 0; i < rows.length(); i++) {
                    JSONObject row = rows.getJSONObject(i);
                    JSONObject payload = row.optJSONObject("payload");
                    JSONObject body = payload != null ? payload : row;
                    // Multi-company tenant: only keep rows for the selected
                    // company. "ALL" or shop_id-as-companyId (legacy intent
                    // extra) disables the filter.
                    if (companyId != null && !companyId.isEmpty()
                        && !"ALL".equalsIgnoreCase(companyId)
                        && !companyId.equalsIgnoreCase(AppConfig.SHOP_ID)
                        && !companyId.equalsIgnoreCase(body.optString("company_id", ""))) continue;
                    if (materialType != null && !materialType.isEmpty()
                        && !"ALL".equalsIgnoreCase(materialType)
                        && !materialType.equalsIgnoreCase(body.optString("jewel_material_type",
                            body.optString("material_type", "")))) continue;
                    String openingDate = body.optString("opening_date", "");
                    if (compDateFrom != null && !compDateFrom.isEmpty()
                        && (openingDate.isEmpty() || openingDate.compareTo(compDateFrom) < 0)) continue;
                    if (compDateTo != null && !compDateTo.isEmpty()
                        && (openingDate.isEmpty() || openingDate.compareTo(compDateTo) > 0)) continue;
                    String replDate = body.optString("repledge_opening_date",
                                      body.optString("opening_date", ""));
                    if (repledgeDateFrom != null && !repledgeDateFrom.isEmpty()
                        && (replDate.isEmpty() || replDate.compareTo(repledgeDateFrom) < 0)) continue;
                    if (repledgeDateTo != null && !repledgeDateTo.isEmpty()
                        && (replDate.isEmpty() || replDate.compareTo(repledgeDateTo) > 0)) continue;
                    if (custLc != null && !custLc.isEmpty()
                        && !body.optString("customer_name", "").toLowerCase().contains(custLc)) continue;
                    if (replLc != null && !replLc.isEmpty()
                        && !body.optString("repledge_name", "").toLowerCase().contains(replLc)) continue;
                    double amt = body.optDouble("amount", 0);
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
                    totalAmount   += amt;
                    totalInterest += body.optDouble("interest", 0);
                }
                // Sort newest-first by opening_date. The cloud's
                // last_updated_at DESC order is meaningless because the
                // one-time replay stamped every row with the same instant.
                java.util.List<JSONObject> sortable = new java.util.ArrayList<>();
                for (int i = 0; i < filtered.length(); i++) sortable.add(filtered.getJSONObject(i));
                sortable.sort((a, b) -> b.optString("opening_date", "")
                                         .compareTo(a.optString("opening_date", "")));
                int from = Math.max(page * size, 0);
                int to   = Math.min(from + size, sortable.size());
                JSONArray slice = new JSONArray();
                for (int i = from; i < to; i++) slice.put(sortable.get(i));
                JSONObject out = new JSONObject();
                // StockDetailsActivity reads these specific keys — match its
                // contract exactly or the screen renders "0 bills ₹0.00".
                out.put("bills",         slice);
                out.put("total",         sortable.size());
                out.put("totalAmount",   totalAmount);
                out.put("totalInterest", totalInterest);
                cb.onSuccess(out);
            } catch (Exception e) { cb.onError(e.getMessage()); }
        });
    }

    // ── Reports (no cloud equivalent yet) ────────────────────────────────────

    public static void getMonthlyReport(String companyId, Callback<JSONObject> cb) {
        EXEC.execute(() -> {
            try {
                HttpUrl.Builder b = HttpUrl.parse(AppConfig.DATA_BASE + "/monthly-report").newBuilder()
                    .addQueryParameter("limit", "6");
                if (companyId != null && !companyId.isEmpty()
                    && !"ALL".equalsIgnoreCase(companyId)
                    && !companyId.equalsIgnoreCase(AppConfig.SHOP_ID))
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

    private static JSONArray fetchTableSync(String table, String query) throws Exception {
        return fetchTableSync(table, query, null);
    }

    /**
     * @param orderBy optional `field` or `field:desc` (e.g. "opening_date:desc")
     *                — pushed to the cloud so the top-N slice is the right
     *                rows. Without it, the cloud orders by last_updated_at
     *                which the one-time replay made meaningless.
     */
    private static JSONArray fetchTableSync(String table, String query, String orderBy) throws Exception {
        HttpUrl.Builder b = HttpUrl.parse(AppConfig.DATA_BASE + "/" + table).newBuilder()
            .addQueryParameter("limit", "500");
        if (query  != null && !query.isEmpty())   b.addQueryParameter("q",        query);
        if (orderBy != null && !orderBy.isEmpty()) b.addQueryParameter("order_by", orderBy);
        try (Response res = CLIENT.newCall(authed(b.build()).get().build()).execute()) {
            String raw = res.body() != null ? res.body().string() : "[]";
            checkStatus(res, raw);
            return new JSONArray(raw);
        }
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
