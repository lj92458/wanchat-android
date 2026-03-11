/*
 * Copyright 2023, 2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.appnav.loggedin

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.lifecycle.Lifecycle
import io.element.android.appnav.R
import io.element.android.libraries.architecture.AsyncData
import io.element.android.libraries.designsystem.components.dialogs.ConfirmationDialog
import io.element.android.libraries.designsystem.components.dialogs.ErrorDialog
import io.element.android.libraries.designsystem.preview.ElementPreview
import io.element.android.libraries.designsystem.preview.PreviewsDayNight
import io.element.android.libraries.designsystem.utils.OnLifecycleEvent
import io.element.android.libraries.matrix.api.exception.isNetworkError
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun LoggedInView(
    state: LoggedInState,
    navigateToNotificationTroubleshoot: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val coroutineScope = rememberCoroutineScope()
    OnLifecycleEvent { _, event ->
        if (event == Lifecycle.Event.ON_RESUME) {
            state.eventSink(LoggedInEvents.CheckSlidingSyncProxyAvailability)
        }
    }
    Box(
        modifier = modifier
            .fillMaxSize()
            .systemBarsPadding()
    ) {
        SyncStateView(
            modifier = Modifier.align(Alignment.TopCenter),
            isVisible = state.showSyncSpinner,
        )
    }
    when (state.pusherRegistrationState) {
        is AsyncData.Uninitialized,
        is AsyncData.Loading,
        is AsyncData.Success -> Unit

        is AsyncData.Failure -> {/* 这里不需要了。下面的when语句负责检测。
            state.pusherRegistrationState.errorOrNull()
                ?.takeIf { !state.ignoreRegistrationError }
                ?.getReason()
                ?.let { reason ->
                    ErrorDialogWithDoNotShowAgain(
                        content = stringResource(id = CommonStrings.common_error_registering_pusher_android, reason),
                        cancelText = stringResource(id = CommonStrings.common_settings),
                        onDismiss = {
                            state.eventSink(LoggedInEvents.CloseErrorDialog(it))
                        },
                        onCancel = {
                            state.eventSink(LoggedInEvents.CloseErrorDialog(false))
                            navigateToNotificationTroubleshoot()
                        }
                    )
                }*/
        }
    }

    // Set the force migration dialog here so it's always displayed over every screen
    if (state.forceNativeSlidingSyncMigration) {
        ForceNativeSlidingSyncMigrationDialog(
            appName = state.appName,
            onSubmit = {
                state.eventSink(LoggedInEvents.LogoutAndMigrateToNativeSlidingSync)
            }
        )
    }

    @Composable
    fun Confirm(content: String, ntfyAction: NtfyAction, okCallback: () -> Unit = { }) {
        ConfirmationDialog(
            content = content,
            onSubmitClick = {
                ntfyAction.confirm(DialogResult.Ok, state)
                okCallback()
            },
            cancelText = stringResource(R.string.loggedin_ntfy_dialog_latter),
            onCancelClick = { ntfyAction.confirm(DialogResult.Latter, state) },
            onDismiss = {},
            thirdButtonText = stringResource(R.string.loggedin_ntfy_dialog_forbids),
            onThirdButtonClick = { ntfyAction.confirm(DialogResult.Never, state) },
        )
    }
    //请求各种权限之前，先弹框提示。
    when (state.ntfyAction) {
        null -> Unit
        //1.安装未知应用
        is NtfyAction.RequestInstallUnknownAppsPermission -> Confirm(stringResource(R.string.loggedin_ntfy_dialog_step1), state.ntfyAction)
        //2.下载安装ntfy
        is NtfyAction.NtfyDownloadInstall -> Confirm(stringResource(R.string.loggedin_ntfy_dialog_step2), state.ntfyAction)
        //3.索要通知权限
        is NtfyAction.RequestNotificationPermission -> Confirm(stringResource(R.string.loggedin_ntfy_dialog_step3), state.ntfyAction)
        //4.启动ntfy。如果有必要，就打开“排查通知问题”页面
        is NtfyAction.Troubleshoot ->
            if (state.ntfyAction.needOpenTroubleshoot) {
                Confirm(stringResource(R.string.loggedin_ntfy_dialog_step4), state.ntfyAction) {
                    coroutineScope.launch {
                        delay(2000)
                        navigateToNotificationTroubleshoot()
                    }
                }
            } else {
                LaunchedEffect(state.ntfyAction) {
                    state.ntfyAction.confirm(DialogResult.Ok, state)
                }
            }

        //5.索要ntfy的“关联启动”权限.(不在队列中执行，由其它队列元素负责调用)
        is NtfyAction.RequestAssociatedStartPermission -> Confirm(stringResource(R.string.loggedin_ntfy_dialog_step5), state.ntfyAction)
        // 6.忽略电池优化，允许高耗电
        is NtfyAction.IgnoreBatteryOptimization -> Confirm(stringResource(R.string.loggedin_ntfy_dialog_step6), state.ntfyAction)
    }
}

private fun Throwable.getReason(): String? {
    return when (this) {
        is PusherRegistrationFailure.RegistrationFailure -> {
            if (isRegisteringAgain && clientException.isNetworkError()) {
                // When registering again, ignore network error
                null
            } else {
                clientException.message ?: "Unknown error"
            }
        }

        is PusherRegistrationFailure.AccountNotVerified -> null
        is PusherRegistrationFailure.NoDistributorsAvailable -> "No distributors available"
        is PusherRegistrationFailure.NoProvidersAvailable -> "No providers available"
        else -> "Other error: $message"
    }
}

@Composable
private fun ForceNativeSlidingSyncMigrationDialog(
    appName: String,
    onSubmit: () -> Unit,
) {
    ErrorDialog(
        title = null,
        content = stringResource(R.string.banner_migrate_to_native_sliding_sync_app_force_logout_title, appName),
        submitText = stringResource(R.string.banner_migrate_to_native_sliding_sync_action),
        onSubmit = onSubmit,
        canDismiss = false,
    )
}

@PreviewsDayNight
@Composable
internal fun LoggedInViewPreview(@PreviewParameter(LoggedInStateProvider::class) state: LoggedInState) = ElementPreview {
    LoggedInView(
        state = state,
        navigateToNotificationTroubleshoot = {},
    )
}
