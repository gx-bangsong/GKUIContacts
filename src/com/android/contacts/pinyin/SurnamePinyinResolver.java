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

import java.util.concurrent.ConcurrentHashMap;

/**
 * Front door for surname-aware indexing: combines a {@link ChineseSurnameTable} with a
 * {@link PinyinFallback} and memoises the result.
 *
 * <p>{@link ChineseSurnameTable} is pure data plus matching; this class adds the ICU fallback wiring
 * and the cache. Splitting them keeps the table host-testable while this class stays trivially thin.
 *
 * <p><b>Cache key is the surname, not the full name.</b> A 5,000-contact address book typically
 * contains only a few hundred distinct surnames, so keying on the leading character(s) gives a far
 * better hit rate than keying on the display name. We cache on the first code point, which is what
 * decides whether we even need to consult ICU.
 *
 * <p>Thread-safe and free of Android imports; {@code SurnameTableLoader} supplies the
 * {@code Context}-dependent parts.
 */
public final class SurnamePinyinResolver {

    private final ChineseSurnameTable mTable;
    private final PinyinFallback mFallback;
    /** Leading code point -> resolved initial. Only consulted for the ICU fallback path. */
    private final ConcurrentHashMap<String, Character> mFallbackCache = new ConcurrentHashMap<>();

    public SurnamePinyinResolver(ChineseSurnameTable table, PinyinFallback fallback) {
        if (table == null) {
            throw new NullPointerException("table");
        }
        mTable = table;
        mFallback = fallback == null ? PinyinFallback.NONE : fallback;
    }

    /** Resolver over the built-in tables with no ICU fallback. Handy for tests. */
    public static SurnamePinyinResolver createDefault() {
        return new SurnamePinyinResolver(ChineseSurnameTable.getDefault(), PinyinFallback.NONE);
    }

    /**
     * Returns the address-book index initial for {@code displayName}: an uppercase A-Z, or
     * {@link ChineseSurnameTable#UNKNOWN_INITIAL}.
     */
    public char getInitial(String displayName) {
        return resolve(displayName).initial;
    }

    /**
     * Full match for {@code displayName}, including the recognised surname and its reading. Used by
     * the section header and the fast-scroll bubble, which show the actual surnames in a bucket.
     */
    public Match resolve(String displayName) {
        // First pass with no fallback: a table hit needs no ICU work at all.
        final Match direct = mTable.match(displayName, ChineseSurnameTable.UNKNOWN_INITIAL);
        if (direct.source != ChineseSurnameTable.Source.UNKNOWN || direct.surname == null) {
            return direct;
        }
        // Table miss on a Han character. direct.surname holds that character.
        final String han = direct.surname;
        Character initial = mFallbackCache.get(han);
        if (initial == null) {
            initial = mFallback.initialOf(han);
            if (mFallbackCache.size() < 4096) {
                mFallbackCache.put(han, initial);
            }
        }
        return mTable.match(displayName, initial);
    }

    /**
     * Returns a key that sorts names into the order the index implies: the initial, then the
     * surname reading, then the name itself so contacts sharing a surname stay stable.
     *
     * <p>Names bucketed under {@link ChineseSurnameTable#UNKNOWN_INITIAL} sort last, matching the
     * convention that '#' sits at the end of the index bar.
     */
    public String getSortKey(String displayName) {
        final Match m = resolve(displayName);
        final char initial = m.initial;
        final StringBuilder sb = new StringBuilder(32);
        // '#' -> '{' so it sorts after 'Z' in plain code-point order.
        sb.append(initial == ChineseSurnameTable.UNKNOWN_INITIAL ? '{' : initial);
        sb.append('\u0001');
        if (m.pinyin != null) {
            sb.append(m.pinyin);
        } else if (m.surname != null) {
            sb.append(m.surname);
        }
        sb.append('\u0001');
        sb.append(displayName == null ? "" : displayName);
        return sb.toString();
    }

    /** The underlying table, for callers that need to enumerate entries. */
    public ChineseSurnameTable getTable() {
        return mTable;
    }

    /** Drops the memoised fallback results. Call after the override table changes. */
    public void clearCache() {
        mFallbackCache.clear();
    }
}
