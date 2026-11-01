package com.magizhchi.cloud.data;

import com.magizhchi.cloud.tenant.TenantJdbc;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * What the phone's MIS needs beyond {@code /mis-report}: each month's expenses, assets and liabilities, the
 * parts behind Company Profit and Repledge Int. Spent, and the list behind any figure. The same sums as the
 * desktop's ReportDBOperation.getMonthly* and MISDrillDown.load, over the JSONB projections.
 */
@RestController
@RequestMapping("/v1/data")
public class MisController {
    private static final Logger log = LoggerFactory.getLogger(MisController.class);
    private final TenantJdbc t;
    public MisController(TenantJdbc t) { this.t = t; }

    private static final String CLOSED =
            "('CLOSED','DELIVERED','REBILLED','REBILLED-REMOVED','REBILLED-ADDED','REBILLED-MULTIPLE')";
    private static final String[] MONTHS =
            {"JAN","FEB","MAR","APR","MAY","JUN","JUL","AUG","SEP","OCT","NOV","DEC"};

    static String text(String f)  { return "(payload->>'" + f + "')"; }

    /** A field as a number, 0 when blank or not one. The payload holds text. */
    static String num(String f) {
        return "(CASE WHEN payload->>'" + f + "' ~ '^-?[0-9]+(\\.[0-9]+)?$' THEN (payload->>'" + f + "')::numeric ELSE 0 END)";
    }

    /** "" when a text field holds only dots and spaces (a "." typed to pass a mandatory box). */
    static String clean(String f) { return "nullif(btrim(payload->>'" + f + "', ' .'), '')"; }

    /** SEP-2026 from a stored yyyy-MM-dd. */
    static String monthLabel(String f) {
        StringBuilder s = new StringBuilder("(CASE substring(payload->>'" + f + "' from 6 for 2)");
        for (int i = 0; i < 12; i++) s.append(String.format(" WHEN '%02d' THEN '%s'", i + 1, MONTHS[i]));
        return s + " END || '-' || substring(payload->>'" + f + "' from 1 for 4))";
    }

    /** dd-MM-yyyy from a stored yyyy-MM-dd, as the desktop's lists show it. */
    static String day(String f) {
        return "(substring(payload->>'" + f + "' from 9 for 2) || '-' || substring(payload->>'" + f
                + "' from 6 for 2) || '-' || substring(payload->>'" + f + "' from 1 for 4))";
    }

    static String hasDate(String f) { return " AND payload->>'" + f + "' ~ '^[0-9]{4}-[0-9]{2}-[0-9]{2}' "; }

    static String rows(String table, String company) {
        return " FROM projections WHERE table_name='" + table + "' AND NOT deleted"
                + (company == null ? "" : " AND payload->>'company_id' = ? ");
    }

    private static String company(String companyId) {
        return companyId == null || companyId.isBlank() || "ALL".equalsIgnoreCase(companyId) ? null : companyId;
    }

    // ------------------------------------------------------------------ the month figures

    static String expensesSql(String c) {
        return "SELECT mon AS month, sum(comp) AS company, sum(sal) AS salary, sum(allow) AS allowance,"
                + " sum(emp) AS employee, sum(rep) AS repledge FROM ("
                + " SELECT " + monthLabel("debitted_date") + " mon, " + num("debitted_amount") + " comp, 0 sal, 0 allow, 0 emp, 0 rep"
                + rows("company_other_debit", c) + hasDate("debitted_date")
                + " AND coalesce(payload->>'expense_or_asset', 'EXPENSE') = 'EXPENSE'"
                + " UNION ALL SELECT " + monthLabel("debitted_date") + ", 0, " + num("debitted_amount") + ", 0, 0, 0"
                + rows("employee_salary_amount_debit", c) + hasDate("debitted_date")
                + " UNION ALL SELECT " + monthLabel("debitted_date") + ", 0, 0, " + num("debitted_amount") + ", 0, 0"
                + rows("employee_daily_allowance_debit", c) + hasDate("debitted_date")
                + " UNION ALL SELECT " + monthLabel("debitted_date") + ", 0, 0, 0, " + num("debitted_amount") + ", 0"
                + rows("employee_other_amount_debit", c) + hasDate("debitted_date")
                + " UNION ALL SELECT " + monthLabel("debitted_date") + ", 0, 0, 0, 0, " + num("debitted_amount")
                + rows("repledge_bill_debit", c) + hasDate("debitted_date")
                + ") x GROUP BY 1";
    }

