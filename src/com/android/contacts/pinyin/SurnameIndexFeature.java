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

import com.android.contacts.R;

/** Feature switches for the Chinese surname index and the group chips bar. */
public final class SurnameIndexFeature {

    /**
     * Whether searching inside a selected group should go through the provider's pinyin/T9
     * matching instead of a plain {@code LIKE}.
     *
     * <p>Product decision D4: off for now. Group search keeps the behaviour the existing
     * {@code FILTER_TYPE_GROUP_MEMBERS} path already has, so this change introduces no regression
     * and no second search implementation. Turning it on requires resolving contact IDs through
     * {@code Contacts.CONTENT_FILTER_URI} and intersecting them with the group's members, which
     * costs an extra query per keystroke — hence the explicit flag rather than a silent change.
     */
    public static final boolean ENABLE_PINYIN_IN_GROUP_SEARCH = false;

    private SurnameIndexFeature() {
    }

    /** Whether to re-index contacts by Chinese surname pronunciation. */
    public static boolean isSurnameIndexEnabled(Context context) {
        return context.getResources().getBoolean(R.bool.config_surname_index_enabled);
    }

    /** Whether to show the group chips bar above the contact list. */
    public static boolean isGroupChipsEnabled(Context context) {
        return context.getResources().getBoolean(R.bool.config_show_group_chips);
    }
}
