package me.bmax.apatch.ui.screen

import android.app.Activity.RESULT_OK
import android.content.Intent
import android.net.Uri
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialogDefaults
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SearchBarScrollBehavior
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.compose.ui.window.PopupProperties
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ramcosta.composedestinations.annotation.Destination
import com.ramcosta.composedestinations.annotation.RootGraph
import com.ramcosta.composedestinations.generated.destinations.PatchesDestination
import com.ramcosta.composedestinations.navigation.DestinationsNavigator
import com.topjohnwu.superuser.nio.ExtendedFile
import com.topjohnwu.superuser.nio.FileSystemManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.bmax.apatch.APApplication
import me.bmax.apatch.Natives
import me.bmax.apatch.R
import me.bmax.apatch.apApp
import me.bmax.apatch.ui.component.ConfirmResult
import me.bmax.apatch.ui.component.KPModuleRemoveButton
import me.bmax.apatch.ui.component.LoadingDialogHandle
import me.bmax.apatch.ui.component.ProvideMenuShape
import me.bmax.apatch.ui.component.SearchAppBar
import me.bmax.apatch.ui.component.pinnedScrollBehavior
import me.bmax.apatch.ui.component.rememberConfirmDialog
import me.bmax.apatch.ui.component.rememberLoadingDialog
import me.bmax.apatch.ui.viewmodel.KPModel
import me.bmax.apatch.ui.viewmodel.KPModuleViewModel
import me.bmax.apatch.ui.viewmodel.PatchesViewModel
import me.bmax.apatch.util.inputStream
import me.bmax.apatch.util.DeviceProfileController
import me.bmax.apatch.util.ui.APDialogBlurBehindUtils
import me.bmax.apatch.util.rootShellForResult
import me.bmax.apatch.util.writeTo
import java.io.File
import java.io.IOException

private const val TAG = "KernelPatchModule"
private const val KSH_CORE_MODULE = "ksh-core"
private const val DEVICE_PROFILE_MODULE = "device-profile"
private const val KPM_LOAD_CORE_REQUIRED = -10_001
private const val KPM_LOAD_INSPECTION_FAILED = -10_002
private const val KPM_LOAD_CORE_UNSAFE = -10_003
private const val KPM_LOAD_CORE_REBOOT_REQUIRED = -10_004
private lateinit var targetKPMToControl: KPModel.KPMInfo