    static String assetsSql(String c) {
        return "SELECT mon AS month, sum(a) AS assets, sum(l) AS liabilities FROM ("
                + " SELECT " + monthLabel("debitted_date") + " mon, " + num("debitted_amount") + " a, 0 l"
                + rows("company_other_debit", c) + hasDate("debitted_date") + " AND payload->>'expense_or_asset' = 'ASSET'"
                + " UNION ALL SELECT " + monthLabel("credited_date") + ", 0, " + num("credited_amount")
                + rows("company_other_credit", c) + hasDate("credited_date")
                + " AND coalesce(payload->>'income_or_liability', 'LIABILITY') = 'LIABILITY'"
                + ") x GROUP BY 1";
    }

    static String interestPartsSql(String c) {
        return "SELECT mon AS month, mat AS material, sum(opn) AS opening, sum(doc) AS \"docCharge\", sum(cls) AS closing,"
                + " sum(oth) AS other, sum(dis) AS discount FROM ("
                + " SELECT " + monthLabel("opening_date") + " mon, upper(payload->>'jewel_material_type') mat,"
                + "  " + num("open_taken_amount") + " opn, " + num("document_charge") + " doc, 0 cls, 0 oth, 0 dis"
                + rows("company_billing", c) + hasDate("opening_date")
                + " AND upper(coalesce(payload->>'status','')) NOT IN ('CANCELED','CANCELLED')"
                + " UNION ALL SELECT " + monthLabel("closing_date") + ", upper(payload->>'jewel_material_type'), 0, 0,"
                + "  " + num("close_taken_amount") + ", " + num("total_other_charges") + ", " + num("discount_amount")
                + rows("company_billing", c) + hasDate("closing_date")
                + " AND upper(coalesce(payload->>'status','')) IN " + CLOSED
                + ") x GROUP BY 1, 2";
    }

    static String repledgePartsSql(String c) {
        return "SELECT mon AS month, sum(opn) AS opening, sum(odiff) AS \"lessAtOpening\", sum(cls) AS closing,"
                + " sum(cdiff) AS \"moreAtClosing\" FROM ("
                + " SELECT " + monthLabel("opening_date") + " mon, " + num("open_taken_amount") + " opn,"
                + "  CASE WHEN " + num("toget_amount") + " > 0 THEN " + num("toget_amount") + " - " + num("got_amount") + " ELSE 0 END odiff,"
                + "  0 cls, 0 cdiff"
                + rows("repledge_billing", c) + hasDate("opening_date")
                + " AND upper(coalesce(payload->>'jewel_material_type','')) = 'GOLD'"
                + " UNION ALL SELECT " + monthLabel("closing_date") + ", 0, 0, " + num("close_taken_amount") + ","
                + "  " + num("given_amount") + " - " + num("togive_amount")
                + rows("repledge_billing", c) + hasDate("closing_date")
                + " AND upper(coalesce(payload->>'jewel_material_type','')) = 'GOLD'"
                + ") x GROUP BY 1";
    }

    @GetMapping("/mis-extras")
    public Map<String,Object> extras(@RequestParam(name="companyId", required=false) String companyId) {
        final String co = company(companyId);
        return t.inTenant(j -> {
            Map<String,Object> out = new LinkedHashMap<>();
            out.put("expenses",      j.queryForList(expensesSql(co), repeat(co, 5)));
            out.put("assets",        j.queryForList(assetsSql(co), repeat(co, 2)));
            out.put("interestParts", j.queryForList(interestPartsSql(co), repeat(co, 2)));
            out.put("repledgeParts", j.queryForList(repledgePartsSql(co), repeat(co, 2)));
            return out;
        });
    }

    private static Object[] repeat(String co, int n) {
        if (co == null) return new Object[0];
        Object[] a = new Object[n];
        Arrays.fill(a, co);
        return a;
    }

    // ------------------------------------------------------------------ the list behind a figure

    /** One list: its columns, which are numbers, which are hidden keys, and its rows. */
    static final class Sheet {
        final String title;
        final List<String> headers = new ArrayList<>();
        final List<Boolean> numeric = new ArrayList<>();
        final List<Boolean> hidden = new ArrayList<>();
        final List<String> exprs = new ArrayList<>();
        int figureColumn = -1;
        String note = "";
        String sql;
        int repeat = 1;
        boolean withMaterial;

