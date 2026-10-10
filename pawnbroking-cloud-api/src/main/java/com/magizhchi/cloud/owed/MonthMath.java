package com.magizhchi.cloud.owed;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/**
 * The desktop's date arithmetic, ported exactly.
 *
 * <p>Every method here mirrors one in
 * {@code com.magizhchi.pawnbroking.common.DateRelatedCalculations} on the
 * desktop app. They are ported verbatim rather than rewritten, quirks
 * included, because the only thing that makes a cloud-computed figure
 * worth showing is that it equals the number Bill Closing puts on the
 * screen in the shop. A tidier month count that disagrees by a day's
 * interest is worse than no number at all.
 *
 * <p>Two things that look wrong and are deliberate:
 * <ul>
 *   <li>{@link #chettinad} fixes a negative day remainder by adding 30,
 *       not by using the real length of the previous month.</li>
 *   <li>{@link #monthMinimumMet} can only ever return true - see its
 *       own note. It is kept that way so the cloud agrees with the
 *       desktop; fixing it is a decision for the shop, not for a port.</li>
 * </ul>
 */
public final class MonthMath {

    private MonthMath() { }

    /** Whole days between two dates. */
    public static long days(LocalDate start, LocalDate end) {
        return ChronoUnit.DAYS.between(start, end);
    }

    /**
     * Months and leftover days by the shop's own reckoning: the day
     * remainder borrows a flat 30, so a month is 30 days when it suits
     * and a calendar month everywhere else.
     *
     * @return {months, leftover days}
     */
    public static long[] chettinad(LocalDate start, LocalDate end) {
        int sDay = start.getDayOfMonth();
        int sMonth = start.getMonthValue();
        int sYear = start.getYear();

        int eDay = end.getDayOfMonth();
        int eMonth = end.getMonthValue();
        int eYear = end.getYear();

        int totDays = eDay - sDay;
        if (totDays < 0 && eMonth > 0) {
            eDay += 30;
            eMonth -= 1;
            totDays = eDay - sDay;
        }

        int totMonths = eMonth - sMonth;
        if (totMonths < 0 && eYear > sYear) {
            eMonth += 12;
            eYear -= 1;
            totMonths = eMonth - sMonth;
        }

        int totYear = eYear > sYear ? eYear - sYear : 0;
        return new long[] { totMonths + (totYear * 12L), totDays };
    }

    /** REDUCTION "MONTHS FROM TOTAL MONTH": take months off the count. */
    public static long[] monthsLessTotalMonths(long[] actual, int reduceMonths) {
        long months = actual[0];
        if (months > 0) months -= reduceMonths;
        return new long[] { months, actual[1] };
    }

    /**
     * REDUCTION "MONTHS FROM OPENING MONTH": walk forward from the
     * opening date spending real month lengths, skipping the first
     * {@code reduceMonths} of them, then count what is left.
     */
    public static long[] monthsLessOpeningMonths(LocalDate start, long totalDays, int reduceMonths) {
        LocalDate cal = start;
        long left = totalDays;
        for (int i = 0; i < reduceMonths; i++) {
            int len = cal.lengthOfMonth();
            if (len <= left) left -= len; else break;
            cal = cal.plusMonths(1);
        }
        long months = 0;
        while (true) {
            int len = cal.lengthOfMonth();
            if (len <= left) { left -= len; months++; } else break;
            cal = cal.plusMonths(1);
        }
        return new long[] { months, left };
    }

    /**
     * REDUCTION "DAYS", month interest: knock the days off first, then
     * count months. The leftover loses a day once a whole month has been
     * counted - the desktop does that and the figures depend on it.
     */
    public static long[] monthsLessDays(LocalDate start, long totalDays, int reduceDays) {
        LocalDate cal = start;
        long left = totalDays;
        if (reduceDays <= left) left -= reduceDays;

        long months = 0;
        while (true) {
            int len = cal.lengthOfMonth();
            if (len <= left) { left -= len; months++; } else break;
            cal = cal.plusMonths(1);
        }
        long remainder = months >= 1 ? (left > 0 ? left - 1 : 0) : left;
        return new long[] { months, remainder };
    }

    /** REDUCTION "MONTHS FROM OPENING MONTH", day interest. */
    public static long daysLessMonths(LocalDate start, long totalDays, int reduceMonths) {
        LocalDate cal = start;
        long left = totalDays;
        for (int i = 0; i < reduceMonths; i++) {
            int len = cal.lengthOfMonth();
            if (len <= left) left -= len; else return 0;
            cal = cal.plusMonths(1);
        }
        return left;
    }

    /** REDUCTION "DAYS", day interest. */
    public static long daysLessDays(long totalDays, int reduceDays) {
        return reduceDays <= totalDays ? totalDays - reduceDays : 0;
    }

    /**
     * MINIMUM "MONTHS FROM OPENING MONTH".
     *
     * <p>Faithful port, and it always returns true: the loop only ever
     * subtracts when there are enough days left, so the counter cannot
     * go below zero and the test below it cannot fail. On the desktop
     * that means a day-interest shop's month-minimum never bites. It is
     * reproduced rather than corrected so the two agree - flag it to the
     * shop if the minimum is supposed to do something.
     */
    public static boolean monthMinimumMet(LocalDate start, long totalDays, int minimumMonths) {
        LocalDate cal = start;
        long left = totalDays;
        for (int i = 0; i < minimumMonths; i++) {
            int len = cal.lengthOfMonth();
            if (len <= left) left -= len; else break;
            cal = cal.plusMonths(1);
        }
        return left >= 0;
    }

    /** MINIMUM "DAYS". */
    public static boolean dayMinimumMet(long totalDays, int minimumDays) {
        return totalDays > minimumDays;
    }

    /** Real days in the next {@code months} calendar months from a date. */
    public static long daysInMonths(LocalDate start, int months) {
        LocalDate cal = start;
        long total = 0;
        for (int i = 0; i < months; i++) {
            total += cal.lengthOfMonth();
            cal = cal.plusMonths(1);
        }
        return total;
    }
}
