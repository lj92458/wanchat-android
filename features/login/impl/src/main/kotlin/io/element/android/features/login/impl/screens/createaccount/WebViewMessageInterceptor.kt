/*
 * Copyright 2023, 2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.login.impl.screens.createaccount

import android.graphics.Bitmap
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import timber.log.Timber

class WebViewMessageInterceptor(
    webView: WebView,
    private val debugLog: Boolean,
    private val onOpenExternalUrl: (String) -> Unit,
    private val onMessage: (String) -> Unit,
    private val onPageNavigation: ((String) -> Boolean)? = null, // Callback to detect page navigation
) {
    companion object {
        // We call both the WebMessageListener and the JavascriptInterface objects in JS with this
        // 'listenerName' so they can both receive the data from the WebView when
        // `${LISTENER_NAME}.postMessage(...)` is called
        const val LISTENER_NAME = "elementX"
    }

    init {
        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
    
                // We inject this JS code when the page starts loading to attach a message listener to the window.
                view?.evaluateJavascript(
                    """
                        window.addEventListener(
                          "mobileregistrationresponse",
                          (event) => {
                            let json = JSON.stringify(event.detail)
                            ${"console.log('message sent: ' + json);".takeIf { debugLog }}
                            $LISTENER_NAME.postMessage(json);
                          },
                          false,
                        );
                        
                        // Override history methods to detect SPA navigation
                        const originalPushState = history.pushState;
                        const originalReplaceState = history.replaceState;
                        
                        history.pushState = function(...args) {
                            originalPushState.apply(this, args);
                            console.log('pushState called, new URL: ' + location.href);
                            $LISTENER_NAME.postMessage(JSON.stringify({type: 'urlChange', url: location.href}));
                        };
                        
                        history.replaceState = function(...args) {
                            originalReplaceState.apply(this, args);
                            console.log('replaceState called, new URL: ' + location.href);
                            $LISTENER_NAME.postMessage(JSON.stringify({type: 'urlChange', url: location.href}));
                        };
                        
                        // Also listen for popstate events
                        window.addEventListener('popstate', () => {
                            console.log('popstate event, URL: ' + location.href);
                            $LISTENER_NAME.postMessage(JSON.stringify({type: 'urlChange', url: location.href}));
                        });
                    """.trimIndent(),
                    null
                )
            }
                
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                // Check if registration is complete after page loads
                url?.let { currentPageUrl ->
                    onPageNavigation?.let { callback ->
                        // If callback returns true, it means we should handle this navigation
                        if (callback(currentPageUrl)) {
                            Timber.d("Registration detected as complete on page finished: $currentPageUrl")
                        }
                    }
                }
            }
    
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                request ?: return false
                val url = request.url.toString()
                
                Timber.d("shouldOverrideUrlLoading: $url")
                    
                // Check if this is a navigation event we should handle
                onPageNavigation?.let { callback ->
                    if (callback(url)) {
                        // Callback handled the navigation, don't load in WebView
                        Timber.d("Blocked by callback: $url")
                        return true
                    }
                }
                    
                // Load other URLs in a Chrome Custom Tab, and return true to cancel the load
                onOpenExternalUrl(url)
                return true
            }
        }

        // Use WebMessageListener if supported, otherwise use JavascriptInterface
        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            // Create a WebMessageListener, which will receive messages from the WebView and reply to them
            val webMessageListener = WebViewCompat.WebMessageListener { _, message, _, _, _ ->
                onMessageReceived(message.data)
            }
            WebViewCompat.addWebMessageListener(
                webView,
                LISTENER_NAME,
                setOf("*"),
                webMessageListener
            )
        } else {
            webView.addJavascriptInterface(
                object {
                    @JavascriptInterface
                    fun postMessage(json: String?) {
                        onMessageReceived(json)
                    }
                },
                LISTENER_NAME,
            )
        }
    }

    private fun onMessageReceived(json: String?) {
        json?.let { 
            Timber.d("onMessageReceived: $it")
            // Check if this is a URL change message
            if (it.contains("\"urlChange\"")) {
                try {
                    val urlMatch = Regex("\"url\":\"([^\"]+)\"").find(it)
                    urlMatch?.groupValues?.get(1)?.let { newUrl ->
                        Timber.d("Detected SPA navigation to: $newUrl")
                        onPageNavigation?.let { callback ->
                            if (callback(newUrl)) {
                                Timber.d("SPA navigation blocked, registration complete")
                            }
                        }
                    }
                } catch (e: Exception) {
                    Timber.e(e, "Failed to parse URL change message")
                }
            }
            onMessage(it) 
        }
    }
}