        Sheet(String title) { this.title = title; }

        Sheet col(String header, boolean number, String expr) {
            headers.add(header); numeric.add(number); hidden.add(false); exprs.add(expr);
            return this;
        }

        Sheet key(String header, String expr) {
            headers.add(header); numeric.add(false); hidden.add(true); exprs.add(expr);
            return this;
        }

        String select() {
            StringBuilder s = new StringBuilder("SELECT ");
            for (int i = 0; i < exprs.size(); i++) s.append(i == 0 ? "" : ", ").append(exprs.get(i)).append(" AS c").append(i);
            return s.toString();
        }
    }

    private static final String DIFFERENCE = "Difference";
    private static final String CUSTOMER =
            "trim(concat_ws(' ', payload->>'customer_name', payload->>'spouse_type', payload->>'spouse_name'))";
    private static final String REPL_BILL =
            "coalesce(nullif(trim(payload->>'repledge_bill_number'), ''), payload->>'repledge_bill_id')";
    private static final String KEY_REPLEDGE = "Repledge bill id";
    private static final String KEY_TABLE = "Table";

    /** yyyy-MM from SEP-2026, null when it is not a month. */
    static String yearMonth(String month) {
        if (month == null) return null;
        String[] p = month.trim().toUpperCase(Locale.ROOT).split("-");
        if (p.length != 2 || !p[1].matches("[0-9]{4}")) return null;
        int m = Arrays.asList(MONTHS).indexOf(p[0]);
        return m < 0 ? null : p[1] + "-" + String.format("%02d", m + 1);
    }

    private static String inMonth(String f) { return " AND left(payload->>'" + f + "', 7) = ? "; }

