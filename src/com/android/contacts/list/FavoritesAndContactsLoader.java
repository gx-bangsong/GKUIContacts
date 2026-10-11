/*
 * Copyright (C) 2011 The Android Open Source Project
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
package com.android.contacts.list;

import android.content.Context;
import android.content.CursorLoader;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.database.MergeCursor;
import android.database.sqlite.SQLiteException;
import android.os.Bundle;
import android.provider.ContactsContract;
import android.provider.ContactsContract.CommonDataKinds.Organization;
import android.provider.ContactsContract.Contacts;
import android.provider.ContactsContract.Data;
import android.provider.ContactsContract.SearchSnippets;
import android.text.TextUtils;

import com.android.contacts.pinyin.SurnameIndexFeature;
import com.android.contacts.pinyin.SurnameSortedCursor;
import com.google.common.collect.Lists;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A loader for use in the default contact list, which will also query for favorite contacts
 * if configured to do so.
 *
 * <p>It additionally supports searching in the fields of the organization (work) data kind, which
 * the contacts provider itself can only match at the beginning of a value. This way searching for
 * "长城机电" finds the contact working at "北京长城机电公司" as well.
 */
public class FavoritesAndContactsLoader extends CursorLoader {

    /** Maximum number of additional results returned by the organization search. */
    private static final int MAX_EXTRA_RESULTS = 100;

    /** Maximum length of the snippet which is displayed below a search result. */
    private static final int MAX_SNIPPET_LENGTH = 100;

    /** The columns which are queried for the additional organization search. */
    private static final String[] ORGANIZATION_COLUMNS = new String[]{
            Data.CONTACT_ID,
            Organization.COMPANY,
            Organization.TITLE,
            Organization.DEPARTMENT,
    };

    /** The columns which are needed to display a search result. */
    private static final String[] CONTACT_COLUMNS = new String[]{
            Contacts._ID,
            Contacts.LOOKUP_KEY,
            Contacts.DISPLAY_NAME_PRIMARY,
            Contacts.DISPLAY_NAME_ALTERNATIVE,
            Contacts.PHOTO_ID,
            Contacts.PHOTO_THUMBNAIL_URI,
            Contacts.STARRED,
            Contacts.CONTACT_PRESENCE,
            Contacts.CONTACT_STATUS,
            Contacts.PHONETIC_NAME,
    };

    private boolean mLoadFavorites;

    private String[] mProjection;

    private boolean mSearchOrganizations;
    private String mSearchQuery;

    public FavoritesAndContactsLoader(Context context) {
        super(context);
    }

    /** Whether to load favorites and merge results in before any other results. */
    public void setLoadFavorites(boolean flag) {
        mLoadFavorites = flag;
    }

    /**
     * Enables the additional search over the organization (work) fields. The regular provider
     * search is not affected by this.
     */
    public void setOrganizationSearch(boolean enabled, String query) {
        mSearchOrganizations = enabled;
        mSearchQuery = query;
    }

    public void setProjection(String[] projection) {
        super.setProjection(projection);
        mProjection = projection;
    }

    @Override
    public Cursor loadInBackground() {
        List<Cursor> cursors = Lists.newArrayList();
        if (mLoadFavorites) {
            cursors.add(loadFavoritesContacts());
        }
        final Cursor contactsCursor = loadContacts();
        cursors.add(contactsCursor);
        final Cursor organizationCursor = loadOrganizationMatches(contactsCursor);
        if (organizationCursor != null) {
            cursors.add(organizationCursor);
        }
        return new MergeCursor(cursors.toArray(new Cursor[cursors.size()])) {
            @Override
            public Bundle getExtras() {
                // Need to get the extras from the contacts cursor.
                return contactsCursor == null ? new Bundle() : contactsCursor.getExtras();
            }
        };
    }

    private Cursor loadContacts() {
        // ContactsCursor.loadInBackground() can return null; MergeCursor
        // correctly handles null cursors.
        try {
            return reindexBySurname(super.loadInBackground());

        } catch (NullPointerException | SQLiteException | SecurityException e) {
            // Ignore NPEs, SQLiteExceptions and SecurityExceptions thrown by providers
        }
        return null;
    }

    /**
     * Re-orders the alphabetical section of the list by Chinese surname pronunciation and
     * republishes the address-book index in the cursor's extras.
     *
     * <p>Applied only to the contacts cursor, never to the favorites one. Favorites are merged in
     * ahead of the alphabetical rows and are deliberately not sorted by name, and
     * {@code ContactEntryListAdapter.setFavoritesSectionHeader()} shifts every indexer position by
     * the favorites count afterwards. Re-sorting them here would scramble that.
     *
     * <p>Runs on the Loader's background thread, so the resolving and sorting never touch the main
     * thread. Failures degrade to the provider's own ordering.
     */
    private Cursor reindexBySurname(Cursor cursor) {
        if (cursor == null || !SurnameIndexFeature.isSurnameIndexEnabled(getContext())) {
            return cursor;
        }
        final int nameColumn = findDisplayNameColumn(cursor);
        if (nameColumn < 0) {
            return cursor;
        }
        return SurnameSortedCursor.wrap(getContext(), cursor, nameColumn);
    }

