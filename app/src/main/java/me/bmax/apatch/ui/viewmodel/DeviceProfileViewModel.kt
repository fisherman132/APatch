package me.bmax.apatch.ui.viewmodel

import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.bmax.apatch.apApp
import me.bmax.apatch.util.DeviceProfileController
import me.bmax.apatch.util.DeviceProfileSnapshot
import java.text.Collator
import java.util.Locale

data class DeviceProfileApp(
    val label: String,
    val packageInfo: PackageInfo,
) {
    val packageName: String
        get() = packageInfo.packageName

    val uid: Int
        get() = packageInfo.applicationInfo?.uid ?: -1
}

class DeviceProfileViewModel(
    private val controller: DeviceProfileController = DeviceProfileController(),
) : ViewModel() {
    companion object {
        var apps by mutableStateOf<List<DeviceProfileApp>>(emptyList())
            private set
    }

    var appSearch by mutableStateOf("")

    val filteredApps by derivedStateOf {
        val query = appSearch.trim().lowercase(Locale.getDefault())
        if (query.isEmpty()) {
            apps
        } else {
            apps.filter { app ->
                app.label.lowercase(Locale.getDefault()).contains(query) ||
                    app.packageName.lowercase(Locale.US).contains(query)
            }
        }
    }

    var isLoadingApps by mutableStateOf(false)
        private set

    var snapshot by mutableStateOf<DeviceProfileSnapshot?>(null)
        private set

    var isRefreshing by mutableStateOf(false)
        private set

    var isMutating by mutableStateOf(false)
        private set

    var errorMessage by mutableStateOf<String?>(null)
        private set

    suspend fun refreshApps(): Result<Unit> {
        isLoadingApps = true
        return try {
            val loaded = withContext(Dispatchers.IO) {
                val packageManager = apApp.packageManager
                val packages =
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        packageManager.getInstalledPackages(PackageManager.PackageInfoFlags.of(0))
                    } else {
                        @Suppress("DEPRECATION")
                        packageManager.getInstalledPackages(0)
                    }
                val labelCollator = Collator.getInstance(Locale.getDefault())
                packages.mapNotNull { packageInfo ->
                    val applicationInfo = packageInfo.applicationInfo ?: return@mapNotNull null
                    val isOrdinaryUserApp =
                        applicationInfo.uid in Process.FIRST_APPLICATION_UID until ANDROID_UID_USER_RANGE &&
                            applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM == 0
                    if (!isOrdinaryUserApp || packageInfo.packageName == apApp.packageName) {
                        return@mapNotNull null
                    }
                    DeviceProfileApp(
                        label = applicationInfo.loadLabel(packageManager).toString(),
                        packageInfo = packageInfo,
                    )
                }.sortedWith { left, right -> labelCollator.compare(left.label, right.label) }
            }
            apps = loaded
            Result.success(Unit)
        } catch (throwable: Throwable) {
            if (throwable is CancellationException) throw throwable
            Result.failure(throwable)
        } finally {
            isLoadingApps = false
        }
    }

    suspend fun refresh(packageName: String): Result<Unit> {
        isRefreshing = true
        return try {
            snapshot = controller.refresh(packageName)
            errorMessage = null
            Result.success(Unit)
        } catch (throwable: Throwable) {
            if (throwable is CancellationException) throw throwable
            errorMessage = throwable.message ?: throwable.javaClass.simpleName
            Result.failure(throwable)
        } finally {
            isRefreshing = false
        }
    }

    suspend fun create(
        packageName: String,
        slotName: String,
    ): Result<Unit> = mutate(packageName) {
        // The first identity must capture the app's current data. Later identities start clean.
        controller.create(packageName, slotName, captureCurrent = snapshot?.slots.isNullOrEmpty())
    }

    suspend fun save(packageName: String): Result<Unit> = mutate(packageName) {
        controller.save(packageName)
    }

    suspend fun switch(packageName: String, slotName: String): Result<Unit> =
        mutate(packageName) { controller.switch(packageName, slotName) }

    suspend fun stop(packageName: String): Result<Unit> = mutate(packageName) {
        controller.stop(packageName)
    }

    suspend fun delete(packageName: String, slotName: String): Result<Unit> =
        mutate(packageName) {
            // The backend serializes delete and stops/saves the slot when it is active.
            // Avoid a UI-side status check followed by a separate stop command: that
            // would race another root client changing the active slot.
            controller.delete(packageName, slotName)
        }

    private suspend fun mutate(
        packageName: String,
        action: suspend () -> Unit,
    ): Result<Unit> {
        if (isMutating) return Result.failure(IllegalStateException("Another profile operation is running"))
        isMutating = true
        return try {
            action()
            snapshot = controller.refresh(packageName)
            errorMessage = null
            Result.success(Unit)
        } catch (throwable: Throwable) {
            if (throwable is CancellationException) throw throwable
            val refreshResult = runCatching { controller.refresh(packageName) }
            refreshResult.getOrNull()?.let { refreshed ->
                snapshot = refreshed
            }
            errorMessage = throwable.message ?: throwable.javaClass.simpleName
            android.util.Log.e(
                "DeviceProfileViewModel",
                "Profile operation failed for $packageName",
                throwable,
            )
            refreshResult.exceptionOrNull()?.let { refreshFailure ->
                android.util.Log.w(
                    "DeviceProfileViewModel",
                    "Profile refresh after failure also failed for $packageName",
                    refreshFailure,
                )
            }
            Result.failure(throwable)
        } finally {
            isMutating = false
        }
    }
}

private const val ANDROID_UID_USER_RANGE = 100_000
