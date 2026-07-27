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

import android.content.Context;
import android.content.res.Resources;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import com.android.contacts.R;

/**
 * The A-Z fast-scroll index bar down the right edge of the contact list.
 *
 * <p>Replaces the framework's built-in {@code ListView} fast scroller, whose overlay cannot be
 * restyled or made to show anything other than the section label. We need the bubble to show the
 * Chinese surnames filed under a letter, which is the whole point of the surname index, so the bar
 * is drawn here instead.
 *
 * <p>Per design decision D1 (option C) the bar itself lists plain A-Z letters: 27-ish entries fit
 * on screen in any orientation and give a comfortable touch target, whereas one entry per distinct
 * surname would need aggressive folding on a real address book. The Chinese surnames surface in the
 * bubble instead, so dragging to S reveals "单·沈·石" and the user can see that 单 has been filed
 * by its surname reading.
 *
 * <p>The bubble is drawn by {@link SurnameIndexBubbleView}, a sibling view, so it can extend past
 * this bar's bounds without needing a window.
 */
public class SurnameIndexBarView extends View {

    /** Notified as the user drags along the bar. */
    public interface OnSectionSelectedListener {
        /**
         * @param sectionIndex index into the array last passed to {@link #setSections}
         * @param fromTouch    true while the user is dragging, false for programmatic updates
         */
        void onSectionSelected(int sectionIndex, boolean fromTouch);

        /** The user has lifted their finger or the gesture was cancelled. */
        void onSectionSelectionEnded();
    }

    private final Paint mTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Rect mTextBounds = new Rect();

    private String[] mSections = new String[0];
    /** Parallel to {@link #mSections}; entry i is the bubble's second line, possibly empty. */
    private String[] mSurnameHints = new String[0];

    /** Index into {@link #mSections} the finger is on, or -1. */
    private int mActiveSection = -1;
    /** Index last reported, so we only fire callbacks and haptics when it actually changes. */
    private int mReportedSection = -1;

    /**
     * When the sections do not all fit, only every (mStride)th one is drawn. Touch handling still
     * maps to the full list, so hidden sections remain reachable; they simply have no label.
     */
    private int mStride = 1;

    private int mNormalColor;
    private int mActiveColor;
    private float mItemHeight;
    private float mMinItemHeight;
    private int mVerticalPadding;

    private OnSectionSelectedListener mListener;
    private SurnameIndexBubbleView mBubble;

    public SurnameIndexBarView(Context context) {
        this(context, null);
    }

