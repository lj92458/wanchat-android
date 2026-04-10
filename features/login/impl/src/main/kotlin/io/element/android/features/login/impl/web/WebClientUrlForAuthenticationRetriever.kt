/*
 * Copyright 2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.login.impl.web

import androidx.core.net.toUri
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import io.element.android.features.login.impl.screens.createaccount.AccountCreationNotSupported
import io.element.android.libraries.wellknown.api.WellknownRetriever
import timber.log.Timber

interface WebClientUrlForAuthenticationRetriever {
    suspend fun retrieve(homeServerUrl: String): String
}

@ContributesBinding(AppScope::class)
class DefaultWebClientUrlForAuthenticationRetriever(
    private val wellknownRetriever: WellknownRetriever,
) : WebClientUrlForAuthenticationRetriever {
    override suspend fun retrieve(homeServerUrl: String): String {
        // Try MAS (Matrix Authentication Service) first
        try {
            val wellknown = wellknownRetriever.getElementWellKnown(homeServerUrl).dataOrNull()
            val registrationHelperUrl = wellknown?.registrationHelperUrl
            if (registrationHelperUrl != null) {
                Timber.d("Using MAS registration helper URL for $homeServerUrl")
                return registrationHelperUrl.toUri()
                    .buildUpon()
                    .appendQueryParameter("hs_url", homeServerUrl)
                    .build()
                    .toString()
            }
        } catch (e: Exception) {
            Timber.w(e, "Failed to get MAS registration URL, falling back to traditional registration")
        }
        
        // Fallback to traditional registration using Cinny
        // Extract domain from homeserver URL
        val domain = try {
            homeServerUrl.toUri().host ?: throw IllegalArgumentException("Invalid homeserver URL")
        } catch (e: Exception) {
            Timber.e(e, "Failed to parse homeserver URL: $homeServerUrl")
            throw AccountCreationNotSupported()
        }
        
        // Use Cinny's registration page with the domain
        val cinnyRegistrationUrl = "https://app.cinny.in/register/$domain"
        //val cinnyRegistrationUrl = "https://wanchat.info/register.html?domain=$domain"
        Timber.d("Using Cinny traditional registration for $domain: $cinnyRegistrationUrl")
        return cinnyRegistrationUrl
    }
}
