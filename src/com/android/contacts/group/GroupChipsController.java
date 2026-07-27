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

import android.app.LoaderManager;
import android.content.Context;
import android.content.Loader;
import android.database.Cursor;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import com.android.contacts.GroupListLoader;
import com.android.contacts.R;
import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;

import java.util.ArrayList;
import java.util.List;

/**
 * Drives the row of group (label) chips shown between the search bar and the contact list.
 *
 * <p>Reuses the existing {@link GroupListLoader} over {@code ContactsContract.Groups}, so no new
 * data model is introduced and the chips track group edits automatically — a {@code CursorLoader}
 * observes its URI, so creating, renaming or deleting a label refreshes the bar without any
 * explicit plumbing.
 *
 * <p>Selection state and the awkward edge cases around it live in {@link GroupChipFilter}, which is
 * pure Java and host-tested. This class is only the view binding.
 */
public final class GroupChipsController {

    /** Loader id for the group list. Negative to stay clear of the contact list's partitions,
     *  which are numbered from zero, and distinct from DIRECTORY_LOADER_ID (-1). */
    private static final int LOADER_GROUP_CHIPS = -100;

    /** Notified when the user picks a chip. */
    public interface OnGroupChipSelectedListener {
        /**
         * @param groupId the selected group, or {@link GroupChipFilter#ALL_GROUPS} for "All"
         */
        void onGroupChipSelected(long groupId);
    }

    private final Context mContext;
    private final View mBar;
    private final ChipGroup mChipGroup;
    /** Built from the ChipGroup's themed context so chips inherit the Material overlay. */
    private final LayoutInflater mInflater;
    private final OnGroupChipSelectedListener mListener;
    private final GroupChipFilter mFilter = new GroupChipFilter();

    /** Guards against the programmatic check in {@link #bindChips} looking like a user click. */
    private boolean mBinding;
    private boolean mGroupsLoaded;

    /**
     * @param root     the fragment's root view, containing {@code @id/group_chips_scroll}
     * @param listener notified on selection changes; must not be null
     */
    public GroupChipsController(Context context, View root,
            OnGroupChipSelectedListener listener) {
        mContext = context;
        mListener = listener;
        mBar = root.findViewById(R.id.group_chips_scroll);
        mChipGroup = root.findViewById(R.id.group_chips);
        mInflater = LayoutInflater.from(
                mChipGroup != null ? mChipGroup.getContext() : context);
        if (mBar != null) {
            mBar.setContentDescription(context.getString(R.string.group_chips_bar_description));
        }
    }

    /** True when the bar exists in this layout; false for layouts that do not include it. */
    public boolean isAttached() {
        return mBar != null && mChipGroup != null;
    }

    /** Starts (or restarts) loading the group list. Safe to call more than once. */
    public void initLoader(LoaderManager loaderManager) {
        if (!isAttached()) {
            return;
        }
        loaderManager.initLoader(LOADER_GROUP_CHIPS, null, mLoaderCallbacks);
    }

    /** The selected group id, or {@link GroupChipFilter#ALL_GROUPS}. */
    public long getSelectedGroupId() {
        return mFilter.getSelectedGroupId();
    }

    /** Whether a real group is selected and the contact list must therefore be restricted. */
    public boolean isFiltering() {
        return mFilter.isFiltering();
    }

    /**
     * Restores a selection saved across rotation. Applied to the chips once the groups arrive, so
     * this may be called before loading finishes.
     */
    public void restoreSelection(long groupId) {
        mFilter.restoreSelection(groupId);
        if (mGroupsLoaded) {
            bindChips();
        }
    }

