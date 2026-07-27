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

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Suite;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Host JVM tests for {@link SurnameIndexBuilder} and {@link SurnamePinyinResolver}.
 *
 * <p>{@code atest ContactsSurnameUnitTests}
 */
@RunWith(Suite.class)
@Suite.SuiteClasses({
        SurnameIndexBuilderTest.Ordering.class,
        SurnameIndexBuilderTest.Sections.class,
        SurnameIndexBuilderTest.SurnameHints.class,
        SurnameIndexBuilderTest.EdgeCases.class,
        SurnameIndexBuilderTest.ResolverBehaviour.class,
})
public class SurnameIndexBuilderTest {

    /**
     * Stands in for {@link IcuPinyinFallback}, which cannot run on a host JVM. Covers the ordinary
     * surnames the fixtures use.
     */
    static final PinyinFallback STUB_FALLBACK = new PinyinFallback() {
        private final Map<String, Character> mMap = new HashMap<String, Character>() {{
            put("张", 'Z'); put("王", 'W'); put("李", 'L'); put("陈", 'C');
            put("刘", 'L'); put("沈", 'S'); put("石", 'S'); put("孙", 'S');
            put("周", 'Z'); put("吴", 'W'); put("郑", 'Z'); put("冯", 'F');
        }};

        @Override
        public char initialOf(String han) {
            final Character c = mMap.get(han);
            return c == null ? ChineseSurnameTable.UNKNOWN_INITIAL : c;
        }
    };

    static SurnamePinyinResolver resolver() {
        return new SurnamePinyinResolver(ChineseSurnameTable.getDefault(), STUB_FALLBACK);
    }

    static SurnameIndexBuilder.Result build(String... names) {
        return SurnameIndexBuilder.build(Arrays.asList(names), resolver());
    }

    /** Applies the permutation, so assertions read as the list the user would actually see. */
    static String[] reorder(String[] names, SurnameIndexBuilder.Result r) {
        final String[] out = new String[r.order.length];
        for (int i = 0; i < out.length; i++) {
            out[i] = names[r.order[i]];
        }
        return out;
    }

    // =============================================================================

    public static class Ordering {

        @Test
        public void polyphonicSurnameMovesToItsRealBucket() {
            // 单 must sort as Shan (S), not Dan (D): after 陈 (C), before 张 (Z).
            final String[] names = {"张三", "单雄信", "陈六"};
            final SurnameIndexBuilder.Result r = build(names);
            assertArrayEquals(new String[]{"陈六", "单雄信", "张三"}, reorder(names, r));
        }

        @Test
        public void compoundSurnameMovesToItsRealBucket() {
            // 万俟 is Moqi (M), so it belongs between 李 (L) and 欧阳 (O), nowhere near W.
            final String[] names = {"欧阳修", "万俟卨", "李四"};
            final SurnameIndexBuilder.Result r = build(names);
            assertArrayEquals(new String[]{"李四", "万俟卨", "欧阳修"}, reorder(names, r));
        }

        @Test
        public void fullMixedAddressBook() {
            final String[] names = {
                    "张三", "单雄信", "陈六", "欧阳修", "Alice", "123", "万俟卨", "李四", "解缙"};
            final SurnameIndexBuilder.Result r = build(names);
            assertArrayEquals(new String[]{
                    "Alice", "陈六", "李四", "万俟卨", "欧阳修", "单雄信", "解缙", "张三", "123"},
                    reorder(names, r));
        }

        @Test
        public void unknownBucketSortsLast() {
            final String[] names = {"123", "Alice", "张三"};
            final SurnameIndexBuilder.Result r = build(names);
            assertArrayEquals(new String[]{"Alice", "张三", "123"}, reorder(names, r));
        }

        @Test
        public void sameSurnameKeepsProviderOrder() {
            // Within one surname we must not re-sort: the provider's locale-aware collation of the
            // given name is better than anything we would invent here.
            final String[] names = {"张三", "张一", "张二"};
            final SurnameIndexBuilder.Result r = build(names);
            assertArrayEquals(new String[]{"张三", "张一", "张二"}, reorder(names, r));
        }

        @Test
        public void sameBucketDifferentSurnamesOrderByReading() {
            // Both S. shan < shen < shi by reading.
            final String[] names = {"石某", "单某", "沈某"};
            final SurnameIndexBuilder.Result r = build(names);
            final String[] sorted = reorder(names, r);
            assertEquals("单某", sorted[0]); // shan, from the polyphonic table
            assertEquals(1, r.sections.length);
            assertEquals("S", r.sections[0]);
        }

