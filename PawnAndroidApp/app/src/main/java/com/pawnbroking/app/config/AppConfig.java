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
    public static final String SHOP_ID  = "mylocal";

    // ── Auth & devices ────────────────────────────────────────────────────────
    public static final String LOGIN              = BASE_URL + "/v1/auth/mobile";
    public static final String BOX_SEND_OTP       = BASE_URL + "/v1/auth/box/send-otp";
    public static final String BOX_VERIFY         = BASE_URL + "/v1/auth/box/verify";
    public static final String BOX_SELECT_SHOP    = BASE_URL + "/v1/auth/box/select-shop";
    public static final String BOX_MY_SHOPS       = BASE_URL + "/v1/auth/box/my-shops";
    public static final String BOX_SET_PASSWORD   = BASE_URL + "/v1/auth/box/set-password";
    public static final String BOX_PASSWORD_LOGIN = BASE_URL + "/v1/auth/box/password-login";
    public static final String DEVICES            = BASE_URL + "/v1/devices";

    // ── Generic projection data API ───────────────────────────────────────────
    public static final String DATA_BASE          = BASE_URL + "/v1/data";
    public static final String DATA_DASHBOARD     = DATA_BASE + "/dashboard";
    public static final String DATA_NOTIFICATIONS = DATA_BASE + "/notifications";

    // ── Projection table names (rows in <schema>.projections) ─────────────────
    // These match the desktop app's actual table names (see V2 trigger).
    // `company_billing` holds ALL bills, distinguished by the `status` column
    // (OPENED, LOCKED, CLOSED, CANCELLED). The app filters client-side.
    public static final String TBL_COMPANY        = "company";
    public static final String TBL_CUSTOMER       = "customer_details";
    public static final String TBL_BILL_OPENING   = "company_billing";
    public static final String TBL_BILL_CLOSING   = "company_billing";
    public static final String TBL_STOCK          = "company_billing";
    public static final String TBL_REPLEDGE       = "repledge_billing";
    public static final String TBL_ADVANCE        = "company_advance_amount";

    /** Bill image proxy. Cloud-api fetches the bytes from Magizhchi Share
     *  using the per-tenant mbk_ token and streams them back. */
    public static final String BILL_IMAGE = BASE_URL + "/v1/bills/image";

    // ── Backup files (off-site copies of the shop's backup folder) ────────────
    /** Metadata list, newest first. */
    public static final String BACKUP_LIST     = BASE_URL + "/v1/files/backup/list";
    /** Streamed bytes for one backup file. */
    public static final String BACKUP_DOWNLOAD = BASE_URL + "/v1/files/backup/download";

    /** How many downloaded backups to keep on the phone. Older local copies
     *  are pruned only AFTER a newer one has downloaded successfully, so the
     *  device always retains several restore points. */
    public static final int BACKUP_KEEP_LOCAL = 3;

    public static String billImageUrl(String companyId, String materialType,
                                       String billNumber, String imageName) {
        try {
            return BILL_IMAGE
                + "?companyId="    + java.net.URLEncoder.encode(companyId,    "UTF-8")
                + "&materialType=" + java.net.URLEncoder.encode(materialType.toUpperCase(), "UTF-8")
                + "&billNumber="   + java.net.URLEncoder.encode(billNumber,   "UTF-8")
                + "&imageName="    + java.net.URLEncoder.encode(imageName,    "UTF-8");
        } catch (java.io.UnsupportedEncodingException e) {
            return BILL_IMAGE + "?companyId=" + companyId
                + "&materialType=" + materialType + "&billNumber=" + billNumber
                + "&imageName=" + imageName;
        }
    }
}
