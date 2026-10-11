/*
 * Copyright (C) 2010 The Android Open Source Project
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

package com.android.contacts.editor;

import android.app.Dialog;
import android.content.Context;
import android.content.res.Resources;
import android.os.Bundle;
import android.provider.ContactsContract.CommonDataKinds.Event;
import android.provider.ContactsContract.Data;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.view.View;
import android.widget.Button;

import com.android.contacts.R;
import com.android.contacts.datepicker.DatePicker;
import com.android.contacts.datepicker.LunarDatePicker;
import com.android.contacts.datepicker.LunarDatePickerDialog;
import com.android.contacts.datepicker.LunarDatePickerDialog.OnDateSetListener;
import com.android.contacts.model.RawContactDelta;
import com.android.contacts.model.ValuesDelta;
import com.android.contacts.model.account.AccountType.EditField;
import com.android.contacts.model.account.AccountType.EventEditType;
import com.android.contacts.model.dataitem.DataKind;
import com.android.contacts.util.CommonDateUtils;
import com.android.contacts.util.DateUtils;
import com.android.contacts.util.LunarCalendarUtils;

import java.text.ParsePosition;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;

/**
 * Editor that allows editing Events using a {@link DatePickerDialog}
 */
public class EventFieldEditorView extends LabeledEditorView {

    /**
     * Default string to show when there is no date selected yet.
     */
    private String mNoDateString;
    private int mPrimaryTextColor;
    private int mHintTextColor;

    /**
     * Column which stores whether the date is a date of the Chinese lunar calendar. The column
     * is not used by events, so it can be used as a marker.
     */
    private static final String MARKER_COLUMN = Data.DATA15;

    private Button mDateView;

    public EventFieldEditorView(Context context) {
        super(context);
    }

    public EventFieldEditorView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public EventFieldEditorView(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
    }

    /** {@inheritDoc} */
    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();

        Resources resources = getContext().getResources();
        mPrimaryTextColor = resources.getColor(R.color.primary_text_color);
        mHintTextColor = resources.getColor(R.color.editor_disabled_text_color);
        mNoDateString = getContext().getString(R.string.event_edit_field_hint_text);

