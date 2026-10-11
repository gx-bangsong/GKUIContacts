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

package com.android.contacts.widget;

import android.database.Cursor;
import android.os.Bundle;
import android.view.View;
import android.widget.AbsListView;
import android.widget.ListView;
import android.widget.SectionIndexer;

import com.android.contacts.R;
import com.android.contacts.pinyin.SurnameSortedCursor;

/**
 * Connects {@link SurnameIndexBarView} to the contact {@link ListView}.
 *
 * <p>Sections come from the adapter's {@link SectionIndexer}, which
 * {@code ContactEntryListAdapter} already builds from the cursor extras — the same extras
 * {@code SurnameSortedCursor} rewrites with surname-correct sections. The surname hints for the
 * bubble ride along in the same Bundle.
 *
 * <p>Owned by the fragment; call {@link #onSectionsChanged} after each load and
 * {@link #setEnabled} when entering or leaving search mode.
 */
public final class SurnameIndexBarController implements
        SurnameIndexBarView.OnSectionSelectedListener, AbsListView.OnScrollListener {

    private final ListView mListView;
    private final SurnameIndexBarView mBar;
    private final SurnameIndexBubbleView mBubble;

    private SectionIndexer mIndexer;
    private String[] mSurnameHints;
    private boolean mEnabled = true;
    /** True while the user is dragging the bar, so scroll events do not fight the drag. */
    private boolean mDragging;

    /**
     * @param root view containing {@code @id/surname_index_bar}
     */
    public SurnameIndexBarController(View root, ListView listView) {
        mListView = listView;
        mBar = root.findViewById(R.id.surname_index_bar);
        mBubble = root.findViewById(R.id.surname_index_bubble);

        if (mBar != null) {
            mBar.setBubbleView(mBubble);
            mBar.setOnSectionSelectedListener(this);
        }
        // Deliberately not calling listView.setOnScrollListener() here: PinnedHeaderListView
        // holds a single listener and ContactEntryListFragment re-registers itself from
        // configurePhotoLoader(), which would silently unhook us. The fragment forwards its
        // own callbacks to onScroll() instead.
    }

    /** True when the layout actually contains the bar. */
    public boolean isAttached() {
        return mBar != null;
    }

    /**
     * Hides the bar without forgetting its data — used for search mode, matching the framework
     * fast scroller's behaviour of disappearing while searching.
     */
    public void setEnabled(boolean enabled) {
        if (mEnabled == enabled) {
            return;
        }
        mEnabled = enabled;
        applyVisibility();
    }

    /**
     * Re-reads the section list from the adapter after a load.
     *
     * @param indexer the adapter, which implements {@link SectionIndexer}; may be null
     * @param cursor  the loaded cursor, for the surname hints in its extras; may be null
     */
    public void onSectionsChanged(SectionIndexer indexer, Cursor cursor) {
        if (mBar == null) {
            return;
        }
        mIndexer = indexer;
        mSurnameHints = extractSurnameHints(cursor);

        final Object[] sections = indexer == null ? null : indexer.getSections();
        if (sections == null || sections.length == 0) {
            mBar.setSections(new String[0], null);
            applyVisibility();
            return;
        }

        final String[] labels = new String[sections.length];
        for (int i = 0; i < sections.length; i++) {
            labels[i] = sections[i] == null ? "" : sections[i].toString();
        }

        // The hints are computed before the favorites header is prepended to the indexer, so a
        // length mismatch means they no longer line up; drop them rather than mislabel.
        final String[] hints =
                mSurnameHints != null && mSurnameHints.length == labels.length
                        ? mSurnameHints
                        : alignHints(mSurnameHints, labels);

        mBar.setSections(labels, hints);
        applyVisibility();
    }

    /**
     * Realigns hints against sections when {@code ContactsSectionIndexer.setFavoritesHeader()} has
     * inserted a leading section for the favorites block. That shifts every section by one but
     * leaves the hint array as it was.
     */
    private static String[] alignHints(String[] hints, String[] labels) {
        if (hints == null || labels.length == 0) {
            return null;
        }
        if (hints.length == labels.length - 1) {
            final String[] shifted = new String[labels.length];
            shifted[0] = "";
            System.arraycopy(hints, 0, shifted, 1, hints.length);
            return shifted;
        }
        return null;
    }

    private static String[] extractSurnameHints(Cursor cursor) {
        if (cursor == null || cursor.isClosed()) {
            return null;
        }
        final Bundle extras = cursor.getExtras();
        if (extras == null) {
            return null;
        }
        return extras.getStringArray(SurnameSortedCursor.EXTRA_SURNAME_HINTS);
    }

    private void applyVisibility() {
        if (mBar == null) {
            return;
        }
        final boolean show = mEnabled && mBar.getSectionCount() > 1;
        mBar.setVisibility(show ? View.VISIBLE : View.GONE);
        if (!show && mBubble != null) {
            mBubble.setVisibility(View.GONE);
        }
    }

    // =================================================================================
    // Bar -> list
    // =================================================================================

    @Override
    public void onSectionSelected(int sectionIndex, boolean fromTouch) {
        if (mIndexer == null || mListView == null) {
            return;
        }
        mDragging = fromTouch;
        final int position = mIndexer.getPositionForSection(sectionIndex);
        if (position < 0) {
            return;
        }
        // Section positions are adapter-relative; the list also carries the search/profile
        // header views, so shift by those to land on the intended row.
        mListView.setSelectionFromTop(position + mListView.getHeaderViewsCount(), 0);
    }

    @Override
    public void onSectionSelectionEnded() {
        mDragging = false;
    }

    // =================================================================================
    // List -> bar
    // =================================================================================

    @Override
    public void onScrollStateChanged(AbsListView view, int scrollState) {
        // Nothing to do; the fragment owns scroll-state handling.
    }

    @Override
    public void onScroll(AbsListView view, int firstVisibleItem, int visibleItemCount,
            int totalItemCount) {
        // While dragging, the bar is the source of truth; echoing the resulting scroll back
        // would make the highlight stutter.
        if (mBar == null || mIndexer == null || mDragging) {
            return;
        }
        final int adapterPosition = firstVisibleItem - mListView.getHeaderViewsCount();
        if (adapterPosition < 0) {
            return;
        }
        mBar.setActiveSection(mIndexer.getSectionForPosition(adapterPosition));
    }
}
