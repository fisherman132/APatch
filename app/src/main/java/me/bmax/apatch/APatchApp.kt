package me.bmax.apatch

import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.util.Log
import android.widget.Toast
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.topjohnwu.superuser.CallbackList
import me.bmax.apatch.ui.CrashHandleActivity
import me.bmax.apatch.util.APatchCli
import me.bmax.apatch.util.APatchKeyHelper
import me.bmax.apatch.util.Version
import me.bmax.apatch.util.getRootShell
import me.bmax.apatch.util.rootShellForResult
import me.bmax.apatch.util.verifyAppSignature
import okhttp3.Cache
import okhttp3.OkHttpClient
import java.io.File
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.system.exitProcess

lateinit var apApp: APApplication

const val TAG = "APatch"

class APApplication : Application(), Thread.UncaughtExceptionHandler {
    lateinit var okhttpClient: OkHttpClient

    init {
        Thread.setDefaultUncaughtExceptionHandler(this)
    }

    enum class State {
        UNKNOWN_STATE,

        KERNELPATCH_INSTALLED, KERNELPATCH_NEED_UPDATE, KERNELPATCH_NEED_REBOOT, KERNELPATCH_UNINSTALLING,

        ANDROIDPATCH_NOT_INSTALLED, ANDROIDPATCH_INSTALLED, ANDROIDPATCH_INSTALLING, ANDROIDPATCH_NEED_UPDATE, ANDROIDPATCH_UNINSTALLING,
    }


