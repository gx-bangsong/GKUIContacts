/*
 * Copyright (C) 2024 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.contacts.util;

import android.content.Context;
import android.icu.util.ChineseCalendar;

import com.android.contacts.R;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.GregorianCalendar;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;

/**
 * Conversions and formatting for the Chinese lunar calendar (农历).
 *
 * <p>The conversions are done with {@link ChineseCalendar}. The months of a lunar year are
 * enumerated by walking over the days of the year, which makes the handling of the leap months
 * (闰月) independent of the way the calendar implementation encodes them.
 */
public final class LunarCalendarUtils {

    /**
     * Value of the marker column of an event which tells that its date is a date of the Chinese
     * lunar calendar.
     */
    public static final String MARKER_LUNAR = "lunar";
    /** Value of the marker column for a lunar date which is in a leap month (闰月). */
    public static final String MARKER_LUNAR_LEAP = "lunar_leap";

    /** The first and the last lunar year which can be picked. */
    public static final int MIN_YEAR = 1900;
    public static final int MAX_YEAR = 2100;

    private static final TimeZone UTC_TIME_ZONE = TimeZone.getTimeZone("UTC");
    private static final long DAY_IN_MILLIS = 24 * 60 * 60 * 1000L;
    /** A lunar year has 353 to 385 days. */
    private static final int MAX_DAYS_OF_YEAR = 400;
    /** The Chinese new year is always between these days of a Gregorian year. */
    private static final int NEW_YEAR_FIRST_DAY = 15;
    private static final int NEW_YEAR_LAST_DAY = 61;

    private static final Map<Integer, List<LunarMonth>> sMonthsCache =
            new HashMap<Integer, List<LunarMonth>>();

    private LunarCalendarUtils() {
    }

    /** One month of a lunar year. */
    public static final class LunarMonth {
        /** The number of the month, from 1 (正月) to 12 (腊月). */
        public final int month;
        /** Whether this is a leap month (闰月). */
        public final boolean leap;
        /** The number of days of this month, 29 or 30. */
        public final int days;
        /** The Gregorian date of the first day of this month. */
        public final int gregorianYear;
        public final int gregorianMonth;
        public final int gregorianDay;

        private final long mStartMillis;

        private LunarMonth(int month, boolean leap, int days, long startMillis,
                int gregorianYear, int gregorianMonth, int gregorianDay) {
            this.month = month;
            this.leap = leap;
            this.days = days;
            this.mStartMillis = startMillis;
            this.gregorianYear = gregorianYear;
            this.gregorianMonth = gregorianMonth;
            this.gregorianDay = gregorianDay;
        }
    }

    /** Returns true when the given marker value denotes a date of the lunar calendar. */
    public static boolean isLunarMarker(String marker) {
        return MARKER_LUNAR.equals(marker) || MARKER_LUNAR_LEAP.equals(marker);
    }

    /** Returns true when the given marker value denotes a lunar date in a leap month. */
    public static boolean isLeapMarker(String marker) {
        return MARKER_LUNAR_LEAP.equals(marker);
    }

    /** Returns the months of the given lunar year, in the order of the year. */
    public static List<LunarMonth> getMonths(int lunarYear) {
        synchronized (sMonthsCache) {
            List<LunarMonth> months = sMonthsCache.get(lunarYear);
            if (months == null) {
                months = Collections.unmodifiableList(computeMonths(lunarYear));
                sMonthsCache.put(lunarYear, months);
            }
            return months;
        }
    }

    /** Returns the month of the given lunar year, or null when the year has no such month. */
    public static LunarMonth getMonth(int lunarYear, int month, boolean leap) {
        for (LunarMonth candidate : getMonths(lunarYear)) {
            if (candidate.month == month && candidate.leap == leap) {
                return candidate;
            }
        }
        return null;
    }