    /**
     * Locates whichever display-name column the current projection carries. The adapter swaps
     * between {@link Contacts#DISPLAY_NAME_PRIMARY} and {@link Contacts#DISPLAY_NAME_ALTERNATIVE}
     * depending on the user's name-display preference, and we must index the one actually being
     * shown and sorted on.
     */
    private static int findDisplayNameColumn(Cursor cursor) {
        final int primary = cursor.getColumnIndex(Contacts.DISPLAY_NAME_PRIMARY);
        if (primary >= 0) {
            return primary;
        }
        return cursor.getColumnIndex(Contacts.DISPLAY_NAME_ALTERNATIVE);
    }

    private Cursor loadFavoritesContacts() {
        final StringBuilder selection = new StringBuilder();
        selection.append(Contacts.STARRED + "=?");
        final ContactListFilter filter =
                ContactListFilterController.getInstance(getContext()).getFilter();
        if (filter != null && filter.filterType == ContactListFilter.FILTER_TYPE_CUSTOM) {
            selection.append(" AND ").append(Contacts.IN_VISIBLE_GROUP + "=1");
        }
        try {
            return getContext().getContentResolver().query(
                    Contacts.CONTENT_URI, mProjection, selection.toString(), new String[]{"1"},
                    getSortOrder());
        } catch (RuntimeException e) {
            // Ignore errors thrown by providers: the regular contacts are still valid.
        }
        return null;
    }

    /**
     * Searches for contacts which have an organization entry containing the search query, even
     * when the query does not match the beginning of the organization name. Returns null when
     * there is nothing to add.
     */
    private Cursor loadOrganizationMatches(Cursor contactsCursor) {
        if (!mSearchOrganizations || TextUtils.isEmpty(mSearchQuery)) {
            return null;
        }
        final String query = mSearchQuery.trim();
        if (TextUtils.isEmpty(query)) {
            return null;
        }

        // The provider only snippets the results of the provider search. When the snippets are
        // deferred, the list creates the snippet itself out of the value; otherwise the matched
        // part has to be marked with the snippet markers.
        final boolean deferredSnippeting = contactsCursor != null
                && contactsCursor.getExtras().getBoolean(
                        ContactsContract.DEFERRED_SNIPPETING, false);

        final Set<Long> knownContactIds = new HashSet<Long>();
        if (contactsCursor != null) {
            final int idColumn = contactsCursor.getColumnIndex(Contacts._ID);
            if (idColumn != -1) {
                for (int i = 0; i < contactsCursor.getCount(); i++) {
                    if (!contactsCursor.moveToPosition(i)) {
                        break;
                    }
                    knownContactIds.add(contactsCursor.getLong(idColumn));
                }
            }
        }

        // Maps the id of a matching contact to the text which should be shown as its snippet.
        final Map<Long, String> matches = new LinkedHashMap<Long, String>();
        Cursor organizationCursor = null;
        try {
            organizationCursor = getContext().getContentResolver().query(
                    Data.CONTENT_URI,
                    ORGANIZATION_COLUMNS,
                    Data.MIMETYPE + "=? AND ("
                            + Organization.COMPANY + " LIKE ? ESCAPE '\\'"
                            + " OR " + Organization.TITLE + " LIKE ? ESCAPE '\\'"
                            + " OR " + Organization.DEPARTMENT + " LIKE ? ESCAPE '\\')",
                    new String[]{
                            Organization.CONTENT_ITEM_TYPE,
                            '%' + escapeLikeValue(query) + '%',
                            '%' + escapeLikeValue(query) + '%',
                            '%' + escapeLikeValue(query) + '%',
                    },
                    null);
            while (organizationCursor != null && organizationCursor.moveToNext()) {
                if (matches.size() >= MAX_EXTRA_RESULTS) {
                    break;
                }
                final long contactId = organizationCursor.getLong(0);
                if (contactId == 0 || knownContactIds.contains(contactId)
                        || matches.containsKey(contactId)) {
                    continue;
                }
                final String snippet = buildSnippet(
                        organizationCursor.getString(1),
                        organizationCursor.getString(2),
                        organizationCursor.getString(3),
                        query, deferredSnippeting);
                if (TextUtils.isEmpty(snippet)) {
                    continue;
                }
                matches.put(contactId, snippet);
            }
        } catch (RuntimeException e) {
            // Ignore errors thrown by the provider: the regular search results are still valid.
            return null;
        } finally {
            if (organizationCursor != null) {
                organizationCursor.close();
            }
        }

        if (matches.isEmpty()) {
            return null;
        }

        return loadExtraContacts(matches);
    }

