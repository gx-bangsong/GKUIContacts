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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.android.contacts.pinyin.ChineseSurnameTable.Match;
import com.android.contacts.pinyin.ChineseSurnameTable.Source;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Suite;

import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * Host JVM unit tests for {@link ChineseSurnameTable}.
 *
 * <p>Run with {@code atest ContactsSurnameUnitTests}. No device required.
 *
 * <p>Structured as a JUnit 4 {@link Suite} of nested static classes, one per concern, so a
 * failure report points straight at the category that broke.
 */
@RunWith(Suite.class)
@Suite.SuiteClasses({
        ChineseSurnameTableTest.PolyphonicSurnames.class,
        ChineseSurnameTableTest.CompoundSurnames.class,
        ChineseSurnameTableTest.ShadowingConflicts.class,
        ChineseSurnameTableTest.OrdinarySurnamesAndFallback.class,
        ChineseSurnameTableTest.NonChineseNames.class,
        ChineseSurnameTableTest.Normalization.class,
        ChineseSurnameTableTest.AmbiguityAndOverrides.class,
        ChineseSurnameTableTest.Sorting.class,
        ChineseSurnameTableTest.LineParsing.class,
        ChineseSurnameTableTest.DataIntegrity.class,
})
public class ChineseSurnameTableTest {

    /** Shared, built-in-data-only table. Immutable, so sharing across tests is safe. */
    static final ChineseSurnameTable TABLE = ChineseSurnameTable.getDefault();

    /** No ICU on the host, so most tests pass '#' and assert the table alone got it right. */
    static final char NO_FALLBACK = ChineseSurnameTable.UNKNOWN_INITIAL;

    static char initialOf(String name) {
        return TABLE.getSurnameInitial(name, NO_FALLBACK);
    }

    static void assertInitial(String name, char expected) {
        assertEquals("initial for \"" + name + "\"", expected, initialOf(name));
    }

    static void assertInitial(String name, char expected, Source expectedSource) {
        final Match m = TABLE.match(name, NO_FALLBACK);
        assertEquals("initial for \"" + name + "\"", expected, m.initial);
        assertEquals("source for \"" + name + "\"", expectedSource, m.source);
    }

    // =============================================================================
    // 1. Polyphonic surnames — the ten called out in the spec, plus the rest of the table
    // =============================================================================

    public static class PolyphonicSurnames {

        @Test
        public void shan_notDan() {
            assertInitial("单雄信", 'S', Source.POLYPHONIC);
        }

        @Test
        public void xie_notJie() {
            assertInitial("解缙", 'X', Source.POLYPHONIC);
        }

        @Test
        public void zeng_notCeng() {
            assertInitial("曾国藩", 'Z', Source.POLYPHONIC);
        }

        @Test
        public void qiu_notChou() {
            assertInitial("仇英", 'Q', Source.POLYPHONIC);
        }

        @Test
        public void zha_notCha() {
            assertInitial("查良镛", 'Z', Source.POLYPHONIC);
        }

        @Test
        public void ou_notQu() {
            assertInitial("区家麟", 'O', Source.POLYPHONIC);
        }

        @Test
        public void hua_sameInitialButStillTableDriven() {
            // 华 Huà vs huá — the initial is unchanged, but it must resolve from the table
            // rather than falling through to ICU, otherwise the reading is wrong downstream.
            assertInitial("华罗庚", 'H', Source.POLYPHONIC);
            assertEquals("hua", TABLE.match("华罗庚", NO_FALLBACK).pinyin);
        }

        @Test
        public void piao_notPu() {
            final Match m = TABLE.match("朴槿惠", NO_FALLBACK);
            assertEquals('P', m.initial);
            assertEquals("piao", m.pinyin);
            assertEquals(Source.POLYPHONIC, m.source);
        }

        @Test
        public void yue_notLe() {
            assertInitial("乐毅", 'Y', Source.POLYPHONIC);
        }

        @Test
        public void she_notZhe() {
            assertInitial("折可适", 'S', Source.POLYPHONIC);
        }

        @Test
        public void secondTierEntries() {
            assertInitial("翟某", 'Z');   // Zhái not Dí
            assertInitial("种师道", 'C'); // Chóng not Zhǒng
            assertInitial("繁某", 'P');   // Pó not Fán
            assertInitial("乜某", 'N');   // Niè not Miē
            assertInitial("逢某", 'P');   // Páng not Féng
            assertInitial("莘某", 'S');   // Shēn not Xīn
            assertInitial("员某", 'Y');   // Yùn
            assertInitial("都某", 'D');   // Dū not Dōu
        }

