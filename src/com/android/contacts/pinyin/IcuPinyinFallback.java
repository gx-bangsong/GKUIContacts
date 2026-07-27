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

import android.icu.text.Transliterator;
import android.util.Log;

import java.util.Collections;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@link PinyinFallback} backed by the platform ICU transliterator.
 *
 * <p>Used only for Han characters that are <em>not</em> in the surname exception tables, i.e.
 * ordinary surnames such as 张 or 王 where the everyday reading is also the surname reading. This
 * is how we avoid shipping a 20,000-character pinyin table of our own.
 *
 * <p><b>Availability.</b> The framework documentation is explicit that "the set of available
 * transliteration IDs is unstable across Android releases and devices" and that callers must check
 * {@link Transliterator#getAvailableIDs()} first. We do exactly that, try a list of IDs in
 * preference order, and additionally smoke-test the chosen one against known characters — an ID
 * can exist yet produce something useless. If nothing works the instance degrades to
 * {@link ChineseSurnameTable#UNKNOWN_INITIAL} for every input, which buckets ordinary Chinese
 * surnames under '#'. The exception tables keep working regardless.
 *
 * <p>Results are memoised per character. A typical address book has a few hundred distinct
 * surnames, so the cache saturates almost immediately and the ICU call effectively disappears from
 * the profile.
 *
 * <p>Thread-safe. {@link Transliterator} instances are not documented as thread-safe, so access is
 * synchronised; the cache in front of it means contention is negligible after warm-up.
 */
public final class IcuPinyinFallback implements PinyinFallback {

    private static final String TAG = "IcuPinyinFallback";

    /**
     * Candidate transliterator IDs, best first.
     *
     * <p>{@code Han-Latin/Names} is tuned for personal names and is what we want. Plain
     * {@code Han-Latin} is the general-purpose form. {@code Any-Latin} is the last resort and is
     * present on essentially every device.
     */
    private static final String[] CANDIDATE_IDS = {
        "Han-Latin/Names",
        "Han-Latin",
        "Any-Latin",
    };

    /**
     * Smoke test: character to expected initial. Chosen to be unambiguous single-reading
     * characters, so a transliterator that works at all must get them right. 张 also guards
     * against an ID that returns the input unchanged.
     */
    private static final String[] PROBE_CHARS = {"张", "王", "李"};
    private static final char[] PROBE_EXPECTED = {'Z', 'W', 'L'};

    private final Transliterator mTransliterator;
    private final ConcurrentHashMap<String, Character> mCache = new ConcurrentHashMap<>();

    private IcuPinyinFallback(Transliterator transliterator) {
        mTransliterator = transliterator;
    }

    private static final class Holder {
        static final IcuPinyinFallback INSTANCE = new IcuPinyinFallback(resolveTransliterator());
    }

    /** Returns the shared instance, creating and probing it on first use. */
    public static IcuPinyinFallback getInstance() {
        return Holder.INSTANCE;
    }

    /** True when a usable transliterator was found. False means every lookup returns '#'. */
    public boolean isAvailable() {
        return mTransliterator != null;
    }

    @Override
    public char initialOf(String han) {
        if (han == null || han.isEmpty() || mTransliterator == null) {
            return ChineseSurnameTable.UNKNOWN_INITIAL;
        }
        final Character cached = mCache.get(han);
        if (cached != null) {
            return cached;
        }
        final char initial = computeInitial(han);
        // Bound the cache. A real address book will never approach this, but a malicious or
        // corrupt vCard import full of distinct CJK characters should not grow it without limit.
        if (mCache.size() < 4096) {
            mCache.put(han, initial);
        }
        return initial;
    }

    private char computeInitial(String han) {
        final String latin;
        try {
            synchronized (mTransliterator) {
                latin = mTransliterator.transliterate(han);
            }
        } catch (RuntimeException e) {
            // Defensive: a bad ICU data table should not take down the contact list.
            Log.w(TAG, "transliterate failed for \"" + han + "\"", e);
            return ChineseSurnameTable.UNKNOWN_INITIAL;
        }
        return firstAsciiLetter(latin);
    }

    /**
     * Returns the first ASCII letter of {@code s} uppercased, or
     * {@link ChineseSurnameTable#UNKNOWN_INITIAL}.
     *
     * <p>ICU output may carry tone marks ({@code zhāng}) or leading punctuation, and for an
     * unmapped character it returns the character itself, which has no ASCII letter — all three
     * cases are handled by simply scanning for the first {@code [A-Za-z]}.
     */
    private static char firstAsciiLetter(String s) {
        if (s == null) {
            return ChineseSurnameTable.UNKNOWN_INITIAL;
        }
        for (int i = 0; i < s.length(); i++) {
            final char c = s.charAt(i);
            if (c >= 'A' && c <= 'Z') {
                return c;
            }
            if (c >= 'a' && c <= 'z') {
                return (char) (c - 'a' + 'A');
            }
        }
        return ChineseSurnameTable.UNKNOWN_INITIAL;
    }

    /**
     * Picks the first candidate ID that both exists on this device and passes the smoke test.
     * Returns null when none does.
     */
    private static Transliterator resolveTransliterator() {
        final Set<String> available = availableIds();
        for (String id : CANDIDATE_IDS) {
            if (!available.isEmpty() && !available.contains(id)) {
                continue;
            }
            final Transliterator candidate;
            try {
                candidate = Transliterator.getInstance(id);
            } catch (RuntimeException e) {
                Log.w(TAG, "Transliterator \"" + id + "\" listed but not constructible", e);
                continue;
            }
            if (candidate != null && passesProbe(candidate)) {
                Log.i(TAG, "Using transliterator \"" + id + "\"");
                return candidate;
            }
            Log.w(TAG, "Transliterator \"" + id + "\" failed the probe, trying next");
        }
        Log.w(TAG, "No usable Han-Latin transliterator; ordinary Chinese surnames will "
                + "fall back to '" + ChineseSurnameTable.UNKNOWN_INITIAL + "'. "
                + "Add entries to res/raw/surnames_override.csv to compensate.");
        return null;
    }

    private static Set<String> availableIds() {
        final Set<String> ids = new HashSet<>();
        try {
            final Enumeration<String> e = Transliterator.getAvailableIDs();
            while (e.hasMoreElements()) {
                ids.add(e.nextElement());
            }
        } catch (RuntimeException e) {
            // If we cannot enumerate, fall through and just try getInstance() directly.
            Log.w(TAG, "getAvailableIDs() failed; will probe IDs directly", e);
            return Collections.emptySet();
        }
        return ids;
    }

    private static boolean passesProbe(Transliterator t) {
        try {
            for (int i = 0; i < PROBE_CHARS.length; i++) {
                final String out = t.transliterate(PROBE_CHARS[i]);
                if (firstAsciiLetter(out) != PROBE_EXPECTED[i]) {
                    Log.w(TAG, "probe mismatch: " + PROBE_CHARS[i] + " -> \"" + out
                            + "\", expected initial " + PROBE_EXPECTED[i]);
                    return false;
                }
            }
            return true;
        } catch (RuntimeException e) {
            Log.w(TAG, "probe threw", e);
            return false;
        }
    }

    /** For logging and the about/debug screen. */
    @Override
    public String toString() {
        return String.format(Locale.US, "IcuPinyinFallback{available=%b, cached=%d}",
                isAvailable(), mCache.size());
    }
}