    public SurnameIndexBarView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public SurnameIndexBarView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init(context);
    }

    private void init(Context context) {
        final Resources res = context.getResources();
        mNormalColor = res.getColor(R.color.surname_index_bar_text, context.getTheme());
        mActiveColor = res.getColor(R.color.surname_index_bar_text_active, context.getTheme());
        mMinItemHeight = res.getDimension(R.dimen.surname_index_bar_min_item_height);
        mVerticalPadding = res.getDimensionPixelSize(R.dimen.surname_index_bar_vertical_padding);

        mTextPaint.setTextSize(res.getDimension(R.dimen.surname_index_bar_text_size));
        mTextPaint.setTextAlign(Paint.Align.CENTER);
        mTextPaint.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        mTextPaint.setColor(mNormalColor);

        setContentDescription(context.getString(R.string.surname_index_bar_description));
        // The bar is decorative until it has sections; visibility is driven by setSections().
        setVisibility(GONE);
    }

    public void setOnSectionSelectedListener(OnSectionSelectedListener listener) {
        mListener = listener;
    }

    /** Associates the bubble shown while dragging. May be null. */
    public void setBubbleView(SurnameIndexBubbleView bubble) {
        mBubble = bubble;
    }

    /**
     * Replaces the section labels.
     *
     * @param sections     one label per address-book index section, e.g. {@code ["A","C","S","#"]}
     * @param surnameHints parallel array of pre-joined surname hints, or null for none
     */
    public void setSections(String[] sections, String[] surnameHints) {
        mSections = sections == null ? new String[0] : sections;
        if (surnameHints != null && surnameHints.length == mSections.length) {
            mSurnameHints = surnameHints;
        } else {
            mSurnameHints = new String[mSections.length];
        }
        mActiveSection = -1;
        mReportedSection = -1;
        // A bar with nothing to jump to is noise; hide it rather than show a stub.
        setVisibility(mSections.length > 1 ? VISIBLE : GONE);
        requestLayout();
        invalidate();
    }

    /** Highlights a section without firing callbacks. Used to follow the list as it scrolls. */
    public void setActiveSection(int sectionIndex) {
        if (mActiveSection != sectionIndex) {
            mActiveSection = sectionIndex;
            invalidate();
        }
    }

    public int getSectionCount() {
        return mSections.length;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        final int width = resolveSize(
                getResources().getDimensionPixelSize(R.dimen.surname_index_bar_width),
                widthMeasureSpec);
        setMeasuredDimension(width, MeasureSpec.getSize(heightMeasureSpec));
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        recomputeItemHeight();
    }

    /**
     * Works out the per-entry height and, when the sections cannot all be shown at a legible size,
     * how many to skip when drawing.
     *
     * <p>Landscape on a short screen is the case that forces this: there simply is not room for 27
     * entries at a readable size, so every other label is dropped. Touch still maps across the full
     * range, so the hidden sections stay reachable.
     */
    private void recomputeItemHeight() {
        final int usable = getHeight() - mVerticalPadding * 2;
        if (mSections.length == 0 || usable <= 0) {
            mItemHeight = 0;
            mStride = 1;
            return;
        }
        mItemHeight = (float) usable / mSections.length;
        if (mItemHeight >= mMinItemHeight) {
            mStride = 1;
        } else {
            // Drop labels until what remains is legible.
            mStride = (int) Math.ceil(mMinItemHeight / Math.max(mItemHeight, 1f));
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (mSections.length == 0 || mItemHeight <= 0) {
            return;
        }
        final float centerX = getWidth() / 2f;
        for (int i = 0; i < mSections.length; i++) {
            final boolean active = i == mActiveSection;
            // Always draw the active entry, even when stride would normally skip it, so the
            // highlight never disappears mid-drag.
            if (!active && mStride > 1 && i % mStride != 0) {
                continue;
            }
            final String label = mSections[i];
            if (TextUtils.isEmpty(label)) {
                // The favorites section carries an empty title; nothing to draw for it.
                continue;
            }
            mTextPaint.setColor(active ? mActiveColor : mNormalColor);
            mTextPaint.getTextBounds(label, 0, label.length(), mTextBounds);
            final float centerY = mVerticalPadding + mItemHeight * i + mItemHeight / 2f;
            canvas.drawText(label, centerX, centerY + mTextBounds.height() / 2f, mTextPaint);
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (mSections.length == 0) {
            return false;
        }
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_MOVE:
                // Keep the gesture even if the finger strays sideways off the bar; users drift.
                getParent().requestDisallowInterceptTouchEvent(true);
                updateFromTouch(event.getY());
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                getParent().requestDisallowInterceptTouchEvent(false);
                endTouch();
                return true;
            default:
                return super.onTouchEvent(event);
        }
    }

    private void updateFromTouch(float y) {
        final int section = sectionAt(y);
        if (section == mReportedSection) {
            return;
        }
        mReportedSection = section;
        mActiveSection = section;

        // A tick per section crossed is the tactile cue that makes a fast scroller usable
        // without looking at it. No IGNORE_VIEW/GLOBAL_SETTING flag: if the user has turned
        // touch feedback off, respect that.
        performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);

        showBubble(section);
        invalidate();

        if (mListener != null) {
            mListener.onSectionSelected(section, /* fromTouch */ true);
        }
    }

    private void endTouch() {
        mReportedSection = -1;
        if (mBubble != null) {
            mBubble.hide();
        }
        if (mListener != null) {
            mListener.onSectionSelectionEnded();
        }
        invalidate();
    }

    private void showBubble(int section) {
        if (mBubble == null || section < 0 || section >= mSections.length) {
            return;
        }
        final String hint = section < mSurnameHints.length ? mSurnameHints[section] : null;
        final float centerY = mVerticalPadding + mItemHeight * section + mItemHeight / 2f;
        mBubble.show(mSections[section], hint, getY() + centerY);
    }

    /** Maps a y coordinate to a section index, clamped to the ends. */
    private int sectionAt(float y) {
        if (mItemHeight <= 0) {
            return 0;
        }
        final int index = (int) ((y - mVerticalPadding) / mItemHeight);
        return Math.max(0, Math.min(mSections.length - 1, index));
    }

    // =================================================================================
    // Accessibility
    //
    // The bar is a single view drawing many targets, so it is opaque to TalkBack by default.
    // Exposing it as a slider lets a screen-reader user move through the sections with the
    // standard scroll-forward/backward gestures instead of trying to hit invisible letters.
    // =================================================================================

    @Override
    public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfo(info);
        info.setClassName(SurnameIndexBarView.class.getName());
        if (mSections.length == 0) {
            return;
        }
        info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD);
        info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD);
        final int current = Math.max(mActiveSection, 0);
        info.setContentDescription(getContext().getString(
                R.string.surname_index_bar_position_description,
                mSections[current], current + 1, mSections.length));
    }

    @Override
    public boolean performAccessibilityAction(int action, Bundle arguments) {
        switch (action) {
            case AccessibilityNodeInfo.ACTION_SCROLL_FORWARD:
                return stepSection(1);
            case AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD:
                return stepSection(-1);
            default:
                return super.performAccessibilityAction(action, arguments);
        }
    }

    private boolean stepSection(int delta) {
        if (mSections.length == 0) {
            return false;
        }
        final int next = Math.max(0, Math.min(mSections.length - 1,
                Math.max(mActiveSection, 0) + delta));
        if (next == mActiveSection) {
            return false;
        }
        mActiveSection = next;
        invalidate();
        if (mListener != null) {
            mListener.onSectionSelected(next, /* fromTouch */ true);
            mListener.onSectionSelectionEnded();
        }
        announceForAccessibility(mSections[next]);
        sendAccessibilityEvent(AccessibilityEvent.TYPE_VIEW_SCROLLED);
        return true;
    }
}