    /** Returns the number of days of the given lunar month, or 0 when it does not exist. */
    public static int getDaysInMonth(int lunarYear, int month, boolean leap) {
        final LunarMonth lunarMonth = getMonth(lunarYear, month, leap);
        return lunarMonth == null ? 0 : lunarMonth.days;
    }

    /** Returns true when the given lunar year has a leap month with the given number. */
    public static boolean hasLeapMonth(int lunarYear, int month) {
        return getMonth(lunarYear, month, true) != null;
    }

    /** Returns the lunar year a Gregorian date belongs to. */
    public static int getLunarYear(int year, int month, int day) {
        final long newYear = findLunarNewYear(year);
        final long millis = toUtcMillis(year, month, day);
        return (newYear > 0 && millis >= newYear) ? year : year - 1;
    }

    /**
     * Converts a Gregorian date into a lunar date.
     *
     * @return an array of {year, month (1-12), day (1-30), leap (0 or 1)}, or null when the date
     *     is outside of the supported range
     */
    public static int[] gregorianToLunar(int year, int month, int day) {
        final int lunarYear = getLunarYear(year, month, day);
        if (lunarYear < MIN_YEAR) {
            return null;
        }
        final long millis = toUtcMillis(year, month, day);
        for (LunarMonth lunarMonth : getMonths(lunarYear)) {
            final long offset = millis - lunarMonth.mStartMillis;
            if (offset >= 0 && offset < (long) lunarMonth.days * DAY_IN_MILLIS) {
                final int lunarDay = (int) (offset / DAY_IN_MILLIS) + 1;
                return new int[] {
                        lunarYear, lunarMonth.month, lunarDay, lunarMonth.leap ? 1 : 0 };
            }
        }
        return null;
    }

    /**
     * Converts a lunar date into a Gregorian date. When the month has less days than the given
     * day, the last day of the month is used. When the lunar year has no leap month with the
     * given number, the regular month is used instead.
     *
     * @return an array of {year, month (1-12), day (1-31)}, or null when the date is outside of
     *     the supported range
     */
    public static int[] lunarToGregorian(int lunarYear, int month, boolean leap, int day) {
        if (lunarYear < MIN_YEAR || lunarYear > MAX_YEAR) {
            return null;
        }
        LunarMonth lunarMonth = getMonth(lunarYear, month, leap);
        if (lunarMonth == null && leap) {
            lunarMonth = getMonth(lunarYear, month, false);
        }
        if (lunarMonth == null) {
            return null;
        }
        final int lastDay = Math.min(Math.max(day, 1), lunarMonth.days);
        final GregorianCalendar gregorian = new GregorianCalendar(UTC_TIME_ZONE, Locale.US);
        gregorian.setTimeInMillis(lunarMonth.mStartMillis + (lastDay - 1) * DAY_IN_MILLIS);
        return new int[] {
                gregorian.get(Calendar.YEAR),
                gregorian.get(Calendar.MONTH) + 1,
                gregorian.get(Calendar.DAY_OF_MONTH),
        };
    }

    /**
     * Returns the next date on which the given lunar date is celebrated, i.e. the next date which
     * is not before today. When the year of the lunar date is known, that date is returned.
     */
    public static Calendar getNextAnnualDate(int lunarYear, int month, boolean leap, int day) {
        final Calendar today = Calendar.getInstance();
        today.setTimeInMillis(System.currentTimeMillis());
        today.set(Calendar.HOUR_OF_DAY, 0);
        today.set(Calendar.MINUTE, 0);
        today.set(Calendar.SECOND, 0);
        today.set(Calendar.MILLISECOND, 0);

        // The next occurrence is searched in the lunar year of today and in the years after it,
        // so that a date of the running lunar year is found even when its Gregorian year differs.
        final int firstYear = lunarYear >= MIN_YEAR ? lunarYear
                : getLunarYear(today.get(Calendar.YEAR), today.get(Calendar.MONTH) + 1,
                        today.get(Calendar.DAY_OF_MONTH));
        for (int year = firstYear; year <= MAX_YEAR; year++) {
            final int[] gregorian = lunarToGregorian(year, month, leap, day);
            if (gregorian == null) {
                return null;
            }
            final Calendar candidate = Calendar.getInstance();
            candidate.clear();
            candidate.set(gregorian[0], gregorian[1] - 1, gregorian[2]);
            if (lunarYear >= MIN_YEAR || !candidate.before(today)) {
                return candidate;
            }
        }
        return null;
    }

