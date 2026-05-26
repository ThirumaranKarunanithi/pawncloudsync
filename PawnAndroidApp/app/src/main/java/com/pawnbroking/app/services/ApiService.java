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
    private static final OkHttpClient CLIENT = new OkHttpClient();
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
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).contains("token");
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
                Company only = new Company(
                    AppConfig.SHOP_ID,
                    capitalize(AppConfig.SHOP_ID),
                    "", null, null, null, "ACTIVE");
                List<Company> list = new ArrayList<>();
                list.add(only);
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
                        Bill bill = Bill.fromJson(arr.getJSONObject(i));
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
                String table = AppConfig.TBL_BILL_OPENING;
                HttpUrl url = HttpUrl.parse(AppConfig.DATA_BASE + "/" + table + "/" + billNumber).newBuilder().build();
                try (Response res = CLIENT.newCall(authed(url).get().build()).execute()) {
                    String raw = res.body() != null ? res.body().string() : "{}";
                    if (res.code() == 404) { cb.onError("Bill " + billNumber + " not found"); return; }
                    checkStatus(res, raw);
                    cb.onSuccess(unwrapPayload(new JSONObject(raw)));
                }
            } catch (Exception e) { cb.onError(e.getMessage()); }
        });
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
                cb.onSuccess(fetchTableSync(AppConfig.TBL_CUSTOMER, query));
            } catch (Exception e) { cb.onError(e.getMessage()); }
        });
    }

    // ── Stock ─────────────────────────────────────────────────────────────────

    public static void getStock(String companyId, String materialType, String search,
                                String from, String to,
                                String customerName, String amountFrom, String amountTo,
                                int page, int size,
                                Callback<JSONObject> cb) {
        fetchStock(AppConfig.TBL_STOCK, materialType, search, page, size, cb);
    }

    public static void getRepledgeStock(String companyId, String materialType, String search,
                                        String repledgeName,
                                        String repledgeDateFrom, String repledgeDateTo,
                                        int page, int size, Callback<JSONObject> cb) {
        fetchStock(AppConfig.TBL_REPLEDGE, materialType, search, page, size, cb);
    }

    public static void getAllStock(String companyId, String materialType, String search,
                                   String compDateFrom, String compDateTo,
                                   String customerName, String amountFrom, String amountTo,
                                   String repledgeName,
                                   String repledgeDateFrom, String repledgeDateTo,
                                   int page, int size,
                                   Callback<JSONObject> cb) {
        fetchStock(AppConfig.TBL_STOCK, materialType, search, page, size, cb);
    }

    private static void fetchStock(String table, String materialType, String search,
                                   int page, int size, Callback<JSONObject> cb) {
        EXEC.execute(() -> {
            try {
                JSONArray rows = fetchTableSync(table, search);
                JSONArray filtered = new JSONArray();
                for (int i = 0; i < rows.length(); i++) {
                    JSONObject row = rows.getJSONObject(i);
                    JSONObject payload = row.optJSONObject("payload");
                    JSONObject body = payload != null ? payload : row;
                    if (materialType != null && !materialType.isEmpty()
                        && !materialType.equalsIgnoreCase(body.optString("jewel_material_type",
                            body.optString("material_type", "")))) continue;
                    filtered.put(payload != null ? payload : row);
                }
                int from = Math.max(page * size, 0);
                int to   = Math.min(from + size, filtered.length());
                JSONArray slice = new JSONArray();
                for (int i = from; i < to; i++) slice.put(filtered.get(i));
                JSONObject out = new JSONObject();
                out.put("items", slice);
                out.put("total", filtered.length());
                cb.onSuccess(out);
            } catch (Exception e) { cb.onError(e.getMessage()); }
        });
    }

    // ── Reports (no cloud equivalent yet) ────────────────────────────────────

    public static void getMonthlyReport(String companyId, Callback<JSONObject> cb) {
        cb.onError("Monthly report is not yet exposed by the cloud API.");
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

    // ── Internals ─────────────────────────────────────────────────────────────

    private static JSONArray fetchTableSync(String table, String query) throws Exception {
        HttpUrl.Builder b = HttpUrl.parse(AppConfig.DATA_BASE + "/" + table).newBuilder()
            .addQueryParameter("limit", "500");
        if (query != null && !query.isEmpty()) b.addQueryParameter("q", query);
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
        if (type != null && !type.isEmpty() && !type.equalsIgnoreCase(bill.materialType)) return false;
        if (status != null && !status.isEmpty() && !status.equalsIgnoreCase(bill.status)) return false;
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
        if (!res.isSuccessful())
            throw new Exception("API error " + res.code() + ": " + raw);
    }

    private static String capitalize(String s) {
        return s == null || s.isEmpty() ? s
            : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    public static class BillsResult {
        public final List<Bill> bills;
        public final int total;
        public BillsResult(List<Bill> bills, int total) {
            this.bills = bills; this.total = total;
        }
    }
}
