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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Selection state and query fragments for the group (label) chips bar.
 *
 * <p>Pure Java with no Android dependency, so the awkward parts — what happens to the selection
 * when the selected group is deleted underneath us, when the bar should hide itself, and how the
 * group restriction combines with a search query — are all exercised on a host JVM.
 *
 * <p>Column names are injected rather than referenced from {@code ContactsContract} for the same
 * reason; the adapter passes the real constants in.
 *
 * <p>Not thread-safe: owned by the UI thread, like the chips bar itself.
 */
public final class GroupChipFilter {

    /** Sentinel for the "All" chip, i.e. no group restriction. */
    public static final long ALL_GROUPS = -1;

    /** One chip. Immutable. */
    public static final class Group {
        public final long id;
        public final String title;

        public Group(long id, String title) {
            this.id = id;
            this.title = title == null ? "" : title;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof Group)) {
                return false;
            }
            final Group other = (Group) o;
            return id == other.id && title.equals(other.title);
        }

        @Override
        public int hashCode() {
            return (int) (id ^ (id >>> 32)) * 31 + title.hashCode();
        }

        @Override
        public String toString() {
            return "Group{" + id + "," + title + "}";
        }
    }

    private List<Group> mGroups = Collections.emptyList();
    private long mSelectedGroupId = ALL_GROUPS;

    /**
     * Replaces the group list, keeping the current selection when that group still exists.
     *
     * <p>If the selected group has disappeared — deleted, or its account was removed while the
     * chip was active — the selection falls back to "All" rather than leaving the list filtered by
     * something the user can no longer see.
     *
     * @return true when the selection changed as a result, meaning the caller must reload
     */
    public boolean setGroups(List<Group> groups) {
        mGroups = groups == null
                ? Collections.<Group>emptyList()
                : Collections.unmodifiableList(new ArrayList<>(groups));
        if (mSelectedGroupId != ALL_GROUPS && indexOfGroup(mSelectedGroupId) < 0) {
            mSelectedGroupId = ALL_GROUPS;
            return true;
        }
        return false;
    }

    /** The groups currently backing the bar, excluding the synthetic "All" chip. */
    public List<Group> getGroups() {
        return mGroups;
    }

    /**
     * Selects a group, or {@link #ALL_GROUPS} to clear the filter.
     *
     * <p>Selecting a group that is not in the list is ignored and leaves the state untouched, so a
     * stale click cannot filter the list into emptiness.
     *
     * @return true when the selection actually changed, meaning the caller must reload
     */
    public boolean select(long groupId) {
        if (groupId != ALL_GROUPS && indexOfGroup(groupId) < 0) {
            return false;
        }
        if (mSelectedGroupId == groupId) {
            return false;
        }
        mSelectedGroupId = groupId;
        return true;
    }

    /** The selected group id, or {@link #ALL_GROUPS}. */
    public long getSelectedGroupId() {
        return mSelectedGroupId;
    }

    /** True when a real group is selected, i.e. the contact list must be restricted. */
    public boolean isFiltering() {
        return mSelectedGroupId != ALL_GROUPS;
    }

    /**
     * Whether the chips bar should be shown at all.
     *
     * <p>Hidden when there are no groups: a bar containing only "All" is pure clutter, since it
     * offers the user no choice.
     */
    public boolean isBarVisible() {
        return !mGroups.isEmpty();
    }

    /** Index of {@code groupId} within {@link #getGroups()}, or -1. */
    public int indexOfGroup(long groupId) {
        for (int i = 0; i < mGroups.size(); i++) {
            if (mGroups.get(i).id == groupId) {
                return i;
            }
        }
        return -1;
    }

    /** Title of the selected group, or null when "All" is selected or the group is gone. */
    public String getSelectedTitle() {
        final int index = indexOfGroup(mSelectedGroupId);
        return index < 0 ? null : mGroups.get(index).title;
    }

    /** Restores a selection across rotation. Unknown ids collapse to "All". */
    public void restoreSelection(long groupId) {
        mSelectedGroupId = (groupId != ALL_GROUPS && indexOfGroup(groupId) < 0)
                ? ALL_GROUPS
                : groupId;
    }

    // =================================================================================
    // Query fragments
    //
    // A group-filtered list queries the Data table for GroupMembership rows, mirroring
    // GroupMembersAdapter. Column names are injected so this stays host-testable.
    // =================================================================================

    /**
     * Builds the {@code WHERE} clause restricting results to one group, optionally also matching a
     * search query against either display-name column.
     *
     * <p>The name predicate uses {@code ?1} for both columns so a single argument covers them,
     * matching the existing {@code FILTER_TYPE_GROUP_MEMBERS} branch in
     * {@code DefaultContactListAdapter}. An explicit {@code ESCAPE} clause is declared because
     * {@link #toLikePattern} escapes {@code %} and {@code _}; without it SQLite would treat the
     * backslashes as literal characters and the search would silently stop matching.
     *
     * @param mimeTypeColumn      e.g. {@code Data.MIMETYPE}
     * @param groupRowIdColumn    e.g. {@code GroupMembership.GROUP_ROW_ID}
     * @param namePrimaryColumn   e.g. {@code Contacts.DISPLAY_NAME_PRIMARY}
     * @param nameAlternativeColumn e.g. {@code Contacts.DISPLAY_NAME_ALTERNATIVE}
     * @param hasQuery            whether a non-empty search query is active
     */
    public static String buildSelection(String mimeTypeColumn, String groupRowIdColumn,
            String namePrimaryColumn, String nameAlternativeColumn, boolean hasQuery) {
        final StringBuilder sb = new StringBuilder();
        sb.append(mimeTypeColumn).append("=?2")
                .append(" AND ").append(groupRowIdColumn).append("=?3");
        if (hasQuery) {
            sb.append(" AND (").append(namePrimaryColumn).append(" LIKE ?1 ESCAPE '\\'")
                    .append(" OR ").append(nameAlternativeColumn).append(" LIKE ?1 ESCAPE '\\')");
        }
        return sb.toString();
    }

    /**
     * Builds the arguments matching {@link #buildSelection}. Positional, so the order is fixed:
     * {@code ?1} the name pattern, {@code ?2} the mime type, {@code ?3} the group id.
     *
     * <p>{@code ?1} is always supplied — SQLite numbers arguments by position in the array, so
     * omitting it when there is no query would shift the others.
     *
     * @param query null or empty when not searching
     */
    public static String[] buildSelectionArgs(String groupMembershipMimeType, long groupId,
            String query) {
        return new String[]{
                toLikePattern(query),
                groupMembershipMimeType,
                String.valueOf(groupId),
        };
    }

    /**
     * Converts a raw query into a {@code LIKE} pattern, escaping the wildcards so a user typing
     * {@code %} or {@code _} searches for those characters rather than matching everything.
     */
    static String toLikePattern(String query) {
        if (query == null) {
            return "%";
        }
        final String trimmed = query.trim();
        if (trimmed.isEmpty()) {
            return "%";
        }
        final StringBuilder sb = new StringBuilder(trimmed.length() + 4);
        for (int i = 0; i < trimmed.length(); i++) {
            final char c = trimmed.charAt(i);
            if (c == '%' || c == '_' || c == '\\') {
                sb.append('\\');
            }
            sb.append(c);
        }
        sb.append('%');
        return sb.toString();
    }
}
