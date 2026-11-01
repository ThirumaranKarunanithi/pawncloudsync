package com.pawnbroking.app.util;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;

import androidx.core.content.res.ResourcesCompat;

import com.pawnbroking.app.R;

/**
 * One row of a wide table, drawn as a single view.
 *
 * <p>The MIS has twenty columns and a shop can have 300 rows of them: 6,000
 * TextViews, measured twice by the scroller around them. One view a row costs
 * nothing to scroll, and it knows which column a finger came down on, which is
 * what a figure's tap and long-press need.
 */
public class GridRowView extends View {

    public interface OnCell {
        void onTap(int column);
        void onLong(int column);
    }

    private final TextPaint paint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final float density;
    private final Typeface regular, bold;
    private final GestureDetector gestures;

    private String[] cells = new String[0];
    private String[] fitted = new String[0];
    private int[] widths = new int[0];
    private int[] colors = new int[0];
    private boolean[] rightAlign = new boolean[0];
    private boolean[] boldCell = new boolean[0];
    private int total, heightPx;
    private OnCell onCell;

    public GridRowView(Context c) {
        super(c);
        density = c.getResources().getDisplayMetrics().density;
        regular = ResourcesCompat.getFont(c, R.font.royal_sans);
        bold = Typeface.create(regular, Typeface.BOLD);
        paint.setFontFeatureSettings("tnum");
        setTextSizeSp(c, 12);
        heightPx = dp(36);
        gestures = new GestureDetector(c, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDown(MotionEvent e) { return true; }
            @Override public boolean onSingleTapUp(MotionEvent e) {
                if (onCell == null) return false;
                onCell.onTap(columnAt(e.getX()));
                return true;
            }
            @Override public void onLongPress(MotionEvent e) {
                if (onCell != null) onCell.onLong(columnAt(e.getX()));
            }
        });
    }

    public final void setTextSizeSp(Context c, float sp) {
        paint.setTextSize(sp * c.getResources().getDisplayMetrics().scaledDensity);
    }

    public GridRowView heightDp(int h) { heightPx = dp(h); return this; }

    /** Widths in dp, one a column. Numbers line up because the face is tabular. */
    public GridRowView set(String[] cells, int[] widthsDp, int[] colors, boolean[] right, boolean[] boldCell) {
        this.cells = cells;
        this.fitted = new String[cells.length];
        this.widths = new int[widthsDp.length];
        total = 0;
        for (int i = 0; i < widthsDp.length; i++) { widths[i] = dp(widthsDp[i]); total += widths[i]; }
        this.colors = colors;
        this.rightAlign = right;
        this.boldCell = boldCell;
        requestLayout();
        invalidate();
        return this;
    }

    public void setOnCell(OnCell l) { onCell = l; setClickable(l != null); }

    private int columnAt(float x) {
        int at = 0;
        for (int i = 0; i < widths.length; i++) {
            at += widths[i];
            if (x < at) return i;
        }
        return widths.length - 1;
    }

    private int dp(int v) { return Math.round(v * density); }

    @Override protected void onMeasure(int w, int h) { setMeasuredDimension(total, heightPx); }

    @Override public boolean onTouchEvent(MotionEvent e) {
        if (onCell == null) return false;
        gestures.onTouchEvent(e);
        return super.onTouchEvent(e);          // the ripple and the pressed state
    }

    @Override protected void onDraw(Canvas canvas) {
        int pad = dp(6), x = 0;
        for (int i = 0; i < cells.length; i++) {
            int w = widths[i];
            String text = cells[i];
            if (text != null && !text.isEmpty()) {
                paint.setColor(colors[i]);
                paint.setTypeface(boldCell[i] ? bold : regular);
                String[] lines = text.split("\n");
                float line = paint.getFontSpacing();
                float y = (heightPx - line * lines.length) / 2f - paint.ascent() - (paint.getFontMetrics().descent * 0f);
                for (String l : lines) {
                    String shown = l;
                    if (lines.length == 1) {
                        if (fitted[i] == null) {
                            fitted[i] = TextUtils.ellipsize(l, paint, w - pad * 2, TextUtils.TruncateAt.END).toString();
                        }
                        shown = fitted[i];
                    }
                    float tw = paint.measureText(shown);
                    float tx = rightAlign[i] ? x + w - pad - tw : x + pad;
                    canvas.drawText(shown, tx, y, paint);
                    y += line;
                }
            }
            x += w;
        }
    }
}