    companion object {
        const val APD_PATH = "/data/adb/apd"

        @Deprecated("No more KPatch ELF from 0.11.0-dev")
        const val KPATCH_PATH = "/data/adb/kpatch"
        const val SUPERCMD = "/system/bin/truncate"
        const val APATCH_FOLDER = "/data/adb/ap/"
        private const val APATCH_BIN_FOLDER = APATCH_FOLDER + "bin/"
        private const val APATCH_LOG_FOLDER = APATCH_FOLDER + "log/"
        private const val APD_LINK_PATH = APATCH_BIN_FOLDER + "apd"
        const val PACKAGE_CONFIG_FILE = APATCH_FOLDER + "package_config"
        const val SU_PATH_FILE = APATCH_FOLDER + "su_path"
        const val SAFEMODE_FILE = "/dev/.safemode"
        private const val NEED_REBOOT_FILE = "/dev/.need_reboot"
        const val GLOBAL_NAMESPACE_FILE = "/data/adb/.global_namespace_enable"
        const val SUCOMPAT_FILE = "/data/adb/ap/sucompat"
        const val JAILBREAK_FILE = APATCH_FOLDER + "jailbreak"
        const val JAILBREAK_KO_PATH = APATCH_FOLDER + "kernelpatch.ko"
        const val KPMS_DIR = APATCH_FOLDER + "kpms/"

        @Deprecated("Use 'apd -V'")
        const val APATCH_VERSION_PATH = APATCH_FOLDER + "version"
        private const val MAGISKPOLICY_BIN_PATH = APATCH_BIN_FOLDER + "magiskpolicy"
        private const val BUSYBOX_BIN_PATH = APATCH_BIN_FOLDER + "busybox"
        private const val RESETPROP_BIN_PATH = APATCH_BIN_FOLDER + "resetprop"
        private const val KPTOOLS_BIN_PATH = APATCH_BIN_FOLDER + "kptools"
        const val DEFAULT_SCONTEXT = "u:r:untrusted_app:s0"
        const val MAGISK_SCONTEXT = "u:r:magisk:s0"

        private const val DEFAULT_SU_PATH = "/system/bin/kp"
        private const val LEGACY_SU_PATH = "/system/bin/su"

        const val SP_NAME = "config"
        private const val SHOW_BACKUP_WARN = "show_backup_warning"
        private const val AUTO_PRODUCT_SCHEMA_KEY = "auto_product_schema"
        private const val AUTO_PRODUCT_SCHEMA = 2
        lateinit var sharedPreferences: SharedPreferences

        private val logCallback: CallbackList<String?> = object : CallbackList<String?>() {
            override fun onAddElement(s: String?) {
                Log.d(TAG, s.toString())
            }
        }

        private val _kpStateLiveData = MutableLiveData(State.UNKNOWN_STATE)
        val kpStateLiveData: LiveData<State> = _kpStateLiveData

        private val _apStateLiveData = MutableLiveData(State.UNKNOWN_STATE)
        val apStateLiveData: LiveData<State> = _apStateLiveData
        private val autoAndroidPatchAttempted = AtomicBoolean(false)

        data class AndroidPatchCommandResult(
            val success: Boolean,
            val output: String,
        )

        fun runRestrictedAndroidPatch(action: String): AndroidPatchCommandResult {
            require(action == "install" || action == "uninstall" || action == "status")
            return runCatching {
                val actionCode = when (action) {
                    "status" -> 0
                    "install" -> 1
                    else -> 2
                }
                var result = Natives.androidPatchAction(actionCode)
                if (action == "install") {
                    var preparationAttempts = 0
                    while ((result == -1L || result == -2L) && preparationAttempts < 30) {
                        Thread.sleep(100)
                        result = Natives.androidPatchAction(actionCode)
                        preparationAttempts++
                    }
                }
                if (action != "status" && result == 0L) {
                    val sourceDir = apApp.applicationInfo.nativeLibraryDir
                    val arguments = mutableListOf(
                        "/system/bin/linker64",
                        APD_PATH,
                        "internal-androidpatch",
                        action,
                    )
                    if (action == "install") {
                        arguments += listOf("--source-dir", sourceDir)
                    }
                    val process = ProcessBuilder(arguments).redirectErrorStream(true).start()
                    if (!process.waitFor(30, TimeUnit.SECONDS)) {
                        process.destroyForcibly()
                        return AndroidPatchCommandResult(false, "AndroidPatch installer timed out")
                    }
                    val output = process.inputStream.bufferedReader().use { it.readText() }.trim()
                    val installerExit = process.exitValue()
                    val expectedVersion = if (action == "install") {
                        Version.getManagerVersion().second
                    } else {
                        0L
                    }
                    var installedVersion = Natives.androidPatchAction(0)
                    var statusAttempts = 0
                    while (installedVersion != expectedVersion && statusAttempts < 20) {
                        Thread.sleep(100)
                        installedVersion = Natives.androidPatchAction(0)
                        statusAttempts++
                    }
                    return AndroidPatchCommandResult(
                        installerExit == 0 && installedVersion == expectedVersion,
                        if (output.isNotEmpty()) {
                            "$output exit=$installerExit version=$installedVersion"
                        } else {
                            "installer=$installerExit version=$installedVersion"
                        },
                    )
                }
                when (action) {
                    "status" -> AndroidPatchCommandResult(
                        result >= 0,
                        "androidpatch=${if (result > 0) "installed" else "not-installed"} version=$result",
                    )
                    "install" -> AndroidPatchCommandResult(
                        result == 0L,
                        if (result == 0L) "androidpatch=installed" else "error=$result",
                    )
                    else -> AndroidPatchCommandResult(
                        result == 0L,
                        if (result == 0L) "androidpatch=uninstalled" else "error=$result",
                    )
                }
            }.getOrElse { throwable ->
                Log.w(TAG, "Restricted AndroidPatch $action unavailable", throwable)
                AndroidPatchCommandResult(false, throwable.message.orEmpty())
            }
        }

        @Suppress("DEPRECATION")
        fun uninstallApatch(): Boolean {
            val previousState = _apStateLiveData.value
            if (previousState != State.ANDROIDPATCH_INSTALLED) return false
            _apStateLiveData.postValue(State.ANDROIDPATCH_UNINSTALLING)

            return runCatching {
                val restricted = runRestrictedAndroidPatch("uninstall")
                if (restricted.success) {
                    Log.d(TAG, "APatch uninstalled through restricted manager channel")
                    sharedPreferences.edit { remove(AUTO_PRODUCT_SCHEMA_KEY) }
                    _apStateLiveData.postValue(State.ANDROIDPATCH_NOT_INSTALLED)
                    return true
                }
                Log.i(TAG, "Restricted AndroidPatch uninstall unavailable: ${restricted.output}")

                Natives.resetSuPath(DEFAULT_SU_PATH)

                val cmds = arrayOf(
                    "rm -f $APD_PATH",
                    "rm -f $KPATCH_PATH",
                    "rm -rf $APATCH_BIN_FOLDER",
                    "rm -rf $APATCH_LOG_FOLDER",
                    "rm -rf $APATCH_VERSION_PATH",
                )

                val shell = getRootShell()
                if (!shell.isRoot) {
                    Log.e(TAG, "APatch uninstall failed: root shell is unavailable")
                    _apStateLiveData.postValue(previousState)
                    return false
                }
                val result = shell.newJob().add(*cmds).to(logCallback, logCallback).exec()
                if (!result.isSuccess) {
                    Log.e(TAG, "APatch uninstall failed: ${result.err.joinToString("\n")}")
                    _apStateLiveData.postValue(previousState)
                    return false
                }

                Log.d(TAG, "APatch uninstalled...")
                if (_kpStateLiveData.value == State.UNKNOWN_STATE) {
                    _apStateLiveData.postValue(State.UNKNOWN_STATE)
                } else {
                    _apStateLiveData.postValue(State.ANDROIDPATCH_NOT_INSTALLED)
                }
                true
            }.getOrElse { throwable ->
                Log.e(TAG, "APatch uninstall crashed", throwable)
                _apStateLiveData.postValue(previousState)
                false
            }
        }

        @Suppress("DEPRECATION")
        fun installApatch(): Boolean {
            val state = _apStateLiveData.value
            if (state != State.ANDROIDPATCH_NOT_INSTALLED && state != State.ANDROIDPATCH_NEED_UPDATE) {
                return false
            }
            _apStateLiveData.postValue(State.ANDROIDPATCH_INSTALLING)

            return runCatching {
                val restricted = runRestrictedAndroidPatch("install")
                if (restricted.success) {
                    Log.d(TAG, "APatch installed through restricted manager channel")
                    sharedPreferences.edit {
                        putInt(AUTO_PRODUCT_SCHEMA_KEY, AUTO_PRODUCT_SCHEMA)
                    }
                    _apStateLiveData.postValue(State.ANDROIDPATCH_INSTALLED)
                    return true
                }
                Log.i(TAG, "Restricted AndroidPatch install unavailable: ${restricted.output}")

                val nativeDir = apApp.applicationInfo.nativeLibraryDir

                Natives.resetSuPath(LEGACY_SU_PATH)

                val cmds = arrayOf(
                    "mkdir -p $APATCH_BIN_FOLDER",
                    "mkdir -p $APATCH_LOG_FOLDER",

                    "cp -f ${nativeDir}/libapd.so $APD_PATH",
                    "chmod +x $APD_PATH",
                    "ln -s $APD_PATH $APD_LINK_PATH",
                    "restorecon $APD_PATH",

                    "rm -f $MAGISKPOLICY_BIN_PATH",
                    "ln -s $APD_PATH $MAGISKPOLICY_BIN_PATH",
                    "rm -f $RESETPROP_BIN_PATH",
                    "ln -s $APD_PATH $RESETPROP_BIN_PATH",

                    "cp -f ${nativeDir}/libbusybox.so $BUSYBOX_BIN_PATH",
                    "chmod +x $BUSYBOX_BIN_PATH",
                    "cp -f ${nativeDir}/libkptools.so $KPTOOLS_BIN_PATH",
                    "chmod +x $KPTOOLS_BIN_PATH",

                    "touch $PACKAGE_CONFIG_FILE",
                    "touch $SU_PATH_FILE",
                    "[ -s $SU_PATH_FILE ] || echo $LEGACY_SU_PATH > $SU_PATH_FILE",
                    "echo ${Version.getManagerVersion().second} > $APATCH_VERSION_PATH",
                    "restorecon -R $APATCH_FOLDER",

                    "${nativeDir}/libmagiskpolicy.so --magisk --live",
                )

                val shell = getRootShell()
                if (!shell.isRoot) {
                    Log.e(TAG, "APatch install failed: root shell is unavailable")
                    _apStateLiveData.postValue(state)
                    return false
                }
                val result = shell.newJob().add(*cmds).to(logCallback, logCallback).exec()
                if (!result.isSuccess) {
                    Log.e(TAG, "APatch install failed: ${result.err.joinToString("\n")}")
                    _apStateLiveData.postValue(state)
                    return false
                }

                runCatching { APatchCli.refresh() }.onFailure {
                    Log.w(TAG, "APatch shell refresh failed after install", it)
                }

                Log.d(TAG, "APatch installed...")
                _apStateLiveData.postValue(State.ANDROIDPATCH_INSTALLED)
                true
            }.getOrElse { throwable ->
                Log.e(TAG, "APatch install crashed", throwable)
                _apStateLiveData.postValue(state)
                false
            }
        }

        fun markNeedReboot() {
            val result = rootShellForResult("touch $NEED_REBOOT_FILE")
            _kpStateLiveData.postValue(State.KERNELPATCH_NEED_REBOOT)
            Log.d(TAG, "mark reboot ${result.code}")
        }


        var superKey: String = ""
            set(value) {
                field = value
                val ready = Natives.nativeReady(value)
                _kpStateLiveData.value =
                    if (ready) State.KERNELPATCH_INSTALLED else State.UNKNOWN_STATE
                _apStateLiveData.value =
                    if (ready) State.ANDROIDPATCH_NOT_INSTALLED else State.UNKNOWN_STATE
                Log.d(TAG, "state: " + _kpStateLiveData.value)
                if (!ready) return

                thread {
                    val restrictedStatus = runRestrictedAndroidPatch("status")
                    val rc = Natives.su(0, null)
                    if (!rc && !restrictedStatus.success) {
                        Log.e(TAG, "Native.su failed")
                        return@thread
                    }
                    if (!rc) {
                        Log.i(TAG, "Using restricted AndroidPatch manager channel")
                    }

                    // KernelPatch version
                    //val buildV = Version.buildKPVUInt()
                    //val installedV = Version.installedKPVUInt()
                    //use build time to check update
                    val buildV = Version.getKpImg()
                    val installedV = Version.installedKPTime()


                    Log.d(TAG, "kp installed version: ${installedV}, build version: $buildV")

                    // use != instead of > to enable downgrade,
                    if (buildV != installedV) {
                        _kpStateLiveData.postValue(State.KERNELPATCH_NEED_UPDATE)
                    }
                    Log.d(TAG, "kp state: " + _kpStateLiveData.value)

                    if (File(NEED_REBOOT_FILE).exists()) {
                        _kpStateLiveData.postValue(State.KERNELPATCH_NEED_REBOOT)
                    }
                    Log.d(TAG, "kp state: " + _kpStateLiveData.value)

                    // AndroidPatch version
                    val mgv = Version.getManagerVersion().second
                    val installedApdVInt = Version.installedApdVUInt()
                    Log.d(TAG, "manager version: $mgv, installed apd version: $installedApdVInt")

                    var detectedState = when {
                        installedApdVInt <= 0 -> State.ANDROIDPATCH_NOT_INSTALLED
                        mgv.toInt() != installedApdVInt -> State.ANDROIDPATCH_NEED_UPDATE
                        else -> State.ANDROIDPATCH_INSTALLED
                    }
                    _apStateLiveData.postValue(detectedState)

                    if (detectedState == State.ANDROIDPATCH_NEED_UPDATE) {
                        // su path
                        val suPathFile = File(SU_PATH_FILE)
                        if (suPathFile.exists()) {
                            val suPath = suPathFile.readLines()[0].trim()
                            if (Natives.suPath() != suPath) {
                                Log.d(TAG, "su path: $suPath")
                                Natives.resetSuPath(suPath)
                            }
                        }
                    }
                    val productBootstrapNeeded =
                        detectedState != State.ANDROIDPATCH_INSTALLED ||
                            sharedPreferences.getInt(AUTO_PRODUCT_SCHEMA_KEY, 0) !=
                            AUTO_PRODUCT_SCHEMA
                    if (restrictedStatus.success && productBootstrapNeeded &&
                        autoAndroidPatchAttempted.compareAndSet(false, true)) {
                        Log.i(TAG, "Automatically initializing AndroidPatch")
                        val initialization = runRestrictedAndroidPatch("install")
                        if (initialization.success) {
                            detectedState = State.ANDROIDPATCH_INSTALLED
                            sharedPreferences.edit {
                                putInt(AUTO_PRODUCT_SCHEMA_KEY, AUTO_PRODUCT_SCHEMA)
                            }
                            _apStateLiveData.postValue(detectedState)
                            if (File(NEED_REBOOT_FILE).exists()) {
                                _kpStateLiveData.postValue(State.KERNELPATCH_NEED_REBOOT)
                            }
                        } else {
                            detectedState = if (installedApdVInt > 0) {
                                State.ANDROIDPATCH_NEED_UPDATE
                            } else {
                                State.ANDROIDPATCH_NOT_INSTALLED
                            }
                            _apStateLiveData.postValue(detectedState)
                            Log.w(TAG, "Automatic AndroidPatch initialization failed: ${initialization.output}")
                        }
                    } else if ((detectedState == State.ANDROIDPATCH_NOT_INSTALLED ||
                            detectedState == State.ANDROIDPATCH_NEED_UPDATE) &&
                        autoAndroidPatchAttempted.compareAndSet(false, true)
                    ) {
                        Log.i(TAG, "Automatically initializing AndroidPatch through legacy root")
                        if (!installApatch()) {
                            Log.w(TAG, "Automatic AndroidPatch initialization failed")
                        }
                    }
                    Log.d(TAG, "ap state: $detectedState")

                    return@thread
                }
            }

        /**
         * Resolve the SuperKey used to authenticate against the running kernel.
         *
         * The new manager defaults to "su" (implicit signature/uid authorization).
         * Kernels patched by legacy versions, however, were patched with a real
         * random/custom SuperKey and know nothing about signature authorization,
         * so "su" fails for users who upgraded from such a version. To keep the
         * original SuperKey upgrade path working, fall back to the legacy SuperKey
         * persisted (Keystore-encrypted) by older managers and use it to elevate,
         * letting the user upgrade the kernel to the latest signature-authorized one.
         *
         * Once "su" succeeds the kernel no longer relies on a SuperKey, so any
         * stale legacy key is cleared.
         */
        private fun resolveSuperKey(): String {
            APatchKeyHelper.setSharedPreferences(sharedPreferences)
            val savedKey = APatchKeyHelper.readSPSuperKey()

            // Signature authorization (new default).
            if (Natives.nativeReady("su")) {
                if (!savedKey.isNullOrEmpty()) {
                    APatchKeyHelper.clearConfigKey()
                    Log.i(TAG, "signature auth ready, cleared legacy SuperKey")
                }
                return "su"
            }

            // Legacy kernel patched with a real SuperKey: reuse the stored one.
            if (!savedKey.isNullOrEmpty() && Natives.nativeReady(savedKey)) {
                Log.i(TAG, "fallback to legacy stored SuperKey for upgrade")
                return savedKey
            }

            return "su"
        }
    }

