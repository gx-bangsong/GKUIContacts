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

import android.content.Context;
import android.database.Cursor;
import android.database.CursorWrapper;
import android.os.Bundle;
import android.provider.ContactsContract.Contacts;
import android.text.TextUtils;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;

/**
 * A {@link CursorWrapper} that re-orders contact rows by Chinese surname pronunciation and
 * republishes the address-book index in {@link #getExtras()}.
 *
 * <p>{@code ContactsProvider} computes {@code EXTRA_ADDRESS_BOOK_INDEX_TITLES}/{@code _COUNTS} with
 * ICU's {@code AlphabeticIndex}, which files 单 under D and 万俟 under W. The provider lives in a
 * system APK we cannot change, so we re-do the work here and hand back a cursor that looks exactly
 * like the one the adapter already expects — {@code ContactEntryListAdapter.updateIndexer()} needs
 * no modification at all.
 *
 * <p>Follows the same shape as {@code GroupMembersFragment.FilterCursorWrapper}, which likewise
 * remaps positions and rewrites the index extras.
 *
 * <p>Construct on a background thread ({@code Loader.loadInBackground()}); all the resolving
 * happens in {@link #wrap}.
 */
public final class SurnameSortedCursor extends CursorWrapper {

    private static final String TAG = "SurnameSortedCursor";

    /** Above this row count we skip re-indexing rather than risk a slow load. */
    private static final int MAX_ROWS = 20000;

    private final int[] mOrder;
    private final Bundle mExtras;
    private final SurnameIndexBuilder.Result mIndex;
    private int mPosition = -1;

    private SurnameSortedCursor(Cursor delegate, SurnameIndexBuilder.Result index, Bundle extras) {
        super(delegate);
        mOrder = index.order;
        mIndex = index;
        mExtras = extras;
        // The wrapper starts before the first row, like a freshly queried cursor.
        super.moveToPosition(-1);
    }

    /**
     * Re-indexes {@code cursor} by surname and returns a wrapper, or {@code cursor} unchanged when
     * re-indexing is not applicable or not worth it.
     *
     * <p>Never throws: any failure logs and returns the original cursor, because a wrong index bar
     * is a far smaller problem than an empty contact list.
     *
     * @param context             any context, used to obtain the resolver
     * @param cursor              the contacts cursor; may be null
     * @param displayNameColumn   index of the display-name column to read
     */
    public static Cursor wrap(Context context, Cursor cursor, int displayNameColumn) {
        if (cursor == null || cursor.isClosed()) {
            return cursor;
        }
        final int count = cursor.getCount();
        if (count == 0) {
            return cursor;
        }
        if (count > MAX_ROWS) {
            Log.i(TAG, "skipping surname re-index for " + count + " rows (limit " + MAX_ROWS + ")");
            return cursor;
        }
        if (displayNameColumn < 0 || displayNameColumn >= cursor.getColumnCount()) {
            Log.w(TAG, "display name column " + displayNameColumn + " out of range");
            return cursor;
        }

        try {
            final long startNanos = System.nanoTime();

            final List<String> names = new ArrayList<>(count);
            final int restore = cursor.getPosition();
            for (int i = 0; i < count; i++) {
                if (!cursor.moveToPosition(i)) {
                    Log.w(TAG, "could not move to row " + i + "; abandoning re-index");
                    cursor.moveToPosition(restore);
                    return cursor;
                }
                names.add(cursor.getString(displayNameColumn));
            }
            cursor.moveToPosition(restore);

            final SurnamePinyinResolver resolver = SurnameTableLoader.getResolver(context);
            final SurnameIndexBuilder.Result index = SurnameIndexBuilder.build(names, resolver);

            final Bundle extras = buildExtras(cursor.getExtras(), index);
            final Cursor wrapped = new SurnameSortedCursor(cursor, index, extras);

            if (Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(TAG, "re-indexed " + count + " rows into " + index.sections.length
                        + " sections in " + ((System.nanoTime() - startNanos) / 1000000L) + "ms"
                        + (index.isIdentityOrder ? " (order unchanged)" : ""));
            }
            return wrapped;
        } catch (RuntimeException e) {
            Log.e(TAG, "surname re-index failed; falling back to provider order", e);
            return cursor;
        }
    }

