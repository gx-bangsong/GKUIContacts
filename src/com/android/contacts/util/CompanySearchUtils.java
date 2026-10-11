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

package com.android.contacts.util;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.preference.PreferenceManager;
import android.text.TextUtils;

import com.android.contacts.R;
import com.android.contacts.activities.CompanyInfoActivity;

/**
 * Helpers for looking up the information of the company a contact works for.
 *
 * <p>The company is looked up on one of the Chinese company information web sites. Which site is
 * used can be selected in the settings, a custom URL template is supported as well.
 */
public final class CompanySearchUtils {

    /** Action of the intent which shows the information of a company. */
    public static final String ACTION_SHOW_COMPANY_INFO =
            "com.android.contacts.action.SHOW_COMPANY_INFO";

    /** The name of the company, as {@link String} extra. */
    public static final String EXTRA_COMPANY_NAME = "company_name";

    /** Preference key of the selected company information provider. */
    public static final String PREF_PROVIDER = "company_search_provider";
    /** Preference key of the user defined URL template. */
    public static final String PREF_CUSTOM_URL = "company_search_custom_url";

    public static final String PROVIDER_AIQICHA = "aiqicha";
    public static final String PROVIDER_TIANYANCHA = "tianyancha";
    public static final String PROVIDER_QCC = "qcc";
    public static final String PROVIDER_CUSTOM = "custom";

    public static final String PROVIDER_AIQICHA_URL = "https://aiqicha.baidu.com/s?q=%s";
    public static final String PROVIDER_TIANYANCHA_URL =
            "https://www.tianyancha.com/search?key=%s";
    public static final String PROVIDER_QCC_URL = "https://www.qcc.com/web/search?key=%s";

    /** Default template of the user defined URL. It has to contain a "%s" placeholder. */
    public static final String DEFAULT_CUSTOM_URL = "https://www.qcc.com/web/search?key=%s";

    private CompanySearchUtils() {
    }

    private static SharedPreferences getPreferences(Context context) {
        return PreferenceManager.getDefaultSharedPreferences(context);
    }

    /** Returns the value of the selected company information provider. */
    public static String getProvider(Context context) {
        return getPreferences(context).getString(PREF_PROVIDER, PROVIDER_AIQICHA);
    }

    /** Returns the template which is used for the given provider. */
    public static String getUrlTemplate(Context context, String provider) {
        if (PROVIDER_TIANYANCHA.equals(provider)) {
            return PROVIDER_TIANYANCHA_URL;
        } else if (PROVIDER_QCC.equals(provider)) {
            return PROVIDER_QCC_URL;
        } else if (PROVIDER_CUSTOM.equals(provider)) {
            final String custom = getPreferences(context).getString(PREF_CUSTOM_URL, null);
            return TextUtils.isEmpty(custom) ? DEFAULT_CUSTOM_URL : custom.trim();
        }
        return PROVIDER_AIQICHA_URL;
    }

    /**
     * Returns the URL which shows the information of the given company.
     */
    public static String getCompanyUrl(Context context, String company) {
        final String template = getUrlTemplate(context, getProvider(context));
        return buildUrl(template, company);
    }

    /**
     * Replaces the placeholder of the given template with the (encoded) company name. Templates
     * without a placeholder get the company name appended as an ordinary query parameter.
     */
    public static String buildUrl(String template, String company) {
        if (TextUtils.isEmpty(template)) {
            return PROVIDER_AIQICHA_URL.replace("%s", Uri.encode(company));
        }
        final String query = Uri.encode(company);
        if (template.contains("%s")) {
            return template.replace("%s", query);
        }
        final String separator = template.contains("?") ? "&" : "?";
        return template + separator + "q=" + query;
    }

    /**
     * Builds the intent which opens the information of the given company inside the app.
     */
    public static Intent buildCompanyInfoIntent(Context context, String company) {
        final Intent intent = new Intent(ACTION_SHOW_COMPANY_INFO);
        intent.setClass(context.getApplicationContext(), CompanyInfoActivity.class);
        intent.putExtra(EXTRA_COMPANY_NAME, company);
        return intent;
    }

    /** Returns true when the given intent is a request to show a company. */
    public static boolean isCompanyInfoIntent(Intent intent) {
        return intent != null && ACTION_SHOW_COMPANY_INFO.equals(intent.getAction())
                && !TextUtils.isEmpty(intent.getStringExtra(EXTRA_COMPANY_NAME));
    }

    /** Returns the intent which opens the given URL in the default browser. */
    public static Intent buildBrowserIntent(String url) {
        return new Intent(Intent.ACTION_VIEW, Uri.parse(url));
    }

    /**
     * Returns the name of the provider that is currently selected, or null when the user defined
     * URL is used.
     */
    public static CharSequence getProviderLabel(Context context, String provider) {
        if (PROVIDER_TIANYANCHA.equals(provider)) {
            return context.getText(R.string.company_search_provider_tianyancha);
        } else if (PROVIDER_QCC.equals(provider)) {
            return context.getText(R.string.company_search_provider_qcc);
        } else if (PROVIDER_CUSTOM.equals(provider)) {
            return null;
        }
        return context.getText(R.string.company_search_provider_aiqicha);
    }
}