        @Test
        public void surnameOnlyNoGivenName() {
            assertInitial("单", 'S', Source.POLYPHONIC);
        }

        @Test
        public void matchExposesRecognisedSurname() {
            final Match m = TABLE.match("单雄信", NO_FALLBACK);
            assertEquals("单", m.surname);
            assertEquals("shan", m.pinyin);
        }
    }

    // =============================================================================
    // 2. Compound surnames
    // =============================================================================

    public static class CompoundSurnames {

        @Test
        public void ouyang() {
            assertInitial("欧阳修", 'O', Source.COMPOUND);
        }

        @Test
        public void sima() {
            assertInitial("司马光", 'S', Source.COMPOUND);
        }

        @Test
        public void moqi_notWansi() {
            // The headline case: 万俟 is Mòqí, so it belongs under M, not W.
            final Match m = TABLE.match("万俟卨", NO_FALLBACK);
            assertEquals('M', m.initial);
            assertEquals("moqi", m.pinyin);
            assertEquals("万俟", m.surname);
        }

        @Test
        public void tantai_notDantai() {
            final Match m = TABLE.match("澹台灭明", NO_FALLBACK);
            assertEquals('T', m.initial);
            assertEquals("tantai", m.pinyin);
        }

        @Test
        public void yuchi_notWeichi() {
            final Match m = TABLE.match("尉迟恭", NO_FALLBACK);
            assertEquals('Y', m.initial);
            assertEquals("yuchi", m.pinyin);
            assertEquals("尉迟", m.surname);
        }

        @Test
        public void remainingSpecMandatedCompounds() {
            assertInitial("诸葛亮", 'Z', Source.COMPOUND);
            assertInitial("上官婉儿", 'S', Source.COMPOUND);
            assertInitial("皇甫嵩", 'H', Source.COMPOUND);
            assertInitial("令狐冲", 'L', Source.COMPOUND);
            assertInitial("慕容复", 'M', Source.COMPOUND);
            assertInitial("独孤求败", 'D', Source.COMPOUND);
            assertInitial("公孙策", 'G', Source.COMPOUND);
        }

        @Test
        public void compoundWithoutGivenName() {
            assertInitial("欧阳", 'O', Source.COMPOUND);
        }

        @Test
        public void surnameLengthReportsTwoForCompound() {
            assertEquals(2, TABLE.surnameLength("欧阳修"));
            assertEquals(1, TABLE.surnameLength("单雄信"));
            assertEquals(0, TABLE.surnameLength("Alice"));
        }
    }

    // =============================================================================
    // 3. Shadowing — a compound surname must beat the single character that starts it.
    //    This is the class of bug that ordering exists to prevent.
    // =============================================================================

    public static class ShadowingConflicts {

        @Test
        public void chanyu_beats_shan() {
            assertInitial("单于某", 'C', Source.COMPOUND);   // Chányú
            assertInitial("单某", 'S', Source.POLYPHONIC);   // Shàn
        }

        @Test
        public void zhangsun_beats_chang() {
            assertInitial("长孙无忌", 'Z', Source.COMPOUND);  // Zhǎngsūn
            assertInitial("长某", 'C', Source.POLYPHONIC);    // Cháng
        }

        @Test
        public void yuchi_beats_wei() {
            assertInitial("尉迟恭", 'Y', Source.COMPOUND);    // Yùchí
            assertInitial("尉某", 'W', Source.POLYPHONIC);    // Wèi
        }

        @Test
        public void yuezheng_and_yue_agreeOnLetterButNotOnSurname() {
            // Same bucket either way, but the recognised surname must differ so the
            // section header shows 乐正 rather than 乐.
            assertInitial("乐正某", 'Y', Source.COMPOUND);
            assertInitial("乐毅", 'Y', Source.POLYPHONIC);
            assertEquals("乐正", TABLE.match("乐正某", NO_FALLBACK).surname);
            assertEquals("乐", TABLE.match("乐毅", NO_FALLBACK).surname);
        }

        @Test
        public void everyShadowedCompoundResolvesCorrectly() {
            // Belt and braces: derived from the data itself, so a newly added compound whose
            // first character is polyphonic is caught even if nobody writes a test for it.
            final List<String> problems = ChineseSurnameTable.validateBuiltInTables();
            assertTrue("built-in table problems: " + problems, problems.isEmpty());
        }
    }

