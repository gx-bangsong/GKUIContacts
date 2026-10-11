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

package com.android.contacts.group;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Suite;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Host JVM tests for {@link GroupChipFilter}.
 *
 * <p>{@code atest ContactsSurnameUnitTests}
 */
@RunWith(Suite.class)
@Suite.SuiteClasses({
        GroupChipFilterTest.Selection.class,
        GroupChipFilterTest.GroupListChanges.class,
        GroupChipFilterTest.BarVisibility.class,
        GroupChipFilterTest.QueryBuilding.class,
        GroupChipFilterTest.LikePatterns.class,
})
public class GroupChipFilterTest {

    // Column names, matching what DefaultContactListAdapter passes in.
    static final String MIMETYPE = "mimetype";
    static final String GROUP_ROW_ID = "data1";
    static final String NAME_PRIMARY = "display_name";
    static final String NAME_ALT = "display_name_alt";
    static final String GROUP_MIME = "vnd.android.cursor.item/group_membership";

    static List<GroupChipFilter.Group> groups(long... ids) {
        final List<GroupChipFilter.Group> list = new ArrayList<>();
        for (long id : ids) {
            list.add(new GroupChipFilter.Group(id, "Label " + id));
        }
        return list;
    }

    static GroupChipFilter filterWith(long... ids) {
        final GroupChipFilter f = new GroupChipFilter();
        f.setGroups(groups(ids));
        return f;
    }

    // =============================================================================

    public static class Selection {

        @Test
        public void defaultsToAllGroups() {
            final GroupChipFilter f = new GroupChipFilter();
            assertEquals(GroupChipFilter.ALL_GROUPS, f.getSelectedGroupId());
            assertFalse(f.isFiltering());
        }

        @Test
        public void selectingAGroupStartsFiltering() {
            final GroupChipFilter f = filterWith(1, 2, 3);
            assertTrue(f.select(2));
            assertEquals(2, f.getSelectedGroupId());
            assertTrue(f.isFiltering());
        }

        @Test
        public void selectingAllClearsTheFilter() {
            final GroupChipFilter f = filterWith(1, 2);
            f.select(1);
            assertTrue(f.select(GroupChipFilter.ALL_GROUPS));
            assertFalse(f.isFiltering());
        }

        @Test
        public void reselectingTheSameGroupIsANoOp() {
            final GroupChipFilter f = filterWith(1, 2);
            assertTrue(f.select(1));
            // Returning false is what stops the caller issuing a redundant reload.
            assertFalse(f.select(1));
            assertEquals(1, f.getSelectedGroupId());
        }

        @Test
        public void selectingAnUnknownGroupIsIgnored() {
            // A stale click must not filter the list down to nothing.
            final GroupChipFilter f = filterWith(1, 2);
            f.select(1);
            assertFalse(f.select(999));
            assertEquals(1, f.getSelectedGroupId());
        }

        @Test
        public void selectingAllIsAlwaysAllowed() {
            // Even with no groups at all, clearing the filter must work.
            final GroupChipFilter f = new GroupChipFilter();
            assertFalse(f.select(GroupChipFilter.ALL_GROUPS)); // already All, so no change
            assertFalse(f.isFiltering());
        }

        @Test
        public void selectedTitleTracksSelection() {
            final GroupChipFilter f = filterWith(7);
            assertNull(f.getSelectedTitle());
            f.select(7);
            assertEquals("Label 7", f.getSelectedTitle());
            f.select(GroupChipFilter.ALL_GROUPS);
            assertNull(f.getSelectedTitle());
        }

        @Test
        public void indexOfGroup() {
            final GroupChipFilter f = filterWith(10, 20, 30);
            assertEquals(0, f.indexOfGroup(10));
            assertEquals(2, f.indexOfGroup(30));
            assertEquals(-1, f.indexOfGroup(999));
            assertEquals(-1, f.indexOfGroup(GroupChipFilter.ALL_GROUPS));
        }
    }

    // =============================================================================

    public static class GroupListChanges {

        @Test
        public void selectionSurvivesAReloadThatKeepsTheGroup() {
            final GroupChipFilter f = filterWith(1, 2, 3);
            f.select(2);
            assertFalse("selection should be untouched", f.setGroups(groups(1, 2, 3)));
            assertEquals(2, f.getSelectedGroupId());
        }

        @Test
        public void selectionResetsWhenTheSelectedGroupIsDeleted() {
            // The label was deleted in another app while its chip was active.
            final GroupChipFilter f = filterWith(1, 2, 3);
            f.select(2);
            assertTrue("caller must be told to reload", f.setGroups(groups(1, 3)));
            assertEquals(GroupChipFilter.ALL_GROUPS, f.getSelectedGroupId());
            assertFalse(f.isFiltering());
        }

        @Test
        public void selectionResetsWhenAllGroupsDisappear() {
            final GroupChipFilter f = filterWith(1);
            f.select(1);
            assertTrue(f.setGroups(Collections.<GroupChipFilter.Group>emptyList()));
            assertEquals(GroupChipFilter.ALL_GROUPS, f.getSelectedGroupId());
        }

        @Test
        public void nullGroupListIsTreatedAsEmpty() {
            final GroupChipFilter f = filterWith(1);
            f.select(1);
            assertTrue(f.setGroups(null));
            assertTrue(f.getGroups().isEmpty());
            assertEquals(GroupChipFilter.ALL_GROUPS, f.getSelectedGroupId());
        }

        @Test
        public void noSelectionMeansNoResetSignal() {
            final GroupChipFilter f = filterWith(1, 2);
            assertFalse(f.setGroups(groups(3, 4)));
        }