    /**
     * Extra carrying the surname hints for the fast-scroll bubble: one entry per address-book
     * index section, holding up to {@link SurnameIndexBuilder#MAX_SURNAME_HINTS} surnames joined
     * by {@link #SURNAME_HINT_SEPARATOR}, or an empty string for sections with no Chinese
     * surnames.
     *
     * <p>Travels in the cursor extras rather than on this object because the adapter sees a
     * {@code MergeCursor} wrapping this one, and {@code MergeCursor.getExtras()} is the only
     * channel that survives the merge.
     */
    public static final String EXTRA_SURNAME_HINTS =
            "com.android.contacts.extra.SURNAME_HINTS";

    /** Separator between surnames within one {@link #EXTRA_SURNAME_HINTS} entry. */
    public static final String SURNAME_HINT_SEPARATOR = "\u00b7";

    private static Bundle buildExtras(Bundle original, SurnameIndexBuilder.Result index) {
        final Bundle extras = original == null || original == Bundle.EMPTY
                ? new Bundle()
                : new Bundle(original);
        extras.putStringArray(Contacts.EXTRA_ADDRESS_BOOK_INDEX_TITLES, index.sections);
        extras.putIntArray(Contacts.EXTRA_ADDRESS_BOOK_INDEX_COUNTS, index.counts);

        final String[] hints = new String[index.sections.length];
        for (int i = 0; i < hints.length; i++) {
            hints[i] = index.getSurnameHintText(i, SURNAME_HINT_SEPARATOR);
        }
        extras.putStringArray(EXTRA_SURNAME_HINTS, hints);
        return extras;
    }

    /** The computed index, for the fast-scroll bar's surname hints. */
    public SurnameIndexBuilder.Result getSurnameIndex() {
        return mIndex;
    }

    // =================================================================================
    // Position remapping
    // =================================================================================

    private boolean moveToMapped(int position) {
        if (position < 0) {
            mPosition = -1;
            super.moveToPosition(-1);
            return false;
        }
        if (position >= mOrder.length) {
            mPosition = mOrder.length;
            super.moveToPosition(mOrder.length);
            return false;
        }
        mPosition = position;
        return super.moveToPosition(mOrder[position]);
    }

    @Override
    public boolean moveToPosition(int position) {
        return moveToMapped(position);
    }

    @Override
    public boolean move(int offset) {
        return moveToMapped(mPosition + offset);
    }

    @Override
    public boolean moveToFirst() {
        return moveToMapped(0);
    }

    @Override
    public boolean moveToLast() {
        return moveToMapped(mOrder.length - 1);
    }

    @Override
    public boolean moveToNext() {
        return moveToMapped(mPosition + 1);
    }

    @Override
    public boolean moveToPrevious() {
        return moveToMapped(mPosition - 1);
    }

    @Override
    public int getPosition() {
        return mPosition;
    }

    @Override
    public int getCount() {
        return mOrder.length;
    }

    @Override
    public boolean isBeforeFirst() {
        return mOrder.length == 0 || mPosition < 0;
    }

    @Override
    public boolean isAfterLast() {
        return mOrder.length == 0 || mPosition >= mOrder.length;
    }

    @Override
    public boolean isFirst() {
        return mPosition == 0 && mOrder.length > 0;
    }

    @Override
    public boolean isLast() {
        return mOrder.length > 0 && mPosition == mOrder.length - 1;
    }

    @Override
    public Bundle getExtras() {
        return mExtras;
    }

    @Override
    public String toString() {
        return "SurnameSortedCursor{rows=" + mOrder.length
                + ", sections=" + TextUtils.join(",", mIndex.sections) + "}";
    }
}