        mDateView = (Button) findViewById(R.id.date_view);
        mDateView.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                showDialog(R.id.dialog_event_date_picker);
            }
        });
    }

    @Override
    public void editNewlyAddedField() {
        showDialog(R.id.dialog_event_date_picker);
    }

    @Override
    protected void requestFocusForFirstEditField() {
        mDateView.requestFocus();
    }

    @Override
    public void setEnabled(boolean enabled) {
        super.setEnabled(enabled);

        mDateView.setEnabled(!isReadOnly() && enabled);
    }

    @Override
    public void setValues(DataKind kind, ValuesDelta entry, RawContactDelta state, boolean readOnly,
            ViewIdGenerator vig) {
        if (kind.fieldList.size() != 1) throw new IllegalStateException("kind must have 1 field");
        super.setValues(kind, entry, state, readOnly, vig);

        mDateView.setEnabled(isEnabled() && !readOnly);

        rebuildDateView();
        updateEmptiness();
    }

    /** Returns the value of the column which stores whether the date is a lunar date. */
    private String getLunarMarker() {
        return getEntry() == null ? null : getEntry().getAsString(MARKER_COLUMN);
    }

    /** Returns true when the date of this event is a date of the lunar calendar. */
    private boolean isLunar() {
        return LunarCalendarUtils.isLunarMarker(getLunarMarker());
    }

    private boolean isLeapMonth() {
        return LunarCalendarUtils.isLeapMarker(getLunarMarker());
    }

    /** Returns true when this type of event can be a lunar date (birthday or anniversary). */
    private boolean isLunarOptionSupported() {
        final EventEditType type = getType();
        if (type == null) {
            return false;
        }
        return type.rawValue == Event.TYPE_BIRTHDAY || type.rawValue == Event.TYPE_ANNIVERSARY;
    }

    private void rebuildDateView() {
        final EditField editField = getKind().fieldList.get(0);
        final String column = editField.column;
        final String rawValue = getEntry().getAsString(column);
        String data = null;
        if (isLunar()) {
            final int[] lunar = LunarCalendarUtils.parseLunarDate(rawValue);
            if (lunar != null) {
                data = LunarCalendarUtils.getDisplayString(getContext(), lunar[0], lunar[1],
                        isLeapMonth(), lunar[2]);
            }
        }
        if (data == null) {
            data = DateUtils.formatDate(getContext(), rawValue,
                    false /*Use the short DateFormat to ensure that it fits inside the EditText*/);
        }
        if (TextUtils.isEmpty(data)) {
            mDateView.setText(mNoDateString);
            mDateView.setTextColor(mHintTextColor);
            setDeleteButtonVisible(false);
        } else {
            mDateView.setText(data);
            mDateView.setTextColor(mPrimaryTextColor);
            setDeleteButtonVisible(true);
        }
    }

    @Override
    public boolean isEmpty() {
        final EditField editField = getKind().fieldList.get(0);
        final String column = editField.column;
        return TextUtils.isEmpty(getEntry().getAsString(column));
    }

    @Override
    public Dialog createDialog(Bundle bundle) {
        if (bundle == null) throw new IllegalArgumentException("bundle must not be null");
        int dialogId = bundle.getInt(DIALOG_ID_KEY);
        if (dialogId == R.id.dialog_event_date_picker) {
            return createDatePickerDialog();
        } else {
            return super.createDialog(bundle);
        }
    }

    @Override
    protected EventEditType getType() {
        return (EventEditType) super.getType();
    }

    @Override
    protected void onLabelRebuilt() {
        // if we changed to a type that requires a year, ensure that it is actually set
        final String column = getKind().fieldList.get(0).column;
        final String oldValue = getEntry().getAsString(column);
        final DataKind kind = getKind();

        final Calendar calendar = Calendar.getInstance(DateUtils.UTC_TIMEZONE, Locale.US);
        final int defaultYear = calendar.get(Calendar.YEAR);

        // Check whether the year is optional
        final boolean isYearOptional = getType() != null && getType().isYearOptional();

        if (isLunar()) {
            // The date of a lunar calendar is stored in the same format as a Gregorian date, but
            // the date formats of the kind cannot parse it, so the year is added here when the
            // type requires one.
            if (!isYearOptional && !TextUtils.isEmpty(oldValue)) {
                final int[] lunar = LunarCalendarUtils.parseLunarDate(oldValue);
                if (lunar == null || lunar[0] != 0) return;

                final Calendar today = Calendar.getInstance();
                final int[] lunarToday = LunarCalendarUtils.gregorianToLunar(
                        today.get(Calendar.YEAR), today.get(Calendar.MONTH) + 1,
                        today.get(Calendar.DAY_OF_MONTH));
                final int year = lunarToday == null ? today.get(Calendar.YEAR) : lunarToday[0];

                onFieldChanged(column, LunarCalendarUtils.encodeDate(year, lunar[1], lunar[2]));
                rebuildDateView();
            }
            return;
        }

        if (!isYearOptional && !TextUtils.isEmpty(oldValue)) {
            final ParsePosition position = new ParsePosition(0);
            final Date date2 = kind.dateFormatWithoutYear == null
                    ? null : kind.dateFormatWithoutYear.parse(oldValue, position);

            // Don't understand the date, lets not change it
            if (date2 == null) return;

            // This value is missing the year. Add it now
            calendar.setTime(date2);
            calendar.set(defaultYear, calendar.get(Calendar.MONTH),
                    calendar.get(Calendar.DAY_OF_MONTH), CommonDateUtils.DEFAULT_HOUR, 0, 0);

            final String formattedDate = kind.dateFormatWithYear == null
                    ? null : kind.dateFormatWithYear.format(calendar.getTime());
            if (formattedDate == null) return;

            onFieldChanged(column, formattedDate);
            rebuildDateView();
        }
    }

    /**
     * Prepare dialog for entering a date
     */
    private Dialog createDatePickerDialog() {
        final String column = getKind().fieldList.get(0).column;
        final String oldValue = getEntry().getAsString(column);
        final DataKind kind = getKind();

        final Calendar calendar = Calendar.getInstance(DateUtils.UTC_TIMEZONE, Locale.US);
        final int defaultYear = calendar.get(Calendar.YEAR);

        // Check whether the year is optional
        final boolean isYearOptional = getType().isYearOptional();

        final int[] lunarToday = LunarCalendarUtils.gregorianToLunar(defaultYear,
                calendar.get(Calendar.MONTH) + 1, calendar.get(Calendar.DAY_OF_MONTH));

        final int mode;
        final int oldYear, oldMonth, oldDay;
        final boolean oldLeap;

        if (isLunar()) {
            // The stored date is a date of the lunar calendar.
            int[] oldLunar = LunarCalendarUtils.parseLunarDate(oldValue);
            if (oldLunar == null) {
                if (lunarToday == null) return null;
                // Not a valid lunar date: use the lunar date of today.
                oldLunar = new int[] { lunarToday[0], lunarToday[1], lunarToday[2] };
            }
            mode = LunarDatePickerDialog.MODE_LUNAR;
            oldYear = (oldLunar[0] == 0 && !isYearOptional) ? defaultYear : oldLunar[0];
            oldMonth = oldLunar[1];
            oldLeap = isLeapMonth();
            oldDay = oldLunar[2];
        } else if (TextUtils.isEmpty(oldValue)) {
            // Default to the current date
            mode = LunarDatePickerDialog.MODE_GREGORIAN;
            oldYear = defaultYear;
            oldMonth = calendar.get(Calendar.MONTH);
            oldLeap = false;
            oldDay = calendar.get(Calendar.DAY_OF_MONTH);
        } else {
            // Try parsing with year
            Calendar cal = DateUtils.parseDate(oldValue, false);
            if (cal != null) {
                mode = LunarDatePickerDialog.MODE_GREGORIAN;
                if (DateUtils.isYearSet(cal)) {
                    oldYear = cal.get(Calendar.YEAR);
                } else {
                    //cal.set(Calendar.YEAR, 0);
                    oldYear = isYearOptional ? DatePicker.NO_YEAR : defaultYear;
                }
                oldMonth = cal.get(Calendar.MONTH);
                oldLeap = false;
                oldDay = cal.get(Calendar.DAY_OF_MONTH);
            } else {
                return null;
            }
        }
        final OnDateSetListener callBack = new OnDateSetListener() {
            @Override
            public void onDateSet(boolean lunar, int year, int monthOfYear, boolean leap,
                    int dayOfMonth) {
                if (lunar) {
                    // The date of a lunar calendar is stored in the same format as a Gregorian
                    // date, the marker column tells which calendar it belongs to.
                    final int lunarYear = (year == LunarDatePicker.NO_YEAR) ? 0 : year;
                    onFieldChanged(column, LunarCalendarUtils.encodeDate(lunarYear, monthOfYear,
                            dayOfMonth));
                    onFieldChanged(MARKER_COLUMN, leap ? LunarCalendarUtils.MARKER_LUNAR_LEAP
                            : LunarCalendarUtils.MARKER_LUNAR);
                    rebuildDateView();
                    return;
                }

                if (year == 0 && !isYearOptional) throw new IllegalStateException();
                final Calendar outCalendar =
                        Calendar.getInstance(DateUtils.UTC_TIMEZONE, Locale.US);

                // If no year specified, set it to 2000 (we could pick any leap year here).
                // The format string will ignore that year.
                // For formats other than Exchange, the time of the day is ignored
                outCalendar.clear();
                outCalendar.set(year == DatePicker.NO_YEAR ? 2000 : year, monthOfYear,
                        dayOfMonth, CommonDateUtils.DEFAULT_HOUR, 0, 0);

                final String resultString;
                if (year == 0) {
                    resultString = kind.dateFormatWithoutYear == null
                            ? null : kind.dateFormatWithoutYear.format(outCalendar.getTime());
                } else {
                    resultString = kind.dateFormatWithYear == null
                            ? null : kind.dateFormatWithYear.format(outCalendar.getTime());
                }
                if (resultString == null) return;

                onFieldChanged(column, resultString);
                // The date is not a date of the lunar calendar any more.
                onFieldChanged(MARKER_COLUMN, "");
                rebuildDateView();
            }
        };
        final LunarDatePickerDialog resultDialog = new LunarDatePickerDialog(getContext(), callBack,
                mode, oldYear, oldMonth, oldLeap, oldDay, isYearOptional, isLunarOptionSupported());
        return resultDialog;
    }

    @Override
    public void clearAllFields() {
        // Update UI
        mDateView.setText(mNoDateString);
        mDateView.setTextColor(mHintTextColor);

        // Update state
        final String column = getKind().fieldList.get(0).column;
        onFieldChanged(column, "");
        // The entry is not a date of the lunar calendar any more.
        onFieldChanged(MARKER_COLUMN, "");
    }

    /**
     * Sets the typeColumn of entry as TYPE_BIRTHDAY and calls rebuildValues() to refresh the view.
     */
    public void restoreBirthday() {
        saveValue(getKind().typeColumn, Integer.toString(Event.TYPE_BIRTHDAY));
        rebuildValues();
    }

    /**
     * EventEditType Birthday:
     * rawValue=3 labelRes=17039911 secondary=false specificMax=1 customColumn=null
     * mYearOptional=true
     */
    public boolean isBirthdayType(){
        final EventEditType eventType = getType();
        return eventType.rawValue == Event.TYPE_BIRTHDAY && !eventType.secondary
                && eventType.specificMax == 1 && eventType.customColumn == null
                && eventType.isYearOptional();
    }
}
