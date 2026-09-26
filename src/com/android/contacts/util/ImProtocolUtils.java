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
import android.net.Uri;
import android.provider.ContactsContract.CommonDataKinds.Im;
import android.text.TextUtils;

import com.android.contacts.R;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Utility methods for the instant messaging services which are stored with the generic
 * {@link Im#PROTOCOL_CUSTOM} protocol value.
 *
 * <p>The provider only defines a handful of protocol values (AIM, MSN, Skype, QQ, ...) which are
 * barely used nowadays. All modern services are therefore stored as a "custom protocol", i.e.
 * {@link Im#PROTOCOL} is {@link Im#PROTOCOL_CUSTOM} and the name of the service is kept in
 * {@link Im#CUSTOM_PROTOCOL}. This is also what other contacts applications do, so the values
 * stored by this app stay readable (and editable) in other apps.
 */
public final class ImProtocolUtils {

    public static final String WECHAT = "微信";
    public static final String WECOM = "企业微信";
    public static final String DINGTALK = "钉钉";
    public static final String FEISHU = "飞书";
    public static final String WEIBO = "微博";
    public static final String DOUYIN = "抖音";
    public static final String XIAOHONGSHU = "小红书";
    public static final String TELEGRAM = "Telegram";
    public static final String WHATSAPP = "WhatsApp";
    public static final String LINE = "LINE";
    public static final String SIGNAL = "Signal";
    public static final String INSTAGRAM = "Instagram";
    public static final String X = "X";
    public static final String FACEBOOK = "Facebook";

    /** The locale used for all URIs and package handling. */
    private static final Locale LOCALE = Locale.US;

    private ImProtocolUtils() {
    }

    /**
     * A known instant messaging service which is stored as a custom protocol.
     */
    public static final class Protocol {
        /**
         * The value of the {@link Im#PROTOCOL} column. It is {@link Im#PROTOCOL_CUSTOM} for all
         * services that have no dedicated protocol value.
         */
        public final int rawProtocol;
        /** The value that is written into {@link Im#CUSTOM_PROTOCOL}, may be null. */
        public final String value;
        /** Label shown in the editor. */
        public final int labelRes;
        /** The package of the application that handles this service. May be null. */
        public final String packageName;
        /**
         * Optional URI template which opens the given account directly. It has to contain a
         * single {@code %s} placeholder, or no placeholder at all.
         */
        public final String uriFormat;

        private final String[] mAliases;

        private Protocol(int rawProtocol, String value, int labelRes, String packageName,
                String uriFormat, String... aliases) {
            this.rawProtocol = rawProtocol;
            this.value = value;
            this.labelRes = labelRes;
            this.packageName = packageName;
            this.uriFormat = uriFormat;
            this.mAliases = aliases;
        }

        /** Returns true if the given string denotes this protocol. */
        public boolean matches(String candidate) {
            if (candidate == null) {
                return false;
            }
            final String trimmed = candidate.trim();
            if (trimmed.isEmpty()) {
                return false;
            }
            if (TextUtils.equals(trimmed, value)) {
                return true;
            }
            for (String alias : mAliases) {
                if (trimmed.equalsIgnoreCase(alias)) {
                    return true;
                }
            }
            return false;
        }
    }

    private static final List<Protocol> PROTOCOLS;

    static {
        final List<Protocol> protocols = new ArrayList<Protocol>();
        // Mainland China
        protocols.add(custom(WECHAT, R.string.chat_wechat, "com.tencent.mm", null,
                "WeChat", "wechat"));
        protocols.add(custom(WECOM, R.string.chat_wecom, "com.tencent.wework", null,
                "WeCom", "WeWork", "Enterprise WeChat", "企业微信"));
        protocols.add(custom(DINGTALK, R.string.chat_dingtalk, "com.alibaba.android.rimet", null,
                "DingTalk", "dingtalk"));
        protocols.add(custom(FEISHU, R.string.chat_feishu, "com.ss.android.lark", null,
                "Feishu", "Lark", "lark"));
        protocols.add(new Protocol(Im.PROTOCOL_QQ, "QQ", R.string.chat_qq, "com.tencent.mobileqq",
                null, "qq"));
        protocols.add(custom(WEIBO, R.string.chat_weibo, "com.sina.weibo", null,
                "Weibo", "weibo"));
        protocols.add(custom(DOUYIN, R.string.chat_douyin, "com.ss.android.ugc.aweme", null,
                "Douyin", "douyin", "TikTok", "tiktok"));
        protocols.add(custom(XIAOHONGSHU, R.string.chat_xiaohongshu, "com.xingin.xhs", null,
                "Xiaohongshu", "xiaohongshu", "RedNote", "rednote", "RED"));
        // International
        protocols.add(custom(TELEGRAM, R.string.chat_telegram, "org.telegram.messenger",
                "https://t.me/%s", "telegram"));
        protocols.add(custom(WHATSAPP, R.string.chat_whatsapp, "com.whatsapp",
                "https://wa.me/%s", "whatsapp"));
        protocols.add(custom(LINE, R.string.chat_line, "jp.naver.line.android", null,
                "Line", "line"));
        protocols.add(custom(SIGNAL, R.string.chat_signal, "org.thoughtcrime.securesms",
                null, "signal"));
        protocols.add(custom(INSTAGRAM, R.string.chat_instagram, "com.instagram.android",
                "https://instagram.com/%s", "instagram", "IG"));
        protocols.add(custom(X, R.string.chat_x, "com.twitter.android", "https://x.com/%s",
                "Twitter", "twitter", "x.com"));
        protocols.add(custom(FACEBOOK, R.string.chat_facebook, "com.facebook.katana",
                "https://www.facebook.com/%s", "facebook", "FB"));
        protocols.add(new Protocol(Im.PROTOCOL_SKYPE, "Skype", R.string.chat_skype,
                "com.skype.raider", null, "skype"));
        PROTOCOLS = Collections.unmodifiableList(protocols);
    }

    private static Protocol custom(String value, int labelRes, String packageName,
            String uriFormat, String... aliases) {
        return new Protocol(Im.PROTOCOL_CUSTOM, value, labelRes, packageName, uriFormat, aliases);
    }

    /** Returns the list of supported protocols, in the order in which they should be displayed. */
    public static List<Protocol> getProtocols() {
        return PROTOCOLS;
    }

    /** Returns the protocol with the given stored value, or null when it is unknown. */
    public static Protocol findProtocol(String customProtocol) {
        if (TextUtils.isEmpty(customProtocol)) {
            return null;
        }
        for (Protocol protocol : PROTOCOLS) {
            if (protocol.matches(customProtocol)) {
                return protocol;
            }
        }
        return null;
    }

    /**
     * Returns true when the given stored protocol value denotes the same service as
     * {@code knownValue}. Both the Chinese and the English name are accepted, so that entries
     * written by other applications are recognized as well.
     */
    public static boolean matches(String storedProtocol, String knownValue) {
        if (TextUtils.isEmpty(storedProtocol) || TextUtils.isEmpty(knownValue)) {
            return false;
        }
        if (TextUtils.equals(storedProtocol.trim(), knownValue)) {
            return true;
        }
        final Protocol protocol = findProtocol(knownValue);
        return protocol != null && protocol.matches(storedProtocol);
    }

    /** Returns the resource id of the label of the given protocol, or 0 when it is unknown. */
    public static int getLabelRes(String customProtocol) {
        final Protocol protocol = findProtocol(customProtocol);
        return protocol == null ? 0 : protocol.labelRes;
    }

    /**
     * Returns the label that should be displayed for the given protocol. Unknown (user defined)
     * protocols are returned unchanged.
     */
    public static CharSequence getDisplayLabel(Context context, String customProtocol) {
        final int labelRes = getLabelRes(customProtocol);
        if (labelRes != 0) {
            return context.getText(labelRes);
        }
        return customProtocol;
    }

    /**
     * Builds an intent which opens the given account in the application of the service, or null
     * if the service is unknown or the application is not installed.
     */
    public static Intent buildOpenAppIntent(Context context, String customProtocol, String account) {
        final Protocol protocol = findProtocol(customProtocol);
        if (protocol == null) {
            return null;
        }
        if (!TextUtils.isEmpty(account)) {
            final String uri = buildUri(protocol.uriFormat, account);
            if (uri != null) {
                final Intent uriIntent = new Intent(Intent.ACTION_VIEW, Uri.parse(uri));
                if (uriIntent.resolveActivity(context.getPackageManager()) != null) {
                    return uriIntent;
                }
            }
        }
        if (!TextUtils.isEmpty(protocol.packageName)) {
            final Intent launchIntent = context.getPackageManager()
                    .getLaunchIntentForPackage(protocol.packageName);
            if (launchIntent != null) {
                return launchIntent;
            }
        }
        return null;
    }

    private static String buildUri(String uriFormat, String account) {
        if (TextUtils.isEmpty(uriFormat)) {
            return null;
        }
        if (uriFormat.contains("%s")) {
            return String.format(LOCALE, uriFormat, Uri.encode(account));
        }
        return uriFormat;
    }
}
