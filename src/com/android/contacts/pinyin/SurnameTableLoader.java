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
import android.content.res.Resources;
import android.util.Log;

import com.android.contacts.R;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * Builds the app-wide {@link SurnamePinyinResolver} by layering the {@code res/raw} CSV files on
 * top of the tables compiled into {@link ChineseSurnameTable}.
 *
 * <p>Layering order, lowest priority first:
 * <ol>
 *   <li>Built-in tables (67 compound, 30 polyphonic) — always present, so the feature still works
 *       if a deployment strips or breaks the CSV files.</li>
 *   <li>{@code res/raw/surnames_compound.csv} and {@code res/raw/surnames_polyphonic.csv} — the
 *       long tail, additive, and able to correct a built-in row since later rows win.</li>
 *   <li>{@code res/raw/surnames_override.csv} — the deployment hook, consulted ahead of both
 *       tables at match time.</li>
 * </ol>
 *
 * <p>Parsing happens once, lazily, on whichever thread asks first. That is a background Loader
 * thread in practice ({@code FavoritesAndContactsLoader.loadInBackground}), never the main thread.
 * Roughly 150 short lines, so it is a sub-millisecond job either way.
 *
 * <p>A missing or malformed resource is logged and skipped — the contact list must never fail to
 * render because somebody fat-fingered a CSV row.
 */
public final class SurnameTableLoader {

    private static final String TAG = "SurnameTableLoader";

    private static volatile SurnamePinyinResolver sResolver;

    private SurnameTableLoader() {
    }

    /**
     * Returns the shared resolver, building it on first call.
     *
     * @param context any context; the application context is retained, not this one
     */
    public static SurnamePinyinResolver getResolver(Context context) {
        SurnamePinyinResolver local = sResolver;
        if (local == null) {
            synchronized (SurnameTableLoader.class) {
                local = sResolver;
                if (local == null) {
                    local = build(context.getApplicationContext());
                    sResolver = local;
                }
            }
        }
        return local;
    }

    /**
     * Discards the cached resolver so the next call re-reads {@code res/raw}. For the settings
     * screen that lets a user pick a reading for an ambiguous surname (P4), and for tests.
     */
    public static void invalidate() {
        synchronized (SurnameTableLoader.class) {
            sResolver = null;
        }
    }

    private static SurnamePinyinResolver build(Context appContext) {
        final ChineseSurnameTable.Builder builder = ChineseSurnameTable.newBuilder();
        final Resources res = appContext.getResources();

        readInto(res, R.raw.surnames_compound, builder, LineKind.COMPOUND);
        readInto(res, R.raw.surnames_polyphonic, builder, LineKind.POLYPHONIC);
        readInto(res, R.raw.surnames_override, builder, LineKind.OVERRIDE);

        final int skipped = builder.getSkippedLineCount();
        if (skipped > 0) {
            Log.w(TAG, skipped + " malformed surname row(s) were skipped; check res/raw/*.csv");
        }

        final ChineseSurnameTable table = builder.build();
        Log.i(TAG, "surname tables loaded: " + table.getCompoundEntries().size() + " compound, "
                + table.getPolyphonicEntries().size() + " polyphonic, "
                + table.getOverrideEntries().size() + " override");

        final PinyinFallback fallback = IcuPinyinFallback.getInstance();
        return new SurnamePinyinResolver(table, fallback);
    }

    private enum LineKind { COMPOUND, POLYPHONIC, OVERRIDE }

    private static void readInto(Resources res, int rawResId,
            ChineseSurnameTable.Builder builder, LineKind kind) {
        InputStream in = null;
        try {
            in = res.openRawResource(rawResId);
            final BufferedReader reader =
                    new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8), 8192);
            String line;
            while ((line = reader.readLine()) != null) {
                switch (kind) {
                    case COMPOUND:
                        builder.addCompoundLine(line);
                        break;
                    case POLYPHONIC:
                        builder.addPolyphonicLine(line);
                        break;
                    case OVERRIDE:
                        builder.addOverrideLine(line);
                        break;
                }
            }
        } catch (Resources.NotFoundException e) {
            Log.w(TAG, "surname resource missing (" + kind + "); using built-in data only", e);
        } catch (IOException e) {
            Log.w(TAG, "failed reading surname resource (" + kind + ")", e);
        } finally {
            closeQuietly(in);
        }
    }

    private static void closeQuietly(InputStream in) {
        if (in != null) {
            try {
                in.close();
            } catch (IOException ignored) {
                // Nothing useful to do.
            }
        }
    }
}
