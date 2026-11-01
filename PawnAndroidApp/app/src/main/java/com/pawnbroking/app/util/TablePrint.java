package com.pawnbroking.app.util;

import android.content.Context;
import android.print.PrintAttributes;
import android.print.PrintDocumentAdapter;
import android.print.PrintManager;
import android.text.TextUtils;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import java.util.ArrayList;
import java.util.List;

/**
 * A table on paper, through Android's own print screen, so any printer the
 * phone knows or "Save as PDF" will do. Rows print as they are shown, with
 * subtotals and the total marked the way the desktop's TablePrint marks them.
 */
public final class TablePrint {

    public enum Kind { ROW, SUBTOTAL, TOTAL }

    private final String[] headers;
    private final boolean[] right;
    private final List<String[]> rows = new ArrayList<>();
    private final List<Kind> kinds = new ArrayList<>();

    public TablePrint(String[] headers, boolean[] right) {
        this.headers = headers;
        this.right = right;
    }

    public TablePrint row(Kind kind, String[] cells) {
        rows.add(cells);
        kinds.add(kind);
        return this;
    }

    public int size() { return rows.size(); }

    /** The WebView has to outlive this call, so it is handed back for the screen to hold. */
    public WebView show(Context c, String title, String sub, String jobName) {
        StringBuilder h = new StringBuilder("<html><head><meta charset='utf-8'><style>"
                + "@page{size:A4 landscape;margin:10mm}"
                + "body{font-family:sans-serif;color:#2B1A14;font-size:9px}"
                + "h1{font-size:15px;color:#611A28;margin:0 0 2px}"
                + ".s{color:#7C6655;margin-bottom:6px}"
                + "table{border-collapse:collapse;width:100%}"
                + "th{background:#611A28;color:#F0DA9B;padding:3px 4px;text-align:left}"
                + "td{padding:2px 4px;border-bottom:1px solid #ECE0CA}"
                + ".r{text-align:right}"
                + ".sub td{font-weight:bold;background:#F6EFE1}"
                + ".tot td{font-weight:bold;background:#ECE0CA;border-top:2px solid #611A28}"
                + "</style></head><body><h1>").append(esc(title)).append("</h1><div class='s'>").append(esc(sub))
                .append("</div><table><thead><tr>");
        for (int i = 0; i < headers.length; i++) {
            h.append("<th").append(right[i] ? " class='r'" : "").append(">")
             .append(esc(headers[i].replace('\n', ' '))).append("</th>");
        }
        h.append("</tr></thead><tbody>");
        for (int r = 0; r < rows.size(); r++) {
            Kind k = kinds.get(r);
            h.append(k == Kind.TOTAL ? "<tr class='tot'>" : k == Kind.SUBTOTAL ? "<tr class='sub'>" : "<tr>");
            String[] cells = rows.get(r);
            for (int i = 0; i < cells.length; i++) {
                h.append("<td").append(right[i] ? " class='r'" : "").append(">").append(esc(cells[i])).append("</td>");
            }
            h.append("</tr>");
        }
        h.append("</tbody></table></body></html>");

        WebView web = new WebView(c);
        web.setWebViewClient(new WebViewClient() {
            @Override public void onPageFinished(WebView view, String url) {
                PrintManager pm = (PrintManager) c.getSystemService(Context.PRINT_SERVICE);
                PrintDocumentAdapter a = view.createPrintDocumentAdapter(jobName);
                pm.print(jobName, a, new PrintAttributes.Builder()
                        .setMediaSize(PrintAttributes.MediaSize.ISO_A4.asLandscape()).build());
            }
        });
        web.loadDataWithBaseURL(null, h.toString(), "text/html", "UTF-8", null);
        return web;
    }

    private static String esc(String s) { return s == null ? "" : TextUtils.htmlEncode(s); }
}
