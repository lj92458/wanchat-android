/*
 * Copyright 2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.login.impl.screens.createaccount

import android.annotation.SuppressLint
import android.view.ViewGroup
import android.webkit.JsResult
import android.webkit.WebChromeClient
import android.webkit.WebView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import io.element.android.compound.theme.ElementTheme
import io.element.android.features.login.impl.R
import io.element.android.libraries.designsystem.components.async.AsyncActionView
import io.element.android.libraries.designsystem.components.button.BackButton
import io.element.android.libraries.designsystem.preview.ElementPreview
import io.element.android.libraries.designsystem.preview.PreviewsDayNight
import io.element.android.libraries.designsystem.theme.components.LinearProgressIndicator
import io.element.android.libraries.designsystem.theme.components.Scaffold
import io.element.android.libraries.designsystem.theme.components.Text
import io.element.android.libraries.designsystem.theme.components.TopAppBar
import io.element.android.libraries.designsystem.theme.progressIndicatorTrackColor
import timber.log.Timber

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreateAccountView(
    state: CreateAccountState,
    onBackClick: () -> Unit,
    onOpenExternalUrl: (String) -> Unit,
    onRegistrationComplete: (() -> Unit)? = null, // Optional callback for registration completion
    modifier: Modifier = Modifier,
) {
    // Track if registration completion has been handled to prevent duplicate triggers
    var hasHandledCompletion by remember { mutableStateOf(false) }
    
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                titleStr = stringResource(R.string.screen_create_account_title),
                navigationIcon = {
                    BackButton(onClick = onBackClick)
                },
            )
        }
    ) { contentPadding ->
        Box(
            modifier = Modifier
                .padding(contentPadding)
                .consumeWindowInsets(contentPadding)
                .fillMaxSize()
        ) {
            CreateAccountWebView(
                modifier = Modifier
                    .fillMaxSize(),
                state = state,
                onRegistrationComplete = onRegistrationComplete,
                onWebViewCreate = { webView ->
                    WebViewMessageInterceptor(
                        webView,
                        state.isDebugBuild,
                        onOpenExternalUrl = onOpenExternalUrl,
                        onMessage = {
                            state.eventSink(CreateAccountEvents.OnMessageReceived(it))
                        },
                        onPageNavigation = { url ->
                            // Detect when user navigates away from registration page
                            if (onRegistrationComplete != null && !hasHandledCompletion) {
                                // Check if it's a registration page (support both cinny and wanchat)
                                val isCinnyRegisterPage = url.startsWith("https://app.cinny.in/register")
                                val isWanchatRegisterPage = url.startsWith("https://wanchat.info/register")
                                val isRegisterPage = isCinnyRegisterPage || isWanchatRegisterPage
                                
                                // If URL doesn't match registration page pattern, registration is complete
                                if (!isRegisterPage) {
                                    Timber.d("✓ Left registration page! Registration likely successful. Navigating to login...")
                                    hasHandledCompletion = true
                                    
                                    // Disable JavaScript to prevent further messages
                                    webView.settings.javaScriptEnabled = false
                                    // Hide WebView immediately to prevent showing chat interface or errors
                                    webView.visibility = android.view.View.GONE
                                    // Trigger navigation (WebView will be destroyed by backstack.newRoot)
                                    onRegistrationComplete()
                                    // Return true to block loading in WebView
                                    return@WebViewMessageInterceptor true
                                }
                            }
                            false // Let default handling proceed
                        }
                    )
                }
            )
            AnimatedVisibility(
                visible = state.pageProgress != 100,
                // Disable enter animation
                enter = fadeIn(initialAlpha = 1f),
                exit = fadeOut(),
            ) {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(2.dp),
                    progress = { state.pageProgress / 100f },
                    trackColor = ElementTheme.colors.progressIndicatorTrackColor,
                )
            }
        }
    }

    AsyncActionView(
        async = state.createAction,
        onSuccess = {},
        onErrorDismiss = onBackClick,
        onRetry = null
    )
}

@Composable
private fun CreateAccountWebView(
    state: CreateAccountState,
    onRegistrationComplete: (() -> Unit)? = null,
    onWebViewCreate: (WebView) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (LocalInspectionMode.current) {
        Box(modifier = modifier, contentAlignment = Alignment.Center) {
            Text("WebView - can't be previewed")
        }
    } else {
        AndroidView(
            modifier = modifier,
            factory = { context ->
                WebView(context).apply {
                    // Clear all cache and data before loading registration page
                    clearCache(true)
                    clearHistory()
                    clearFormData()
                    
                    onWebViewCreate(this)
                    setup(state)
                }
            },
            update = { webView ->
                if (webView.url != state.url) {
                    // Clear all data before loading new URL to ensure clean state
                    webView.clearCache(true)
                    webView.clearHistory()
                    webView.clearFormData()
                    webView.evaluateJavascript(
                        "localStorage.clear(); sessionStorage.clear();",
                        null
                    )
                    webView.loadUrl(state.url)
                }
            },
            onRelease = { webView ->
                webView.destroy()
            }
        )
    }
}

@SuppressLint("SetJavaScriptEnabled")
private fun WebView.setup(state: CreateAccountState) {
    layoutParams = ViewGroup.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.MATCH_PARENT
    )
    with(settings) {
        javaScriptEnabled = true
        domStorageEnabled = true
    }

    webChromeClient = object : WebChromeClient() {
        override fun onProgressChanged(view: WebView?, newProgress: Int) {
            super.onProgressChanged(view, newProgress)
            state.eventSink(CreateAccountEvents.SetPageProgress(newProgress))
        }

        override fun onJsBeforeUnload(view: WebView?, url: String?, message: String?, result: JsResult?): Boolean {
            Timber.w("onJsBeforeUnload, cancelling the dialog, we will open external links in a Custom Chrome Tab")
            result?.confirm()
            return true
        }
    }
}

@PreviewsDayNight
@Composable
internal fun CreateAccountViewPreview(@PreviewParameter(CreateAccountStateProvider::class) state: CreateAccountState) = ElementPreview {
    CreateAccountView(
        state = state,
        onBackClick = {},
        onOpenExternalUrl = {},
    )
}
