package com.pawnbroking.app.util;

import android.content.Context;

import androidx.core.content.ContextCompat;

import com.pawnbroking.app.R;

/**
 * The royal palette, resolved from resources.
 *
 * <p>Several screens build their tables in code rather than in a layout —
 * the MIS report, Today's Account, the account drill-down. Those used to
 * carry their own {@code Color.parseColor("#1E2A4A")} constants, which
 * could not follow light/night and went on painting the old navy scheme
 * long after the rest of the app had changed. Anything drawn in code now
 * takes its colours from here, so one edit to {@code colors.xml} moves
 * the whole app.
 *
 * <p>Build one per Activity in {@code onCreate} and keep it in a field.
 * Do not cache it statically: the values differ between light and night,
 * and a static copy would survive the switch.
 */
public final class Royal {

    /** Bands, primary buttons, filled surfaces. A SURFACE colour. */
    public final int wine;
    public final int wineDeep;

    /**
     * Wine as the colour of a word, not of a surface.
     *
     * <p>The two cannot be one token. A surface goes darker after dark
     * while ink has to go lighter, so painting text with {@link #wine}
     * put near-black on near-black at night and the Capital figure
     * disappeared from every stock row. Use this for any wine text,
     * and for an icon drawn on parchment.
     */
    public final int wineInk;

    /** The only gold readable as text on parchment. */
    public final int goldInk;
    /** Rules and fills. Too light for small text on parchment. */
    public final int goldRule;
    /** The tint behind an applied filter chip, and its edge. */
    public final int goldWash;
    public final int goldWashLine;

    /** Page, cards, card strips, hairlines. */
    public final int parchment;
    public final int surface;
    public final int strip;
    public final int line;
    public final int rowHighlight;

    /** Type on parchment, darkest first. */
    public final int ink;
    public final int inkBody;
    public final int inkSoft;
    public final int inkMute;
    /** For an em dash standing in for a zero. Not for real text. */
    public final int inkFaint;

    /** The silver side of the shop. */
    public final int navy;
    public final int silver;

    /** Ledger accents. */
    public final int emerald;
    public final int ruby;
    public final int violet;
    public final int amber;

    /** Text on the wine band. These do not flip at night. */
    public final int onWine;
    public final int onWineTitle;
    public final int onWineDim;

    public Royal(Context c) {
        wine         = of(c, R.color.wine);
        wineDeep     = of(c, R.color.wine_deep);
        wineInk      = of(c, R.color.wine_ink);
        goldInk      = of(c, R.color.gold_ink);
        goldRule     = of(c, R.color.gold_rule);
        goldWash     = of(c, R.color.gold_wash);
        goldWashLine = of(c, R.color.gold_wash_line);
        parchment    = of(c, R.color.parchment);
        surface      = of(c, R.color.surface);
        strip        = of(c, R.color.parchment_strip);
        line         = of(c, R.color.parchment_line);
        rowHighlight = of(c, R.color.row_highlight);
        ink          = of(c, R.color.ink);
        inkBody      = of(c, R.color.ink_body);
        inkSoft      = of(c, R.color.ink_soft);
        inkMute      = of(c, R.color.ink_mute);
        inkFaint     = of(c, R.color.ink_faint);
        navy         = of(c, R.color.navy);
        silver       = of(c, R.color.silver);
        emerald      = of(c, R.color.emerald);
        ruby         = of(c, R.color.ruby);
        violet       = of(c, R.color.violet);
        amber        = of(c, R.color.amber);
        onWine       = of(c, R.color.on_wine);
        onWineTitle  = of(c, R.color.on_wine_title);
        onWineDim    = of(c, R.color.on_wine_dim);
    }

    /** The colour a bill's left edge and number take, by metal. */
    public int metal(boolean gold) {
        return gold ? goldInk : navy;
    }

    /** Debit red, credit green, and a faint dash when the figure is zero. */
    public int money(double value, boolean debit) {
        if (value == 0) return inkFaint;
        return debit ? ruby : emerald;
    }

    private static int of(Context c, int res) {
        return ContextCompat.getColor(c, res);
    }
}