@OptIn(ExperimentalMaterial3Api::class)
@Destination<RootGraph>
@Composable
fun KPModuleScreen(navigator: DestinationsNavigator) {
    val state by APApplication.apStateLiveData.observeAsState(APApplication.State.UNKNOWN_STATE)
    if (state == APApplication.State.UNKNOWN_STATE &&
        !Natives.nativeRestrictedKpmReady()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(12.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row {
                Text(
                    text = stringResource(id = R.string.kpm_kp_not_installed),
                    style = MaterialTheme.typography.titleMedium
                )
            }
        }
        return
    }

    val viewModel = viewModel<KPModuleViewModel>()
    val scrollBehavior = pinnedScrollBehavior()
    val kpModuleListState = rememberLazyListState()

    LaunchedEffect(Unit) {
        if (viewModel.moduleList.isEmpty() || viewModel.isNeedRefresh) {
            viewModel.fetchModuleList()
        }
    }

    Scaffold(topBar = {
        SearchAppBar(
            searchText = viewModel.search,
            onSearchTextChange = { viewModel.search = it },
            searchBarPlaceHolderText = stringResource(R.string.search_modules)
        )
    }, floatingActionButton = run {
        {
            val scope = rememberCoroutineScope()
            val context = LocalContext.current

            val moduleLoad = stringResource(id = R.string.kpm_load)
            val moduleInstall = stringResource(id = R.string.kpm_install)
            val moduleEmbed = stringResource(id = R.string.kpm_embed)
            val successToastText = stringResource(id = R.string.kpm_load_toast_succ)
            val failToastText = stringResource(id = R.string.kpm_load_toast_failed)
            val loadingDialog = rememberLoadingDialog()

            val selectKpmLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.StartActivityForResult()
            ) {
                if (it.resultCode != RESULT_OK) {
                    return@rememberLauncherForActivityResult
                }
                val data = it.data ?: return@rememberLauncherForActivityResult
                val uri = data.data ?: return@rememberLauncherForActivityResult

                // todo: args
                scope.launch {
                    val rc = loadModule(loadingDialog, uri, "")
                    val profileStack = if (rc == 0) {
                        reconcileManualProfileStackIfReady()
                    } else {
                        null
                    }
                    val toastText = when {
                        rc == KPM_LOAD_CORE_REQUIRED ->
                            context.getString(R.string.kpm_load_core_required)
                        rc == KPM_LOAD_INSPECTION_FAILED ->
                            context.getString(R.string.kpm_inspection_failed)
                        rc == KPM_LOAD_CORE_UNSAFE ->
                            context.getString(R.string.kpm_load_core_unsafe)
                        rc == KPM_LOAD_CORE_REBOOT_REQUIRED ->
                            context.getString(R.string.kpm_load_core_reboot_required)
                        rc != 0 -> "$failToastText: $rc"
                        profileStack == false -> context.getString(R.string.kpm_profile_reconcile_failed)
                        profileStack == true -> context.getString(R.string.kpm_profile_reconciled)
                        else -> successToastText
                    }
                    withContext(Dispatchers.Main) {
                        Toast.makeText(
                            context, toastText, Toast.LENGTH_SHORT
                        ).show()
                    }
                    viewModel.markNeedRefresh()
                    viewModel.fetchModuleList()
                }
            }

            var expanded by remember { mutableStateOf(false) }
            val options = listOf(moduleEmbed, moduleInstall, moduleLoad)

            Column {
                FloatingActionButton(
                    onClick = {
                        expanded = !expanded
                    },
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    containerColor = MaterialTheme.colorScheme.primary,
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.package_import),
                        contentDescription = null
                    )
                }

                ProvideMenuShape(RoundedCornerShape(10.dp)) {
                    DropdownMenu(
                        expanded = expanded,
                        onDismissRequest = { expanded = false },
                        properties = PopupProperties(focusable = true)
                    ) {
                        options.forEach { label ->
                            DropdownMenuItem(text = { Text(label) }, onClick = {
                                expanded = false
                                when (label) {
                                    moduleEmbed -> {
                                        navigator.navigate(PatchesDestination(PatchesViewModel.PatchMode.PATCH_AND_INSTALL))
                                    }

                                    moduleInstall -> {
                                        val intent = Intent(Intent.ACTION_GET_CONTENT)
                                        intent.type = "*/*"
                                        selectKpmLauncher.launch(intent)
                                    }

                                    moduleLoad -> {
                                        val intent = Intent(Intent.ACTION_GET_CONTENT)
                                        intent.type = "*/*"
                                        selectKpmLauncher.launch(intent)
                                    }
                                }
                            })
                        }
                    }
                }
            }
        }
    }) { innerPadding ->
        KPModuleList(
            viewModel = viewModel,
            modules = viewModel.moduleList,
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize(),
            state = kpModuleListState,
            scrollBehavior = scrollBehavior
        )
    }
}

private suspend fun reconcileManualProfileStackIfReady(): Boolean? =
    withContext(Dispatchers.IO) {
        val loaded = Natives.kernelPatchModuleList()
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toSet()
        if (!loaded.containsAll(setOf(KSH_CORE_MODULE, DEVICE_PROFILE_MODULE))) {
            return@withContext null
        }
        isPreZygoteKshCore() && runCatching {
            val backend = Natives.kernelPatchModuleControl(
                DEVICE_PROFILE_MODULE,
                "backend_status",
            )
            check(backend.rc >= 0L)
            val profile = Natives.kernelPatchModuleControl(
                DEVICE_PROFILE_MODULE,
                "ksh_profile_status",
            )
            val providerRegistered = profile.outMsg.orEmpty()
                .lineSequence()
                .any { it == "provider_registered=1" }
            check(profile.rc >= 0L && providerRegistered)
            DeviceProfileController().reconcile()
        }.isSuccess
    }

