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

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.DialogInterface.OnClickListener;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.RadioGroup;
import android.widget.TextView;

import com.android.contacts.R;
import com.android.contacts.util.DateUtils;
import com.android.contacts.util.LunarCalendarUtils;

import java.text.DateFormat;
import java.util.Calendar;
import java.util.Locale;

/**
 * A dialog which allows to pick either a Gregorian date or a date of the Chinese lunar calendar.
 *
 * <p>The user switches between the two calendars with the radio buttons at the top of the dialog,
 * the date which is picked in one calendar is converted when the other calendar is selected.
 */
public class LunarDatePickerDialog extends AlertDialog implements OnClickListener,
        DatePicker.OnDateChangedListener, LunarDatePicker.OnDateChangedListener {

    /** The dialog shows a Gregorian date. */
    public static final int MODE_GREGORIAN = 0;
    /** The dialog shows a date of the lunar calendar. */
    public static final int MODE_LUNAR = 1;

    private static final String MODE = "mode";
    private static final String YEAR = "year";
    private static final String MONTH = "month";
    private static final String LEAP = "leap";
    private static final String DAY = "day";
    private static final String YEAR_OPTIONAL = "year_optional";
    private static final String LUNAR_OPTION = "lunar_option";

    private final DatePicker mDatePicker;
    private final LunarDatePicker mLunarDatePicker;
    private final RadioGroup mModeGroup;
    private final TextView mGregorianHint;
    private final OnDateSetListener mCallBack;
    private final DateFormat mTitleDateFormat;

    private final boolean mYearOptional;
    private final boolean mLunarOptionVisible;

    private boolean mUpdating;
    private int mMode;

    /**
     * The callback used to indicate the user is done filling in the date.
     */
    public interface OnDateSetListener {
        /**
         * @param lunar Whether the picked date is a date of the lunar calendar
         * @param year The year that was set or {@link DatePicker#NO_YEAR} if the user has not
         *     specified a year
         * @param monthOfYear The month that was set: 0-11 for a Gregorian date, 1-12 for a lunar
         *     date
         * @param leap Whether the lunar month is a leap month (闰月)
         * @param dayOfMonth The day of the month that was set
         */
        void onDateSet(boolean lunar, int year, int monthOfYear, boolean leap, int dayOfMonth);
    }

    /**
     * @param context The context the dialog is to run in.
     * @param callBack How the parent is notified that the date is set.
     * @param mode {@link #MODE_GREGORIAN} or {@link #MODE_LUNAR}
     * @param year The initial year or {@link DatePicker#NO_YEAR} if no year has been specified.
     *     For {@link #MODE_GREGORIAN} this is a Gregorian year, otherwise a lunar year.
     * @param monthOfYear The initial month: 0-11 for {@link #MODE_GREGORIAN}, 1-12 for
     *     {@link #MODE_LUNAR}
     * @param leap Whether the initial lunar month is a leap month
     * @param dayOfMonth The initial day of the month.
     * @param yearOptional Whether the year can be toggled by the user.
     * @param lunarOptionVisible Whether the user can switch between the two calendars.
     */
    public LunarDatePickerDialog(Context context, OnDateSetListener callBack, int mode, int year,
            int monthOfYear, boolean leap, int dayOfMonth, boolean yearOptional,
            boolean lunarOptionVisible) {
        super(context, 0);

        mCallBack = callBack;
        mMode = lunarOptionVisible ? mode : MODE_GREGORIAN;
        mYearOptional = yearOptional;
        mLunarOptionVisible = lunarOptionVisible;
        mTitleDateFormat = DateFormat.getDateInstance(DateFormat.FULL);

        setButton(BUTTON_POSITIVE, context.getText(R.string.date_time_set), this);
        setButton(BUTTON_NEGATIVE, context.getText(android.R.string.cancel),
                (OnClickListener) null);

        final LayoutInflater inflater =
                (LayoutInflater) context.getSystemService(Context.LAYOUT_INFLATER_SERVICE);
        final View view = inflater.inflate(R.layout.lunar_date_picker_dialog, null);
        setView(view);

        mModeGroup = (RadioGroup) view.findViewById(R.id.date_picker_mode);
        mGregorianHint = (TextView) view.findViewById(R.id.lunar_date_hint);
        mDatePicker = (DatePicker) view.findViewById(R.id.datePicker);
        mLunarDatePicker = (LunarDatePicker) view.findViewById(R.id.lunarDatePicker);

        // Initialize both pickers with the initial date, converted into the other calendar.
        if (mMode == MODE_LUNAR) {
            mLunarDatePicker.init(year, monthOfYear, leap, dayOfMonth, yearOptional, this);
            initGregorianPickerFromLunar();
        } else {
            mDatePicker.init(year, monthOfYear, dayOfMonth, yearOptional, this);
            initLunarPickerFromGregorian();
        }

        mModeGroup.check(mMode == MODE_LUNAR ? R.id.date_mode_lunar : R.id.date_mode_gregorian);
        mModeGroup.setVisibility(mLunarOptionVisible ? View.VISIBLE : View.GONE);
        mModeGroup.setOnCheckedChangeListener((group, checkedId) -> {
            if (mUpdating) {
                return;
            }
            final int mode = checkedId == R.id.date_mode_lunar ? MODE_LUNAR : MODE_GREGORIAN;
            if (mode != mMode) {
                switchMode(mode);
            }
        });

        switchMode(mMode);
    }

    @Override
    public void onClick(DialogInterface dialog, int which) {
        if (mCallBack == null) {
            return;
        }
        if (mMode == MODE_LUNAR) {
            mLunarDatePicker.clearFocus();
            mCallBack.onDateSet(true, mLunarDatePicker.getYear(), mLunarDatePicker.getMonth(),
                    mLunarDatePicker.isLeapMonth(), mLunarDatePicker.getDayOfMonth());
        } else {
            mDatePicker.clearFocus();
            mCallBack.onDateSet(false, mDatePicker.getYear(), mDatePicker.getMonth(), false,
                    mDatePicker.getDayOfMonth());
        }
    }

    @Override
    public void onDateChanged(DatePicker view, int year, int month, int day) {
        updateTitle();
    }

    @Override
    public void onDateChanged(LunarDatePicker view, int year, int month, boolean leap, int day) {
        updateTitle();
    }

    @Override
    public Bundle onSaveInstanceState() {
        final Bundle state = super.onSaveInstanceState();
        state.putInt(MODE, mMode);
        state.putBoolean(YEAR_OPTIONAL, mYearOptional);
        state.putBoolean(LUNAR_OPTION, mLunarOptionVisible);
        if (mMode == MODE_LUNAR) {
            state.putInt(YEAR, mLunarDatePicker.getYear());
            state.putInt(MONTH, mLunarDatePicker.getMonth());
            state.putBoolean(LEAP, mLunarDatePicker.isLeapMonth());
            state.putInt(DAY, mLunarDatePicker.getDayOfMonth());
        } else {
            state.putInt(YEAR, mDatePicker.getYear());
            state.putInt(MONTH, mDatePicker.getMonth());
            state.putBoolean(LEAP, false);
            state.putInt(DAY, mDatePicker.getDayOfMonth());
        }
        return state;
    }

    @Override
    public void onRestoreInstanceState(Bundle savedInstanceState) {
        super.onRestoreInstanceState(savedInstanceState);
        mMode = savedInstanceState.getInt(MODE, MODE_GREGORIAN);
        final int year = savedInstanceState.getInt(YEAR);
        final int month = savedInstanceState.getInt(MONTH);
        final boolean leap = savedInstanceState.getBoolean(LEAP);
        final int day = savedInstanceState.getInt(DAY);
        if (mMode == MODE_LUNAR) {
            mLunarDatePicker.init(year, month, leap, day, mYearOptional, this);
            initGregorianPickerFromLunar();
        } else {
            mDatePicker.init(year, month, day, mYearOptional, this);
            initLunarPickerFromGregorian();
        }
        switchMode(mMode);
    }

    /** Shows the pickers of the given calendar. */
    private void switchMode(int mode) {
        mMode = mode;
        mUpdating = true;
        mModeGroup.check(mode == MODE_LUNAR ? R.id.date_mode_lunar : R.id.date_mode_gregorian);
        mUpdating = false;

        mDatePicker.setVisibility(mode == MODE_LUNAR ? View.GONE : View.VISIBLE);
        mLunarDatePicker.setVisibility(mode == MODE_LUNAR ? View.VISIBLE : View.GONE);
        updateTitle();
    }

    private void updateTitle() {
        if (mMode == MODE_LUNAR) {
            final int year = mLunarDatePicker.getYear();
            setTitle(LunarCalendarUtils.getDisplayString(getContext(), year,
                    mLunarDatePicker.getMonth(), mLunarDatePicker.isLeapMonth(),
                    mLunarDatePicker.getDayOfMonth()));
            final int[] gregorian = mLunarDatePicker.getGregorianDate();
            if (gregorian == null) {
                mGregorianHint.setVisibility(View.GONE);
            } else {
                final Calendar calendar = Calendar.getInstance(DateUtils.UTC_TIMEZONE, Locale.US);
                calendar.clear();
                calendar.set(gregorian[0], gregorian[1] - 1, gregorian[2]);
                mGregorianHint.setText(getContext().getString(R.string.lunar_date_gregorian_hint,
                        mTitleDateFormat.format(calendar.getTime())));
                mGregorianHint.setVisibility(View.VISIBLE);
            }
        } else {
            final Calendar calendar = Calendar.getInstance(DateUtils.UTC_TIMEZONE, Locale.US);
            calendar.clear();
            calendar.set(Math.max(mDatePicker.getYear(), 1), mDatePicker.getMonth(),
                    mDatePicker.getDayOfMonth());
            setTitle(mTitleDateFormat.format(calendar.getTime()));
            mGregorianHint.setVisibility(View.GONE);
        }
    }

    private void initGregorianPickerFromLunar() {
        final int[] gregorian = mLunarDatePicker.getGregorianDate();
        if (gregorian == null) {
            return;
        }
        final int year = (mYearOptional && mLunarDatePicker.getYear() == LunarDatePicker.NO_YEAR)
                ? DatePicker.NO_YEAR : gregorian[0];
        mDatePicker.init(year, gregorian[1] - 1, gregorian[2], mYearOptional, this);
    }

    private void initLunarPickerFromGregorian() {
        final boolean hasYear = !mYearOptional || mDatePicker.getYear() != DatePicker.NO_YEAR;
        final int year = hasYear ? mDatePicker.getYear()
                : Calendar.getInstance().get(Calendar.YEAR);
        final int month = mDatePicker.getMonth() + 1;
        final int day = mDatePicker.getDayOfMonth();
        final int[] lunar = LunarCalendarUtils.gregorianToLunar(year, month, day);
        if (lunar == null) {
            return;
        }
        final int lunarYear = hasYear ? lunar[0] : LunarDatePicker.NO_YEAR;
        mLunarDatePicker.init(lunarYear, lunar[1], lunar[3] == 1, lunar[2], mYearOptional, this);
    }
}
