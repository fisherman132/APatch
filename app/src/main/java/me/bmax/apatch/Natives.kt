package me.bmax.apatch

import android.os.Parcelable
import androidx.annotation.Keep
import androidx.compose.runtime.Immutable
import dalvik.annotation.optimization.FastNative
import kotlinx.parcelize.Parcelize

object Natives {
    init {
        System.loadLibrary("apjni")
    }

    @Immutable
    @Parcelize
    @Keep
    data class Profile(
        var uid: Int = 0,
        var toUid: Int = 0,
        var scontext: String = APApplication.DEFAULT_SCONTEXT,
    ) : Parcelable

    @FastNative
    private external fun nativeSu(superKey: String, toUid: Int, scontext: String?): Long

    fun su(toUid: Int, scontext: String?): Boolean {
        return nativeSu(APApplication.superKey, toUid, scontext) == 0L
    }

    fun su(): Boolean {
        return su(0, "")
    }

    @FastNative
    external fun nativeReady(superKey: String): Boolean

    private external fun nativeAndroidPatchAction(superKey: String, action: Int): Long

    fun androidPatchAction(action: Int): Long {
        return nativeAndroidPatchAction(APApplication.superKey, action)
    }

    @FastNative
    private external fun nativeSuPath(superKey: String): String

    fun suPath(): String {
        return nativeSuPath(APApplication.superKey)
    }

    @FastNative
    private external fun nativeSuUids(superKey: String): IntArray

    fun suUids(): IntArray {
        return nativeSuUids(APApplication.superKey)
    }

    @FastNative
    private external fun nativeKernelPatchVersion(superKey: String): Long
    fun kernelPatchVersion(): Long {
        return nativeKernelPatchVersion(APApplication.superKey)
    }

    @FastNative
    external fun nativeRestrictedKpmReady(): Boolean

    @FastNative
    private external fun nativeKernelPatchBuildTime(superKey: String): String
    fun kernelPatchBuildTime(): String {
        return nativeKernelPatchBuildTime(APApplication.superKey)
    }

    @Keep
    class KPMCtlRes {
        var rc: Long = -1
        var outMsg: String? = null
    }

    @FastNative
    private external fun nativeKernelPatchModuleNum(superKey: String): Long
    fun kernelPatchModuleNum(): Long = nativeKernelPatchModuleNum(APApplication.superKey)

    @FastNative
    private external fun nativeKernelPatchModuleList(superKey: String): String
    fun kernelPatchModuleList(): String = nativeKernelPatchModuleList(APApplication.superKey)

    @FastNative
    private external fun nativeKernelPatchModuleInfo(
        superKey: String, moduleName: String): String
    fun kernelPatchModuleInfo(moduleName: String): String =
        nativeKernelPatchModuleInfo(APApplication.superKey, moduleName)

    @FastNative
    private external fun nativeControlKernelPatchModule(
        superKey: String, moduleName: String, controlArg: String): KPMCtlRes
    fun kernelPatchModuleControl(moduleName: String, controlArg: String): KPMCtlRes =
        nativeControlKernelPatchModule(APApplication.superKey, moduleName, controlArg)

    @FastNative
    private external fun nativeLoadKernelPatchModule(
        superKey: String, modulePath: String, args: String): Long
    fun loadKernelPatchModule(modulePath: String, args: String): Long =
        nativeLoadKernelPatchModule(APApplication.superKey, modulePath, args)

    @FastNative
    private external fun nativeUnloadKernelPatchModule(
        superKey: String, moduleName: String): Long
    fun unloadKernelPatchModule(moduleName: String): Long =
        nativeUnloadKernelPatchModule(APApplication.superKey, moduleName)

    fun grantSu(uid: Int, toUid: Int, scontext: String?): Long = -1

    fun revokeSu(uid: Int): Long = -1

    @FastNative
    private external fun nativeSetUidExclude(superKey: String, uid: Int, exclude: Int): Int
    fun setUidExclude(uid: Int, exclude: Int): Int {
        return nativeSetUidExclude(APApplication.superKey, uid, exclude)
    }

    @FastNative
    private external fun nativeGetUidExclude(superKey: String, uid: Int): Int
    fun isUidExcluded(uid: Int): Int {
        return nativeGetUidExclude(APApplication.superKey, uid)
    }

    @FastNative
    private external fun nativeSuProfile(superKey: String, uid: Int): Profile
    fun suProfile(uid: Int): Profile {
        return nativeSuProfile(APApplication.superKey, uid)
    }

    @FastNative
    private external fun nativeResetSuPath(superKey: String, path: String): Boolean
    fun resetSuPath(path: String): Boolean {
        return nativeResetSuPath(APApplication.superKey, path)
    }

    @FastNative
    private external fun nativeControlFeature(superKey: String, featureName: String, state: Int): Long
    fun controlFeature(featureName: String, enable: Boolean): Long {
        return nativeControlFeature(APApplication.superKey, featureName, if (enable) 1 else 0)
    }
}