private fun isPreZygoteKshCore(): Boolean {
    val status = Natives.kernelPatchModuleControl(KSH_CORE_MODULE, "ksh runtime-status")
    val lines = status.outMsg.orEmpty().lineSequence().toSet()
    return status.rc >= 0L && "runtime=active" in lines
            && "route_mask=63" in lines
            && "load_event=pre-kernel-init" in lines
}

suspend fun loadModule(loadingDialog: LoadingDialogHandle, uri: Uri, args: String): Int {
    val rc = loadingDialog.withLoading {
        withContext(Dispatchers.IO) {
            run {
                val kpmDir: ExtendedFile =
                    FileSystemManager.getLocal().getFile(apApp.filesDir.parent, "kpm")
                kpmDir.deleteRecursively()
                kpmDir.mkdirs()
                val rand = (1..4).map { ('a'..'z').random() }.joinToString("")
                val kpm = kpmDir.getChildFile("${rand}.kpm")
                Log.d(TAG, "save tmp kpm: ${kpm.path}")
                var rc = -1
                try {
                    uri.inputStream().buffered().writeTo(kpm)
                    val moduleName = inspectKpmName(kpm.path)
                    val loaded = Natives.kernelPatchModuleList()
                        .lineSequence()
                        .map { it.trim() }
                        .filter { it.isNotEmpty() }
                        .toSet()
                    rc = if (moduleName == null) {
                        KPM_LOAD_INSPECTION_FAILED
                    } else if (moduleName == KSH_CORE_MODULE) {
                        KPM_LOAD_CORE_REBOOT_REQUIRED
                    } else if (moduleName == DEVICE_PROFILE_MODULE) {
                        when {
                            KSH_CORE_MODULE !in loaded -> KPM_LOAD_CORE_REQUIRED
                            !isPreZygoteKshCore() -> KPM_LOAD_CORE_UNSAFE
                            else -> Natives.loadKernelPatchModule(kpm.path, args).toInt()
                        }
                    } else {
                        Natives.loadKernelPatchModule(kpm.path, args).toInt()
                    }
                } catch (e: IOException) {
                    Log.e(TAG, "Copy kpm error: $e")
                }
                Log.d(TAG, "load ${kpm.path} rc: $rc")
                rc
            }
        }
    }
    return rc
}

