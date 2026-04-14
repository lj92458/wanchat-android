/*
 * Copyright 2023, 2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.appnav.loggedin

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import dev.zacsweers.metro.Inject
import im.vector.app.features.analytics.plan.CryptoSessionStateChange
import im.vector.app.features.analytics.plan.UserProperties
import io.element.android.libraries.androidutils.system.findFragmentActivity
import io.element.android.libraries.architecture.AsyncData
import io.element.android.libraries.architecture.Presenter
import io.element.android.libraries.core.extensions.runCatchingExceptions
import io.element.android.libraries.core.log.logger.LoggerTag
import io.element.android.libraries.core.meta.BuildMeta
import io.element.android.libraries.core.meta.BuildType
import io.element.android.libraries.designsystem.utils.OnLifecycleEvent
import io.element.android.libraries.matrix.api.MatrixClient
import io.element.android.libraries.matrix.api.encryption.EncryptionService
import io.element.android.libraries.matrix.api.encryption.RecoveryState
import io.element.android.libraries.matrix.api.oidc.AccountManagementAction
import io.element.android.libraries.matrix.api.roomlist.RoomListService
import io.element.android.libraries.matrix.api.sync.SlidingSyncVersion
import io.element.android.libraries.matrix.api.sync.SyncService
import io.element.android.libraries.matrix.api.verification.SessionVerificationService
import io.element.android.libraries.matrix.api.verification.SessionVerifiedStatus
import io.element.android.libraries.push.api.PushService
import io.element.android.libraries.pushproviders.api.Distributor
import io.element.android.libraries.pushproviders.api.PushProvider
import io.element.android.libraries.pushproviders.api.RegistrationFailure
import io.element.android.libraries.pushproviders.unifiedpush.UnifiedPushProvider
import io.element.android.libraries.troubleshoot.impl.TroubleshootTestSuite
import io.element.android.services.analytics.api.AnalyticsService
import io.element.android.services.toolbox.api.strings.StringProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import timber.log.Timber

private val pusherTag = LoggerTag("Pusher", LoggerTag.PushLoggerTag)
const val NTFY_APK_NAME: String = "ntfy" //名字格式只能是xxx.yyy
const val NTFY_PACKAGE_NAME = "io.heckel.ntfy"
const val NTFY_ACTIVITY_MAIN = "io.heckel.ntfy.ui.MainActivity"

@Inject
class LoggedInPresenter(
    private val matrixClient: MatrixClient,
    private val syncService: SyncService,
    private val pushService: PushService,
    private val sessionVerificationService: SessionVerificationService,
    private val analyticsService: AnalyticsService,
    private val encryptionService: EncryptionService,
    private val buildMeta: BuildMeta,
    private val troubleshootTestSuite: TroubleshootTestSuite,
    //@ApplicationContext private val context: Context,
    private val stringProvider: StringProvider,
) : Presenter<LoggedInState> {

    @Composable
    override fun present(): LoggedInState {
        val activityContext: Context? = LocalContext.current.findFragmentActivity()
        val coroutineScope = rememberCoroutineScope()
        val ignoreRegistrationError by remember {
            pushService.ignoreRegistrationError(matrixClient.sessionId)
        }.collectAsState(initial = false)
        val pusherRegistrationState = remember<MutableState<AsyncData<Unit>>> { mutableStateOf(AsyncData.Uninitialized) }
        var ntfyAction by remember { mutableStateOf<NtfyAction?>(null) }
        //上次权限检查是什么时候
        var lastCheckTime by rememberSaveable { mutableLongStateOf(0) }

        /** 初始值：不拒绝。如果用户明确拒绝(在confirm弹窗点“禁止”按钮)，那就等app重启后才能再次询问。
         * 如果想持久保存配置项，可以研究ErrorDialogWithDoNotShowAgain
         * */
        var userDelayed by rememberSaveable { mutableStateOf(false) }
        val stepContext = remember { NtfyStepContext() }

        /** 如果用户点击“稍后询问”，才让每项检查间隔6小时。否则不间隔，连续检查。 */
        var isLatter by rememberSaveable { mutableStateOf(false) }



        LaunchedEffect(Unit) { preloadAccountManagementUrl() }
        LaunchedEffect(Unit) {
            sessionVerificationService.sessionVerifiedStatus
                .onEach { sessionVerifiedStatus ->
                    when (sessionVerifiedStatus) {
                        SessionVerifiedStatus.Unknown -> Unit
                        SessionVerifiedStatus.Verified -> {
                            ensurePusherIsRegistered(pusherRegistrationState)
                        }

                        SessionVerifiedStatus.NotVerified -> {
                            pusherRegistrationState.value = AsyncData.Failure(PusherRegistrationFailure.AccountNotVerified())
                        }
                    }
                }
                .launchIn(this)
        }
        val syncIndicator by matrixClient.roomListService.syncIndicator.collectAsState()
        val isOnline by syncService.isOnline.collectAsState()
        val showSyncSpinner by remember {
            derivedStateOf {
                isOnline && syncIndicator == RoomListService.SyncIndicator.Show
            }
        }
        var forceNativeSlidingSyncMigration by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) {
            combine(
                sessionVerificationService.sessionVerifiedStatus,
                encryptionService.recoveryStateStateFlow
            ) { verificationState, recoveryState ->
                reportCryptoStatusToAnalytics(verificationState, recoveryState)
            }.launchIn(this)
        }

        fun getFdroidActions(context: Context): List<NtfyAction> = listOf(
            NtfyAction.RequestInstallUnknownAppsPermission(context),
            NtfyAction.NtfyDownloadInstall(context, pushService, matrixClient),
            NtfyAction.RequestNotificationPermission(context),
            NtfyAction.Troubleshoot(context, troubleshootTestSuite, stepContext, pushService, matrixClient),
            NtfyAction.IgnoreBatteryOptimization(context)
        )

        fun ntfyActionList(): List<NtfyAction> {
            val context = activityContext ?: return emptyList()
            // 将 flavor 转换为统一的小写枚举或常量进行判断
            return when (buildMeta.flavorDescription.lowercase()) {
                "fdroid" -> getFdroidActions(context)
                //任何flavor，都采用同样的检测流程，只是当分发器不是ntfy时，就不再强制唤醒ntfy
                "googlepaly" -> getFdroidActions(context) //getGooglePlayActions(context)
                else -> getFdroidActions(context) // 默认兜底逻辑
            }
        }

        /** 机械化执行stepList，无状态(不判断该不该执行，不记录执行时间)，所以能反复调用、递归调用。
         * 返回true，表示执行了最后一步
         */
        suspend fun runStepList(ntfyActionList: List<NtfyAction>): Boolean {
            var i = 0
            while (i < ntfyActionList.size) {
                val step = ntfyActionList[i++]
                if (step.check()) {
                    if (ntfyAction != null) return false
                    ntfyAction = step
                    break
                }
            }
            return i >= ntfyActionList.size && i > 0
        }

        /**
         * ntfy充当distributor,只是为了让其它app显示通知。但是它自己也有显示通知的能力，就像手机短信。被人忽视了。
         * 如何配置，才能让它显示通知？只需订阅一个topic。
         */
        fun needCheckNtfy(duration: Long): Boolean {
            if (lastCheckTime == 0L) {
                lastCheckTime = System.currentTimeMillis()
                return false
            } else if (System.currentTimeMillis() - lastCheckTime < duration) {
                return false
            } else {
                lastCheckTime = System.currentTimeMillis()
                return true
            }
        }

        /** 检查ntfy。注意：权限列表是ROM厂商聚合的，无法通过代码调用，显示所有权限。 */
        suspend fun checkNtfy() {
            Timber.d("checkNtfy")
            if (userDelayed) return
            //下列检查，会打扰用户，因此在release版至少间隔6小时才执行一次。
            val duration = if (buildMeta.buildType == BuildType.DEBUG) {
                //if (!isLatter) 1 * 1000L else 60 * 1000
                if (!isLatter) 1 * 1000L else 6 * 60 * 60 * 1000
            } else {
                if (!isLatter) 1 * 1000L else 6 * 60 * 60 * 1000
            }
            val needRun = needCheckNtfy(duration)
            if (needRun) {
                val isFinish = runStepList(ntfyActionList())
                if (isFinish) {
                    isLatter = true
                }
            }
        }

        var checkJob by remember { mutableStateOf<Job?>(null) }
        /* 注意：WorkManager能替代while循环、ON_RESUME事件
        val work = PeriodicWorkRequestBuilder<CheckNtfyWorker>(
            30, TimeUnit.MINUTES
        ).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            "CheckNtfy",
            ExistingPeriodicWorkPolicy.KEEP,
            work
        ) */
        OnLifecycleEvent { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                /* 下面两端代码，都会导致短暂黑屏
                checkJob?.cancel()
                checkJob = coroutineScope.launch {
                    checkNtfy()
                }*/

                coroutineScope.launch {
                    checkJob?.cancelAndJoin()
                    checkJob = launch {
                        checkNtfy()
                    }
                }
            }
        }

        fun handleEvent(event: LoggedInEvents) {
            when (event) {
                is LoggedInEvents.CloseErrorDialog -> {
                    pusherRegistrationState.value = AsyncData.Uninitialized
                    if (event.doNotShowAgain) {
                        coroutineScope.launch {
                            pushService.setIgnoreRegistrationError(matrixClient.sessionId, true)
                        }
                    }
                }

                LoggedInEvents.CheckSlidingSyncProxyAvailability -> coroutineScope.launch {
                    forceNativeSlidingSyncMigration = matrixClient.needsForcedNativeSlidingSyncMigration().getOrDefault(false)
                }

                LoggedInEvents.LogoutAndMigrateToNativeSlidingSync -> coroutineScope.launch {
                    // Force the logout since Native Sliding Sync is already enforced by the SDK
                    matrixClient.logout(userInitiated = true, ignoreSdkError = true)
                }

                is LoggedInEvents.ChangeStep -> ntfyAction = event.ntfyAction
                is LoggedInEvents.UserDelayed -> {
                    userDelayed = event.delayed
                    if (!userDelayed) isLatter = false
                }

                is LoggedInEvents.IsLatter -> isLatter = event.isLatter
            }
        }

        return LoggedInState(
            showSyncSpinner = showSyncSpinner,
            pusherRegistrationState = pusherRegistrationState.value,
            ignoreRegistrationError = ignoreRegistrationError,
            forceNativeSlidingSyncMigration = forceNativeSlidingSyncMigration,
            appName = buildMeta.applicationName,
            eventSink = ::handleEvent,
            ntfyAction = ntfyAction
        )
    }

    // Force the user to log out if they were using the proxy sliding sync as it's no longer supported by the SDK
    private suspend fun MatrixClient.needsForcedNativeSlidingSyncMigration(): Result<Boolean> = runCatchingExceptions {
        val currentSlidingSyncVersion = currentSlidingSyncVersion().getOrThrow()
        currentSlidingSyncVersion == SlidingSyncVersion.Proxy
    }

    private fun List<PushProvider>.findNtfyProvider(): PushProvider? {
        return find { it is UnifiedPushProvider && it.getDistributors().isNotEmpty() }
            ?: firstOrNull { it.getDistributors().isNotEmpty() }
            ?: firstOrNull()
    }

    private fun List<Distributor>.findPreferredDistributor(): Distributor? {
        return find { it.fullName.lowercase().contains(NTFY_PACKAGE_NAME) } ?: firstOrNull()
    }

    private suspend fun ensurePusherIsRegistered(pusherRegistrationState: MutableState<AsyncData<Unit>>) {
        Timber.tag(pusherTag.value).d("Ensure pusher is registered")
        val currentPushProvider = pushService.getCurrentPushProvider(matrixClient.sessionId)
        val result = if (currentPushProvider == null) {
            Timber.tag(pusherTag.value).d("Register with the first available push provider with at least one distributor")
            val pushProvider = pushService.getAvailablePushProviders()
                .findNtfyProvider()
                ?: return Unit
                    .also { Timber.tag(pusherTag.value).w("No push providers available") }
                    .also { pusherRegistrationState.value = AsyncData.Failure(PusherRegistrationFailure.NoProvidersAvailable()) }
            val distributor = pushProvider.getDistributors()
                .findPreferredDistributor()
                ?: return Unit
                    .also { Timber.tag(pusherTag.value).w("No distributors available") }
                    .also {
                        // In this case, consider the push provider is chosen.
                        pushService.selectPushProvider(matrixClient.sessionId, pushProvider)
                    }
                    .also { pusherRegistrationState.value = AsyncData.Failure(PusherRegistrationFailure.NoDistributorsAvailable()) }
            pushService.registerWith(matrixClient, pushProvider, distributor)
        } else {
            val currentPushDistributor = currentPushProvider.getCurrentDistributor(matrixClient.sessionId)
            if (currentPushDistributor == null) {
                Timber.tag(pusherTag.value).d("Register with the first available distributor")
                val distributor = currentPushProvider.getDistributors()
                    .findPreferredDistributor()
                    ?: return Unit
                        .also { Timber.tag(pusherTag.value).w("No distributors available") }
                        .also { pusherRegistrationState.value = AsyncData.Failure(PusherRegistrationFailure.NoDistributorsAvailable()) }
                pushService.registerWith(matrixClient, currentPushProvider, distributor)
            } else {
                Timber.tag(pusherTag.value).d("Re-register with the current distributor")
                pushService.registerWith(matrixClient, currentPushProvider, currentPushDistributor)
            }
        }
        result.fold(
            onSuccess = {
                Timber.tag(pusherTag.value).d("Pusher registered")
                pusherRegistrationState.value = AsyncData.Success(Unit)
            },
            onFailure = {
                Timber.tag(pusherTag.value).e(it, "Failed to register pusher")
                if (it is RegistrationFailure) {
                    pusherRegistrationState.value = AsyncData.Failure(
                        PusherRegistrationFailure.RegistrationFailure(it.clientException, it.isRegisteringAgain)
                    )
                } else {
                    pusherRegistrationState.value = AsyncData.Failure(it)
                }
            }
        )
    }

    private fun reportCryptoStatusToAnalytics(verificationState: SessionVerifiedStatus, recoveryState: RecoveryState) {
        // Update first the user property, to store the current status for that posthog user
        val userVerificationState = verificationState.toAnalyticsUserPropertyValue()
        val userRecoveryState = recoveryState.toAnalyticsUserPropertyValue()
        if (userRecoveryState != null && userVerificationState != null) {
            // we want to report when both value are known (if one is unknown we wait until we have them both)
            analyticsService.updateUserProperties(
                UserProperties(
                    verificationState = userVerificationState,
                    recoveryState = userRecoveryState
                )
            )
        }

        // Also report when there is a change in the state, to be able to track the changes
        val changeVerificationState = verificationState.toAnalyticsStateChangeValue()
        val changeRecoveryState = recoveryState.toAnalyticsStateChangeValue()
        if (changeVerificationState != null && changeRecoveryState != null) {
            analyticsService.capture(CryptoSessionStateChange(changeRecoveryState, changeVerificationState))
        }
    }

    private fun CoroutineScope.preloadAccountManagementUrl() = launch {
        matrixClient.getAccountManagementUrl(AccountManagementAction.Profile)
        matrixClient.getAccountManagementUrl(AccountManagementAction.SessionsList)
    }
}
