package com.pawnbroking.app;

import android.app.Dialog;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.view.View;
import android.view.Window;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.pawnbroking.app.config.AppConfig;
import com.pawnbroking.app.services.ApiService;

import org.json.JSONObject;

import java.text.NumberFormat;
import java.util.Locale;

public class BillingActivity extends AppCompatActivity {

    private String companyId;
    private String selectedMaterialType = "GOLD";

    // Gold/Silver toggle
    private TextView chipGold, chipSilver;

    // Bill search
    private EditText     etBillNumber;
    private Button       btnSearchBill;
    private LinearLayout layoutBillDetails;

    // Bill info (read-only)
    private TextView tvMaterialType, tvOpeningDate;
    private TextView tvCreatedBy, tvCreatedAt;
    private LinearLayout layoutCreatedInfo;

    // Customer (read-only)
    private TextView tvCustomerName, tvGender, tvSpouseType, tvSpouseName;
    private TextView tvMobile, tvDoorNo, tvStreet, tvArea, tvCity;

    // Jewel (read-only)
    private TextView tvItems, tvGrossWt, tvNetWt, tvPurity, tvAmount;

    // Opening calculated amounts
    private TextView tvInterest, tvIntPerMonth, tvDocCharge, tvRatePerGm;
    private TextView tvTakenAmt, tvToGive, tvGivenAmount;
    private TextView tvStatus, tvPhysicalLocation;

    // Closing section
    private LinearLayout layoutClosingSection;
    private TextView tvCloseInterestType, tvActualTotalMonths;
    private TextView tvMinimumMonths, tvToReduceMonths;
    private TextView tvCloseAcceptedDate, tvForMonths;
    private TextView tvCloseInterest, tvClosingDate;
    private TextView tvCloseTakenAmt;
    private TextView tvTotalAdvance, tvOtherCharges;
    private TextView tvToGetAmt;
    private TextView tvDiscount, tvGotAmt;
    private TextView tvCustomerCopy, tvClosedBy;

    // Additional
    private TextView tvNominee, tvIdProofType, tvIdProofNumber;
    private TextView tvReferredBy, tvCustomerId, tvOccupation;
    private TextView tvAcceptedDate, tvNote;

    // Repledge section
    private LinearLayout layoutRepledgeSection;
    private TextView tvReplBillId, tvReplStatus, tvReplName;
    private TextView tvReplBillNumber, tvReplCompanyBill;
    private TextView tvReplOpenedDate, tvReplAmount;

    // Bill images
    private LinearLayout layoutClosingImages;
    private ImageView ivOpenCustomer, ivOpenJewel, ivOpenUser;
    private ImageView ivCloseCustomer, ivCloseJewel, ivCloseUser;

