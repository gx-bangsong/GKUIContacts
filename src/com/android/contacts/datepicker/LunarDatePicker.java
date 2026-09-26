/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.contacts.datepicker;

import android.content.Context;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.CompoundButton.OnCheckedChangeListener;
import android.widget.FrameLayout;
import android.widget.NumberPicker;
import android.widget.NumberPicker.OnValueChangeListener;

import com.android.contacts.R;
import com.android.contacts.util.LunarCalendarUtils;
import com.android.contacts.util.LunarCalendarUtils.LunarMonth;

import java.util.Calendar;
import java.util.List;

/**
 * A view which allows to select a date of the Chinese lunar calendar. Like the {@link DatePicker}
 * of the app, the year can be toggled on and off.
 */
public class LunarDatePicker extends FrameLayout {
    /** Magic year that represents "no year" */
    public static final int NO_YEAR = DatePicker.NO_YEAR;

    private final CheckBox mYearToggle;
    private final NumberPicker mMonthPicker;
    private final NumberPicker mDayPicker;
    private final NumberPicker mYearPicker;

    /**
     * How we notify users the date has changed.
     */
    private OnDateChangedListener mOnDateChangedListener;

    private List<LunarMonth> mMonths;

    /** The selected lunar year. */
    private int mYear;
    /** The selected month, from 1 (正月) to 12 (腊月). */
    private int mMonth;
    /** Whether the selected month is a leap month (闰月). */
    private boolean mLeapMonth;
    /** The selected day of the month. */
    private int mDay;
    private boolean mYearOptional;
    private boolean mHasYear;
    private boolean mUpdating;

    /**
     * The callback used to indicate the user changes the date.
     */
    public interface OnDateChangedListener {
        /**
         * @param view The view associated with this listener.
         * @param year The lunar year that was set or {@link LunarDatePicker#NO_YEAR} if no year
         *     was set
         * @param month The month that was set, from 1 to 12
         * @param leapMonth Whether the month is a leap month
         * @param dayOfMonth The day of the month that was set
         */
        void onDateChanged(LunarDatePicker view, int year, int month, boolean leapMonth,
                int dayOfMonth);
    }

    public LunarDatePicker(Context context) {
        this(context, null);
    }