        @Test
        public void groupListIsDefensivelyCopied() {
            final List<GroupChipFilter.Group> source = groups(1, 2);
            final GroupChipFilter f = new GroupChipFilter();
            f.setGroups(source);
            source.clear();
            assertEquals("caller's mutation must not leak in", 2, f.getGroups().size());
        }

        @Test
        public void restoreSelectionAcceptsAKnownGroup() {
            final GroupChipFilter f = filterWith(1, 2);
            f.restoreSelection(2);
            assertEquals(2, f.getSelectedGroupId());
        }

        @Test
        public void restoreSelectionRejectsAnUnknownGroup() {
            // Rotated back after the label was deleted.
            final GroupChipFilter f = filterWith(1, 2);
            f.restoreSelection(99);
            assertEquals(GroupChipFilter.ALL_GROUPS, f.getSelectedGroupId());
        }

        @Test
        public void restoreSelectionAcceptsAll() {
            final GroupChipFilter f = filterWith(1);
            f.select(1);
            f.restoreSelection(GroupChipFilter.ALL_GROUPS);
            assertFalse(f.isFiltering());
        }
    }

    // =============================================================================

    public static class BarVisibility {

        @Test
        public void hiddenWhenThereAreNoGroups() {
            // A bar containing only "All" offers no choice, so it is pure clutter.
            assertFalse(new GroupChipFilter().isBarVisible());
        }

        @Test
        public void visibleWithAtLeastOneGroup() {
            assertTrue(filterWith(1).isBarVisible());
        }

        @Test
        public void hidesAgainWhenTheLastGroupIsDeleted() {
            final GroupChipFilter f = filterWith(1);
            assertTrue(f.isBarVisible());
            f.setGroups(Collections.<GroupChipFilter.Group>emptyList());
            assertFalse(f.isBarVisible());
        }
    }

    // =============================================================================

    public static class QueryBuilding {

        @Test
        public void selectionWithoutAQueryRestrictsToTheGroupOnly() {
            final String selection = GroupChipFilter.buildSelection(
                    MIMETYPE, GROUP_ROW_ID, NAME_PRIMARY, NAME_ALT, /* hasQuery */ false);
            assertEquals("mimetype=?2 AND data1=?3", selection);
        }

        @Test
        public void selectionWithAQueryAlsoMatchesEitherNameColumn() {
            final String selection = GroupChipFilter.buildSelection(
                    MIMETYPE, GROUP_ROW_ID, NAME_PRIMARY, NAME_ALT, /* hasQuery */ true);
            assertEquals("mimetype=?2 AND data1=?3"
                            + " AND (display_name LIKE ?1 ESCAPE '\\'"
                            + " OR display_name_alt LIKE ?1 ESCAPE '\\')",
                    selection);
        }

        @Test
        public void escapeClauseIsPresentWheneverLikeIs() {
            // Without ESCAPE, the backslashes toLikePattern() inserts would be matched literally
            // and the search would silently stop working.
            final String selection = GroupChipFilter.buildSelection(
                    MIMETYPE, GROUP_ROW_ID, NAME_PRIMARY, NAME_ALT, true);
            final int likeCount = countOccurrences(selection, "LIKE ?1");
            final int escapeCount = countOccurrences(selection, "ESCAPE");
            assertEquals(likeCount, escapeCount);
        }

        @Test
        public void argumentsAreAlwaysPositionallyStable() {
            // SQLite numbers ?N by position in the array, so ?1 must be supplied even when
            // unused; dropping it would silently shift the mime type into its place.
            final String[] noQuery = GroupChipFilter.buildSelectionArgs(GROUP_MIME, 42, null);
            assertArrayEquals(new String[]{"%", GROUP_MIME, "42"}, noQuery);

            final String[] withQuery = GroupChipFilter.buildSelectionArgs(GROUP_MIME, 42, "ann");
            assertArrayEquals(new String[]{"ann%", GROUP_MIME, "42"}, withQuery);
        }

        @Test
        public void groupIdIsStringified() {
            final String[] args = GroupChipFilter.buildSelectionArgs(GROUP_MIME, 12345L, null);
            assertEquals("12345", args[2]);
        }

        private static int countOccurrences(String haystack, String needle) {
            int count = 0;
            int from = 0;
            while (true) {
                final int at = haystack.indexOf(needle, from);
                if (at < 0) {
                    return count;
                }
                count++;
                from = at + needle.length();
            }
        }
    }

    // =============================================================================

    public static class LikePatterns {

        @Test
        public void prefixMatch() {
            assertEquals("ann%", GroupChipFilter.toLikePattern("ann"));
        }

        @Test
        public void queryIsTrimmed() {
            assertEquals("ann%", GroupChipFilter.toLikePattern("  ann  "));
        }

        @Test
        public void nullAndBlankMatchEverything() {
            assertEquals("%", GroupChipFilter.toLikePattern(null));
            assertEquals("%", GroupChipFilter.toLikePattern(""));
            assertEquals("%", GroupChipFilter.toLikePattern("   "));
        }

        @Test
        public void wildcardsInTheQueryAreEscaped() {
            // Typing '%' should look for a literal percent sign, not match every contact.
            assertEquals("100\\%%", GroupChipFilter.toLikePattern("100%"));
            assertEquals("a\\_b%", GroupChipFilter.toLikePattern("a_b"));
            assertEquals("a\\\\b%", GroupChipFilter.toLikePattern("a\\b"));
        }

        @Test
        public void chineseQueryIsPassedThrough() {
            assertEquals("单%", GroupChipFilter.toLikePattern("单"));
        }
    }
}