    private final NumberFormat inFmt =
        NumberFormat.getNumberInstance(new Locale("en", "IN"));

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_billing);

        companyId = getIntent().getStringExtra("companyId");

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle("Billing Screen");
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }
        toolbar.setNavigationOnClickListener(v -> finish());

        bindViews();
        setupChips();
        setupSearchBar();

        // If launched from a bill list, pre-fill and auto-search
        String launchBill = getIntent().getStringExtra("billNumber");
        String launchMat  = getIntent().getStringExtra("materialType");
        if (launchBill != null && !launchBill.isEmpty()) {
            if (launchMat != null && !launchMat.isEmpty()) selectMaterial(launchMat);
            etBillNumber.setText(launchBill);
            searchBill();
        }
    }

    // ── View binding ──────────────────────────────────────────────────────────

    private void bindViews() {
        chipGold          = findViewById(R.id.chipGold);
        chipSilver        = findViewById(R.id.chipSilver);
        etBillNumber      = findViewById(R.id.etBillNumber);
        btnSearchBill     = findViewById(R.id.btnSearchBill);
        layoutBillDetails = findViewById(R.id.layoutBillDetails);

        // Bill info
        tvMaterialType    = findViewById(R.id.tvMaterialType);
        tvOpeningDate     = findViewById(R.id.tvOpeningDate);
        layoutCreatedInfo = findViewById(R.id.layoutCreatedInfo);
        tvCreatedBy       = findViewById(R.id.tvCreatedBy);
        tvCreatedAt       = findViewById(R.id.tvCreatedAt);

        // Customer
        tvCustomerName    = findViewById(R.id.tvCustomerName);
        tvGender          = findViewById(R.id.tvGender);
        tvSpouseType      = findViewById(R.id.tvSpouseType);
        tvSpouseName      = findViewById(R.id.tvSpouseName);
        tvMobile          = findViewById(R.id.tvMobile);
        tvDoorNo          = findViewById(R.id.tvDoorNo);
        tvStreet          = findViewById(R.id.tvStreet);
        tvArea            = findViewById(R.id.tvArea);
        tvCity            = findViewById(R.id.tvCity);

        // Jewel
        tvItems           = findViewById(R.id.tvItems);
        tvGrossWt         = findViewById(R.id.tvGrossWt);
        tvNetWt           = findViewById(R.id.tvNetWt);
        tvPurity          = findViewById(R.id.tvPurity);
        tvAmount          = findViewById(R.id.tvAmount);

        // Opening calculated
        tvInterest        = findViewById(R.id.tvInterest);
        tvIntPerMonth     = findViewById(R.id.tvIntPerMonth);
        tvDocCharge       = findViewById(R.id.tvDocCharge);
        tvRatePerGm       = findViewById(R.id.tvRatePerGm);
        tvTakenAmt        = findViewById(R.id.tvTakenAmt);
        tvToGive          = findViewById(R.id.tvToGive);
        tvGivenAmount     = findViewById(R.id.tvGivenAmount);
        tvStatus          = findViewById(R.id.tvStatus);
        tvPhysicalLocation= findViewById(R.id.tvPhysicalLocation);

        // Closing section
        layoutClosingSection  = findViewById(R.id.layoutClosingSection);
        tvCloseInterestType   = findViewById(R.id.tvCloseInterestType);
        tvActualTotalMonths   = findViewById(R.id.tvActualTotalMonths);
        tvMinimumMonths       = findViewById(R.id.tvMinimumMonths);
        tvToReduceMonths      = findViewById(R.id.tvToReduceMonths);
        tvCloseAcceptedDate   = findViewById(R.id.tvCloseAcceptedDate);
        tvForMonths           = findViewById(R.id.tvForMonths);
        tvCloseInterest       = findViewById(R.id.tvCloseInterest);
        tvClosingDate         = findViewById(R.id.tvClosingDate);
        tvCloseTakenAmt   = findViewById(R.id.tvCloseTakenAmt);
        tvTotalAdvance    = findViewById(R.id.tvTotalAdvance);
        tvOtherCharges    = findViewById(R.id.tvOtherCharges);
        tvToGetAmt        = findViewById(R.id.tvToGetAmt);
        tvDiscount        = findViewById(R.id.tvDiscount);
        tvGotAmt          = findViewById(R.id.tvGotAmt);
        tvCustomerCopy    = findViewById(R.id.tvCustomerCopy);
        tvClosedBy        = findViewById(R.id.tvClosedBy);

        // Additional
        tvNominee         = findViewById(R.id.tvNominee);
        tvIdProofType     = findViewById(R.id.tvIdProofType);
        tvIdProofNumber   = findViewById(R.id.tvIdProofNumber);
        tvReferredBy      = findViewById(R.id.tvReferredBy);
        tvCustomerId      = findViewById(R.id.tvCustomerId);
        tvOccupation      = findViewById(R.id.tvOccupation);
        tvAcceptedDate    = findViewById(R.id.tvAcceptedDate);
        tvNote            = findViewById(R.id.tvNote);

        // Repledge
        layoutRepledgeSection = findViewById(R.id.layoutRepledgeSection);
        tvReplBillId          = findViewById(R.id.tvReplBillId);
        tvReplStatus          = findViewById(R.id.tvReplStatus);
        tvReplName            = findViewById(R.id.tvReplName);
        tvReplBillNumber      = findViewById(R.id.tvReplBillNumber);
        tvReplCompanyBill     = findViewById(R.id.tvReplCompanyBill);
        tvReplOpenedDate      = findViewById(R.id.tvReplOpenedDate);
        tvReplAmount          = findViewById(R.id.tvReplAmount);

        // Bill images
        layoutClosingImages = findViewById(R.id.layoutClosingImages);
        ivOpenCustomer      = findViewById(R.id.ivOpenCustomer);
        ivOpenJewel         = findViewById(R.id.ivOpenJewel);
        ivOpenUser          = findViewById(R.id.ivOpenUser);
        ivCloseCustomer     = findViewById(R.id.ivCloseCustomer);
        ivCloseJewel        = findViewById(R.id.ivCloseJewel);
        ivCloseUser         = findViewById(R.id.ivCloseUser);
    }

    // ── Material type chips ───────────────────────────────────────────────────

    private void setupChips() {
        chipGold.setOnClickListener(v -> selectMaterial("GOLD"));
        chipSilver.setOnClickListener(v -> selectMaterial("SILVER"));
        selectMaterial("GOLD");
    }

    private void selectMaterial(String type) {
        selectedMaterialType = type;
        boolean gold = "GOLD".equals(type);
        chipGold.setBackgroundResource(gold ? R.drawable.chip_selected : R.drawable.chip_unselected);
        chipGold.setTextColor(getResources().getColor(gold ? R.color.bg_dark : R.color.gold, getTheme()));
        chipSilver.setBackgroundResource(gold ? R.drawable.chip_unselected : R.drawable.chip_selected);
        chipSilver.setTextColor(getResources().getColor(gold ? R.color.gold : R.color.bg_dark, getTheme()));
        layoutBillDetails.setVisibility(View.GONE);
    }

    // ── Search ────────────────────────────────────────────────────────────────

    private void setupSearchBar() {
        btnSearchBill.setOnClickListener(v -> searchBill());
        etBillNumber.setOnEditorActionListener((v, actionId, event) -> {
            searchBill(); return true;
        });
    }

    private void searchBill() {
        String billNo = etBillNumber.getText().toString().trim();
        if (billNo.isEmpty()) {
            Toast.makeText(this, "Enter a bill number", Toast.LENGTH_SHORT).show();
            return;
        }
        btnSearchBill.setEnabled(false);
        btnSearchBill.setText("…");
        layoutBillDetails.setVisibility(View.GONE);

        ApiService.findBill(companyId, billNo, selectedMaterialType, new ApiService.Callback<JSONObject>() {
            @Override public void onSuccess(JSONObject r) {
                runOnUiThread(() -> {
                    btnSearchBill.setEnabled(true);
                    btnSearchBill.setText("SEARCH");
                    populateFromBill(r);
                });
            }
            @Override public void onError(String msg) {
                runOnUiThread(() -> {
                    btnSearchBill.setEnabled(true);
                    btnSearchBill.setText("SEARCH");
                    Toast.makeText(BillingActivity.this,
                        "Not found: " + msg, Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    // ── Populate all fields from fetched bill ─────────────────────────────────

    private void populateFromBill(JSONObject r) {
        // Bill info
        tvMaterialType.setText(r.optString("material_type", ""));
        tvOpeningDate.setText(r.optString("opening_date", ""));

        String createdAt = r.optString("created_date", "");
        if (!createdAt.isEmpty() && !"null".equals(createdAt)) {
            tvCreatedAt.setText(createdAt);
            tvCreatedBy.setText(r.optString("created_user_id", ""));
            layoutCreatedInfo.setVisibility(View.VISIBLE);
        } else {
            layoutCreatedInfo.setVisibility(View.GONE);
        }

        // Customer
        tvCustomerName.setText(r.optString("customer_name", ""));
        tvGender.setText(r.optString("gender", ""));
        tvSpouseType.setText(r.optString("spouse_type", ""));
        tvSpouseName.setText(r.optString("spouse_name", ""));
        tvMobile.setText(r.optString("mobile_number", ""));
        tvDoorNo.setText(r.optString("door_number", ""));
        tvStreet.setText(r.optString("street", ""));
        tvArea.setText(r.optString("area", ""));
        tvCity.setText(r.optString("city", ""));

        // Jewel
        tvItems.setText(r.optString("items", ""));
        tvGrossWt.setText(r.optString("gross_weight", ""));
        tvNetWt.setText(r.optString("net_weight", ""));
        tvPurity.setText(r.optString("purity", ""));
        tvAmount.setText(fmt(r.optDouble("amount", 0)));

        // Opening calculated amounts
        double interest    = r.optDouble("interest",        0);
        double docCharge   = r.optDouble("document_charge", 0);
        double takenAmt    = r.optDouble("taken_amount",    0);
        double toGive      = r.optDouble("to_give_amount",  0);
        double givenAmt    = r.optDouble("given_amount",    0);
        double intPerMonth = takenAmt - docCharge;
        double grossWt     = r.optDouble("gross_weight",    0);
        double amount      = r.optDouble("amount",          0);
        double ratePerGm   = grossWt > 0 ? Math.round(amount / grossWt) : 0;

        tvInterest.setText(   fmt(interest));
        tvIntPerMonth.setText(fmt(intPerMonth));
        tvDocCharge.setText(  fmt(docCharge));
        tvRatePerGm.setText(  fmt(ratePerGm));
        tvTakenAmt.setText(   fmt(takenAmt));
        tvToGive.setText(     fmt(toGive));
        tvGivenAmount.setText(fmt(givenAmt));
        String statusVal = r.optString("status", "");
        tvStatus.setText(statusVal);
        tvStatus.setTextColor(getResources().getColor(statusColor(statusVal), getTheme()));
        tvPhysicalLocation.setText(r.optString("physical_location", ""));

        // Closing section — always show
        double closeTaken = r.optDouble("close_taken_amount", 0);
        String billStatus = r.optString("status", "");
        // CLOSED/DELIVERED/REBILLED/* → show stored DB values
        // OPENED/LOCKED/CANCELLED     → calculate via API
        boolean fetchFromDb = "CLOSED".equals(billStatus)
                           || "DELIVERED".equals(billStatus)
                           || "REBILLED".equals(billStatus)
                           || "REBILLED-REMOVED".equals(billStatus)
                           || "REBILLED-ADDED".equals(billStatus)
                           || "REBILLED-MULTIPLE".equals(billStatus);

        String acceptedClosingDate = r.optString("accepted_closing_date", "");
        String openDateIso         = toIsoDate(r.optString("opening_date", ""));

        // DB amount fields (used for both branches — fetchFromDb uses them directly,
        // OPENED branch will get them from the API response instead)
        final double dbCloseTaken    = closeTaken;
        final double dbTotalAdvance  = r.optDouble("total_advance_amount_paid", 0);
        final double dbOtherCharges  = r.optDouble("total_other_charges",       0);
        final double dbToGet         = r.optDouble("toget_amount",              0);
        final double dbDiscount      = r.optDouble("discount_amount",           0);
        final double dbGot           = r.optDouble("got_amount",                0);
        final String dbCustomerCopy  = r.optString("customer_copy",             "");
        final String dbClosedBy      = r.optString("closed_user_id",            "");
        final String dbClosingDate   = r.optString("closing_date",              "");

        if (fetchFromDb) {
            // CLOSED / REBILLED / DELIVERED — read ALL closing fields directly from DB.
            // No calculation needed; desktop stores every field on bill close.
            populateClosingSection(
                r.optString("close_interest_type",   ""),
                r.optString("total_days_or_months",  ""),
                String.valueOf(r.optInt("minimum_days_or_months", 0)),
                String.valueOf(r.optInt("reduce_days_or_months",  0)),
                acceptedClosingDate,
                String.valueOf(r.optDouble("taken_days_or_months", 0)),
                fmt(interest),
                dbClosingDate,
                dbCloseTaken,
                dbTotalAdvance,
                dbOtherCharges,
                dbToGet,
                dbDiscount,
                dbGot,
                dbCustomerCopy,
                dbClosedBy);
        } else {
            // OPENED / LOCKED / CANCELLED — fully calculate via server
            ApiService.calculateClosing(
                companyId, selectedMaterialType,
                amount, interest, docCharge,
                openDateIso, 0,
                null,   // closing date = today
                new ApiService.Callback<JSONObject>() {
                    @Override public void onSuccess(JSONObject c) {
                        runOnUiThread(() -> populateClosingSection(
                            c.optString("interestType",      "MONTH"),
                            c.optString("actualTotalMonths", ""),
                            c.optString("minimumMonths",     "0"),
                            c.optString("toReduceMonths",    "0"),
                            acceptedClosingDate,
                            String.valueOf(c.optDouble("forMonths", 0)),
                            fmt(interest),
                            "Today",
                            c.optDouble("closeTakenAmount",  0),
                            c.optDouble("totalAdvancePaid",  0),
                            c.optDouble("totalOtherCharges", 0),
                            c.optDouble("toGetAmount",       0),
                            c.optDouble("discount",          0),
                            c.optDouble("gotAmount",         0),
                            "", ""));
                    }
                    @Override public void onError(String msg) {
                        // Fallback: simple estimate
                        final long fallbackMonths = Math.max(1, calcMonthsElapsed(r.optString("opening_date", "")));
                        final double est    = Math.round(intPerMonth * fallbackMonths);
                        final double estGet = amount + est;
                        runOnUiThread(() -> populateClosingSection(
                            "MONTH",
                            fallbackMonths + " Months and 0 Days.",
                            "0", "0",
                            acceptedClosingDate,
                            String.valueOf(fallbackMonths),
                            fmt(interest), "Today",
                            est, 0, 0, estGet, 0, estGet, "", ""));
                    }
                });
        }

        // Additional
        tvNominee.setText(      r.optString("nominee_name",        ""));
        tvIdProofType.setText(  r.optString("cust_id_proof_type",  ""));
        tvIdProofNumber.setText(r.optString("cust_id_proof_number",""));
        tvReferredBy.setText(   r.optString("refered_by_name",     ""));
        tvCustomerId.setText(   r.optString("customer_id",         ""));
        tvOccupation.setText(   r.optString("customer_occupation", ""));
        String accDate = r.optString("accepted_closing_date", "");
        tvAcceptedDate.setText("null".equals(accDate) ? "—" : accDate);
        tvNote.setText(         r.optString("note",                ""));

        // Repledge section — show only when repledge data is present
        String replBillId = r.optString("repledge_bill_id", "").trim();
        if (!replBillId.isEmpty() && !"".equals(replBillId)) {
            tvReplBillId.setText(replBillId);
            tvReplName.setText(r.optString("repl_name", ""));
            tvReplBillNumber.setText(r.optString("repl_bill_number", ""));
            tvReplCompanyBill.setText(r.optString("repl_company_bill", ""));
            tvReplOpenedDate.setText(r.optString("repl_opening_date", ""));
            tvReplAmount.setText(fmt(r.optDouble("repl_amount", 0)));

            String replStatus = r.optString("repl_status", "");
            tvReplStatus.setText(replStatus);
            int replColor;
            switch (replStatus.toUpperCase()) {
                case "GIVEN":    replColor = getResources().getColor(R.color.blue,   getTheme()); break;
                case "OPENED":   replColor = getResources().getColor(R.color.green,  getTheme()); break;
                case "SUSPENSE": replColor = getResources().getColor(R.color.orange, getTheme()); break;
                default:         replColor = getResources().getColor(R.color.white,  getTheme()); break;
            }
            tvReplStatus.setTextColor(replColor);
            layoutRepledgeSection.setVisibility(View.VISIBLE);
        } else {
            layoutRepledgeSection.setVisibility(View.GONE);
        }

        // Bill images — load opening images always; closing only for non-open statuses
        String billNum = r.optString("bill_number", "");
        loadBillImages(billNum, selectedMaterialType, statusVal);

        layoutBillDetails.setVisibility(View.VISIBLE);
        Toast.makeText(this, "Loaded: " + billNum, Toast.LENGTH_SHORT).show();
    }

    private void populateClosingSection(
            String interestType, String actualTotalMonths,
            String minimumMonths, String toReduceMonths,
            String acceptedEndingDate, String forMonths,
            String interestPct, String closingDate,
            double closeTaken, double totalAdvance,
            double otherCharges, double toGet,
            double discount, double got,
            String customerCopy, String closedBy) {

        tvCloseInterestType.setText( interestType);
        tvActualTotalMonths.setText( actualTotalMonths.isEmpty() ? "—" : actualTotalMonths);
        tvMinimumMonths.setText(     minimumMonths);
        tvToReduceMonths.setText(    toReduceMonths);
        String accDate = acceptedEndingDate;
        tvCloseAcceptedDate.setText( "null".equals(accDate) || accDate.isEmpty() ? "—" : accDate);
        tvForMonths.setText(         forMonths);
        tvCloseInterest.setText(     interestPct);
        tvClosingDate.setText(       "null".equals(closingDate) || closingDate.isEmpty() ? "—" : closingDate);
        tvCloseTakenAmt.setText(     fmt(closeTaken));
        tvTotalAdvance.setText(      fmt(totalAdvance));
        tvOtherCharges.setText(      fmt(otherCharges));
        tvToGetAmt.setText(          fmt(toGet));
        tvDiscount.setText(          fmt(discount));
        tvGotAmt.setText(            fmt(got));
        tvCustomerCopy.setText(      "null".equals(customerCopy) || customerCopy.isEmpty() ? "—" : customerCopy);
        tvClosedBy.setText(          "null".equals(closedBy)     || closedBy.isEmpty()     ? "—" : closedBy);
        layoutClosingSection.setVisibility(View.VISIBLE);
    }

    /** Converts DD-MM-YYYY → yyyy-MM-dd for API calls */
    private String toIsoDate(String ddMmYyyy) {
        try {
            String[] p = ddMmYyyy.split("-");
            if (p.length == 3) return p[2] + "-" + p[1] + "-" + p[0];
        } catch (Exception ignored) {}
        return ddMmYyyy;
    }

    /** Returns months elapsed from DD-MM-YYYY opening date to today (ceiling, minimum 1) */
    private long calcMonthsElapsed(String ddMmYyyy) {
        try {
            String[] p = ddMmYyyy.split("-");
            if (p.length != 3) return 1;
            java.util.Calendar open = java.util.Calendar.getInstance();
            open.set(Integer.parseInt(p[2]), Integer.parseInt(p[1]) - 1, Integer.parseInt(p[0]));
            java.util.Calendar now = java.util.Calendar.getInstance();
            long days = (now.getTimeInMillis() - open.getTimeInMillis()) / (1000L * 60 * 60 * 24);
            return Math.max(1, (long) Math.ceil(days / 30.0));
        } catch (Exception e) {
            return 1;
        }
    }

    /** Returns a colour resource id appropriate for each bill status */
    private int statusColor(String status) {
        switch (status) {
            case "OPENED":           return R.color.green;
            case "LOCKED":           return R.color.orange;
            case "CANCELLED":        return R.color.red;
            case "CLOSED":
            case "DELIVERED":        return R.color.blue;
            case "REBILLED":
            case "REBILLED-REMOVED":
            case "REBILLED-ADDED":
            case "REBILLED-MULTIPLE": return R.color.gold;
            default:                 return R.color.white;
        }
    }

    private String fmt(double v) { return inFmt.format(v); }

    // ── Bill images ───────────────────────────────────────────────────────────

    /**
     * Loads all 6 bill images from S3.
     * Opening images are always shown.
     * Closing images are shown only when the bill is in a closed state.
     */
    private void loadBillImages(String billNumber, String materialType, String status) {
        if (billNumber.isEmpty()) return;

        boolean isClosed = "CLOSED".equals(status)
                        || "DELIVERED".equals(status)
                        || "REBILLED".equals(status)
                        || "REBILLED-REMOVED".equals(status)
                        || "REBILLED-ADDED".equals(status)
                        || "REBILLED-MULTIPLE".equals(status);

        layoutClosingImages.setVisibility(isClosed ? View.VISIBLE : View.GONE);

        loadImg(AppConfig.billImageUrl(companyId, materialType, billNumber, "open_customer.png"),
                ivOpenCustomer, "Opening – Customer");
        loadImg(AppConfig.billImageUrl(companyId, materialType, billNumber, "open_jewel.png"),
                ivOpenJewel,    "Opening – Jewel");
        loadImg(AppConfig.billImageUrl(companyId, materialType, billNumber, "open_user.png"),
                ivOpenUser,     "Opening – User");

        if (isClosed) {
            loadImg(AppConfig.billImageUrl(companyId, materialType, billNumber, "close_customer.png"),
                    ivCloseCustomer, "Closing – Customer");
            loadImg(AppConfig.billImageUrl(companyId, materialType, billNumber, "close_jewel.png"),
                    ivCloseJewel,    "Closing – Jewel");
            loadImg(AppConfig.billImageUrl(companyId, materialType, billNumber, "close_user.png"),
                    ivCloseUser,     "Closing – User");
        }
    }

    /** Loads a single S3 image into an ImageView; tap opens full-screen viewer. */
    private void loadImg(String url, ImageView iv, String title) {
        Glide.with(this)
             .load(url)
             .diskCacheStrategy(DiskCacheStrategy.ALL)
             .placeholder(android.R.color.darker_gray)
             .error(android.R.color.darker_gray)
             .into(iv);

        iv.setOnClickListener(v -> openImageFullScreen(url, title));
    }

    /** Shows the image full-screen in a dismiss-on-tap dialog. */
    private void openImageFullScreen(String url, String title) {
        Dialog dialog = new Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.BLACK));
        }

        ImageView full = new ImageView(this);
        full.setBackgroundColor(Color.BLACK);
        full.setScaleType(ImageView.ScaleType.FIT_CENTER);

        Glide.with(this)
             .load(url)
             .diskCacheStrategy(DiskCacheStrategy.ALL)
             .into(full);

        full.setOnClickListener(v -> dialog.dismiss());
        dialog.setContentView(full);
        dialog.show();
    }
}
