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

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Resolves the address-book index initial ({@code A}-{@code Z} or {@code '#'}) for a contact
 * display name, honouring Chinese surname pronunciation rules.
 *
 * <p>This class is <strong>pure Java</strong>: it has no Android dependency whatsoever, so it can
 * be exercised from a {@code java_test_host} module on a plain host JVM. Anything that needs a
 * {@code Context} (reading the overlay CSV files out of {@code res/raw}) or the platform ICU
 * ({@code android.icu.text.Transliterator}) lives in separate classes that feed this one.
 *
 * <h3>Matching order</h3>
 * <ol>
 *   <li><b>Override table</b> — deployment/user supplied, highest priority. Consulted at every
 *       candidate length, so an override can target either a compound or a single-character
 *       surname.</li>
 *   <li><b>Compound surname table</b> (複姓) — longest match first. This <em>must</em> run before
 *       the single-character table, otherwise {@code 单于} (Chányú) would be swallowed by
 *       {@code 单} (Shàn), {@code 长孙} (Zhǎngsūn) by {@code 长} (Cháng), and so on.</li>
 *   <li><b>Polyphonic surname table</b> (多音姓) — single character, surname reading rather than
 *       the everyday reading.</li>
 *   <li><b>Caller supplied fallback</b> — normally derived from
 *       {@code android.icu.text.Transliterator("Han-Latin/Names")}.</li>
 * </ol>
 *
 * <p>Non-Han names never reach steps 1-4: an ASCII/Latin initial is uppercased (accents are
 * stripped, so {@code Élodie} yields {@code E}), everything else yields {@code '#'}.
 *
 * <p>Instances are immutable and therefore thread-safe. Build one with {@link #newBuilder()} when
 * you have overlay data, or use the built-in-only {@link #getDefault()}.
 */
public final class ChineseSurnameTable {

    /** Bucket used for names with no usable Latin initial (digits, symbols, empty, CJK misses). */
    public static final char UNKNOWN_INITIAL = '#';

    /** Where a {@link Match} came from. Useful for diagnostics and for asserting in tests. */
    public enum Source {
        /** Matched the deployment/user override table. */
        OVERRIDE,
        /** Matched the compound (multi-character) surname table. */
        COMPOUND,
        /** Matched the single-character polyphonic surname table. */
        POLYPHONIC,
        /** Name did not start with a Han ideograph; initial taken from the Latin letter. */
        LATIN,
        /** Han ideograph not in any table; initial taken from the caller supplied fallback. */
        FALLBACK,
        /** Nothing usable could be derived; initial is {@link #UNKNOWN_INITIAL}. */
        UNKNOWN
    }

    // =================================================================================
    // Built-in data
    //
    // Kept in the exact same line format as the res/raw/*.csv overlay files so that one parser
    // serves both. '#' starts a comment, blank lines are ignored, fields are trimmed.
    //
    // The `initial` column is redundant (it is always the uppercased first letter of `pinyin`);
    // it exists so a human reviewer can eyeball the file without knowing pinyin, and
    // #validateBuiltInTables() cross-checks the two so a typo fails the unit test rather than
    // silently mis-filing somebody's contacts.
    // =================================================================================

    /** Format: {@code surname,pinyin,initial,note} */
    private static final String[] BUILT_IN_COMPOUND = {
        "欧阳,ouyang,O,",
        "司马,sima,S,",
        "诸葛,zhuge,Z,",
        "上官,shangguan,S,",
        "皇甫,huangfu,H,",
        "令狐,linghu,L,令 here is Líng not Lìng",
        "尉迟,yuchi,Y,尉 here is Yù not Wèi — contrast the single-char 尉 Wèi below",
        "万俟,moqi,M,critical: Mòqí not Wànsì",
        "澹台,tantai,T,critical: Tántái not Dàntái",
        "慕容,murong,M,",
        "独孤,dugu,D,",
        "公孙,gongsun,G,",
        "长孙,zhangsun,Z,长 here is Zhǎng — contrast the single-char 长 Cháng below",
        "单于,chanyu,C,critical: Chányú — must outrank the single-char 单 Shàn",
        "乐正,yuezheng,Y,",
        "宇文,yuwen,Y,",
        "夏侯,xiahou,X,",
        "东方,dongfang,D,",
        "西门,ximen,X,",
        "南宫,nangong,N,",
        "北堂,beitang,B,",
        "司徒,situ,S,",
        "司空,sikong,S,",
        "司寇,sikou,S,",
        "呼延,huyan,H,",
        "太史,taishi,T,",
        "端木,duanmu,D,",
        "拓跋,tuoba,T,拓 here is Tuò",
        "赫连,helian,H,",
        "钟离,zhongli,Z,",
        "闾丘,lvqiu,L,闾 Lǘ",
        "子车,zijv,Z,Zǐjū",
        "颛孙,zhuansun,Z,",
        "壤驷,rangsi,R,",
        "公良,gongliang,G,",
        "漆雕,qidiao,Q,",
        "公西,gongxi,G,",
        "巫马,wuma,W,",
        "仲孙,zhongsun,Z,",
        "鲜于,xianyu,X,鲜 here is Xiān",
        "第五,diwu,D,",
        "梁丘,liangqiu,L,",
        "左丘,zuoqiu,Z,",
        "东郭,dongguo,D,",
        "百里,baili,B,",
        "东门,dongmen,D,",
        "南门,nanmen,N,",
        "羊舌,yangshe,Y,",
        "微生,weisheng,W,",
        "夹谷,jiagu,J,",
        "宰父,zaifu,Z,",
        "谷梁,guliang,G,",
        "段干,duangan,D,",
        "即墨,jimo,J,",
        "达奚,daxi,D,",
        "褚师,chushi,C,",
        "淳于,chunyu,C,",
        "仉督,zhangdu,Z,仉 Zhǎng",
        "公冶,gongye,G,",
        "宗政,zongzheng,Z,",
        "濮阳,puyang,P,",
        "申屠,shentu,S,",
        "公羊,gongyang,G,",
        "轩辕,xuanyuan,X,",
        "仲长,zhongzhang,Z,",
        "叔孙,shusun,S,",
        "毌丘,guanqiu,G,毌 (not 毋) Guàn",
    };

    /**
     * Format: {@code char,surname_pinyin,initial,common_pinyin,confidence,note}
     *
     * <p>{@code surname_pinyin} is the reading used <em>as a surname</em> and is what we index on.
     * {@code common_pinyin} is the everyday reading and is documentation only — it makes it obvious
     * at a glance which entries actually move a contact between buckets.
     * {@code confidence} is {@code certain} or {@code ambiguous}; ambiguous entries are the ones a
     * deployment may reasonably want to flip via {@code res/raw/surnames_override.csv}.
     *
     * <p>Roughly the 30 most frequently encountered polyphonic surnames. The ten called out in the
     * feature spec come first, then the remainder ordered by real-world population impact.
     */
    private static final String[] BUILT_IN_POLYPHONIC = {
        // --- the ten mandated by the spec -------------------------------------------------
        "单,shan,S,dan,certain,单雄信 — D→S",
        "解,xie,X,jie,certain,解缙 — J→X",
        "曾,zeng,Z,ceng,certain,曾国藩 — C→Z",
        "仇,qiu,Q,chou,certain,仇英 — C→Q",
        "查,zha,Z,cha,certain,查良镛 — C→Z",
        "区,ou,O,qu,certain,区家麟 — Q→O",
        "华,hua,H,hua,certain,华罗庚 — falling tone; same initial",
        "朴,piao,P,pu,certain,朴槿惠 — same initial",
        "乐,yue,Y,le,certain,乐毅 — L→Y",
        "折,she,S,zhe,certain,折可适 — Z→S",
        // --- next tier, ordered by frequency x impact -------------------------------------
        "翟,zhai,Z,di,certain,翟姓多读 Zhái — D→Z",
        "覃,tan,T,qin,ambiguous,★Tán(湘鄂赣)/Qín(广西壮族) — deployment default is Tán; see override CSV",
        "种,chong,C,zhong,certain,种师道 — Z→C",
        "长,chang,C,zhang,certain,长姓读 Cháng — Z→C; 长孙 handled by the compound table",
        "繁,po,P,fan,certain,繁姓读 Pó — F→P",
        "谌,shen,S,chen,ambiguous,谌 Shèn/Chén — C→S",
        "隗,kui,K,wei,ambiguous,隗 Kuí/Wěi — W→K",
        "郗,xi,X,chi,ambiguous,郗 Xī/Chī",
        "牟,mu,M,mou,ambiguous,牟 Mù/Móu — same initial",
        "员,yun,Y,yuan,certain,员姓读 Yùn — same initial",
        "都,du,D,dou,certain,都姓读 Dū — same initial",
        "乜,nie,N,mie,certain,乜姓读 Niè — M→N",
        "逢,pang,P,feng,certain,逢姓读 Páng — F→P",
        "莘,shen,S,xin,certain,莘姓读 Shēn — X→S",
        "盖,ge,G,gai,certain,盖姓读 Gě — same initial",
        "缪,miao,M,miu,certain,缪姓读 Miào — same initial",
        "阚,kan,K,han,certain,阚泽 — same initial",
        "纪,ji,J,ji,certain,纪姓读 Jǐ — same initial",
        "任,ren,R,ren,certain,任姓读 Rén — same initial",
        "尉,wei,W,wei,certain,★defensive: bare 尉 is Wèi; only 尉迟 is Yùchí",
    };

    // =================================================================================
    // Instance state
    // =================================================================================

    private final Map<String, Entry> mOverride;
    private final Map<String, Entry> mCompound;
    private final Map<String, Entry> mPolyphonic;
    /** Longest key, in code points, across the override + compound tables. Always >= 1. */
    private final int mMaxCompoundLength;

    private ChineseSurnameTable(Map<String, Entry> override, Map<String, Entry> compound,
            Map<String, Entry> polyphonic) {
        mOverride = Collections.unmodifiableMap(override);
        mCompound = Collections.unmodifiableMap(compound);
        mPolyphonic = Collections.unmodifiableMap(polyphonic);

        int max = 1;
        for (String key : override.keySet()) {
            max = Math.max(max, key.codePointCount(0, key.length()));
        }
        for (String key : compound.keySet()) {
            max = Math.max(max, key.codePointCount(0, key.length()));
        }
        mMaxCompoundLength = max;
    }

    /** Lazily created, built-in-data-only instance. Holder idiom keeps it thread-safe. */
    private static final class DefaultHolder {
        static final ChineseSurnameTable INSTANCE = newBuilder().build();
    }

    /**
     * Returns the shared table containing only the built-in data — no {@code res/raw} overlays.
     * This is what unit tests and any caller without a {@code Context} should use.
     */
    public static ChineseSurnameTable getDefault() {
        return DefaultHolder.INSTANCE;
    }

    // =================================================================================
    // Public API
    // =================================================================================

    /**
     * Returns the address-book index initial for {@code displayName}.
     *
     * <p>This is the hot-path entry point used by {@code SurnameIndexBuilder}.
     *
     * @param displayName the contact's display name, in surname-first order. May be {@code null},
     *                    empty, or padded with whitespace / interpunct separators.
     * @param icuFallback the initial derived by the caller for a Han character that is not in any
     *                    table — normally from {@code Transliterator("Han-Latin/Names")}. Pass
     *                    {@link #UNKNOWN_INITIAL} when no fallback is available. Lowercase input is
     *                    accepted and uppercased; anything that is not an ASCII letter degrades to
     *                    {@link #UNKNOWN_INITIAL}.
     * @return an uppercase {@code 'A'}-{@code 'Z'}, or {@link #UNKNOWN_INITIAL}.
     */
    public char getSurnameInitial(String displayName, char icuFallback) {
        return match(displayName, icuFallback).initial;
    }

    /**
     * Full result of resolving {@code displayName} — the initial plus the surname that was
     * recognised, its reading and which table produced it. Used by the section-header UI (which
     * shows the actual surnames inside a letter bucket) and by tests.
     */
    public Match match(String displayName, char icuFallback) {
        final String name = normalizeLeading(displayName);
        if (name.isEmpty()) {
            return Match.unknown();
        }

        final int firstCp = name.codePointAt(0);
        if (!isHanIdeograph(firstCp)) {
            final char latin = latinInitialOf(firstCp);
            return latin == UNKNOWN_INITIAL
                    ? Match.unknown()
                    : new Match(null, latin, Source.LATIN, null, false);
        }

        // Compound surnames, longest match first. Overrides are consulted at each length so a
        // deployment can retarget a compound surname as well as a single character.
        for (int len = mMaxCompoundLength; len >= 2; len--) {
            final String candidate = firstCodePoints(name, len);
            if (candidate == null) {
                continue;
            }
            final Entry override = mOverride.get(candidate);
            if (override != null) {
                return Match.of(override, Source.OVERRIDE);
            }
            final Entry compound = mCompound.get(candidate);
            if (compound != null) {
                return Match.of(compound, Source.COMPOUND);
            }
        }

        final String single = firstCodePoints(name, 1);
        final Entry override = mOverride.get(single);
        if (override != null) {
            return Match.of(override, Source.OVERRIDE);
        }
        final Entry polyphonic = mPolyphonic.get(single);
        if (polyphonic != null) {
            return Match.of(polyphonic, Source.POLYPHONIC);
        }

        final char fallback = sanitizeInitial(icuFallback);
        return fallback == UNKNOWN_INITIAL
                ? new Match(single, UNKNOWN_INITIAL, Source.UNKNOWN, null, false)
                : new Match(single, fallback, Source.FALLBACK, null, false);
    }

    /**
     * Returns the number of code points the recognised surname occupies at the start of
     * {@code displayName}, or {@code 0} when no Chinese surname was recognised. Lets the caller
     * split "surname + given name" without re-running the match.
     */
    public int surnameLength(String displayName) {
        final Match m = match(displayName, UNKNOWN_INITIAL);
        return m.surname == null ? 0 : m.surname.codePointCount(0, m.surname.length());
    }

    /** Read-only view of the compound surname table, keyed by surname. */
    public Map<String, Entry> getCompoundEntries() {
        return mCompound;
    }

    /** Read-only view of the single-character polyphonic surname table, keyed by surname. */
    public Map<String, Entry> getPolyphonicEntries() {
        return mPolyphonic;
    }

    /** Read-only view of the override table, keyed by surname. */
    public Map<String, Entry> getOverrideEntries() {
        return mOverride;
    }

    // =================================================================================
    // Types
    // =================================================================================

    /** One row of surname data. Immutable. */
    public static final class Entry {
        /** The surname itself, e.g. {@code 单} or {@code 欧阳}. Never null or empty. */
        public final String surname;
        /** Toneless lowercase pinyin of the surname reading, e.g. {@code shan}. Null for
         *  override rows, which only carry an initial. */
        public final String pinyin;
        /** Uppercase {@code 'A'}-{@code 'Z'}, or {@link #UNKNOWN_INITIAL}. */
        public final char initial;
        /** The everyday (non-surname) reading. Documentation only; nullable. */
        public final String commonPinyin;
        /** True when the surname genuinely has more than one accepted reading. */
        public final boolean ambiguous;
        /** Free-text maintainer note; never null, possibly empty. */
        public final String note;

        Entry(String surname, String pinyin, char initial, String commonPinyin, boolean ambiguous,
                String note) {
            this.surname = surname;
            this.pinyin = pinyin;
            this.initial = initial;
            this.commonPinyin = commonPinyin;
            this.ambiguous = ambiguous;
            this.note = note == null ? "" : note;
        }

        @Override
        public String toString() {
            return "Entry{" + surname + "," + pinyin + "," + initial
                    + (ambiguous ? ",ambiguous" : "") + "}";
        }
    }

    /** Outcome of {@link ChineseSurnameTable#match}. Immutable. */
    public static final class Match {
        /** The recognised surname, or the leading character when only the fallback applied.
         *  Null for non-Han and empty names. */
        public final String surname;
        /** Uppercase {@code 'A'}-{@code 'Z'}, or {@link #UNKNOWN_INITIAL}. */
        public final char initial;
        /** Which stage produced {@link #initial}. */
        public final Source source;
        /** Toneless pinyin of the surname reading when known, else null. */
        public final String pinyin;
        /** Whether the matched table row was flagged ambiguous. */
        public final boolean ambiguous;

        Match(String surname, char initial, Source source, String pinyin, boolean ambiguous) {
            this.surname = surname;
            this.initial = initial;
            this.source = source;
            this.pinyin = pinyin;
            this.ambiguous = ambiguous;
        }

        static Match of(Entry entry, Source source) {
            return new Match(entry.surname, entry.initial, source, entry.pinyin, entry.ambiguous);
        }

        static Match unknown() {
            return new Match(null, UNKNOWN_INITIAL, Source.UNKNOWN, null, false);
        }

        @Override
        public String toString() {
            return "Match{" + surname + "->" + initial + "," + source + "}";
        }
    }

    // =================================================================================
    // Builder
    // =================================================================================

    /** Returns a builder pre-populated with the built-in compound and polyphonic tables. */
    public static Builder newBuilder() {
        return new Builder(true);
    }

    /** Returns a builder with no data at all. Intended for focused unit tests. */
    public static Builder newEmptyBuilder() {
        return new Builder(false);
    }

    /**
     * Accumulates surname data. Not thread-safe; the {@link ChineseSurnameTable} it produces is.
     *
     * <p>{@code addXxxLine} accepts the raw CSV line format, so the Android-side loader can pipe
     * {@code res/raw} straight through without an intermediate representation. Malformed lines are
     * skipped rather than throwing — a botched overlay must not brick the contact list — and are
     * counted in {@link #getSkippedLineCount()} so tests and logging can notice.
     */
    public static final class Builder {
        private final Map<String, Entry> mOverride = new LinkedHashMap<>();
        private final Map<String, Entry> mCompound = new LinkedHashMap<>();
        private final Map<String, Entry> mPolyphonic = new LinkedHashMap<>();
        private int mSkipped;

        private Builder(boolean withBuiltIns) {
            if (withBuiltIns) {
                for (String line : BUILT_IN_COMPOUND) {
                    addCompoundLine(line);
                }
                for (String line : BUILT_IN_POLYPHONIC) {
                    addPolyphonicLine(line);
                }
            }
        }

        /** Adds one {@code surname,pinyin,initial,note} line. Later rows replace earlier ones. */
        public Builder addCompoundLine(String line) {
            final Entry entry = parseCompoundLine(line);
            if (entry == null) {
                countSkip(line);
            } else {
                mCompound.put(entry.surname, entry);
            }
            return this;
        }

        /** Adds one {@code char,surname_pinyin,initial,common_pinyin,confidence,note} line. */
        public Builder addPolyphonicLine(String line) {
            final Entry entry = parsePolyphonicLine(line);
            if (entry == null) {
                countSkip(line);
            } else {
                mPolyphonic.put(entry.surname, entry);
            }
            return this;
        }

        /**
         * Adds one {@code 汉字,首字母,注释} override line. Overrides beat both built-in tables at
         * the same match length and are how a deployment retargets e.g. 覃 from Tán to Qín.
         */
        public Builder addOverrideLine(String line) {
            final Entry entry = parseOverrideLine(line);
            if (entry == null) {
                countSkip(line);
            } else {
                mOverride.put(entry.surname, entry);
            }
            return this;
        }

        /** Convenience for tests and for the user-preference layer. */
        public Builder addOverride(String surname, char initial, String note) {
            final String trimmed = surname == null ? "" : surname.trim();
            final char sane = sanitizeInitial(initial);
            if (trimmed.isEmpty() || sane == UNKNOWN_INITIAL) {
                mSkipped++;
                return this;
            }
            mOverride.put(trimmed, new Entry(trimmed, null, sane, null, false, note));
            return this;
        }

        /** Feeds a whole file. Comments, blank lines and a UTF-8 BOM are handled. */
        public Builder addCompoundLines(Iterable<String> lines) {
            for (String line : lines) {
                addCompoundLine(line);
            }
            return this;
        }

        /** Feeds a whole file. */
        public Builder addPolyphonicLines(Iterable<String> lines) {
            for (String line : lines) {
                addPolyphonicLine(line);
            }
            return this;
        }

        /** Feeds a whole file. */
        public Builder addOverrideLines(Iterable<String> lines) {
            for (String line : lines) {
                addOverrideLine(line);
            }
            return this;
        }

        /** Number of non-comment, non-blank lines that failed to parse. */
        public int getSkippedLineCount() {
            return mSkipped;
        }

        public ChineseSurnameTable build() {
            return new ChineseSurnameTable(
                    new LinkedHashMap<>(mOverride),
                    new LinkedHashMap<>(mCompound),
                    new LinkedHashMap<>(mPolyphonic));
        }

        private void countSkip(String line) {
            // Comments and blanks are not failures.
            if (!isIgnorableLine(line)) {
                mSkipped++;
            }
        }
    }

    // =================================================================================
    // Line parsing — shared by the built-in arrays and the res/raw overlay files
    // =================================================================================

    /** Parses {@code surname,pinyin,initial,note}. Returns null when the line is unusable. */
    public static Entry parseCompoundLine(String line) {
        final String[] f = splitCsv(line, 4);
        if (f == null || f[0].isEmpty() || f[1].isEmpty()) {
            return null;
        }
        final String surname = f[0];
        final String pinyin = f[1].toLowerCase(Locale.US);
        final char initial = initialOfPinyin(pinyin);
        if (initial == UNKNOWN_INITIAL) {
            return null;
        }
        return new Entry(surname, pinyin, initial, null, false, f[3]);
    }

    /**
     * Parses {@code char,surname_pinyin,initial,common_pinyin,confidence,note}.
     * Returns null when the line is unusable.
     */
    public static Entry parsePolyphonicLine(String line) {
        final String[] f = splitCsv(line, 6);
        if (f == null || f[0].isEmpty() || f[1].isEmpty()) {
            return null;
        }
        final String surname = f[0];
        final String pinyin = f[1].toLowerCase(Locale.US);
        final char initial = initialOfPinyin(pinyin);
        if (initial == UNKNOWN_INITIAL) {
            return null;
        }
        final String common = f[3].isEmpty() ? null : f[3].toLowerCase(Locale.US);
        final boolean ambiguous = "ambiguous".equalsIgnoreCase(f[4]);
        return new Entry(surname, pinyin, initial, common, ambiguous, f[5]);
    }

    /** Parses {@code 汉字,首字母,注释}. Returns null when the line is unusable. */
    public static Entry parseOverrideLine(String line) {
        final String[] f = splitCsv(line, 3);
        if (f == null || f[0].isEmpty() || f[1].isEmpty()) {
            return null;
        }
        final char initial = sanitizeInitial(f[1].charAt(0));
        if (initial == UNKNOWN_INITIAL) {
            return null;
        }
        return new Entry(f[0], null, initial, null, false, f[2]);
    }

    /**
     * Splits a CSV line into exactly {@code fieldCount} trimmed fields, padding with empty strings.
     * Returns null for blank lines and {@code #} comments. Extra commas beyond the last field are
     * folded into it, so notes may contain commas.
     */
    private static String[] splitCsv(String line, int fieldCount) {
        if (isIgnorableLine(line)) {
            return null;
        }
        String s = stripBom(line);
        // Drop a trailing inline comment, but only when it is preceded by whitespace so that a
        // '#' inside a note is still possible at the start of a word.
        final int hash = indexOfInlineComment(s);
        if (hash >= 0) {
            s = s.substring(0, hash);
        }
        final String[] out = new String[fieldCount];
        int field = 0;
        int start = 0;
        for (int i = 0; i < s.length() && field < fieldCount - 1; i++) {
            if (s.charAt(i) == ',') {
                out[field++] = s.substring(start, i).trim();
                start = i + 1;
            }
        }
        out[field++] = s.substring(start).trim();
        while (field < fieldCount) {
            out[field++] = "";
        }
        return out;
    }

    private static boolean isIgnorableLine(String line) {
        if (line == null) {
            return true;
        }
        final String s = stripBom(line).trim();
        return s.isEmpty() || s.charAt(0) == '#';
    }

    private static String stripBom(String s) {
        return !s.isEmpty() && s.charAt(0) == '\uFEFF' ? s.substring(1) : s;
    }

    private static int indexOfInlineComment(String s) {
        for (int i = 1; i < s.length(); i++) {
            if (s.charAt(i) == '#' && Character.isWhitespace(s.charAt(i - 1))) {
                return i;
            }
        }
        return -1;
    }

    // =================================================================================
    // Name normalisation and character classification
    // =================================================================================

    /**
     * Strips leading whitespace and separator punctuation so that {@code "·单雄信"},
     * {@code "  单雄信"} and {@code "\"单雄信\""} all resolve identically. Trailing content is
     * untouched — we only ever look at the head of the string.
     */
    private static String normalizeLeading(String raw) {
        if (raw == null) {
            return "";
        }
        int i = 0;
        final int len = raw.length();
        while (i < len) {
            final int cp = raw.codePointAt(i);
            if (!isLeadingNoise(cp)) {
                break;
            }
            i += Character.charCount(cp);
        }
        return i == 0 ? raw : raw.substring(i);
    }

    private static boolean isLeadingNoise(int cp) {
        if (Character.isWhitespace(cp) || cp == 0x00A0 || cp == 0x3000) {
            return true;
        }
        switch (cp) {
            case 0x00B7:  // · middle dot
            case 0x2022:  // • bullet
            case 0x2027:  // ‧ hyphenation point
            case 0x30FB:  // ・ katakana middle dot
            case 0xFF0E:  // ． fullwidth full stop
            case 0x22:    // "
            case 0x27:    // '
            case 0x2018: case 0x2019: case 0x201C: case 0x201D:
            case 0x28:    // (
            case 0xFF08:  // （
            case 0x5B:    // [
            case 0x3010:  // 【
            case 0x300A:  // 《
                return true;
            default:
                return false;
        }
    }

    /** True for the Han ranges we index: URO, Ext-A, Compatibility Ideographs and Ext-B onwards. */
    static boolean isHanIdeograph(int cp) {
        return (cp >= 0x4E00 && cp <= 0x9FFF)      // CJK Unified Ideographs
                || (cp >= 0x3400 && cp <= 0x4DBF)  // Extension A
                || (cp >= 0xF900 && cp <= 0xFAFF)  // Compatibility Ideographs
                || (cp >= 0x20000 && cp <= 0x3FFFF); // Extension B and beyond
    }

    /**
     * Returns the first {@code count} code points of {@code s}, or null when {@code s} is shorter
     * than that. Code-point aware so surrogate pairs (Ext-B ideographs) are not sliced in half.
     */
    private static String firstCodePoints(String s, int count) {
        int idx = 0;
        for (int i = 0; i < count; i++) {
            if (idx >= s.length()) {
                return null;
            }
            idx += Character.charCount(s.codePointAt(idx));
        }
        return s.substring(0, idx);
    }

    /**
     * Maps a non-Han leading code point to a bucket. ASCII letters map directly; full-width Latin
     * is narrowed first; accented Latin is decomposed so {@code É} lands in {@code E}. Digits,
     * symbols, emoji and non-Latin scripts (Cyrillic, Arabic, Kana, Hangul, ...) all fall in
     * {@link #UNKNOWN_INITIAL}, matching the existing "A-Z or #" behaviour.
     */
    private static char latinInitialOf(int cp) {
        // Full-width ASCII -> half-width.
        int c = cp;
        if (c >= 0xFF01 && c <= 0xFF5E) {
            c -= 0xFEE0;
        }
        if (c >= 'A' && c <= 'Z') {
            return (char) c;
        }
        if (c >= 'a' && c <= 'z') {
            return (char) (c - 'a' + 'A');
        }
        if (!Character.isLetter(c)) {
            return UNKNOWN_INITIAL;
        }
        // Accented Latin: decompose and take the base letter if it is ASCII.
        final String decomposed =
                Normalizer.normalize(new String(Character.toChars(c)), Normalizer.Form.NFD);
        for (int i = 0; i < decomposed.length(); i++) {
            final char base = decomposed.charAt(i);
            if (base >= 'A' && base <= 'Z') {
                return base;
            }
            if (base >= 'a' && base <= 'z') {
                return (char) (base - 'a' + 'A');
            }
        }
        return UNKNOWN_INITIAL;
    }

    /** Uppercases an ASCII letter, or returns {@link #UNKNOWN_INITIAL}. */
    static char sanitizeInitial(char c) {
        if (c >= 'A' && c <= 'Z') {
            return c;
        }
        if (c >= 'a' && c <= 'z') {
            return (char) (c - 'a' + 'A');
        }
        return UNKNOWN_INITIAL;
    }

    private static char initialOfPinyin(String pinyin) {
        return pinyin.isEmpty() ? UNKNOWN_INITIAL : sanitizeInitial(pinyin.charAt(0));
    }

    // =================================================================================
    // Self check
    // =================================================================================

    /**
     * Validates the built-in tables and returns the problems found, empty when all is well.
     *
     * <p>Dependency-free on purpose so it can run from the host unit test, from an instrumentation
     * test, or from a debug build, without dragging JUnit into the shipping APK. It catches the
     * failure modes that are easy to introduce when hand-editing the data arrays:
     * a declared {@code initial} column that disagrees with the pinyin, a compound surname whose
     * leading character is shadowed by nothing (fine) or that duplicates another row, and — the
     * important one — a single-character polyphonic entry that would swallow a compound surname.
     */
    public static List<String> validateBuiltInTables() {
        final List<String> problems = new ArrayList<>();
        final ChineseSurnameTable table = getDefault();

        if (table.mCompound.size() != BUILT_IN_COMPOUND.length) {
            problems.add("compound table: parsed " + table.mCompound.size() + " of "
                    + BUILT_IN_COMPOUND.length + " rows (duplicate or malformed row?)");
        }
        if (table.mPolyphonic.size() != BUILT_IN_POLYPHONIC.length) {
            problems.add("polyphonic table: parsed " + table.mPolyphonic.size() + " of "
                    + BUILT_IN_POLYPHONIC.length + " rows (duplicate or malformed row?)");
        }

        checkDeclaredInitials(BUILT_IN_COMPOUND, 4, 2, "compound", problems);
        checkDeclaredInitials(BUILT_IN_POLYPHONIC, 6, 2, "polyphonic", problems);

        for (Entry e : table.mCompound.values()) {
            final int cps = e.surname.codePointCount(0, e.surname.length());
            if (cps < 2) {
                problems.add("compound table: '" + e.surname + "' is not multi-character");
            }
            for (int i = 0; i < e.surname.length(); ) {
                final int cp = e.surname.codePointAt(i);
                if (!isHanIdeograph(cp)) {
                    problems.add("compound table: '" + e.surname + "' contains a non-Han char");
                    break;
                }
                i += Character.charCount(cp);
            }
        }
        for (Entry e : table.mPolyphonic.values()) {
            if (e.surname.codePointCount(0, e.surname.length()) != 1) {
                problems.add("polyphonic table: '" + e.surname + "' must be exactly one character");
            }
            if (!isHanIdeograph(e.surname.codePointAt(0))) {
                problems.add("polyphonic table: '" + e.surname + "' is not a Han character");
            }
        }

        // The shadowing check: for every compound surname whose first character also exists in the
        // polyphonic table, confirm the compound really does win. This is the 单于/单 class of bug.
        for (Entry compound : table.mCompound.values()) {
            final String head = firstCodePoints(compound.surname, 1);
            final Entry single = table.mPolyphonic.get(head);
            if (single == null) {
                continue;
            }
            final char got = table.getSurnameInitial(compound.surname + "某", UNKNOWN_INITIAL);
            if (got != compound.initial) {
                problems.add("shadowing: '" + compound.surname + "' resolved to " + got
                        + " but should be " + compound.initial
                        + " (single-char '" + head + "' won)");
            }
        }
        return problems;
    }

    private static void checkDeclaredInitials(String[] rows, int fieldCount, int initialColumn,
            String label, List<String> problems) {
        for (String row : rows) {
            final String[] f = splitCsv(row, fieldCount);
            if (f == null) {
                continue;
            }
            final String declared = f[initialColumn];
            final char computed = initialOfPinyin(f[1].toLowerCase(Locale.US));
            if (declared.length() != 1 || sanitizeInitial(declared.charAt(0)) != computed) {
                problems.add(label + " table: '" + f[0] + "' declares initial '" + declared
                        + "' but pinyin '" + f[1] + "' gives '" + computed + "'");
            }
        }
    }
}