private fun inspectKpmName(path: String): String? {
    val executable = File(apApp.applicationInfo.nativeLibraryDir, "libkptools.so")
    return runCatching {
        val result = rootShellForResult("${executable.path} -l -M $path")
        if (!result.isSuccess) return@runCatching null
        result.out.asSequence()
            .map { it.trim() }
            .firstOrNull { it.startsWith("name=") }
            ?.removePrefix("name=")
            ?.takeIf(String::isNotEmpty)
    }.getOrNull()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KPMControlDialog(showDialog: MutableState<Boolean>) {
    var controlParam by remember { mutableStateOf("") }
    var enable by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val loadingDialog = rememberLoadingDialog()
    val context = LocalContext.current
    val outMsgStringRes = stringResource(id = R.string.kpm_control_outMsg)
    val okStringRes = stringResource(id = R.string.kpm_control_ok)
    val failedStringRes = stringResource(id = R.string.kpm_control_failed)

    lateinit var controlResult: Natives.KPMCtlRes

    suspend fun onModuleControl(module: KPModel.KPMInfo) {
        loadingDialog.withLoading {
            withContext(Dispatchers.IO) {
                controlResult = Natives.kernelPatchModuleControl(module.name, controlParam)
            }
        }

        if (controlResult.rc >= 0) {
            Toast.makeText(
                context,
                "$okStringRes\n${outMsgStringRes}: ${controlResult.outMsg}",
                Toast.LENGTH_SHORT
            ).show()
        } else {
            Toast.makeText(
                context,
                "$failedStringRes\n${outMsgStringRes}: ${controlResult.outMsg}",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    BasicAlertDialog(
        onDismissRequest = { showDialog.value = false }, properties = DialogProperties(
            decorFitsSystemWindows = true,
            usePlatformDefaultWidth = false,
        )
    ) {
        Surface(
            modifier = Modifier
                .width(310.dp)
                .wrapContentHeight(),
            shape = RoundedCornerShape(30.dp),
            tonalElevation = AlertDialogDefaults.TonalElevation,
            color = AlertDialogDefaults.containerColor,
        ) {
            Column(modifier = Modifier.padding(PaddingValues(all = 24.dp))) {
                Box(
                    Modifier
                        .padding(PaddingValues(bottom = 16.dp))
                        .align(Alignment.Start)
                ) {
                    Text(
                        text = stringResource(id = R.string.kpm_control_dialog_title),
                        style = MaterialTheme.typography.headlineSmall
                    )
                }

                Box(
                    Modifier
                        .weight(weight = 1f, fill = false)
                        .align(Alignment.Start)
                ) {
                    Text(
                        text = stringResource(id = R.string.kpm_control_dialog_content),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }

                Box(
                    contentAlignment = Alignment.CenterEnd,
                ) {
                    OutlinedTextField(
                        value = controlParam,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 6.dp),
                        onValueChange = {
                            controlParam = it
                            enable = controlParam.isNotBlank()
                        },
                        shape = RoundedCornerShape(50.0f),
                        label = { Text(stringResource(id = R.string.kpm_control_paramters)) },
                        visualTransformation = VisualTransformation.None,
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = { showDialog.value = false }) {
                        Text(stringResource(id = android.R.string.cancel))
                    }

                    Button(onClick = {
                        showDialog.value = false

                        scope.launch { onModuleControl(targetKPMToControl) }

                    }, enabled = enable) {
                        Text(stringResource(id = android.R.string.ok))
                    }
                }
            }
        }
        val dialogWindowProvider = LocalView.current.parent as DialogWindowProvider
        APDialogBlurBehindUtils.setupWindowBlurListener(dialogWindowProvider.window)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun KPModuleList(
    viewModel: KPModuleViewModel,
    modules: List<KPModel.KPMInfo>,
    modifier: Modifier = Modifier,
    state: LazyListState,
    scrollBehavior: SearchBarScrollBehavior
) {
    val moduleStr = stringResource(id = R.string.kpm)
    val moduleUninstallConfirm = stringResource(id = R.string.kpm_unload_confirm)
    val uninstall = stringResource(id = R.string.kpm_unload)
    val cancel = stringResource(id = android.R.string.cancel)

    val confirmDialog = rememberConfirmDialog()
    val loadingDialog = rememberLoadingDialog()

    val showKPMControlDialog = remember { mutableStateOf(false) }
    if (showKPMControlDialog.value) {
        KPMControlDialog(showDialog = showKPMControlDialog)
    }

    suspend fun onModuleUninstall(module: KPModel.KPMInfo) {
        val confirmResult = confirmDialog.awaitConfirm(
            moduleStr,
            content = moduleUninstallConfirm.format(module.name),
            confirm = uninstall,
            dismiss = cancel
        )
        if (confirmResult != ConfirmResult.Confirmed) {
            return
        }

        val success = loadingDialog.withLoading {
            withContext(Dispatchers.IO) {
                Natives.unloadKernelPatchModule(module.name) == 0L
            }
        }

        if (success) {
            viewModel.fetchModuleList()
        }
    }

    PullToRefreshBox(
        modifier = modifier,
        onRefresh = { viewModel.fetchModuleList() },
        isRefreshing = viewModel.isRefreshing
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize().nestedScroll(scrollBehavior.nestedScrollConnection),
            state = state,
            verticalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = remember {
                PaddingValues(
                    start = 16.dp,
                    top = 11.dp, // spacedBy - TopBar padding
                    end = 16.dp,
                    bottom = 16.dp + 16.dp + 56.dp /*  Scaffold Fab Spacing + Fab container height */
                )
            },
        ) {
            item {
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    shape = RoundedCornerShape(16.dp),
                ) {
                    Text(
                        text = stringResource(R.string.kpm_manual_load_notice),
                        modifier = Modifier.padding(16.dp),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            when {
                modules.isEmpty() -> {
                    item {
                        Box(
                            modifier = Modifier.fillParentMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                stringResource(R.string.kpm_apm_empty), textAlign = TextAlign.Center
                            )
                        }
                    }
                }

                else -> {
                    items(modules) { module ->
                        val scope = rememberCoroutineScope()
                        KPModuleItem(
                            module,
                            onUninstall = {
                                scope.launch { onModuleUninstall(module) }
                            },
                            onControl = {
                                targetKPMToControl = module
                                showKPMControlDialog.value = true
                            },
                        )

                        // fix last item shadow incomplete in LazyColumn
                        Spacer(Modifier.height(1.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun KPModuleItem(
    module: KPModel.KPMInfo,
    onUninstall: (KPModel.KPMInfo) -> Unit,
    onControl: (KPModel.KPMInfo) -> Unit,
    modifier: Modifier = Modifier,
    alpha: Float = 1f,
) {
    val moduleAuthor = stringResource(id = R.string.kpm_author)
    val moduleArgs = stringResource(id = R.string.kpm_args)
    val decoration = TextDecoration.None
    val canUnload = module.name != KSH_CORE_MODULE &&
        module.name != DEVICE_PROFILE_MODULE

    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
        shape = RoundedCornerShape(20.dp)
    ) {

        Box(
            modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center
        ) {
            Column(
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(all = 16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(
                        modifier = Modifier
                            .alpha(alpha = alpha)
                            .weight(1f),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        Text(
                            text = module.name,
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                            maxLines = 2,
                            textDecoration = decoration,
                            overflow = TextOverflow.Ellipsis
                        )

                        Text(
                            text = "${module.version}, $moduleAuthor ${module.author}",
                            style = MaterialTheme.typography.bodySmall,
                            textDecoration = decoration,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        Text(
                            text = "$moduleArgs: ${module.args}",
                            style = MaterialTheme.typography.bodySmall,
                            textDecoration = decoration,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                }

                Text(
                    modifier = Modifier
                        .alpha(alpha = alpha)
                        .padding(horizontal = 16.dp),
                    text = module.description,
                    style = MaterialTheme.typography.bodySmall,
                    textDecoration = decoration,
                    color = MaterialTheme.colorScheme.outline
                )

                HorizontalDivider(
                    thickness = 1.5.dp,
                    color = MaterialTheme.colorScheme.surface,
                    modifier = Modifier.padding(top = 8.dp)
                )

                Row(
                    modifier = Modifier
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                        .fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Spacer(modifier = Modifier.weight(1f))

                    FilledTonalButton(
                        onClick = { onControl(module) },
                        enabled = true,
                        contentPadding = PaddingValues(12.dp)
                    ) {
                        Icon(
                            modifier = Modifier.size(20.dp),
                            painter = painterResource(id = R.drawable.settings),
                            contentDescription = stringResource(id = R.string.kpm_control)
                        )
                    }

                    Spacer(modifier = Modifier.width(12.dp))

                    KPModuleRemoveButton(enabled = canUnload, onClick = { onUninstall(module) })
                }
            }

        }
    }
}