    // =============================================================================
    // 4. Ordinary surnames and the ICU fallback contract
    // =============================================================================

    public static class OrdinarySurnamesAndFallback {

        @Test
        public void ordinarySurnameUsesCallerFallback() {
            // 张 is not in any exception table; the caller's ICU-derived letter is used.
            assertEquals('Z', TABLE.getSurnameInitial("张三", 'Z'));
            assertEquals(Source.FALLBACK, TABLE.match("张三", 'Z').source);
        }

        @Test
        public void moreOrdinarySurnames() {
            assertEquals('W', TABLE.getSurnameInitial("王五", 'W'));
            assertEquals('L', TABLE.getSurnameInitial("李四", 'L'));
            assertEquals('C', TABLE.getSurnameInitial("陈六", 'C'));
        }

        @Test
        public void lowercaseFallbackIsUppercased() {
            assertEquals('Z', TABLE.getSurnameInitial("张三", 'z'));
        }

        @Test
        public void unusableFallbackDegradesToHash() {
            // ICU unavailable, or returned something that is not a letter.
            assertEquals('#', TABLE.getSurnameInitial("张三", '#'));
            assertEquals('#', TABLE.getSurnameInitial("张三", '1'));
            assertEquals('#', TABLE.getSurnameInitial("张三", ' '));
            assertEquals(Source.UNKNOWN, TABLE.match("张三", '#').source);
        }

        @Test
        public void fallbackIsNeverConsultedForTableHits() {
            // A wrong ICU answer must not be able to override the exception table.
            assertEquals('S', TABLE.getSurnameInitial("单雄信", 'D'));
            assertEquals('M', TABLE.getSurnameInitial("万俟卨", 'W'));
        }

        @Test
        public void unknownHanStillReportsTheCharacter() {
            final Match m = TABLE.match("张三", '#');
            assertEquals("张", m.surname);
            assertNull(m.pinyin);
        }
    }

    // =============================================================================
    // 5. Non-Chinese names
    // =============================================================================

    public static class NonChineseNames {

        @Test
        public void asciiLetters() {
            assertInitial("Alice", 'A', Source.LATIN);
            assertInitial("alice", 'A', Source.LATIN);
            assertInitial("Zoe", 'Z', Source.LATIN);
        }

        @Test
        public void digitsAndSymbolsGoToHash() {
            assertInitial("123", '#', Source.UNKNOWN);
            assertInitial("+8613800138000", '#', Source.UNKNOWN);
            assertInitial("!!!", '#', Source.UNKNOWN);
        }

        @Test
        public void emptyAndNull() {
            assertInitial("", '#', Source.UNKNOWN);
            assertInitial("   ", '#', Source.UNKNOWN);
            assertEquals('#', TABLE.getSurnameInitial(null, 'A'));
        }

        @Test
        public void emoji() {
            assertInitial("😀", '#', Source.UNKNOWN);
        }

        @Test
        public void nonLatinScriptsGoToHash() {
            // Matches the existing "A-Z or #" bucketing; we deliberately do not invent
            // buckets for Cyrillic/Arabic/Kana/Hangul.
            assertInitial("Владимир", '#');
            assertInitial("محمد", '#');
            assertInitial("さくら", '#');
            assertInitial("김철수", '#');
        }

        @Test
        public void accentedLatinFoldsToBaseLetter() {
            assertInitial("Élodie", 'E', Source.LATIN);
            assertInitial("Ångström", 'A', Source.LATIN);
            assertInitial("Über", 'U', Source.LATIN);
            assertInitial("Ñoño", 'N', Source.LATIN);
        }

        @Test
        public void fullWidthLatinIsNarrowed() {
            assertInitial("Ａlice", 'A', Source.LATIN);
        }

        @Test
        public void mixedScriptUsesLeadingCharacter() {
            assertEquals('T', TABLE.getSurnameInitial("Tom张", '#'));
            assertEquals('Z', TABLE.getSurnameInitial("张Alice", 'Z'));
        }
    }

    // =============================================================================
    // 6. Leading-noise normalisation
    // =============================================================================

    public static class Normalization {

        @Test
        public void leadingInterpunct() {
            assertInitial("·单雄信", 'S');
            assertInitial("•单雄信", 'S');
            assertInitial("・单雄信", 'S');
        }

