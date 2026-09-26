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

package com.android.contacts.activities;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.ProgressBar;
import android.widget.Toast;

import com.android.contacts.R;
import com.android.contacts.util.CompanySearchUtils;

/**
 * Shows the information of a company (the organization a contact works for) inside a WebView.
 *
 * <p>The web site which is used for the lookup can be selected in the settings of the contacts
 * app.
 */
public class CompanyInfoActivity extends Activity {

    private WebView mWebView;
    private ProgressBar mProgressBar;

    private String mCompanyName;
    private String mUrl;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        mCompanyName = getIntent().getStringExtra(CompanySearchUtils.EXTRA_COMPANY_NAME);
        if (TextUtils.isEmpty(mCompanyName)) {
            finish();
            return;
        }
        mUrl = CompanySearchUtils.getCompanyUrl(this, mCompanyName);

        setContentView(R.layout.company_info_activity);
        setTitle(mCompanyName);

        mProgressBar = (ProgressBar) findViewById(R.id.company_info_progress);
        mWebView = (WebView) findViewById(R.id.company_info_webview);

        final WebSettings settings = mWebView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setLoadWithOverviewMode(true);
        settings.setUseWideViewPort(true);
        settings.setBuiltInZoomControls(true);
        settings.setDisplayZoomControls(false);
        settings.setSupportZoom(true);

        mWebView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                if (mProgressBar != null) {
                    mProgressBar.setVisibility(View.VISIBLE);
                }
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                if (mProgressBar != null) {
                    mProgressBar.setVisibility(View.GONE);
                }
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                // Keep the navigation inside the WebView so that the user can browse the
                // company information (and its search results) without leaving the app.
                return false;
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request,
                    WebResourceError error) {
                if (request != null && request.isForMainFrame()) {
                    showLoadError(request.getUrl() == null ? null : request.getUrl().toString());
                }
            }
        });
        mWebView.setWebChromeClient(new WebChromeClient());

        if (savedInstanceState != null) {
            mWebView.restoreState(savedInstanceState);
        }
        if (TextUtils.isEmpty(mWebView.getUrl())) {
            mWebView.loadUrl(mUrl);
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        if (mWebView != null) {
            mWebView.saveState(outState);
        }
    }

    @Override
    public void onBackPressed() {
        if (mWebView != null && mWebView.canGoBack()) {
            mWebView.goBack();
            return;
        }
        super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        if (mWebView != null) {
            // Detach the WebView before destroying it to make sure that no web page keeps a
            // reference to this activity.
            final ViewGroup parent = (ViewGroup) mWebView.getParent();
            if (parent != null) {
                parent.removeView(mWebView);
            }
            mWebView.destroy();
            mWebView = null;
        }
        super.onDestroy();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.company_info_activity, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        final int itemId = item.getItemId();
        if (itemId == R.id.menu_open_in_browser) {
            openInBrowser();
            return true;
        } else if (itemId == R.id.menu_refresh) {
            if (mWebView != null) {
                mWebView.reload();
            }
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    /** Tells the user why the company information could not be loaded. */
    private void showLoadError(String url) {
        if (mProgressBar != null) {
            mProgressBar.setVisibility(View.GONE);
        }
        // The application does not allow cleartext HTTP traffic, so a user defined address
        // which starts with "http://" cannot be loaded at all.
        final int message = url != null && url.startsWith("http://")
                ? R.string.company_info_cleartext_blocked : R.string.company_info_load_failed;
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }

    private void openInBrowser() {
        final String url = mWebView != null && mWebView.getUrl() != null
                ? mWebView.getUrl() : mUrl;
        if (TextUtils.isEmpty(url)) {
            return;
        }
        try {
            startActivity(CompanySearchUtils.buildBrowserIntent(url));
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, R.string.missing_app, Toast.LENGTH_SHORT).show();
        }
    }
}