    /** Returns the name of the given lunar month, for example "八月" or "闰四月". */
    public static String getMonthName(Context context, int month, boolean leap) {
        final String[] names = context.getResources().getStringArray(R.array.lunar_month_names);
        final String name = (month >= 1 && month <= names.length)
                ? names[month - 1] : String.valueOf(month);
        return leap ? context.getString(R.string.lunar_leap_month, name) : name;
    }

    /** Returns the name of the given day of a lunar month, for example "十五". */
    public static String getDayName(Context context, int day) {
        final String[] names = context.getResources().getStringArray(R.array.lunar_day_names);
        return (day >= 1 && day <= names.length) ? names[day - 1] : String.valueOf(day);
    }

    /**
     * Returns the text of a lunar date, for example "农历八月十五" or "农历1990年八月十五".
     *
     * @param year the lunar year, or 0 when the date has no year
     */
    public static String getDisplayString(
            Context context, int year, int month, boolean leap, int day) {
        final String monthName = getMonthName(context, month, leap);
        final String dayName = getDayName(context, day);
        if (year > 0) {
            return context.getString(R.string.lunar_date_with_year, year, monthName, dayName);
        }
        return context.getString(R.string.lunar_date_without_year, "", monthName, dayName);
    }

    /**
     * Returns the text of a lunar date which is stored in the given value, or null when the
     * value does not hold a lunar date.
     */
    public static String getDisplayString(Context context, String storedDate, String marker) {
        if (!isLunarMarker(marker)) {
            return null;
        }
        final int[] date = parseLunarDate(storedDate);
        if (date == null) {
            return null;
        }
        return getDisplayString(context, date[0], date[1], isLeapMarker(marker), date[2]);
    }

    /**
     * Encodes a lunar date the way a Gregorian date is stored, i.e. as "yyyy-MM-dd" or, when the
     * year is not set, as "--MM-dd".
     */
    public static String encodeDate(int year, int month, int day) {
        if (year < MIN_YEAR) {
            return String.format(Locale.US, "--%02d-%02d", month, day);
        }
        return String.format(Locale.US, "%04d-%02d-%02d", year, month, day);
    }

    /**
     * Parses a date which was encoded by {@link #encodeDate(int, int, int)}.
     *
     * @return an array of {year, month, day}, where the year is 0 when the date has no year, or
     *     null when the value is not a date
     */
    public static int[] parseLunarDate(String value) {
        if (value == null) {
            return null;
        }
        final String trimmed = value.trim();
        try {
            if (trimmed.length() == 7 && trimmed.startsWith("--")) {
                return new int[] {
                        0,
                        Integer.parseInt(trimmed.substring(2, 4)),
                        Integer.parseInt(trimmed.substring(5, 7)),
                };
            } else if (trimmed.length() >= 10) {
                return new int[] {
                        Integer.parseInt(trimmed.substring(0, 4)),
                        Integer.parseInt(trimmed.substring(5, 7)),
                        Integer.parseInt(trimmed.substring(8, 10)),
                };
            }
        } catch (NumberFormatException e) {
            // Not a date.
        }
        return null;
    }

