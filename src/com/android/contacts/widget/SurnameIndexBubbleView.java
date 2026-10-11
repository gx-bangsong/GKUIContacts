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
import android.graphics.RectF;
import android.graphics.Typeface;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.view.View;

import com.android.contacts.R;

/**
 * The magnified preview shown while dragging {@link SurnameIndexBarView}.
 *
 * <p>Per design decision D1 it shows the section letter at 32sp and, when that letter contains
 * Chinese surnames, up to three of them at 10sp underneath. An ordinary Latin section shows the
 * letter alone with no second line, and the bubble shrinks to suit.
 *
 * <p>Drawn as a sibling of the bar inside the list's {@code FrameLayout} rather than in a
 * {@code PopupWindow}: a popup would need window tokens and careful lifecycle handling for what is
 * ultimately a rounded rectangle with two lines of text.
 */
public class SurnameIndexBubbleView extends View {

    private final Paint mBackgroundPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mLetterPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mHintPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Rect mTextBounds = new Rect();
    private final RectF mBubbleRect = new RectF();

    private String mLetter;
    private String mHint;

    private float mCornerRadius;
    private float mMinSize;
    private float mHorizontalPadding;
    private float mVerticalPadding;
    private float mLineSpacing;
    private int mMarginEnd;

    /** Vertical centre of the bubble, in the parent's coordinate space. */
    private float mAnchorY;

    public SurnameIndexBubbleView(Context context) {
        this(context, null);
    }

    public SurnameIndexBubbleView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public SurnameIndexBubbleView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init(context);
    }

    private void init(Context context) {
        final Resources res = context.getResources();

        mBackgroundPaint.setColor(
                res.getColor(R.color.surname_index_bubble_background, context.getTheme()));

        mLetterPaint.setColor(res.getColor(R.color.surname_index_bubble_text, context.getTheme()));
        mLetterPaint.setTextSize(res.getDimension(R.dimen.surname_index_bubble_letter_size));
        mLetterPaint.setTextAlign(Paint.Align.CENTER);
        mLetterPaint.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));

        mHintPaint.setColor(res.getColor(R.color.surname_index_bubble_hint, context.getTheme()));
        mHintPaint.setTextSize(res.getDimension(R.dimen.surname_index_bubble_hint_size));
        mHintPaint.setTextAlign(Paint.Align.CENTER);

        mCornerRadius = res.getDimension(R.dimen.surname_index_bubble_corner_radius);
        mMinSize = res.getDimension(R.dimen.surname_index_bubble_min_size);
        mHorizontalPadding = res.getDimension(R.dimen.surname_index_bubble_horizontal_padding);
        mVerticalPadding = res.getDimension(R.dimen.surname_index_bubble_vertical_padding);
        mLineSpacing = res.getDimension(R.dimen.surname_index_bubble_line_spacing);
        mMarginEnd = res.getDimensionPixelSize(R.dimen.surname_index_bubble_margin_end);

        setVisibility(GONE);
        // Purely a visual echo of the bar, which is already announced; announcing it again would
        // just repeat every letter the user drags across.
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    /**
     * Shows the bubble for a section.
     *
     * @param letter  the section label, e.g. {@code "S"}
     * @param hint    surnames in that section joined for display, or null/empty for none
     * @param anchorY vertical centre in the parent's coordinates
     */
    public void show(String letter, String hint, float anchorY) {
        mLetter = letter;
        mHint = hint;
        mAnchorY = anchorY;
        if (getVisibility() != VISIBLE) {
            setVisibility(VISIBLE);
            setAlpha(1f);
        }
        animate().cancel();
        invalidate();
    }

    /** Fades the bubble out. */
    public void hide() {
        if (getVisibility() != VISIBLE) {
            return;
        }
        animate()
                .alpha(0f)
                .setDuration(getResources().getInteger(
                        R.integer.surname_index_bubble_fade_duration))
                .withEndAction(new Runnable() {
                    @Override
                    public void run() {
                        setVisibility(GONE);
                        setAlpha(1f);
                    }
                })
                .start();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        // Measured against the content so a letter-only bubble stays square and compact while a
        // bubble with three surnames grows only as much as it must.
        float contentWidth = 0f;
        float contentHeight = 0f;

        if (!TextUtils.isEmpty(mLetter)) {
            mLetterPaint.getTextBounds(mLetter, 0, mLetter.length(), mTextBounds);
            contentWidth = mLetterPaint.measureText(mLetter);
            contentHeight = mTextBounds.height();
        }
        if (!TextUtils.isEmpty(mHint)) {
            contentWidth = Math.max(contentWidth, mHintPaint.measureText(mHint));
            mHintPaint.getTextBounds(mHint, 0, mHint.length(), mTextBounds);
            contentHeight += mLineSpacing + mTextBounds.height();
        }

        final int width = (int) Math.max(mMinSize, contentWidth + mHorizontalPadding * 2);
        final int height = (int) Math.max(mMinSize, contentHeight + mVerticalPadding * 2);
        setMeasuredDimension(width, height);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (TextUtils.isEmpty(mLetter)) {
            return;
        }
        mBubbleRect.set(0, 0, getWidth(), getHeight());
        canvas.drawRoundRect(mBubbleRect, mCornerRadius, mCornerRadius, mBackgroundPaint);

        final float centerX = getWidth() / 2f;
        mLetterPaint.getTextBounds(mLetter, 0, mLetter.length(), mTextBounds);
        final float letterHeight = mTextBounds.height();

        if (TextUtils.isEmpty(mHint)) {
            // Letter only: centre it in the bubble.
            canvas.drawText(mLetter, centerX, getHeight() / 2f + letterHeight / 2f, mLetterPaint);
            return;
        }

        mHintPaint.getTextBounds(mHint, 0, mHint.length(), mTextBounds);
        final float hintHeight = mTextBounds.height();
        final float blockHeight = letterHeight + mLineSpacing + hintHeight;
        final float top = (getHeight() - blockHeight) / 2f;

        canvas.drawText(mLetter, centerX, top + letterHeight, mLetterPaint);
        canvas.drawText(mHint, centerX, top + letterHeight + mLineSpacing + hintHeight, mHintPaint);
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);
        positionAgainstAnchor();
    }

    /**
     * Places the bubble beside the bar at {@link #mAnchorY}, clamped so it never runs off the top
     * or bottom of the list.
     */
    private void positionAgainstAnchor() {
        final View parent = (View) getParent();
        if (parent == null) {
            return;
        }
        final boolean rtl = getLayoutDirection() == LAYOUT_DIRECTION_RTL;
        final float x = rtl
                ? mMarginEnd
                : parent.getWidth() - getWidth() - mMarginEnd;
        final float maxY = parent.getHeight() - getHeight();
        final float y = Math.max(0f, Math.min(maxY, mAnchorY - getHeight() / 2f));
        setX(x);
        setY(y);
    }

    @Override
    public void invalidate() {
        // Content changes alter the measured size, so a re-layout has to accompany the redraw.
        requestLayout();
        super.invalidate();
    }
}