    override fun onCreate() {
        super.onCreate()
        // The app-zygote for the jailbreak MagicaService runs without a UserManager,
        // so shared prefs and other context-dependent setup are unavailable there.
        // AppZygotePreload drives the jailbreak via JNI directly; skip init here.
        if (getSystemService(Context.USER_SERVICE) == null) {
            return
        }
        apApp = this

        val isArm64 = Build.SUPPORTED_ABIS.any { it == "arm64-v8a" }
        if (!isArm64) {
            Toast.makeText(applicationContext, "Unsupported architecture!", Toast.LENGTH_LONG)
                .show()
            Thread.sleep(5000)
            exitProcess(0)
        }

        if (!BuildConfig.DEBUG && !verifyAppSignature("1x2twMoHvfWUODv7KkRRNKBzOfEqJwRKGzJpgaz18xk=")) {
            while (true) {
                val intent = Intent(Intent.ACTION_DELETE)
                intent.data = "package:$packageName".toUri()
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                intent.addFlags(Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
                startActivity(intent)
                exitProcess(0)
            }
        }

        // TODO: We can't totally protect superkey from be stolen by root or LSPosed-like injection tools in user space, the only way is don't use superkey,
        // TODO: 1. make me root by kernel
        // TODO: 2. remove all usage of superkey
        // Create the device-protected mailbox while this thread still has the
        // Manager app UID.  The root initialization worker must never be the
        // first creator of the app-private files directory.
        createDeviceProtectedStorageContext().filesDir
        sharedPreferences = getSharedPreferences(SP_NAME, Context.MODE_PRIVATE)
        superKey = resolveSuperKey()

        okhttpClient =
            OkHttpClient.Builder().cache(Cache(File(cacheDir, "okhttp"), 10 * 1024 * 1024))
                .addInterceptor { block ->
                    block.proceed(
                        block.request().newBuilder()
                            .header("User-Agent", "APatch/${BuildConfig.VERSION_CODE}")
                            .header("Accept-Language", Locale.getDefault().toLanguageTag()).build()
                    )
                }.build()
    }

    fun getBackupWarningState(): Boolean {
        return sharedPreferences.getBoolean(SHOW_BACKUP_WARN, true)
    }

    fun updateBackupWarningState(state: Boolean) {
        sharedPreferences.edit { putBoolean(SHOW_BACKUP_WARN, state) }
    }

    override fun uncaughtException(t: Thread, e: Throwable) {
        val exceptionMessage = Log.getStackTraceString(e)
        val threadName = t.name
        Log.e(TAG, "Error on thread $threadName:\n $exceptionMessage")
        val intent = Intent(this, CrashHandleActivity::class.java).apply {
            putExtra("exception_message", exceptionMessage)
            putExtra("thread", threadName)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        startActivity(intent)
        exitProcess(10)
    }
}