    /**
     * Returns the first day of the lunar year which starts in the given Gregorian year, as UTC
     * milliseconds, or -1 when it cannot be determined.
     */
    private static long findLunarNewYear(int gregorianYear) {
        final GregorianCalendar day = new GregorianCalendar(UTC_TIME_ZONE, Locale.US);
        day.clear();
        day.set(gregorianYear, Calendar.JANUARY, NEW_YEAR_FIRST_DAY);
        for (int i = NEW_YEAR_FIRST_DAY; i <= NEW_YEAR_LAST_DAY; i++) {
            final int[] lunar = lunarDayOf(day.getTimeInMillis());
            if (lunar[0] == 1 && lunar[1] == 1 && lunar[2] == 0) {
                return day.getTimeInMillis();
            }
            day.add(Calendar.DAY_OF_MONTH, 1);
        }
        return -1;
    }

    /**
     * Walks over the days of the given lunar year and collects its months. A leap month is a
     * month with the number of the month it follows.
     */
    private static List<LunarMonth> computeMonths(int lunarYear) {
        final List<LunarMonth> months = new ArrayList<LunarMonth>();
        final long newYear = findLunarNewYear(lunarYear);
        if (newYear < 0) {
            return months;
        }

        int monthNumber = 0;
        boolean previousLeap = false;
        int previousMonth = -1;
        int days = 0;
        long startMillis = newYear;
        int startYear = 0;
        int startMonth = 0;
        int startDay = 0;

        long millis = newYear;
        for (int i = 0; i < MAX_DAYS_OF_YEAR; i++) {
            final int[] lunar = lunarDayOf(millis);
            final int month = lunar[0];
            final int dayOfMonth = lunar[1];
            final boolean leap = lunar[2] == 1;

            if (i > 0 && dayOfMonth == 1) {
                // The first day of a month: finish the month before it.
                months.add(new LunarMonth(monthNumber, previousLeap, days, startMillis,
                        startYear, startMonth, startDay));
                if (!leap && month == 1 && months.size() >= 12) {
                    // The first month of the next lunar year.
                    break;
                }
                days = 0;
                startMillis = millis;
                final GregorianCalendar start = new GregorianCalendar(UTC_TIME_ZONE, Locale.US);
                start.setTimeInMillis(millis);
                startYear = start.get(Calendar.YEAR);
                startMonth = start.get(Calendar.MONTH) + 1;
                startDay = start.get(Calendar.DAY_OF_MONTH);
            }

            if (previousMonth != month || previousLeap != leap) {
                if (i == 0) {
                    final GregorianCalendar start = new GregorianCalendar(UTC_TIME_ZONE, Locale.US);
                    start.setTimeInMillis(millis);
                    startYear = start.get(Calendar.YEAR);
                    startMonth = start.get(Calendar.MONTH) + 1;
                    startDay = start.get(Calendar.DAY_OF_MONTH);
                }
                // A leap month keeps the number of the month before it.
                monthNumber = leap ? monthNumber : monthNumber + 1;
                previousMonth = month;
                previousLeap = leap;
            }

            days++;
            millis += DAY_IN_MILLIS;
        }

        if (days > 0 && months.isEmpty()) {
            months.add(new LunarMonth(monthNumber, previousLeap, days, startMillis,
                    startYear, startMonth, startDay));
        }
        return months;
    }

    /**
     * Returns the month and the day of the lunar calendar of the given UTC date, as
     * {month (1-12), day (1-30), leap (0 or 1)}.
     */
    private static int[] lunarDayOf(long utcMillis) {
        final ChineseCalendar calendar = new ChineseCalendar();
        calendar.setTimeZone(UTC_TIME_ZONE);
        calendar.setTimeInMillis(utcMillis);
        return new int[] {
                calendar.get(ChineseCalendar.MONTH) + 1,
                calendar.get(ChineseCalendar.DAY_OF_MONTH),
                calendar.get(ChineseCalendar.IS_LEAP_MONTH) == 1 ? 1 : 0,
        };
    }

    private static long toUtcMillis(int year, int month, int day) {
        final GregorianCalendar calendar = new GregorianCalendar(UTC_TIME_ZONE, Locale.US);
        calendar.clear();
        calendar.set(year, month - 1, day);
        return calendar.getTimeInMillis();
    }
}
