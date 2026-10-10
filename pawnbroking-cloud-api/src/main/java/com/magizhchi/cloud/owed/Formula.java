package com.magizhchi.cloud.owed;

/**
 * Evaluates a shop's stored close formula.
 *
 * <p>The desktop keeps the formula as a string in {@code company_formula}
 * and hands it to a JavaScript engine. Every shop seen so far stores
 * plain arithmetic:
 *
 * <pre>((AMOUNT*INTEREST)/100)*TAKEN_MONTHS</pre>
 *
 * <p>so this is a small recursive-descent parser over {@code + - * / ( )}
 * and numbers rather than a scripting engine. That is deliberate: a
 * script engine on a multi-tenant server will happily run whatever a
 * row in a shop's database says, and the formula arrives there over the
 * sync like any other data.
 *
 * <p>The trade is that an unusual formula will not parse. When that
 * happens {@link #eval} throws and the caller shows nothing rather than
 * a number nobody can vouch for.
 */
public final class Formula {

    /** Thrown when a formula is not plain arithmetic this can evaluate. */
    public static class Unsupported extends RuntimeException {
        Unsupported(String m) { super(m); }
    }

    private final String s;
    private int i;

    private Formula(String s) { this.s = s; }

    /**
     * Substitutes the placeholders and evaluates.
     *
     * @param formula the raw text from company_formula
     * @throws Unsupported if it is not arithmetic, or divides by zero
     */
    public static double eval(String formula, double amount, double interest,
                              double documentCharge, double takenMonths, double takenDays) {
        if (formula == null || formula.isBlank()) throw new Unsupported("empty formula");
        // Longest names first: TAKEN_MONTHS and TAKEN_DAYS both start
        // with TAKEN_, and DOCUMENT_CHARGE must go before any shorter
        // token that could prefix it.
        String f = formula
                .replace("DOCUMENT_CHARGE", num(documentCharge))
                .replace("TAKEN_MONTHS",    num(takenMonths))
                .replace("TAKEN_DAYS",      num(takenDays))
                .replace("AMOUNT",          num(amount))
                .replace("INTEREST",        num(interest));

        Formula p = new Formula(f);
        double v = p.expr();
        p.skip();
        if (p.i < p.s.length()) {
            throw new Unsupported("unexpected '" + p.s.charAt(p.i) + "' in: " + formula);
        }
        if (Double.isNaN(v) || Double.isInfinite(v)) throw new Unsupported("not a number: " + formula);
        return v;
    }

    /** Bracketed so a negative value cannot turn "a-INTEREST" into "a--1". */
    private static String num(double d) { return "(" + d + ")"; }

    private void skip() {
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
    }

    private boolean eat(char c) {
        skip();
        if (i < s.length() && s.charAt(i) == c) { i++; return true; }
        return false;
    }

    private double expr() {
        double v = term();
        for (;;) {
            if (eat('+')) v += term();
            else if (eat('-')) v -= term();
            else return v;
        }
    }

    private double term() {
        double v = factor();
        for (;;) {
            if (eat('*')) v *= factor();
            else if (eat('/')) {
                double d = factor();
                if (d == 0) throw new Unsupported("divide by zero");
                v /= d;
            } else return v;
        }
    }

    private double factor() {
        if (eat('+')) return factor();
        if (eat('-')) return -factor();
        if (eat('(')) {
            double v = expr();
            if (!eat(')')) throw new Unsupported("missing )");
            return v;
        }
        skip();
        int from = i;
        while (i < s.length() && (Character.isDigit(s.charAt(i)) || s.charAt(i) == '.')) i++;
        if (from == i) {
            throw new Unsupported(i < s.length()
                    ? "cannot read '" + s.charAt(i) + "'" : "ends too soon");
        }
        try {
            return Double.parseDouble(s.substring(from, i));
        } catch (NumberFormatException e) {
            throw new Unsupported("bad number '" + s.substring(from, i) + "'");
        }
    }
}
