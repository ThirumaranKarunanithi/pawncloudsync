package com.pawnbroking.app.config;

/**
 * Endpoint catalogue for the deployed pawnbroking-cloud-api.
 * The cloud exposes only the /v1/* surface (auth, projections, dashboard,
 * notifications, sync, devices); the legacy /api/* surface used by the
 * old standalone REST server no longer exists.
 *
 * Multi-tenant: a single shop_id is hard-bound to the app build. Change
 * SHOP_ID to repurpose the app for another tenant.
 */
public class AppConfig {
    public static final String BASE_URL = "https://devpawn.magizhchi.academy";

    /** Tenant key. Matches a row in public.tenants on the cloud DB. */
    public static final String SHOP_ID  = "alwarpuram";

    // ── Auth & devices ────────────────────────────────────────────────────────
    public static final String LOGIN              = BASE_URL + "/v1/auth/mobile";
    public static final String BOX_SEND_OTP       = BASE_URL + "/v1/auth/box/send-otp";
    public static final String BOX_VERIFY         = BASE_URL + "/v1/auth/box/verify";
    public static final String DEVICES            = BASE_URL + "/v1/devices";

    // ── Generic projection data API ───────────────────────────────────────────
    public static final String DATA_BASE          = BASE_URL + "/v1/data";
    public static final String DATA_DASHBOARD     = DATA_BASE + "/dashboard";
    public static final String DATA_NOTIFICATIONS = DATA_BASE + "/notifications";

    // ── Projection table names (rows in <schema>.projections) ─────────────────
    public static final String TBL_COMPANY        = "company_master";
    public static final String TBL_CUSTOMER       = "customer_master";
    public static final String TBL_BILL_OPENING   = "bill_opening";
    public static final String TBL_BILL_CLOSING   = "bill_closing";
    public static final String TBL_STOCK          = "stock_master";
    public static final String TBL_REPLEDGE       = "repledge_billing";
    public static final String TBL_ADVANCE        = "advance_amount";

    /** Bill image proxy is not yet implemented on the cloud — returns null. */
    public static String billImageUrl(String companyId, String materialType,
                                       String billNumber, String imageName) {
        return null;
    }
}
