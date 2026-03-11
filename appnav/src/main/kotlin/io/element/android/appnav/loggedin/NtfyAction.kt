package io.element.android.appnav.loggedin

import android.Manifest
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Context.POWER_SERVICE
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import androidx.core.net.toUri
import io.element.android.libraries.androidutils.system.toast
import io.element.android.libraries.matrix.api.MatrixClient
import io.element.android.libraries.push.api.PushService
import io.element.android.libraries.troubleshoot.api.test.NotificationTroubleshootTestState
import io.element.android.libraries.troubleshoot.impl.TroubleshootTestSuite
import io.element.android.libraries.troubleshoot.impl.TroubleshootTestSuiteState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream

class NtfyStepContext {
    var testState: TroubleshootTestSuiteState? = null
    var needStartNtfy: Boolean = false
    var failedCount: Int = 0
}

sealed class NtfyAction(
    val context: Context,
    /** 只能处理运行时权限(app弹窗请求)。如果索要特殊权限(跳转到系统设置界面)，请使用SystemUtils中的函数 */
    //permissionsPresenterFactory: PermissionsPresenter.Factory,
    //buildVersionSdkIntProvider: BuildVersionSdkIntProvider,
) {
    /*
    val postNotificationPermissionsPresenter: PermissionsPresenter =
        // Ask for POST_NOTIFICATION PERMISSION on Android 13+
        if (buildVersionSdkIntProvider.isAtLeast(Build.VERSION_CODES.TIRAMISU)) {
            permissionsPresenterFactory.create(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            NoopPermissionsPresenter()
        }*/

    abstract suspend fun check(): Boolean

    abstract fun confirm(result: DialogResult, loggedInState: LoggedInState)

    fun storeDialogResult(result: DialogResult, loggedInState: LoggedInState) {
        loggedInState.eventSink(LoggedInEvents.ChangeStep(null))
        when (result) {
            DialogResult.Ok -> loggedInState.eventSink(LoggedInEvents.UserDelayed(false))
            DialogResult.Latter -> loggedInState.eventSink(LoggedInEvents.IsLatter(true))
            DialogResult.Never -> loggedInState.eventSink(LoggedInEvents.UserDelayed(true))
        }
    }

    /** 启动ntfy，如果启动失败，就说明没权限，就设置“关联启动”权限 */
    fun tryStartApp(packageName: String, activityFullName: String, loggedInState: LoggedInState) {
        try {
            val intent = Intent().apply {
                component = ComponentName(packageName, activityFullName)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: SecurityException) {
            Timber.tag("Permission").e(e, "没有权限启动App")
            loggedInState.eventSink(
                LoggedInEvents.ChangeStep(RequestAssociatedStartPermission(context))
            )
        } catch (e: Exception) {
            Timber.tag("Permission").e(e, "启动失败")
        }
    }

    suspend fun setPushProvider(pushService: PushService, matrixClient: MatrixClient) {
        val distributors = pushService.getAvailablePushProviders()
            .flatMap { pushProvider ->
                pushProvider.getDistributors().map { distributor ->
                    pushProvider to distributor
                }
            }
        if (distributors.isNotEmpty()) {
            val (pushProvider, distributor) = distributors.find { (_, distributor) ->
                distributor.fullName.lowercase().contains(NTFY_PACKAGE_NAME)
            } ?: distributors[0] // 优先采用ntfy，如果没有ntfy，就取第一个元素
            pushService.registerWith(
                matrixClient = matrixClient,
                pushProvider = pushProvider,
                distributor = distributor
            ).fold(
                { Timber.d("setPushProvider success") },
                { Timber.d(it) }
            )
        }
    }

    fun openIgnoreBatteryOptimization() {
        val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        if (intent.resolveActivity(context.packageManager) != null) {
            context.startActivity(intent)
        } else {
            //如果走到这里，说明标准 Intent 返回了 null（国产机命中此处）
            // 开始厂商私有页面的“黑魔法”适配
            val manufacturer = Build.MANUFACTURER.lowercase()
            val vendorIntent = Intent()
            when {
                manufacturer.contains("huawei") || manufacturer.contains("honor") -> {
                    vendorIntent.component = ComponentName(
                        "com.huawei.systemmanager",
                        "com.huawei.systemmanager.appcontrol.activity.StartupAppControlActivity"
                    )
                }

                manufacturer.contains("xiaomi") || manufacturer.contains("redmi") -> {
                    vendorIntent.component = ComponentName(
                        "com.miui.securitycenter",
                        "com.miui.permcenter.autostart.AutoStartManagementActivity"
                    )

                    // MIUI备用方案：电池优化页面
                    if (intent.resolveActivity(context.packageManager) == null) {
                        vendorIntent.component = ComponentName(
                            "com.miui.powerkeeper",
                            "com.miui.powerkeeper.ui.HiddenAppsConfigActivity"
                        )
                    }
                }

                manufacturer.contains("vivo") || manufacturer.contains("iqoo") -> {
                    vendorIntent.component = ComponentName(
                        "com.iqoo.secure",
                        "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity"
                    )

                    // vivo备用方案：后台高耗电管理
                    if (intent.resolveActivity(context.packageManager) == null) {
                        vendorIntent.component = ComponentName(
                            "com.vivo.permissionmanager",
                            "com.vivo.permissionmanager.activity.PurviewTabActivity"
                        )
                        vendorIntent.putExtra("packagename", context.packageName)
                        vendorIntent.putExtra("tabId", "1") // 通常是后台高耗电页面
                    }
                }

                manufacturer.contains("oppo") || manufacturer.contains("realme") || manufacturer.contains(
                    "oneplus"
                ) -> {
                    vendorIntent.component = ComponentName(
                        "com.coloros.safecenter",
                        "com.coloros.safecenter.permission.startup.StartupAppListActivity"
                    )

                    // ColorOS备用方案：后台耗电管理
                    if (intent.resolveActivity(context.packageManager) == null) {
                        vendorIntent.component = ComponentName(
                            "com.coloros.oppoguardelf",
                            "com.coloros.powermanager.fuelgaue.PowerUsageModelActivity"
                        )
                        vendorIntent.putExtra("extra_package_name", context.packageName)
                    }
                }

                manufacturer.contains("meizu") -> {
                    vendorIntent.component = ComponentName(
                        "com.meizu.safe",
                        "com.meizu.safe.permission.SmartBgActivity"
                    )

                    // Flyme备用方案
                    if (intent.resolveActivity(context.packageManager) == null) {
                        vendorIntent.component = ComponentName(
                            "com.meizu.permissioncenter",
                            "com.meizu.permissionmanager.activity.ApplicationManageActivity"
                        )
                        vendorIntent.putExtra(
                            "extra_app_permission_group_key",
                            "smart_background"
                        )
                    }
                }

                manufacturer.contains("zte") || manufacturer.contains("nubia") -> {
                    vendorIntent.component = ComponentName(
                        "com.zte.heartyservice",
                        "com.zte.heartyservice.setting.ClearAppSettingsActivity"
                    )
                }

                manufacturer.contains("lenovo") -> {
                    vendorIntent.component = ComponentName(
                        "com.lenovo.security",
                        "com.lenovo.security.purebackground.PureBackgroundActivity"
                    )
                }

                manufacturer.contains("smartisan") -> {
                    vendorIntent.component = ComponentName(
                        "com.smartisanos.security",
                        "com.smartisanos.security.PermissionControlActivity"
                    )
                    vendorIntent.putExtra("pkg", context.packageName)
                    vendorIntent.putExtra("type", "background")
                }

                manufacturer.contains("asus") -> {
                    vendorIntent.component = ComponentName(
                        "com.asus.mobilemanager",
                        "com.asus.mobilemanager.autostart.AutoStartActivity"
                    )
                }

                manufacturer.contains("samsung") -> {
                    // 三星通常支持标准Intent，但这里作为备用
                    vendorIntent.component = ComponentName(
                        "com.samsung.android.sm",
                        "com.samsung.android.sm.ui.battery.AppSleepListActivity"
                    )
                }
            }

            // 4. 尝试跳转私有页面（这里必须用 try-catch，因为包名/类名可能随系统更新失效）
            if (vendorIntent.resolveActivity(context.packageManager) != null) {
                try {
                    context.startActivity(vendorIntent)
                } catch (_: Exception) {
                    // 如果私有页面也跳转失败（如类名改了），最终兜底方案
                    //Toast.makeText(context, "跳转失败，请手动前往“手机管家”设置", Toast.LENGTH_LONG).show()
                }
            } else {
                showManualGuideToast(context)
            }
        }
    }

    private fun showManualGuideToast(context: Context) {
        val fullMessage = """请为$NTFY_APK_NAME 手动设置：
                ${tipsRequestIgnoreBattery()}。
                额外步骤：某些手机需要点击“按钮所在的一整行”才能真正进入设置页面。"""

        // 使用长时Toast
        val toast = Toast.makeText(context, fullMessage, Toast.LENGTH_LONG)
        // 在Android 8.0+上，可以增加显示时间
        if (Build.VERSION.SDK_INT < 30) { // 在Android 11之前
            val m = toast::class.java.getMethod("setDuration", Int::class.java)
            m.invoke(toast, 5000) // 5秒
        }
        toast.show()
    }

    fun tipsRequestIgnoreBattery(): String {
        val manufacturer = Build.MANUFACTURER.lowercase()
        val settingPath = when {
            manufacturer.contains("huawei") || manufacturer.contains("honor") -> "设置 > 应用启动管理 > 手动管理(三项全开)"
            manufacturer.contains("vivo") || manufacturer.contains("iqoo") -> "设置 > 电池 > 后台高耗电管理 > 允许后台高耗电"
            manufacturer.contains("oppo") || manufacturer.contains("realme") -> "设置 > 电池 > 省电设置 > 应用耗电管理 > 允许完全后台行为"
            manufacturer.contains("meizu") -> "设置 > 电池 > 应用耗电管理 > 忽略电池优化(允许后台运行)"
            manufacturer.contains("oneplus") -> "设置 > 电池 > 电池优化\n 或：电池 > 更多设置(高级优化) > 优化电池使用 > 不优化"
            manufacturer.contains("xiaomi") -> "设置 > 电量和性能 > 应用智能省电"
            manufacturer.contains("samsung") -> "设置 > 电池 > 后台使用限制 > 不允许休眠的app"
            else -> "设置 > 电池 > 电池优化"
        }
        return settingPath
    }

    //1.给当前app检查并设置权限“安装未知应用”。(自启动权限，没有统一入口)。
    class RequestInstallUnknownAppsPermission(context: Context) : NtfyAction(context = context) {
        override suspend fun check() = Build.VERSION.SDK_INT < Build.VERSION_CODES.O || !context.packageManager.canRequestPackageInstalls()
        override fun confirm(result: DialogResult, loggedInState: LoggedInState) {
            storeDialogResult(result, loggedInState)
            if (result == DialogResult.Ok && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                var intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
                    .setData("package:${context.packageName}".toUri())
                if (intent.resolveActivity(context.packageManager) == null) {
                    intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = Uri.fromParts("package", context.packageName, null)
                    }
                }
                context.startActivity(intent)
            }
        }
    }

    //2.检查ntfy，如果没有，就下载并安装
    class NtfyDownloadInstall(
        context: Context,
        val pushService: PushService,
        val matrixClient: MatrixClient,
    ) : NtfyAction(context = context) {
        override suspend fun check(): Boolean {
            delay(3000) //等待上一轮安装完成，才能继续检查。安装，需要两三秒
            return !isAppInstalled(NTFY_PACKAGE_NAME)
        }

        override fun confirm(result: DialogResult, loggedInState: LoggedInState) {
            storeDialogResult(result, loggedInState)
            if (result == DialogResult.Ok) {
                val apkFile = copyApkFromAssets(context, "$NTFY_APK_NAME.apk")
                if (apkFile != null && apkFile.exists()) {
                    installApkAndCleanup(context, apkFile, NTFY_PACKAGE_NAME, loggedInState)
                } else {
                    context.toast("安装文件提取失败")
                    cleanupAllNtfyApkFiles(context)
                }
            }
        }

        fun isAppInstalled(packageName: String): Boolean {
            try {
                //获取某app的信息，需要在mainfest中声明queries标签
                context.packageManager.getPackageInfo(packageName, 0)
                return true
            } catch (_: PackageManager.NameNotFoundException) {
                return false
            }
        }

        /** 安装APK。 mainFest.xml中有了REQUEST_INSTALL_PACKAGES声明，当前app才能打开安装界面
         *  Intent.ACTION_VIEW，目标类型是APK文件，会将安装任务交给系统
         *  */
        fun installApkAndCleanup(context: Context, apkFile: File, targetPackage: String, loggedInState: LoggedInState) {
            val packageInstaller = context.packageManager.packageInstaller
            // 1. 配置安装参数
            val sessionParams =
                PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
                    setAppPackageName(targetPackage)
                }
            var sessionId = -1
            try {
                // 2. 创建安装会话
                sessionId = packageInstaller.createSession(sessionParams)
                val session = packageInstaller.openSession(sessionId)

                // 3. 将 APK 文件内容写入 Session
                apkFile.inputStream().use { inputStream ->
                    session.openWrite("ntfy_install", 0, apkFile.length()).use { outputStream ->
                        inputStream.copyTo(outputStream)
                        session.fsync(outputStream)
                    }
                }

                // 4. 准备安装结果的回调广播
                val receiverAction = "${context.packageName}.INSTALL_STATUS_REPORT"
                val intent = Intent(receiverAction).apply {
                    // 确保广播只发送给当前应用
                    setPackage(context.packageName)
                }

                // 动态注册结果监听广播
                val receiver = object : BroadcastReceiver() {
                    override fun onReceive(ctx: Context, intent: Intent) {
                        val status = intent.getIntExtra(
                            PackageInstaller.EXTRA_STATUS,
                            PackageInstaller.STATUS_FAILURE
                        )
                        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)

                        when (status) {
                            PackageInstaller.STATUS_SUCCESS -> {
                                Timber.d("安装成功: $targetPackage")
                                cleanupApkFile(apkFile) // 安装成功后清理
                                ctx.unregisterReceiver(this)
                                tryStartApp(targetPackage, NTFY_ACTIVITY_MAIN, loggedInState)
                                CoroutineScope(Dispatchers.Main).launch {
                                    delay(500) //启动ntfy，需要一点时间
                                    setPushProvider(pushService, matrixClient)
                                }
                            }

                            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                                // 弹出系统安装确认界面
                                val confirmIntent = IntentCompat.getParcelableExtra(
                                    intent,
                                    Intent.EXTRA_INTENT,
                                    Intent::class.java
                                )
                                if (confirmIntent != null) {
                                    confirmIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    ctx.startActivity(confirmIntent)
                                }
                            }

                            else -> {
                                Timber.e("安装失败 ($status): $message")
                                // 失败也可以考虑清理，或者留给下一次检查清理
                                ctx.unregisterReceiver(this)
                            }
                        }
                    }
                }

                // 注意：Android 14+ 动态注册广播需要指定 RECEIVER_NOT_EXPORTED
                val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    Context.RECEIVER_NOT_EXPORTED
                } else 0
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.registerReceiver(receiver, IntentFilter(receiverAction), flags)
                }
                // 5. 提交会话，触发安装进程
                val pendingIntent = PendingIntent.getBroadcast(
                    context,
                    sessionId,
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
                )

                session.commit(pendingIntent.intentSender)
                session.close()
            } catch (e: Exception) {
                Timber.e(e, "PackageInstaller 安装过程出现异常")
                if (sessionId != -1) {
                    try {
                        packageInstaller.abandonSession(sessionId)
                    } catch (_: Exception) {
                    }
                }
            }
        }

        /** 清理APK临时文件 */
        fun cleanupApkFile(apkFile: File) {
            try {
                if (apkFile.exists()) {
                    val deleted = apkFile.delete()
                    if (deleted) {
                        Timber.d("Successfully cleaned up temporary APK file: ${apkFile.name}")
                    } else {
                        Timber.w("Failed to delete temporary APK file: ${apkFile.name}")
                    }
                }
            } catch (e: Exception) {
                Timber.e(e, "Error while cleaning up APK file: ${apkFile.name}")
            }
        }

        /** 批量清理所有ntfy相关的临时APK文件 */
        fun cleanupAllNtfyApkFiles(context: Context) {
            try {
                val cacheDir = context.cacheDir
                val ntfyApkFiles = cacheDir.listFiles { file ->
                    file.name.startsWith(NTFY_APK_NAME) && file.name.endsWith(".apk")
                }
                ntfyApkFiles?.forEach { file ->
                    if (file.delete()) {
                        Timber.d("Cleaned up old APK file: ${file.name}")
                    }
                }
            } catch (e: Exception) {
                Timber.e(e, "Error while cleaning up all NTFY APK files")
            }
        }

        fun copyApkFromAssets(context: Context, assetFileName: String): File? {
            return try {
                // 先清理旧的APK文件
                cleanupAllNtfyApkFiles(context)
                context.assets.open(assetFileName).use { inputStream ->
                    val apkSize = inputStream.available().toLong()
                    val maxApkSize = 400L * 1024 * 1024 // 400MB
                    require(apkSize < maxApkSize) {
                        "APK too large: $apkSize bytes"
                    }
                    val outputFile = File(context.cacheDir, assetFileName)
                    // 确保父目录存在
                    outputFile.parentFile?.let { parent ->
                        if (!parent.exists()) {
                            parent.mkdirs()
                        }
                    }
                    FileOutputStream(outputFile).use { outputStream ->
                        inputStream.copyTo(outputStream)
                    }
                    // 设置文件权限（可选）
                    outputFile.setWritable(true, true)  // 只有本应用可写

                    Timber.d("Successfully copied APK from assets to: ${outputFile.absolutePath}")
                    outputFile
                }
            } catch (e: Exception) {
                Timber.e(e, "copyApkFromAssets error")
                null
            }
        }
    }

    //3. 索要通知权限。在“排查通知问题”页面，也能开启通知权限，因此不必要在这里开启？还是需要的。这里有强制效果，防止用户不去点击页面的“修复问题”。
    class RequestNotificationPermission(context: Context) : NtfyAction(context = context) {
        override suspend fun check(): Boolean {
            val showNotificationPermissionDialog =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                    ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                else !NotificationManagerCompat.from(context).areNotificationsEnabled()
            return showNotificationPermissionDialog
        }

        override fun confirm(result: DialogResult, loggedInState: LoggedInState) {
            storeDialogResult(result, loggedInState)
            if (result == DialogResult.Ok) {
                //方法a: 把“通知”看作运行时权限
                //notificationsPermissionsState.eventSink(PermissionsEvents.RequestPermissions)
                //方法b: 把“通知”看作特殊权限，会更稳妥。万一用户从系统设置界面关闭了通知，就无法请求运行时权限。
                val intent = Intent()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    intent.action = Settings.ACTION_APP_NOTIFICATION_SETTINGS
                    intent.putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                } else {
                    intent.action = Settings.ACTION_APPLICATION_DETAILS_SETTINGS
                    intent.data = Uri.fromParts("package", context.packageName, null)
                }
                context.startActivity(intent)
            }
        }
    }

    //4.在后台静默运行“排查通知问题”，如果不成功, 再打开“排查通知问题”页面让用户自己解决。
    //如果仅仅是“推送回路”不通，就启动ntfy(如果启动不了，再索要“关联启动”权限)，不打开排查页面。
    class Troubleshoot(
        context: Context,
        private val troubleshootTestSuite: TroubleshootTestSuite,
        private var stepContext: NtfyStepContext,
        private var pushService: PushService,
        private var matrixClient: MatrixClient,
    ) : NtfyAction(context = context) {
        var needOpenTroubleshoot: Boolean = false
        override suspend fun check(): Boolean {
            stepContext.testState = runTroubleshootTest()
            val testState = stepContext.testState
            stepContext.failedCount = testState?.tests?.count { it.status !is NotificationTroubleshootTestState.Status.Success } ?: 100
            stepContext.needStartNtfy = testState == null
                || testState.mainState.isConfirming() && stepContext.failedCount >= 2
            needOpenTroubleshoot = stepContext.failedCount > 2
            return stepContext.needStartNtfy
        }

        override fun confirm(result: DialogResult, loggedInState: LoggedInState) {
            storeDialogResult(result, loggedInState)
            if (result == DialogResult.Ok) {
                CoroutineScope(Dispatchers.Default).launch {
                    val isNtfy = pushService.getCurrentPushProvider(matrixClient.sessionId)
                        ?.getCurrentDistributor(matrixClient.sessionId)
                        ?.fullName?.lowercase()?.contains(NTFY_PACKAGE_NAME) ?: false
                    if (isNtfy) {
                        //ntfy可能不需要启动进程，而是启动后台服务就行。
                        tryStartApp(
                            NTFY_PACKAGE_NAME,
                            NTFY_ACTIVITY_MAIN,
                            loggedInState
                        )
                    }
                }
            }
        }

        suspend fun runTroubleshootTest(): TroubleshootTestSuiteState? {
            return coroutineScope {
                // 重置测试套件状态，确保每次都重新运行测试
                troubleshootTestSuite.reset()
                val resultChannel = Channel<TroubleshootTestSuiteState?>(Channel.RENDEZVOUS)
                val stateObserver = launch {
                    troubleshootTestSuite.state.collect { state ->
                        val isTerminalStatus = { status: NotificationTroubleshootTestState.Status ->
                            status is NotificationTroubleshootTestState.Status.Success ||
                                status is NotificationTroubleshootTestState.Status.Failure ||
                                status is NotificationTroubleshootTestState.Status.WaitingForUser
                        }
                        if (state.tests.isNotEmpty() && state.tests.all { isTerminalStatus(it.status) }) {
                            resultChannel.send(state)
                        }
                    }
                }
                val testJob = launch(Dispatchers.Default) {
                    troubleshootTestSuite.start(this)
                    troubleshootTestSuite.runTestSuite(this)
                }
                try {
                    withTimeout(5000) {
                        resultChannel.receive()
                    }
                } catch (_: TimeoutCancellationException) {
                    null
                } finally {
                    stateObserver.cancel()
                    testJob.cancel()
                    resultChannel.close()
                }
            }
        }
    }

    //注意：(不在队列中执行，有其它队列元素负责调用) 获取关联启动权限
    class RequestAssociatedStartPermission(context: Context) : NtfyAction(context = context) {
        override suspend fun check() = true
        override fun confirm(result: DialogResult, loggedInState: LoggedInState) {
            storeDialogResult(result, loggedInState)
            if (result == DialogResult.Ok) goStartOtherAppSetting(context)
        }

        /** 关联启动 */
        fun goStartOtherAppSetting(context: Context) {
            val manufacturer = Build.MANUFACTURER.lowercase()
            // 第一轮尝试：厂商定制页面
            val primaryIntent = createManufacturerIntent(manufacturer)
            if (tryStartActivity(context, primaryIntent)) {
                return
            }
            // 第二轮尝试：备用方案
            val backupIntent = createBackupIntent(manufacturer)
            if (tryStartActivity(context, backupIntent)) {
                return
            }
            // 所有跳转都失败，回退到应用详情页
            openAppDetailSetting(context)
        }

        /** app的系统设置页 */
        private fun openAppDetailSetting(context: Context) {
            // 最终兜底方案：跳转到应用详情页
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", context.packageName, null)
            }
            try {
                if (intent.resolveActivity(context.packageManager) != null) {
                    context.startActivity(intent)
                }
            } catch (_: Exception) {
            }
        }

        private fun createManufacturerIntent(manufacturer: String): Intent? {
            return when {
                manufacturer.contains("xiaomi") || manufacturer.contains("redmi") ->
                    Intent().apply {
                        component = ComponentName(
                            "com.miui.securitycenter",
                            "com.miui.permcenter.autostart.AutoStartManagementActivity"
                        )
                    }

                manufacturer.contains("huawei") || manufacturer.contains("honor") ->
                    Intent().apply {
                        component = ComponentName(
                            "com.huawei.systemmanager",
                            "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"
                        )
                    }

                manufacturer.contains("oppo") || manufacturer.contains("realme") || manufacturer.contains(
                    "oneplus"
                ) ->
                    Intent().apply {
                        component = ComponentName(
                            "com.coloros.safecenter",
                            "com.coloros.safecenter.startupapp.StartupAppListActivity"
                        )
                    }

                manufacturer.contains("vivo") || manufacturer.contains("iqoo") ->
                    Intent().apply {
                        component = ComponentName(
                            "com.iqoo.secure",
                            "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity"
                        )
                    }

                manufacturer.contains("meizu") ->
                    Intent().apply {
                        component = ComponentName(
                            "com.meizu.safe",
                            "com.meizu.safe.permission.SmartBgActivity"
                        )
                    }

                manufacturer.contains("samsung") ->
                    Intent().apply {
                        component = ComponentName(
                            "com.samsung.android.lool",  // 电池与设备维护
                            "com.samsung.android.sm.ui.battery.BatteryActivity"
                        )
                    }

                manufacturer.contains("zte") || manufacturer.contains("nubia") ->
                    Intent().apply {
                        component = ComponentName(
                            "com.zte.heartyservice",
                            "com.zte.heartyservice.setting.StartupAppListActivity"
                        )
                    }

                manufacturer.contains("lenovo") ->
                    Intent().apply {
                        component = ComponentName(
                            "com.lenovo.security",
                            "com.lenovo.security.purebackground.PureBackgroundActivity"
                        )
                    }

                manufacturer.contains("smartisan") ->
                    Intent().apply {
                        component = ComponentName(
                            "com.smartisanos.security",
                            "com.smartisanos.security.PermissionControlActivity"
                        )
                        putExtra("type", "association_start")  // 坚果需要传参
                    }

                manufacturer.contains("asus") ->
                    Intent().apply {
                        component = ComponentName(
                            "com.asus.mobilemanager",
                            "com.asus.mobilemanager.autostart.AutoStartActivity"
                        )
                    }

                else -> null
            }
        }

        private fun createBackupIntent(manufacturer: String): Intent? {
            return when {
                manufacturer.contains("xiaomi") || manufacturer.contains("redmi") ->
                    Intent().apply {
                        component = ComponentName(
                            "com.miui.powerkeeper",
                            "com.miui.powerkeeper.ui.HiddenAppsConfigActivity"
                        )
                    }

                manufacturer.contains("huawei") || manufacturer.contains("honor") ->
                    Intent().apply {
                        component = ComponentName(
                            "com.huawei.systemmanager",
                            "com.huawei.systemmanager.optimize.process.ProtectActivity"
                        )
                    }

                manufacturer.contains("oppo") || manufacturer.contains("realme") || manufacturer.contains(
                    "oneplus"
                ) ->
                    Intent().apply {
                        component = ComponentName(
                            "com.coloros.oppoguardelf",
                            "com.coloros.powermanager.fuelgaue.PowerUsageModelActivity"
                        )
                    }

                manufacturer.contains("vivo") || manufacturer.contains("iqoo") ->
                    Intent().apply {
                        component = ComponentName(
                            "com.vivo.permissionmanager",
                            "com.vivo.permissionmanager.activity.PurviewTabActivity"
                        )
                        putExtra("tabId", "2")  // 2通常是关联启动/自启动页面
                    }

                manufacturer.contains("meizu") ->
                    Intent().apply {
                        component = ComponentName(
                            "com.meizu.permissioncenter",
                            "com.meizu.permissionmanager.activity.AssociationStartupActivity"
                        )
                    }

                manufacturer.contains("samsung") ->
                    Intent().apply {
                        component = ComponentName(
                            "com.samsung.android.sm",  // 设备维护
                            "com.samsung.android.sm.ui.appmanagement.AppManagementActivity"
                        )
                    }

                else -> null
            }
        }

        private fun tryStartActivity(context: Context, intent: Intent?): Boolean {
            if (intent == null) return false
            return try {
                if (intent.resolveActivity(context.packageManager) != null) {
                    context.startActivity(intent)
                    true
                } else {
                    false
                }
            } catch (_: Exception) {
                false
            }
        }
        //end 关联启动
    }

    //5. 给ntfy检查并设置权限(允许高耗电)。(无权检查ntfy的通知权限)
    class IgnoreBatteryOptimization(context: Context) : NtfyAction(context = context) {
        override suspend fun check() = !(context.getSystemService(POWER_SERVICE) as PowerManager)
            .isIgnoringBatteryOptimizations(NTFY_PACKAGE_NAME)

        override fun confirm(result: DialogResult, loggedInState: LoggedInState) {
            storeDialogResult(result, loggedInState)
            if (result == DialogResult.Ok) {
                //这一行不要了。让用户手动去ntfy界面设置？不靠谱
                openIgnoreBatteryOptimization()
            }
        }
    }
}