    public LunarDatePicker(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public LunarDatePicker(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);

        final LayoutInflater inflater = (LayoutInflater) context.getSystemService(
                Context.LAYOUT_INFLATER_SERVICE);
        inflater.inflate(R.layout.lunar_date_picker, this, true);

        mMonthPicker = (NumberPicker) findViewById(R.id.lunar_month);
        mMonthPicker.setOnLongPressUpdateInterval(200);
        mMonthPicker.setWrapSelectorWheel(false);
        mMonthPicker.setOnValueChangedListener(new OnValueChangeListener() {
            @Override
            public void onValueChange(NumberPicker picker, int oldVal, int newVal) {
                if (mUpdating || mMonths == null || newVal < 0 || newVal >= mMonths.size()) {
                    return;
                }
                final LunarMonth month = mMonths.get(newVal);
                mMonth = month.month;
                mLeapMonth = month.leap;
                updateDayPicker();
                notifyDateChanged();
            }
        });

        mDayPicker = (NumberPicker) findViewById(R.id.lunar_day);
        mDayPicker.setOnLongPressUpdateInterval(100);
        mDayPicker.setOnValueChangedListener(new OnValueChangeListener() {
            @Override
            public void onValueChange(NumberPicker picker, int oldVal, int newVal) {
                if (mUpdating) {
                    return;
                }
                mDay = newVal;
                notifyDateChanged();
            }
        });

        mYearPicker = (NumberPicker) findViewById(R.id.lunar_year);
        mYearPicker.setOnLongPressUpdateInterval(100);
        mYearPicker.setOnValueChangedListener(new OnValueChangeListener() {
            @Override
            public void onValueChange(NumberPicker picker, int oldVal, int newVal) {
                if (mUpdating) {
                    return;
                }
                mYear = newVal;
                updateMonths();
                notifyDateChanged();
            }
        });

        mYearToggle = (CheckBox) findViewById(R.id.lunar_yearToggle);
        mYearToggle.setOnCheckedChangeListener(new OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                if (mUpdating) {
                    return;
                }
                mHasYear = isChecked;
                updateSpinners();
                notifyDateChanged();
            }
        });

        // Initialize with the lunar date of today.
        final Calendar today = Calendar.getInstance();
        final int[] lunarToday = LunarCalendarUtils.gregorianToLunar(
                today.get(Calendar.YEAR), today.get(Calendar.MONTH) + 1,
                today.get(Calendar.DAY_OF_MONTH));
        if (lunarToday != null) {
            init(lunarToday[0], lunarToday[1], lunarToday[3] == 1, lunarToday[2],
                    false /* yearOptional */, true /* hasYear */, null);
        } else {
            init(currentLunarYear(), 1, false, 1, false, true, null);
        }
    }

    /**
     * Initializes the picker.
     *
     * @param year The lunar year, or {@link #NO_YEAR} if no year has been specified
     * @param month The month, from 1 to 12
     * @param leapMonth Whether the month is a leap month
     * @param dayOfMonth The day of the month
     * @param yearOptional Whether the user can toggle the year
     * @param hasYear Whether the year is set
     * @param onDateChangedListener How the user is notified, can be null
     */
    public void init(int year, int month, boolean leapMonth, int dayOfMonth, boolean yearOptional,
            boolean hasYear, OnDateChangedListener onDateChangedListener) {
        mYearOptional = yearOptional;
        mHasYear = yearOptional ? hasYear : true;
        mYear = clampYear(year);
        mMonths = LunarCalendarUtils.getMonths(mYear);

        LunarMonth resolved = LunarCalendarUtils.getMonth(mYear, month, leapMonth);
        if (resolved == null) {
            resolved = LunarCalendarUtils.getMonth(mYear, month, false);
        }
        if (resolved == null && !mMonths.isEmpty()) {
            resolved = mMonths.get(0);
        }
        if (resolved == null) {
            return;
        }
        mMonth = resolved.month;
        mLeapMonth = resolved.leap;
        mDay = Math.max(1, Math.min(dayOfMonth, resolved.days));
        mOnDateChangedListener = onDateChangedListener;
        updateSpinners();
    }

    /**
     * Initializes the picker, with the year enabled when the given year is set.
     *
     * @param year The lunar year, or {@link #NO_YEAR} if no year has been specified
     * @param month The month, from 1 to 12
     * @param leapMonth Whether the month is a leap month
     * @param dayOfMonth The day of the month
     * @param yearOptional Whether the user can toggle the year
     * @param onDateChangedListener How the user is notified, can be null
     */
    public void init(int year, int month, boolean leapMonth, int dayOfMonth, boolean yearOptional,
            OnDateChangedListener onDateChangedListener) {
        init(year, month, leapMonth, dayOfMonth, yearOptional,
                year != NO_YEAR, onDateChangedListener);
    }

    @Override
    public void setEnabled(boolean enabled) {
        super.setEnabled(enabled);
        mMonthPicker.setEnabled(enabled);
        mDayPicker.setEnabled(enabled);
        mYearPicker.setEnabled(enabled);
        mYearToggle.setEnabled(enabled);
    }

    /** Returns the lunar year, or {@link #NO_YEAR} when no year is set. */
    public int getYear() {
        return mHasYear ? mYear : NO_YEAR;
    }

    /** Returns the selected month, from 1 (正月) to 12 (腊月). */
    public int getMonth() {
        return mMonth;
    }

    /** Returns true when the selected month is a leap month (闰月). */
    public boolean isLeapMonth() {
        return mLeapMonth;
    }

    /** Returns the selected day of the month. */
    public int getDayOfMonth() {
        return mDay;
    }

    public boolean isYearOptional() {
        return mYearOptional;
    }

    /** Returns the Gregorian date of the selected lunar date, as {year, month, day}. */
    public int[] getGregorianDate() {
        return LunarCalendarUtils.lunarToGregorian(mYear, mMonth, mLeapMonth, mDay);
    }

    private void updateMonths() {
        mMonths = LunarCalendarUtils.getMonths(mYear);
        // Keep the selected month when the new year has it as well.
        LunarMonth resolved = LunarCalendarUtils.getMonth(mYear, mMonth, mLeapMonth);
        if (resolved == null) {
            resolved = LunarCalendarUtils.getMonth(mYear, mMonth, false);
        }
        if (resolved == null && !mMonths.isEmpty()) {
            resolved = mMonths.get(0);
        }
        if (resolved != null) {
            mMonth = resolved.month;
            mLeapMonth = resolved.leap;
        }
        updateSpinners();
    }

    private void updateSpinners() {
        if (mMonths == null || mMonths.isEmpty()) {
            return;
        }
        mUpdating = true;

        final String[] monthNames = new String[mMonths.size()];
        for (int i = 0; i < mMonths.size(); i++) {
            final LunarMonth month = mMonths.get(i);
            monthNames[i] = LunarCalendarUtils.getMonthName(getContext(), month.month, month.leap);
        }
        mMonthPicker.setMinValue(0);
        mMonthPicker.setMaxValue(mMonths.size() - 1);
        mMonthPicker.setDisplayedValues(monthNames);
        mMonthPicker.setValue(indexOf(mMonths, mMonth, mLeapMonth));

        updateDayPicker();

        mYearPicker.setMinValue(LunarCalendarUtils.MIN_YEAR);
        mYearPicker.setMaxValue(LunarCalendarUtils.MAX_YEAR);
        mYearPicker.setValue(mYear);
        mYearPicker.setVisibility(mHasYear ? VISIBLE : GONE);
        mYearToggle.setChecked(mHasYear);
        mYearToggle.setVisibility(mYearOptional ? VISIBLE : GONE);

        mUpdating = false;
    }

    private void updateDayPicker() {
        final int days = getDaysInMonth();
        mDay = Math.max(1, Math.min(mDay, days));

        final String[] dayNames = new String[days];
        final String[] names = getResources().getStringArray(R.array.lunar_day_names);
        for (int i = 0; i < days; i++) {
            dayNames[i] = i < names.length ? names[i] : String.valueOf(i + 1);
        }
        mDayPicker.setMinValue(1);
        mDayPicker.setMaxValue(days);
        mDayPicker.setDisplayedValues(dayNames);
        mDayPicker.setValue(mDay);
    }

    private int getDaysInMonth() {
        final LunarMonth month = LunarCalendarUtils.getMonth(mYear, mMonth, mLeapMonth);
        return month == null ? 30 : month.days;
    }

    private static int indexOf(List<LunarMonth> months, int month, boolean leap) {
        for (int i = 0; i < months.size(); i++) {
            final LunarMonth candidate = months.get(i);
            if (candidate.month == month && candidate.leap == leap) {
                return i;
            }
        }
        return 0;
    }

    private static int currentLunarYear() {
        final Calendar today = Calendar.getInstance();
        final int[] lunarToday = LunarCalendarUtils.gregorianToLunar(
                today.get(Calendar.YEAR), today.get(Calendar.MONTH) + 1,
                today.get(Calendar.DAY_OF_MONTH));
        if (lunarToday == null) {
            return today.get(Calendar.YEAR);
        }
        return lunarToday[0];
    }

    private static int clampYear(int year) {
        if (year < LunarCalendarUtils.MIN_YEAR) {
            return LunarCalendarUtils.MIN_YEAR;
        }
        return Math.min(year, LunarCalendarUtils.MAX_YEAR);
    }

    private void notifyDateChanged() {
        if (mOnDateChangedListener != null) {
            mOnDateChangedListener.onDateChanged(this, getYear(), mMonth, mLeapMonth, mDay);
        }
    }
}
