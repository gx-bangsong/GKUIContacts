/*
 * Copyright (C) 2026 The LineageOS Project
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

package com.android.contacts.pinyin;

import com.android.contacts.pinyin.ChineseSurnameTable.Match;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * Turns a list of display names into a surname-correct address-book index: a row permutation plus
 * the {@code sections}/{@code counts} pair that {@code ContactsSectionIndexer} consumes.
 *
 * <p>Pure Java, so the whole re-indexing algorithm is exercised on a host JVM.
 * {@code SurnameSortedCursor} is the thin Android wrapper that applies the result to a
 * {@code Cursor}.
 *
 * <h3>Why we re-sort rather than only re-labelling</h3>
 * {@code ContactsProvider} returns rows already ordered by {@code SORT_KEY_PRIMARY}, which puts 单
 * among the D's. If we only relabelled its section to S, the section boundaries would no longer be
 * contiguous and {@code ContactsSectionIndexer} — which assumes each section is one unbroken run —
 * would scroll to the wrong place. So the rows have to move too.
 *
 * <h3>Sort order</h3>
 * {@code (initial, surname reading, original position)}. Falling back to the original position
 * rather than re-comparing names is deliberate: it preserves the provider's locale-aware collation
 * within a surname group instead of substituting a naive code-point comparison, and it makes the
 * sort stable for free.
 */
public final class SurnameIndexBuilder {

    /** Sections with no contacts are dropped, so '#' only appears when something lands in it. */
    public static final String UNKNOWN_SECTION = "#";

    /** How many distinct surnames to record per section for the fast-scroll bubble (D1/C). */
    public static final int MAX_SURNAME_HINTS = 3;

    private SurnameIndexBuilder() {
    }

    /** Outcome of {@link #build}. Immutable; arrays are not shared with the caller's input. */
    public static final class Result {
        /**
         * {@code order[newPosition] = oldPosition}. Length equals the input length. Apply it to a
         * cursor by mapping requested positions through it.
         */
        public final int[] order;
        /** Section labels, e.g. {@code ["A", "C", "S", "#"]}. Never contains an empty string. */
        public final String[] sections;
        /** Row count per section, parallel to {@link #sections}. Never contains a zero. */
        public final int[] counts;
        /**
         * Up to {@link #MAX_SURNAME_HINTS} distinct Chinese surnames per section, in list order,
         * parallel to {@link #sections}. Empty for sections with no Chinese surnames — the bubble
         * then shows the bare letter, per D1.
         */
        public final String[][] surnameHints;
        /** True when the permutation is the identity, i.e. no row actually moved. */
        public final boolean isIdentityOrder;

        Result(int[] order, String[] sections, int[] counts, String[][] surnameHints,
                boolean isIdentityOrder) {
            this.order = order;
            this.sections = sections;
            this.counts = counts;
            this.surnameHints = surnameHints;
            this.isIdentityOrder = isIdentityOrder;
        }

        /** Total number of rows described. */
        public int getRowCount() {
            return order.length;
        }

        /**
         * Returns the surname hints for {@code sectionIndex} joined with {@code separator}, or an
         * empty string when there are none. Convenience for the bubble's second line.
         */
        public String getSurnameHintText(int sectionIndex, String separator) {
            if (sectionIndex < 0 || sectionIndex >= surnameHints.length) {
                return "";
            }
            final String[] hints = surnameHints[sectionIndex];
            if (hints.length == 0) {
                return "";
            }
            final StringBuilder sb = new StringBuilder();
            for (int i = 0; i < hints.length; i++) {
                if (i > 0) {
                    sb.append(separator);
                }
                sb.append(hints[i]);
            }
            return sb.toString();
        }
    }

