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
            cancelText = "稍后询问",
            onCancelClick = { ntfyAction.confirm(DialogResult.Latter, state) },
            onDismiss = {},
            thirdButtonText = "禁止",
            onThirdButtonClick = { ntfyAction.confirm(DialogResult.Never, state) },
        )
    }
    //请求各种权限之前，先弹框提示。
    when (state.ntfyAction) {
        null -> Unit
        //1.安装未知应用
        is NtfyAction.RequestInstallUnknownAppsPermission -> Confirm("步骤1/5：请允许“安装未知应用”，否则无法使用插件", state.ntfyAction)
        //2.下载安装ntfy
        is NtfyAction.NtfyDownloadInstall -> Confirm("步骤2/5：您没有安装ntfy，无法收到消息提醒。现在为您安装", state.ntfyAction)
        //3.索要通知权限
        is NtfyAction.RequestNotificationPermission -> Confirm("步骤3/5：请开启“通知”权限，否则无法收到消息提醒", state.ntfyAction)
        //4.启动ntfy。如果有必要，就打开“排查通知问题”页面
        is NtfyAction.Troubleshoot ->
            if (state.ntfyAction.needOpenTroubleshoot) {
                Confirm("步骤4/5：未知的故障，导致您收不到通知(请确保ntfy已运行)。现在排查？", state.ntfyAction) {
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

        //索要ntfy的“关联启动”权限.(不在队列中执行，由其它队列元素负责调用)
        is NtfyAction.RequestAssociatedStartPermission -> Confirm("步骤4/6：请允许“关联启动”，否则无法为您启动ntfy，导致您收不到通知提醒", state.ntfyAction)
        // 5.忽略电池优化，允许高耗电
        is NtfyAction.IgnoreBatteryOptimization ->
            Confirm(
                """
                         步骤5/5：请允许ntfy忽略电池优化、常驻后台、高耗电。(也可在ntfy首页设置)。
                         注意：某些手机需要点击"按钮所在的一整行"，才能真正进入界面。
                        """.trimIndent(),
                state.ntfyAction
            )
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