        @Test
        public void leadingWhitespace() {
            assertInitial("  单雄信", 'S');
            assertInitial("\t单雄信", 'S');
            assertInitial("\u3000单雄信", 'S'); // ideographic space
            assertInitial("\u00A0单雄信", 'S'); // nbsp
        }

        @Test
        public void leadingQuotesAndBrackets() {
            assertInitial("\"单雄信\"", 'S');
            assertInitial("“单雄信”", 'S');
            assertInitial("(单雄信)", 'S');
            assertInitial("【单雄信】", 'S');
        }

        @Test
        public void interpunctInsideNameIsIrrelevant() {
            assertInitial("单·雄信", 'S');
        }

        @Test
        public void noiseOnlyStringIsHash() {
            assertInitial("···", '#');
        }
    }

    // =============================================================================
    // 7. Ambiguous readings and the override mechanism (D3)
    // =============================================================================

    public static class AmbiguityAndOverrides {

        @Test
        public void tanIsTheShippedDefaultForTan2() {
            // Product decision D3: 覃 defaults to Tán, not Qín.
            final Match m = TABLE.match("覃某", NO_FALLBACK);
            assertEquals('T', m.initial);
            assertEquals("tan", m.pinyin);
            assertTrue("覃 must be flagged ambiguous", m.ambiguous);
        }

        @Test
        public void ambiguousEntriesAreFlagged() {
            assertTrue(TABLE.match("谌某", NO_FALLBACK).ambiguous);
            assertTrue(TABLE.match("隗某", NO_FALLBACK).ambiguous);
            assertTrue(TABLE.match("牟某", NO_FALLBACK).ambiguous);
            assertFalse(TABLE.match("单某", NO_FALLBACK).ambiguous);
        }

        @Test
        public void overrideRetargetsASingleCharacter() {
            // A deployment in Guangxi flips 覃 back to Qín via res/raw/surnames_override.csv.
            final ChineseSurnameTable t = ChineseSurnameTable.newBuilder()
                    .addOverrideLine("覃,Q,广西壮族地区定制包")
                    .build();
            assertEquals('Q', t.getSurnameInitial("覃某", NO_FALLBACK));
            assertEquals(Source.OVERRIDE, t.match("覃某", NO_FALLBACK).source);
            // The shipped table is untouched.
            assertEquals('T', TABLE.getSurnameInitial("覃某", NO_FALLBACK));
        }

        @Test
        public void overrideBeatsCompoundTable() {
            final ChineseSurnameTable t = ChineseSurnameTable.newBuilder()
                    .addOverrideLine("欧阳,X,contrived but must win")
                    .build();
            assertEquals('X', t.getSurnameInitial("欧阳修", NO_FALLBACK));
            assertEquals(Source.OVERRIDE, t.match("欧阳修", NO_FALLBACK).source);
        }

        @Test
        public void overrideCanIntroduceAnUnknownSurname() {
            final ChineseSurnameTable t = ChineseSurnameTable.newBuilder()
                    .addOverrideLine("张,Z,pin it down without ICU")
                    .build();
            assertEquals('Z', t.getSurnameInitial("张三", NO_FALLBACK));
        }

        @Test
        public void lastOverrideWins() {
            final ChineseSurnameTable t = ChineseSurnameTable.newBuilder()
                    .addOverrideLine("覃,Q,first")
                    .addOverrideLine("覃,T,second")
                    .build();
            assertEquals('T', t.getSurnameInitial("覃某", NO_FALLBACK));
        }

        @Test
        public void programmaticOverrideForUserPreferenceLayer() {
            final ChineseSurnameTable t = ChineseSurnameTable.newBuilder()
                    .addOverride("覃", 'q', "user picked Qin in settings")
                    .build();
            assertEquals('Q', t.getSurnameInitial("覃某", NO_FALLBACK));
        }
    }

    // =============================================================================
    // 8. Sorting behaviour built on top of the initial
    // =============================================================================

    public static class Sorting {

        @Test
        public void namesSortByCorrectedInitial() {
            // 单 must land between 陈(C) and 张(Z) as Shàn, not next to 陈 as Dān.
            final String[] names = {"张某", "单某", "陈某"};
            final Comparator<String> byInitial = Comparator.comparingInt(
                    n -> TABLE.getSurnameInitial(n, guessAscii(n)));
            Arrays.sort(names, byInitial);
            assertArrayEqualsMsg(new String[]{"陈某", "单某", "张某"}, names);
        }

