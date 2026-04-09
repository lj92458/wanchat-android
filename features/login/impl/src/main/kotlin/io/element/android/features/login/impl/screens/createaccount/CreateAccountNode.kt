/*
 * Copyright 2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.login.impl.screens.createaccount

import android.app.Activity
import android.webkit.CookieManager
import android.webkit.WebStorage
import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.bumble.appyx.core.lifecycle.subscribe
import com.bumble.appyx.core.modality.BuildContext
import com.bumble.appyx.core.node.Node
import com.bumble.appyx.core.plugin.Plugin
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Assisted
import dev.zacsweers.metro.AssistedInject
import io.element.android.annotations.ContributesNode
import io.element.android.compound.theme.ElementTheme
import io.element.android.libraries.androidutils.browser.openUrlInChromeCustomTab
import io.element.android.libraries.architecture.NodeInputs
import io.element.android.libraries.architecture.inputs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch

@ContributesNode(AppScope::class)
@AssistedInject
class CreateAccountNode(
    @Assisted buildContext: BuildContext,
    @Assisted plugins: List<Plugin>,
    presenterFactory: CreateAccountPresenter.Factory,
) : Node(buildContext, plugins = plugins) {
    interface Callback : Plugin {
        fun onRegistrationComplete()
    }

    data class Inputs(
        val url: String,
    ) : NodeInputs

    private val presenter = presenterFactory.create(inputs<Inputs>().url)
    private val callback: Callback? = plugins.filterIsInstance<Callback>().firstOrNull()

    override fun onBuilt() {
        super.onBuilt()
        lifecycle.subscribe(
            onDestroy = {
                // Clear all WebView data when node is destroyed
                // Use async operations to avoid blocking UI transition
                GlobalScope.launch(Dispatchers.IO) {
                    try {
                        CookieManager.getInstance().removeAllCookies(null)
                        CookieManager.getInstance().flush()
                        WebStorage.getInstance().deleteAllData()
                    } catch (e: Exception) {
                        // Ignore errors during cleanup
                    }
                }
            }
        )
    }

    private fun onOpenExternalUrl(activity: Activity, darkTheme: Boolean, url: String) {
        activity.openUrlInChromeCustomTab(null, darkTheme, url)
    }

    @Composable
    override fun View(modifier: Modifier) {
        val activity = requireNotNull(LocalActivity.current)
        val isDark = ElementTheme.isLightTheme.not()
        val state = presenter.present()
        CreateAccountView(
            state = state,
            modifier = modifier,
            onBackClick = ::navigateUp,
            onOpenExternalUrl = {
                onOpenExternalUrl(activity, isDark, it)
            },
            onRegistrationComplete = {
                callback?.onRegistrationComplete()
            }
        )
    }
}
