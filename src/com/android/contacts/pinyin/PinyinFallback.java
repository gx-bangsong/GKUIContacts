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

/**
 * Supplies the index initial for a Han character that is not in any surname exception table.
 *
 * <p>Exists so {@link ChineseSurnameTable} can stay free of Android imports: the production
 * implementation ({@link IcuPinyinFallback}) uses {@code android.icu.text.Transliterator}, while
 * host JVM tests inject {@link #NONE} or a stub.
 *
 * <p>Deliberately narrow. We do <em>not</em> want a second full pinyin engine in this app — search
 * already goes through {@code ContactsProvider}'s pinyin/T9 support via
 * {@code Contacts.CONTENT_FILTER_URI}, and the surname exception tables cover the cases the
 * platform gets wrong. This interface only fills the remaining gap: "what letter does this
 * ordinary Han character start with?"
 */
public interface PinyinFallback {

    /**
     * Returns the uppercase Latin initial for {@code han}, or
     * {@link ChineseSurnameTable#UNKNOWN_INITIAL} when it cannot be determined.
     *
     * @param han a single Han character, possibly a surrogate pair, never null or empty
     */
    char initialOf(String han);

    /** A fallback that never resolves anything. Used on hosts and when ICU is unavailable. */
    PinyinFallback NONE = han -> ChineseSurnameTable.UNKNOWN_INITIAL;
}