        @Test
        public void identityOrderIsDetected() {
            final SurnameIndexBuilder.Result sorted = build("Alice", "Bob");
            assertTrue(sorted.isIdentityOrder);

            final SurnameIndexBuilder.Result shuffled = build("Bob", "Alice");
            assertFalse(shuffled.isIdentityOrder);
        }

        @Test
        public void orderIsAValidPermutation() {
            final String[] names = {"张三", "单雄信", "陈六", "欧阳修", "Alice", "123"};
            final SurnameIndexBuilder.Result r = build(names);
            final boolean[] seen = new boolean[names.length];
            for (int position : r.order) {
                assertTrue("index out of range: " + position,
                        position >= 0 && position < names.length);
                assertFalse("duplicate index: " + position, seen[position]);
                seen[position] = true;
            }
        }
    }

    // =============================================================================

    public static class Sections {

        @Test
        public void sectionsAndCountsAreParallelAndComplete() {
            final String[] names = {
                    "张三", "单雄信", "陈六", "欧阳修", "Alice", "123", "万俟卨", "李四", "解缙"};
            final SurnameIndexBuilder.Result r = build(names);
            assertArrayEquals(new String[]{"A", "C", "L", "M", "O", "S", "X", "Z", "#"},
                    r.sections);
            assertArrayEquals(new int[]{1, 1, 1, 1, 1, 1, 1, 1, 1}, r.counts);
            assertEquals(r.sections.length, r.counts.length);
        }

        @Test
        public void countsSumToRowCount() {
            final SurnameIndexBuilder.Result r =
                    build("张三", "单雄信", "陈六", "欧阳修", "Alice", "123");
            int total = 0;
            for (int c : r.counts) {
                total += c;
            }
            assertEquals(r.getRowCount(), total);
        }

        @Test
        public void adjacentRowsWithTheSameInitialFormOneSection() {
            final SurnameIndexBuilder.Result r = build("单某", "沈某", "石某", "张某");
            assertArrayEquals(new String[]{"S", "Z"}, r.sections);
            assertArrayEquals(new int[]{3, 1}, r.counts);
        }

        @Test
        public void noEmptySectionsOrZeroCounts() {
            final SurnameIndexBuilder.Result r =
                    build("张三", "单雄信", "Alice", "123", "欧阳修");
            for (String s : r.sections) {
                assertFalse("empty section label", s.isEmpty());
            }
            for (int c : r.counts) {
                assertTrue("zero count", c > 0);
            }
        }

        @Test
        public void hashSectionOnlyAppearsWhenPopulated() {
            final SurnameIndexBuilder.Result clean = build("Alice", "单雄信");
            assertFalse(Arrays.asList(clean.sections).contains("#"));

            final SurnameIndexBuilder.Result dirty = build("Alice", "123");
            assertTrue(Arrays.asList(dirty.sections).contains("#"));
        }

        @Test
        public void sectionOffsetsLineUpWithTheSortedRows() {
            // The contract ContactsSectionIndexer relies on: section i covers a contiguous run.
            final String[] names = {"张三", "单雄信", "陈六", "单田芳", "Alice"};
            final SurnameIndexBuilder.Result r = build(names);
            final String[] sorted = reorder(names, r);
            int offset = 0;
            for (int s = 0; s < r.sections.length; s++) {
                for (int k = 0; k < r.counts[s]; k++) {
                    final char expected = r.sections[s].charAt(0);
                    final char actual = initialOf(sorted[offset + k]);
                    assertEquals("row " + (offset + k) + " (" + sorted[offset + k]
                            + ") in section " + r.sections[s], expected, actual);
                }
                offset += r.counts[s];
            }
            assertEquals(names.length, offset);
        }

        private static char initialOf(String name) {
            final char c = resolver().getInitial(name);
            return c == ChineseSurnameTable.UNKNOWN_INITIAL ? '#' : c;
        }
    }

    // =============================================================================

    public static class SurnameHints {

        @Test
        public void bubbleShowsUpToThreeDistinctSurnames() {
            // D1: the fast-scroll bubble shows the letter plus the first three surnames.
            final SurnameIndexBuilder.Result r = build("单某", "沈某", "石某", "孙某", "张某");
            assertEquals("S", r.sections[0]);
            assertArrayEquals(new String[]{"单", "沈", "石"}, r.surnameHints[0]);
        }