        @Test
        public void compoundSurnamesSortUnderTheirRealReading() {
            // 万俟 under M, between 李(L) and 欧阳(O).
            final String[] names = {"欧阳某", "万俟某", "李某"};
            final Comparator<String> byInitial = Comparator.comparingInt(
                    n -> TABLE.getSurnameInitial(n, guessAscii(n)));
            Arrays.sort(names, byInitial);
            assertArrayEqualsMsg(new String[]{"李某", "万俟某", "欧阳某"}, names);
        }

        /** Stand-in for the ICU fallback: only the ordinary surnames used above need it. */
        private static char guessAscii(String name) {
            if (name.startsWith("张")) return 'Z';
            if (name.startsWith("陈")) return 'C';
            if (name.startsWith("李")) return 'L';
            return ChineseSurnameTable.UNKNOWN_INITIAL;
        }

        private static void assertArrayEqualsMsg(String[] expected, String[] actual) {
            assertEquals(Arrays.toString(expected), Arrays.toString(actual));
        }
    }

    // =============================================================================
    // 9. CSV line parsing — a botched overlay must degrade, never crash
    // =============================================================================

    public static class LineParsing {

        @Test
        public void commentsAndBlanksAreSkippedSilently() {
            final ChineseSurnameTable.Builder b = ChineseSurnameTable.newEmptyBuilder()
                    .addPolyphonicLine("# this is a comment")
                    .addPolyphonicLine("")
                    .addPolyphonicLine("   ")
                    .addPolyphonicLine(null);
            assertEquals(0, b.getSkippedLineCount());
            assertTrue(b.build().getPolyphonicEntries().isEmpty());
        }

        @Test
        public void bomIsStripped() {
            final ChineseSurnameTable t = ChineseSurnameTable.newEmptyBuilder()
                    .addPolyphonicLine("\uFEFF单,shan,S,dan,certain,with BOM")
                    .build();
            assertEquals('S', t.getSurnameInitial("单某", NO_FALLBACK));
        }

        @Test
        public void crlfIsTrimmed() {
            final ChineseSurnameTable t = ChineseSurnameTable.newEmptyBuilder()
                    .addPolyphonicLine("单,shan,S,dan,certain,note\r")
                    .build();
            assertEquals('S', t.getSurnameInitial("单某", NO_FALLBACK));
        }

        @Test
        public void missingTrailingColumnsArePadded() {
            final ChineseSurnameTable t = ChineseSurnameTable.newEmptyBuilder()
                    .addPolyphonicLine("单,shan")
                    .addCompoundLine("欧阳,ouyang")
                    .build();
            assertEquals('S', t.getSurnameInitial("单某", NO_FALLBACK));
            assertEquals('O', t.getSurnameInitial("欧阳修", NO_FALLBACK));
        }

        @Test
        public void malformedLinesAreCountedNotThrown() {
            final ChineseSurnameTable.Builder b = ChineseSurnameTable.newEmptyBuilder()
                    .addPolyphonicLine("单")                 // no pinyin
                    .addPolyphonicLine(",shan,S,,certain,")  // no surname
                    .addPolyphonicLine("单,123,S,,certain,"); // pinyin not a letter
            assertEquals(3, b.getSkippedLineCount());
            assertTrue(b.build().getPolyphonicEntries().isEmpty());
        }

        @Test
        public void notesMayContainCommas() {
            final ChineseSurnameTable t = ChineseSurnameTable.newEmptyBuilder()
                    .addPolyphonicLine("单,shan,S,dan,certain,see 单雄信, also 单田芳")
                    .build();
            assertEquals("see 单雄信, also 单田芳",
                    t.getPolyphonicEntries().get("单").note);
        }

        @Test
        public void inlineCommentIsStripped() {
            final ChineseSurnameTable t = ChineseSurnameTable.newEmptyBuilder()
                    .addCompoundLine("令狐,linghu,L,   # 令 here is Líng")
                    .build();
            assertEquals('L', t.getSurnameInitial("令狐冲", NO_FALLBACK));
        }

        @Test
        public void surroundingWhitespaceIsTrimmed() {
            final ChineseSurnameTable t = ChineseSurnameTable.newEmptyBuilder()
                    .addPolyphonicLine("  单 , shan , S , dan , certain , note ")
                    .build();
            assertEquals('S', t.getSurnameInitial("单某", NO_FALLBACK));
        }