    /**
     * Computes the index for {@code displayNames}.
     *
     * @param displayNames one entry per row, in the order the provider returned them. Individual
     *                     entries may be null or empty; they land in {@code '#'}.
     * @param resolver     supplies the surname-aware initial for each name
     * @return the permutation and section data; never null
     */
    public static Result build(List<String> displayNames, SurnamePinyinResolver resolver) {
        final int n = displayNames == null ? 0 : displayNames.size();
        if (n == 0) {
            return new Result(new int[0], new String[0], new int[0], new String[0][], true);
        }

        // Resolve every name once, up front. This is the only pass that can touch ICU.
        final char[] initials = new char[n];
        final String[] surnames = new String[n];
        final String[] readings = new String[n];
        for (int i = 0; i < n; i++) {
            final Match m = resolver.resolve(displayNames.get(i));
            initials[i] = m.initial;
            surnames[i] = isChineseSurname(m) ? m.surname : null;
            // Sort ordinary Han characters by the character itself when no reading is known, so
            // the ordering is at least deterministic.
            readings[i] = m.pinyin != null ? m.pinyin : (m.surname != null ? m.surname : "");
        }

        final Integer[] boxed = new Integer[n];
        for (int i = 0; i < n; i++) {
            boxed[i] = i;
        }
        Arrays.sort(boxed, new RowComparator(initials, readings));

        final int[] order = new int[n];
        boolean identity = true;
        for (int i = 0; i < n; i++) {
            order[i] = boxed[i];
            if (order[i] != i) {
                identity = false;
            }
        }

        // Walk the sorted rows and cut a section every time the initial changes.
        final List<String> sections = new ArrayList<>();
        final List<Integer> counts = new ArrayList<>();
        final List<String[]> hints = new ArrayList<>();

        int runStart = 0;
        while (runStart < n) {
            final char initial = initials[order[runStart]];
            int runEnd = runStart + 1;
            while (runEnd < n && initials[order[runEnd]] == initial) {
                runEnd++;
            }
            sections.add(sectionLabel(initial));
            counts.add(runEnd - runStart);
            hints.add(collectSurnameHints(order, surnames, runStart, runEnd));
            runStart = runEnd;
        }

        final int[] countArray = new int[counts.size()];
        for (int i = 0; i < countArray.length; i++) {
            countArray[i] = counts.get(i);
        }
        return new Result(order,
                sections.toArray(new String[0]),
                countArray,
                hints.toArray(new String[0][]),
                identity);
    }

    /**
     * Convenience overload for callers holding an array.
     */
    public static Result build(String[] displayNames, SurnamePinyinResolver resolver) {
        return build(displayNames == null ? null : Arrays.asList(displayNames), resolver);
    }

    /**
     * Only surnames we actually recognised as Chinese are worth showing in the bubble. A match that
     * came from the ICU fallback carries the leading character in {@code surname}, which would be
     * useful too, so it is included; Latin and unknown matches are not.
     */
    private static boolean isChineseSurname(Match m) {
        if (m.surname == null || m.surname.isEmpty()) {
            return false;
        }
        switch (m.source) {
            case COMPOUND:
            case POLYPHONIC:
            case OVERRIDE:
            case FALLBACK:
                return true;
            default:
                return false;
        }
    }

    private static String[] collectSurnameHints(int[] order, String[] surnames, int from, int to) {
        final LinkedHashSet<String> distinct = new LinkedHashSet<>();
        for (int i = from; i < to && distinct.size() < MAX_SURNAME_HINTS; i++) {
            final String s = surnames[order[i]];
            if (s != null) {
                distinct.add(s);
            }
        }
        return distinct.toArray(new String[0]);
    }

    private static String sectionLabel(char initial) {
        return initial == ChineseSurnameTable.UNKNOWN_INITIAL
                ? UNKNOWN_SECTION
                : String.valueOf(initial);
    }

    /** Orders by bucket, then by surname reading, then by the provider's original position. */
    private static final class RowComparator implements Comparator<Integer> {
        private final char[] mInitials;
        private final String[] mReadings;

        RowComparator(char[] initials, String[] readings) {
            mInitials = initials;
            mReadings = readings;
        }

        @Override
        public int compare(Integer left, Integer right) {
            final int a = left;
            final int b = right;

            final int bucket = Integer.compare(bucketRank(mInitials[a]), bucketRank(mInitials[b]));
            if (bucket != 0) {
                return bucket;
            }
            final int reading = mReadings[a].compareTo(mReadings[b]);
            if (reading != 0) {
                return reading;
            }
            return Integer.compare(a, b);
        }

        /** A-Z keep their natural order; '#' sorts last. */
        private static int bucketRank(char initial) {
            return initial == ChineseSurnameTable.UNKNOWN_INITIAL ? Integer.MAX_VALUE : initial;
        }
    }
}