    /**
     * Loads the data of the contacts found by {@link #loadOrganizationMatches(Cursor)} and returns
     * them as a cursor which uses the same projection as the regular search results.
     */
    private Cursor loadExtraContacts(Map<Long, String> matches) {
        final String[] projection = getProjection();
        if (projection == null) {
            return null;
        }
        final MatrixCursor result = new MatrixCursor(projection);
        final StringBuilder selection = new StringBuilder(Contacts._ID).append(" IN (");
        final String[] selectionArgs = new String[matches.size()];
        int i = 0;
        for (Long contactId : matches.keySet()) {
            if (i > 0) {
                selection.append(',');
            }
            selection.append('?');
            selectionArgs[i++] = String.valueOf(contactId);
        }
        selection.append(')');

        Cursor contactCursor = null;
        try {
            contactCursor = getContext().getContentResolver().query(
                    Contacts.CONTENT_URI, CONTACT_COLUMNS, selection.toString(), selectionArgs,
                    getSortOrder());
            while (contactCursor != null && contactCursor.moveToNext()) {
                final long contactId = contactCursor.getLong(
                        contactCursor.getColumnIndex(Contacts._ID));
                final String snippet = matches.get(contactId);
                final Object[] values = new Object[projection.length];
                for (int column = 0; column < projection.length; column++) {
                    values[column] = getValue(projection[column], contactCursor, snippet);
                }
                result.addRow(values);
            }
        } catch (NullPointerException | SQLiteException | SecurityException e) {
            return null;
        } finally {
            if (contactCursor != null) {
                contactCursor.close();
            }
        }

        if (result.getCount() == 0) {
            result.close();
            return null;
        }
        return result;
    }

    /**
     * Returns the value of the given column of the search projection for the given contact.
     */
    private Object getValue(String column, Cursor contactCursor, String snippet) {
        if (SearchSnippets.SNIPPET.equals(column)) {
            return snippet;
        }
        final int index = contactCursor.getColumnIndex(column);
        if (index == -1) {
            return isNumericColumn(column) ? (Object) Long.valueOf(0) : null;
        }
        switch (contactCursor.getType(index)) {
            case Cursor.FIELD_TYPE_INTEGER:
                return contactCursor.getLong(index);
            case Cursor.FIELD_TYPE_BLOB:
                return contactCursor.getBlob(index);
            case Cursor.FIELD_TYPE_STRING:
                return contactCursor.getString(index);
            case Cursor.FIELD_TYPE_FLOAT:
                return contactCursor.getFloat(index);
            default:
                // Null values of numeric columns have to be replaced: the list expects a number
                // for them.
                return isNumericColumn(column) ? (Object) Long.valueOf(0) : null;
        }
    }

    private static boolean isNumericColumn(String column) {
        return Contacts._ID.equals(column)
                || Contacts.PHOTO_ID.equals(column)
                || Contacts.CONTACT_PRESENCE.equals(column)
                || Contacts.STARRED.equals(column);
    }

    /**
     * Builds the text which is displayed below a search result, together with the part which
     * matched the query.
     */
    private static String buildSnippet(
            String company, String title, String department, String query,
            boolean deferredSnippeting) {
        final String[] values = new String[] {company, title, department};
        for (String value : values) {
            if (TextUtils.isEmpty(value)) {
                continue;
            }
            final int match = indexOfIgnoreCase(value, query);
            if (match == -1) {
                continue;
            }
            // Show at most MAX_SNIPPET_LENGTH characters around the matched part.
            final int start = Math.max(0, Math.min(
                    match - (MAX_SNIPPET_LENGTH - query.length()) / 2,
                    value.length() - MAX_SNIPPET_LENGTH));
            final int end = Math.min(value.length(), start + MAX_SNIPPET_LENGTH);
            final StringBuilder snippet = new StringBuilder();
            if (start > 0) {
                snippet.append('\u2026');
            }
            snippet.append(value, start, end);
            if (end < value.length()) {
                snippet.append('\u2026');
            }
            if (deferredSnippeting) {
                // The list looks for the query inside the text and highlights it, so the text
                // has to be returned unchanged.
                return snippet.toString().replace('\n', ' ');
            }
            // Mark the matched part, like the provider does for its own snippets.
            final int prefixLength = start > 0 ? 1 : 0;
            final int markedStart = Math.max(0, prefixLength + match - start);
            final int markedEnd = Math.min(markedStart + query.length(), snippet.length());
            snippet.insert(markedEnd, DefaultContactListAdapter.SNIPPET_END_MATCH);
            snippet.insert(markedStart, DefaultContactListAdapter.SNIPPET_START_MATCH);
            return snippet.toString().replace('\n', ' ');
        }
        return null;
    }

    private static int indexOfIgnoreCase(String value, String query) {
        return value.toLowerCase().indexOf(query.toLowerCase());
    }

    /** Escapes the special characters of a SQL LIKE pattern. */
    private static String escapeLikeValue(String value) {
        final StringBuilder builder = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            final char c = value.charAt(i);
            if (c == '\\' || c == '%' || c == '_') {
                builder.append('\\');
            }
            builder.append(c);
        }
        return builder.toString();
    }
}