    private final LoaderManager.LoaderCallbacks<Cursor> mLoaderCallbacks =
            new LoaderManager.LoaderCallbacks<Cursor>() {
        @Override
        public Loader<Cursor> onCreateLoader(int id, Bundle args) {
            return new GroupListLoader(mContext);
        }

        @Override
        public void onLoadFinished(Loader<Cursor> loader, Cursor data) {
            final List<GroupChipFilter.Group> groups = new ArrayList<>();
            if (data != null && !data.isClosed()) {
                for (int i = 0; i < data.getCount(); i++) {
                    if (!data.moveToPosition(i)) {
                        break;
                    }
                    final GroupListItem item = GroupUtil.getGroupListItem(data, i);
                    if (item == null || GroupUtil.isEmptyFFCGroup(item)) {
                        // Skip the empty "Friends"/"Family"/"Coworkers" system groups that
                        // Google accounts ship with; the drawer hides them too.
                        continue;
                    }
                    groups.add(new GroupChipFilter.Group(item.getGroupId(), item.getTitle()));
                }
            }

            final boolean selectionReset = mFilter.setGroups(groups);
            mGroupsLoaded = true;
            bindChips();

            if (selectionReset) {
                // The group that was selected no longer exists, so the list is showing a filter
                // the user can no longer see. Fall back to All and reload.
                mListener.onGroupChipSelected(GroupChipFilter.ALL_GROUPS);
            }
        }

        @Override
        public void onLoaderReset(Loader<Cursor> loader) {
            mFilter.setGroups(null);
            mGroupsLoaded = false;
            bindChips();
        }
    };

    /**
     * Rebuilds the chips from the current group list.
     *
     * <p>Views are recreated rather than diffed: a handful of chips is cheap, and this runs only
     * when the label set actually changes.
     */
    private void bindChips() {
        if (!isAttached()) {
            return;
        }
        if (!mFilter.isBarVisible()) {
            // No labels, so the bar would only ever contain "All". Hide it entirely.
            mBar.setVisibility(View.GONE);
            mChipGroup.removeAllViews();
            return;
        }

        mBinding = true;
        try {
            mChipGroup.removeAllViews();

            addChip(GroupChipFilter.ALL_GROUPS, mContext.getString(R.string.group_chip_all));
            for (GroupChipFilter.Group group : mFilter.getGroups()) {
                addChip(group.id, group.title);
            }
        } finally {
            mBinding = false;
        }
        mBar.setVisibility(View.VISIBLE);
    }

    private void addChip(long groupId, String title) {
        // Inflate from the ChipGroup's own context, not the activity's. android:theme on the
        // ChipGroup makes its context a ContextThemeWrapper carrying
        // ThemeOverlay.Contacts.GroupChips; a LayoutInflater built from the activity context
        // would bypass that overlay and Chip's ThemeEnforcement would then fail against the
        // plain AppCompat app theme. Passing the ChipGroup as parent only supplies LayoutParams,
        // it does not propagate the theme.
        final Chip chip = (Chip) mInflater.inflate(
                R.layout.group_chip_item, (ViewGroup) mChipGroup, false);
        chip.setText(title);
        chip.setTag(groupId);
        chip.setChecked(groupId == mFilter.getSelectedGroupId());
        chip.setOnClickListener(mChipClickListener);
        mChipGroup.addView(chip);
    }

    private final View.OnClickListener mChipClickListener = new View.OnClickListener() {
        @Override
        public void onClick(View view) {
            if (mBinding) {
                return;
            }
            final Chip chip = (Chip) view;
            final long groupId = (Long) chip.getTag();

            if (!chip.isChecked()) {
                // singleSelection + selectionRequired means tapping the active chip should not
                // clear it. Put the check mark back and do nothing.
                chip.setChecked(true);
                return;
            }
            if (mFilter.select(groupId)) {
                announceSelection(chip, groupId);
                mListener.onGroupChipSelected(groupId);
            }
        }
    };

    private void announceSelection(View source, long groupId) {
        final String announcement = groupId == GroupChipFilter.ALL_GROUPS
                ? mContext.getString(R.string.group_chip_all_selected_announcement)
                : mContext.getString(R.string.group_chip_selected_announcement,
                        mFilter.getSelectedTitle());
        source.announceForAccessibility(announcement);
    }
}