        @Test
        public void hintsAreCappedAtThree() {
            final SurnameIndexBuilder.Result r = build("单某", "沈某", "石某", "孙某");
            assertEquals(SurnameIndexBuilder.MAX_SURNAME_HINTS, r.surnameHints[0].length);
        }

        @Test
        public void duplicateSurnamesCountOnce() {
            final SurnameIndexBuilder.Result r = build("单某甲", "单某乙", "沈某");
            assertArrayEquals(new String[]{"单", "沈"}, r.surnameHints[0]);
        }

        @Test
        public void compoundSurnameAppearsWhole() {
            final SurnameIndexBuilder.Result r = build("欧阳修");
            assertArrayEquals(new String[]{"欧阳"}, r.surnameHints[0]);
        }

        @Test
        public void latinSectionsHaveNoHints() {
            // D1: an ordinary letter bucket shows the letter only, with no second line.
            final SurnameIndexBuilder.Result r = build("Alice", "Bob");
            assertEquals(0, r.surnameHints[0].length);
            assertEquals(0, r.surnameHints[1].length);
        }

        @Test
        public void hashSectionHasNoHints() {
            final SurnameIndexBuilder.Result r = build("123", "!!!");
            final int last = r.sections.length - 1;
            assertEquals("#", r.sections[last]);
            assertEquals(0, r.surnameHints[last].length);
        }

        @Test
        public void hintsArrayIsParallelToSections() {
            final SurnameIndexBuilder.Result r =
                    build("张三", "单雄信", "Alice", "123", "欧阳修");
            assertEquals(r.sections.length, r.surnameHints.length);
        }

        @Test
        public void hintTextJoinsWithTheGivenSeparator() {
            final SurnameIndexBuilder.Result r = build("单某", "沈某", "石某");
            assertEquals("单·沈·石", r.getSurnameHintText(0, "·"));
        }

        @Test
        public void hintTextIsEmptyForLatinAndOutOfRange() {
            final SurnameIndexBuilder.Result r = build("Alice");
            assertEquals("", r.getSurnameHintText(0, "·"));
            assertEquals("", r.getSurnameHintText(99, "·"));
            assertEquals("", r.getSurnameHintText(-1, "·"));
        }

        @Test
        public void ordinarySurnamesResolvedByFallbackStillProduceHints() {
            // 张 comes from the ICU fallback rather than a table, but the bubble should still
            // be able to show it.
            final SurnameIndexBuilder.Result r = build("张三");
            assertArrayEquals(new String[]{"张"}, r.surnameHints[0]);
        }
    }

    // =============================================================================

    public static class EdgeCases {

        @Test
        public void emptyInput() {
            final SurnameIndexBuilder.Result r =
                    SurnameIndexBuilder.build(Collections.<String>emptyList(), resolver());
            assertEquals(0, r.getRowCount());
            assertEquals(0, r.sections.length);
            assertEquals(0, r.counts.length);
            assertTrue(r.isIdentityOrder);
        }

        @Test
        public void nullInput() {
            final SurnameIndexBuilder.Result r =
                    SurnameIndexBuilder.build((List<String>) null, resolver());
            assertEquals(0, r.getRowCount());
        }

        @Test
        public void singleRow() {
            final SurnameIndexBuilder.Result r = build("单雄信");
            assertArrayEquals(new String[]{"S"}, r.sections);
            assertArrayEquals(new int[]{1}, r.counts);
            assertTrue(r.isIdentityOrder);
        }

        @Test
        public void nullAndEmptyNamesGoToHash() {
            final String[] names = {"张三", null, "", "   "};
            final SurnameIndexBuilder.Result r = build(names);
            assertArrayEquals(new String[]{"Z", "#"}, r.sections);
            assertArrayEquals(new int[]{1, 3}, r.counts);
        }

        @Test
        public void allRowsUnresolvable() {
            final SurnameIndexBuilder.Result r = build("123", "456", "!!!");
            assertArrayEquals(new String[]{"#"}, r.sections);
            assertArrayEquals(new int[]{3}, r.counts);
        }

        @Test
        public void arrayOverloadMatchesListOverload() {
            final String[] names = {"张三", "单雄信", "Alice"};
            final SurnameIndexBuilder.Result fromArray =
                    SurnameIndexBuilder.build(names, resolver());
            final SurnameIndexBuilder.Result fromList =
                    SurnameIndexBuilder.build(Arrays.asList(names), resolver());
            assertArrayEquals(fromList.order, fromArray.order);
            assertArrayEquals(fromList.sections, fromArray.sections);
        }