    /**
     * The list and its SQL. The arguments go (company?, month[, material]) per leg. material null = the
     * month's TOTAL row; repledge is GOLD only, and a month's expenses, assets and liabilities sit on GOLD.
     */
    static Sheet sheet(String kind, String month, String material, String c) {
        String mat = material == null ? "" : " AND upper(payload->>'jewel_material_type') = ? ";
        String of = "  -  " + month;
        String ofMat = of + (material == null ? "" : "  -  " + material);
        boolean silver = "SILVER".equals(material);
        switch (kind) {
            case "COMPANY_OPENED": {
                Sheet s = new Sheet("Bills opened" + ofMat);
                s.withMaterial = material != null;
                s.col("Opened", false, day("opening_date")).col("Bill", false, text("bill_number"))
                 .col("Material", false, "upper(payload->>'jewel_material_type')").col("Customer", false, CUSTOMER)
                 .col("Items", false, text("items")).col("Amount", true, num("amount"))
                 .col("Taken at opening", true, num("open_taken_amount")).col("Of it doc charge", true, num("document_charge"))
                 .col("Amount to give", true, num("togive_amount")).col("Given amount", true, num("given_amount"))
                 .col(DIFFERENCE, true, num("togive_amount") + " - " + num("given_amount")).col("Status", false, text("status"));
                s.figureColumn = 5;
                s.sql = s.select() + rows("company_billing", c) + inMonth("opening_date")
                        + " AND upper(coalesce(payload->>'status','')) NOT IN ('CANCELED','CANCELLED')" + mat
                        + " ORDER BY payload->>'opening_date', payload->>'bill_number'";
                return s;
            }
            case "COMPANY_REDEEMED": {
                Sheet s = new Sheet("Bills redeemed" + ofMat);
                s.withMaterial = material != null;
                s.col("Closed", false, day("closing_date")).col("Bill", false, text("bill_number"))
                 .col("Material", false, "upper(payload->>'jewel_material_type')").col("Customer", false, CUSTOMER)
                 .col("Opened", false, day("opening_date")).col("Amount", true, num("amount"))
                 .col("Closing interest", true, num("close_taken_amount")).col("Other charges", true, num("total_other_charges"))
                 .col("Discount", true, num("discount_amount")).col("Amount to be received", true, num("toget_amount"))
                 .col("Received amount", true, num("got_amount"))
                 .col(DIFFERENCE, true, num("toget_amount") + " - " + num("got_amount")).col("Status", false, text("status"));
                s.figureColumn = 5;
                s.sql = s.select() + rows("company_billing", c) + inMonth("closing_date")
                        + " AND upper(coalesce(payload->>'status','')) IN " + CLOSED + mat
                        + " ORDER BY payload->>'closing_date', payload->>'bill_number'";
                return s;
            }
            case "REPLEDGE_OPENED": {
                Sheet s = new Sheet("Repledge bills opened" + ofMat);
                if (silver) { s.note = "Silver is not repledged."; return s; }
                s.col("Opened", false, day("opening_date")).col("Repledge bill", false, REPL_BILL)
                 .col("Party", false, text("repledge_name")).col("Company bill", false, text("company_bill_number"))
                 .col("Amount", true, num("amount")).col("Interest taken", true, num("open_taken_amount"))
                 .col("Amount to be received", true, num("toget_amount")).col("Received amount", true, num("got_amount"))
                 .col(DIFFERENCE, true, num("toget_amount") + " - " + num("got_amount")).col("Status", false, text("status"))
                 .key(KEY_REPLEDGE, text("repledge_bill_id"));
                s.figureColumn = 4;
                s.sql = s.select() + rows("repledge_billing", c) + inMonth("opening_date")
                        + " AND upper(coalesce(payload->>'jewel_material_type','')) = 'GOLD'"
                        + " ORDER BY payload->>'opening_date', 2";
                return s;
            }
            case "REPLEDGE_REDEEMED": {
                Sheet s = new Sheet("Repledge bills redeemed" + ofMat);
                if (silver) { s.note = "Silver is not repledged."; return s; }
                s.col("Closed", false, day("closing_date")).col("Repledge bill", false, REPL_BILL)
                 .col("Party", false, text("repledge_name")).col("Company bill", false, text("company_bill_number"))
                 .col("Opened", false, day("opening_date")).col("Amount", true, num("amount"))
                 .col("Closing interest", true, num("close_taken_amount")).col("Amount to give", true, num("togive_amount"))
                 .col("Given amount", true, num("given_amount"))
                 .col(DIFFERENCE, true, num("togive_amount") + " - " + num("given_amount")).col("Status", false, text("status"))
                 .key(KEY_REPLEDGE, text("repledge_bill_id"));
                s.figureColumn = 5;
                s.sql = s.select() + rows("repledge_billing", c) + inMonth("closing_date")
                        + " AND upper(coalesce(payload->>'jewel_material_type','')) = 'GOLD'"
                        + " ORDER BY payload->>'closing_date', 2";
                return s;
            }
            case "COMPANY_PROFIT": {
                Sheet s = new Sheet("Company profit, bill by bill" + ofMat);
                s.withMaterial = material != null;
                s.repeat = 2;
                s.col("Date", false, "shown").col("Event", false, "ev").col("Bill", false, "bill").col("Material", false, "mat")
                 .col("Customer", false, "cust").col("Interest", true, "intr").col("Doc charge", true, "doc")
                 .col("Other charges", true, "oth").col("Discount", true, "dis").col("Profit", true, "profit");
                s.figureColumn = 9;
                s.sql = s.select() + " FROM ("
                        + " SELECT payload->>'opening_date' d, " + day("opening_date") + " shown, 'Opened' ev, payload->>'bill_number' bill,"
                        + "  upper(payload->>'jewel_material_type') mat, " + CUSTOMER + " cust,"
                        + "  " + num("open_taken_amount") + " - " + num("document_charge") + " intr, " + num("document_charge") + " doc,"
                        + "  0 oth, 0 dis, " + num("open_taken_amount") + " profit"
                        + rows("company_billing", c) + inMonth("opening_date")
                        + " AND upper(coalesce(payload->>'status','')) NOT IN ('CANCELED','CANCELLED')" + mat
                        + " UNION ALL SELECT payload->>'closing_date', " + day("closing_date") + ", 'Closed', payload->>'bill_number',"
                        + "  upper(payload->>'jewel_material_type'), " + CUSTOMER + ","
                        + "  " + num("close_taken_amount") + ", 0, " + num("total_other_charges") + ", " + num("discount_amount") + ","
                        + "  " + num("close_taken_amount") + " + " + num("total_other_charges") + " - " + num("discount_amount")
                        + rows("company_billing", c) + inMonth("closing_date")
                        + " AND upper(coalesce(payload->>'status','')) IN " + CLOSED + mat
                        + ") x ORDER BY d, bill, ev DESC";
                return s;
            }
            case "REPLEDGE_INTEREST": {
                Sheet s = new Sheet("Repledge interest spent, bill by bill" + ofMat);
                if (silver) { s.note = "Silver is not repledged."; return s; }
                s.repeat = 2;
                s.col("Date", false, "shown").col("Event", false, "ev").col("Repledge bill", false, "rb").col("Party", false, "party")
                 .col("Company bill", false, "cb").col("Interest", true, "intr").col("Paid more / received less", true, "diff")
                 .col("Spent", true, "intr + diff").key(KEY_REPLEDGE, "rid");
                s.figureColumn = 7;
                s.sql = s.select() + " FROM ("
                        + " SELECT payload->>'opening_date' d, " + day("opening_date") + " shown, 'Opened' ev, " + REPL_BILL + " rb,"
                        + "  payload->>'repledge_name' party, payload->>'company_bill_number' cb, " + num("open_taken_amount") + " intr,"
                        + "  CASE WHEN " + num("toget_amount") + " > 0 THEN " + num("toget_amount") + " - " + num("got_amount") + " ELSE 0 END diff,"
                        + "  payload->>'repledge_bill_id' rid"
                        + rows("repledge_billing", c) + inMonth("opening_date")
                        + " AND upper(coalesce(payload->>'jewel_material_type','')) = 'GOLD'"
                        + " UNION ALL SELECT payload->>'closing_date', " + day("closing_date") + ", 'Closed', " + REPL_BILL + ","
                        + "  payload->>'repledge_name', payload->>'company_bill_number', " + num("close_taken_amount") + ","
                        + "  " + num("given_amount") + " - " + num("togive_amount") + ", payload->>'repledge_bill_id'"
                        + rows("repledge_billing", c) + inMonth("closing_date")
                        + " AND upper(coalesce(payload->>'jewel_material_type','')) = 'GOLD'"
                        + ") x ORDER BY d, rb, ev DESC";
                return s;
            }
            case "EXPENSES": {
                Sheet s = new Sheet("Expenses" + of);
                if (silver) { s.note = "A month's expenses are on its GOLD row."; return s; }
                s.repeat = 5;
                s.col("Date", false, "shown").col("Id", false, "id").col("Kind", false, "kind").col("Type / person", false, "who")
                 .col("Details", false, "details").col("Amount", true, "amt").key(KEY_TABLE, "tbl");
                s.figureColumn = 5;
                String debit = "debitted_date";
                s.sql = s.select() + " FROM ("
                        + " SELECT payload->>'" + debit + "' d, " + day(debit) + " shown, payload->>'id' id, 'Company expense' kind,"
                        + "  coalesce(payload->>'expense_type', '') who,"
                        + "  concat_ws(' - ', " + clean("name") + ", " + clean("reason") + ", " + clean("note") + ") details,"
                        + "  " + num("debitted_amount") + " amt, 'company_other_debit' tbl"
                        + rows("company_other_debit", c) + inMonth(debit) + " AND coalesce(payload->>'expense_or_asset', 'EXPENSE') = 'EXPENSE'"
                        + " UNION ALL SELECT payload->>'" + debit + "', " + day(debit) + ", payload->>'id', 'Salary', payload->>'employee_name',"
                        + "  concat_ws(' ', 'for', payload->>'sal_for_mon_year', " + clean("note") + "), " + num("debitted_amount") + ", 'employee_salary_amount_debit'"
                        + rows("employee_salary_amount_debit", c) + inMonth(debit)
                        + " UNION ALL SELECT payload->>'" + debit + "', " + day(debit) + ", payload->>'id', 'Daily allowance', payload->>'employee_name',"
                        + "  coalesce(" + clean("note") + ", ''), " + num("debitted_amount") + ", 'employee_daily_allowance_debit'"
                        + rows("employee_daily_allowance_debit", c) + inMonth(debit)
                        + " UNION ALL SELECT payload->>'" + debit + "', " + day(debit) + ", payload->>'id', 'Paid to employee', payload->>'employee_name',"
                        + "  concat_ws(' - ', " + clean("debitted_reason") + ", " + clean("note") + "), " + num("debitted_amount") + ", 'employee_other_amount_debit'"
                        + rows("employee_other_amount_debit", c) + inMonth(debit)
                        + " UNION ALL SELECT payload->>'" + debit + "', " + day(debit) + ", payload->>'id', 'Repledge bill charge', payload->>'repledge_name',"
                        + "  concat_ws(' ', 'bill', coalesce(nullif(trim(payload->>'repledge_bill_number'), ''), payload->>'bill_number'), " + clean("note") + "),"
                        + "  " + num("debitted_amount") + ", 'repledge_bill_debit'"
                        + rows("repledge_bill_debit", c) + inMonth(debit)
                        + ") x ORDER BY d, kind, id";
                return s;
            }
            case "ASSETS": {
                Sheet s = new Sheet("Assets" + of);
                if (silver) { s.note = "A month's assets are on its GOLD row."; return s; }
                s.col("Date", false, day("debitted_date")).col("Id", false, text("id")).col("Type", false, "coalesce(payload->>'expense_type', '')")
                 .col("Name", false, "coalesce(" + clean("name") + ", '')")
                 .col("Details", false, "concat_ws(' - ', " + clean("reason") + ", " + clean("invoice_no") + ", " + clean("note") + ")")
                 .col("Amount", true, num("debitted_amount"));
                s.figureColumn = 5;
                s.sql = s.select() + rows("company_other_debit", c) + inMonth("debitted_date")
                        + " AND payload->>'expense_or_asset' = 'ASSET' ORDER BY payload->>'debitted_date', payload->>'id'";
                return s;
            }
            case "LIABILITIES": {
                Sheet s = new Sheet("Liabilities" + of);
                if (silver) { s.note = "A month's liabilities are on its GOLD row."; return s; }
                s.col("Date", false, day("credited_date")).col("Id", false, text("id")).col("Type", false, "coalesce(payload->>'expense_type', '')")
                 .col("Name", false, "coalesce(" + clean("name") + ", '')")
                 .col("Details", false, "concat_ws(' - ', " + clean("reason") + ", " + clean("invoice_no") + ", " + clean("note") + ")")
                 .col("Amount", true, num("credited_amount"));
                s.figureColumn = 5;
                s.sql = s.select() + rows("company_other_credit", c) + inMonth("credited_date")
                        + " AND coalesce(payload->>'income_or_liability', 'LIABILITY') = 'LIABILITY'"
                        + " ORDER BY payload->>'credited_date', payload->>'id'";
                return s;
            }
            default:
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "unknown list");
        }
    }

    /** (company?, month[, material]) once per leg, as {@link #sheet} wrote them. */
    static Object[] args(Sheet s, String yearMonth, String material, String c) {
        List<Object> a = new ArrayList<>();
        for (int i = 0; i < s.repeat; i++) {
            if (c != null) a.add(c);
            a.add(yearMonth);
            if (s.withMaterial) a.add(material);
        }
        return a.toArray();
    }

    @GetMapping("/mis-list")
    public Map<String,Object> list(@RequestParam String kind,
                                   @RequestParam String month,
                                   @RequestParam(required=false) String material,
                                   @RequestParam(name="companyId", required=false) String companyId) {
        final String ym = yearMonth(month);
        if (ym == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "bad month");
        final String mat = material == null || material.isBlank() ? null : material.toUpperCase(Locale.ROOT);
        final String co = company(companyId);
        final Sheet s = sheet(kind, month.toUpperCase(Locale.ROOT), mat, co);
        Map<String,Object> out = new LinkedHashMap<>();
        out.put("title", s.title);
        out.put("headers", s.headers);
        out.put("numeric", s.numeric);
        out.put("hidden", s.hidden);
        out.put("figureColumn", s.figureColumn);
        out.put("note", s.note);
        if (s.sql == null) {                                // a note instead of a list
            out.put("rows", List.of());
            return out;
        }
        try {
            out.put("rows", t.inTenant(j -> {
                List<List<Object>> rows = new ArrayList<>();
                for (Map<String,Object> m : j.queryForList(s.sql, args(s, ym, mat, co))) {
                    List<Object> row = new ArrayList<>(s.headers.size());
                    for (int i = 0; i < s.headers.size(); i++) row.add(m.get("c" + i));
                    rows.add(row);
                }
                return rows;
            }));
        } catch (RuntimeException e) {
            log.error("misList {} failed: {}", kind, e.toString(), e);
            throw e;
        }
        return out;
    }
}