        @Test
        public void confidenceColumnDrivesTheAmbiguousFlag() {
            final ChineseSurnameTable t = ChineseSurnameTable.newEmptyBuilder()
                    .addPolyphonicLine("覃,tan,T,qin,ambiguous,")
                    .addPolyphonicLine("单,shan,S,dan,certain,")
                    .build();
            assertTrue(t.getPolyphonicEntries().get("覃").ambiguous);
            assertFalse(t.getPolyphonicEntries().get("单").ambiguous);
        }

        @Test
        public void malformedOverrideLinesAreRejected() {
            final ChineseSurnameTable.Builder b = ChineseSurnameTable.newEmptyBuilder()
                    .addOverrideLine("覃")        // no initial
                    .addOverrideLine("覃,1,note") // initial not a letter
                    .addOverrideLine(",Q,note");  // no surname
            assertEquals(3, b.getSkippedLineCount());
            assertTrue(b.build().getOverrideEntries().isEmpty());
        }

        @Test
        public void overrideInitialIsUppercased() {
            final ChineseSurnameTable t = ChineseSurnameTable.newEmptyBuilder()
                    .addOverrideLine("覃,q,lowercase in the file")
                    .build();
            assertEquals('Q', t.getSurnameInitial("覃某", NO_FALLBACK));
        }

        @Test
        public void wholeFileIngestion() {
            final ChineseSurnameTable t = ChineseSurnameTable.newEmptyBuilder()
                    .addCompoundLines(Arrays.asList(
                            "# compound table",
                            "",
                            "欧阳,ouyang,O,",
                            "司马,sima,S,"))
                    .addPolyphonicLines(Arrays.asList(
                            "# polyphonic table",
                            "单,shan,S,dan,certain,"))
                    .build();
            assertEquals(2, t.getCompoundEntries().size());
            assertEquals(1, t.getPolyphonicEntries().size());
            assertEquals('O', t.getSurnameInitial("欧阳修", NO_FALLBACK));
        }
    }

    // =============================================================================
    // 10. Data integrity of the shipped tables
    // =============================================================================

    public static class DataIntegrity {

        @Test
        public void builtInTablesAreSelfConsistent() {
            final List<String> problems = ChineseSurnameTable.validateBuiltInTables();
            assertTrue("built-in table problems:\n  " + String.join("\n  ", problems),
                    problems.isEmpty());
        }

        @Test
        public void polyphonicTableCoversTheSpecMandatedTen() {
            for (String s : new String[]{"单", "解", "曾", "仇", "查", "区", "华", "朴", "乐", "折"}) {
                assertTrue("missing mandated polyphonic surname " + s,
                        TABLE.getPolyphonicEntries().containsKey(s));
            }
        }

        @Test
        public void compoundTableCoversTheSpecMandatedSet() {
            for (String s : new String[]{"欧阳", "司马", "诸葛", "上官", "皇甫", "令狐", "尉迟",
                    "万俟", "澹台", "慕容", "独孤", "公孙"}) {
                assertTrue("missing mandated compound surname " + s,
                        TABLE.getCompoundEntries().containsKey(s));
            }
        }

        @Test
        public void polyphonicTableHasAtLeastThirtyEntries() {
            assertTrue("expected >= 30 polyphonic entries, got "
                            + TABLE.getPolyphonicEntries().size(),
                    TABLE.getPolyphonicEntries().size() >= 30);
        }

        @Test
        public void everyEntryProducesALetterInitial() {
            for (ChineseSurnameTable.Entry e : TABLE.getCompoundEntries().values()) {
                assertTrue(e.surname + " -> " + e.initial,
                        e.initial >= 'A' && e.initial <= 'Z');
            }
            for (ChineseSurnameTable.Entry e : TABLE.getPolyphonicEntries().values()) {
                assertTrue(e.surname + " -> " + e.initial,
                        e.initial >= 'A' && e.initial <= 'Z');
            }
        }

        @Test
        public void resolvingIsStable() {
            // Cheap guard against accidental mutable state in the shared instance.
            for (int i = 0; i < 1000; i++) {
                assertEquals('S', TABLE.getSurnameInitial("单雄信", NO_FALLBACK));
                assertEquals('C', TABLE.getSurnameInitial("单于某", NO_FALLBACK));
            }
        }
    }
}