        @Test
        public void largeInputStaysConsistent() {
            // Rough guard on the O(n log n) path and on section contiguity at scale.
            final List<String> names = new ArrayList<>();
            final String[] surnames = {"张", "单", "欧阳", "陈", "解", "万俟", "李", "王"};
            for (int i = 0; i < 5000; i++) {
                names.add(surnames[i % surnames.length] + i);
            }
            final SurnameIndexBuilder.Result r = SurnameIndexBuilder.build(names, resolver());
            assertEquals(5000, r.getRowCount());
            int total = 0;
            for (int c : r.counts) {
                total += c;
            }
            assertEquals(5000, total);
        }
    }

    // =============================================================================

    public static class ResolverBehaviour {

        @Test
        public void tableHitsNeverConsultTheFallback() {
            final CountingFallback counting = new CountingFallback();
            final SurnamePinyinResolver r =
                    new SurnamePinyinResolver(ChineseSurnameTable.getDefault(), counting);
            r.getInitial("单雄信");
            r.getInitial("欧阳修");
            r.getInitial("万俟卨");
            assertEquals(0, counting.calls);
        }

        @Test
        public void fallbackIsConsultedOncePerDistinctSurname() {
            final CountingFallback counting = new CountingFallback();
            final SurnamePinyinResolver r =
                    new SurnamePinyinResolver(ChineseSurnameTable.getDefault(), counting);
            for (int i = 0; i < 100; i++) {
                r.getInitial("张" + i);
            }
            assertEquals("expected memoisation on the surname", 1, counting.calls);
        }

        @Test
        public void clearCacheForcesReResolution() {
            final CountingFallback counting = new CountingFallback();
            final SurnamePinyinResolver r =
                    new SurnamePinyinResolver(ChineseSurnameTable.getDefault(), counting);
            r.getInitial("张三");
            r.clearCache();
            r.getInitial("张三");
            assertEquals(2, counting.calls);
        }

        @Test
        public void nullFallbackIsTreatedAsNone() {
            final SurnamePinyinResolver r =
                    new SurnamePinyinResolver(ChineseSurnameTable.getDefault(), null);
            assertEquals('S', r.getInitial("单雄信"));
            assertEquals(ChineseSurnameTable.UNKNOWN_INITIAL, r.getInitial("张三"));
        }

        @Test
        public void sortKeyOrdersByBucketThenReading() {
            final SurnamePinyinResolver r = resolver();
            final String shan = r.getSortKey("单某");
            final String chen = r.getSortKey("陈某");
            final String zhang = r.getSortKey("张某");
            assertTrue("陈 < 单", chen.compareTo(shan) < 0);
            assertTrue("单 < 张", shan.compareTo(zhang) < 0);
        }

        @Test
        public void sortKeyPutsUnknownLast() {
            final SurnamePinyinResolver r = resolver();
            assertTrue(r.getSortKey("张某").compareTo(r.getSortKey("123")) < 0);
            assertTrue(r.getSortKey("Alice").compareTo(r.getSortKey("123")) < 0);
        }

        @Test
        public void createDefaultResolvesTablesButNotOrdinarySurnames() {
            final SurnamePinyinResolver r = SurnamePinyinResolver.createDefault();
            assertEquals('S', r.getInitial("单雄信"));
            assertEquals(ChineseSurnameTable.UNKNOWN_INITIAL, r.getInitial("张三"));
        }

        @Test
        public void resolveExposesTheMatchDetails() {
            final ChineseSurnameTable.Match m = resolver().resolve("万俟卨");
            assertEquals("万俟", m.surname);
            assertEquals("moqi", m.pinyin);
            assertEquals('M', m.initial);
            assertEquals(ChineseSurnameTable.Source.COMPOUND, m.source);
        }

        @Test
        public void fallbackMatchIsReportedAsFallback() {
            final ChineseSurnameTable.Match m = resolver().resolve("张三");
            assertEquals('Z', m.initial);
            assertEquals(ChineseSurnameTable.Source.FALLBACK, m.source);
            assertEquals("张", m.surname);
        }

        private static final class CountingFallback implements PinyinFallback {
            int calls;

            @Override
            public char initialOf(String han) {
                calls++;
                return STUB_FALLBACK.initialOf(han);
            }
        }
    }
}
