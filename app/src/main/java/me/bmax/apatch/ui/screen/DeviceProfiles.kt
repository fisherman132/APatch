package me.bmax.apatch.ui.screen

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.dropUnlessResumed
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.ramcosta.composedestinations.annotation.Destination
import com.ramcosta.composedestinations.annotation.RootGraph
import com.ramcosta.composedestinations.generated.destinations.DeviceProfileDetailScreenDestination
import com.ramcosta.composedestinations.navigation.DestinationsNavigator
import kotlinx.coroutines.launch
import me.bmax.apatch.R
import me.bmax.apatch.ui.component.SearchAppBar
import me.bmax.apatch.ui.viewmodel.DeviceProfileViewModel
import me.bmax.apatch.util.DeviceProfileCommandFactory
import me.bmax.apatch.util.DeviceProfileSlot
import me.bmax.apatch.util.ui.LocalSnackbarHost

@OptIn(ExperimentalMaterial3Api::class)
@Destination<RootGraph>
@Composable
fun DeviceProfilesScreen(navigator: DestinationsNavigator) {
    val appViewModel = viewModel<DeviceProfileViewModel>()
    val scope = rememberCoroutineScope()
    val apps = appViewModel.filteredApps
    var navigationPending by remember { mutableStateOf(false) }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        navigationPending = false
    }

    LaunchedEffect(Unit) {
        if (DeviceProfileViewModel.apps.isEmpty()) appViewModel.refreshApps()
    }

    Scaffold(
        topBar = {
            SearchAppBar(
                searchText = appViewModel.appSearch,
                onSearchTextChange = { appViewModel.appSearch = it },
                searchBarPlaceHolderText = stringResource(R.string.device_profiles_search),
                dropdownContent = {
                    IconButton(onClick = { scope.launch { appViewModel.refreshApps() } }) {
                        Icon(
                            imageVector = Icons.Filled.Refresh,
                            contentDescription = stringResource(R.string.profile_refresh),
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        PullToRefreshBox(
            modifier = Modifier.padding(innerPadding),
            isRefreshing = appViewModel.isLoadingApps,
            onRefresh = { scope.launch { appViewModel.refreshApps() } },
        ) {
            if (apps.isEmpty() && !appViewModel.isLoadingApps) {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(stringResource(R.string.profile_app_list_empty))
                }
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(apps, key = { it.packageName }) { app ->
                        ListItem(
                            modifier = Modifier.clickable(
                                enabled = !navigationPending,
                                onClick = dropUnlessResumed {
                                    if (navigationPending) return@dropUnlessResumed
                                    navigationPending = true
                                    navigator.navigate(
                                        DeviceProfileDetailScreenDestination(app.packageName)
                                    )
                                },
                            ),
                            headlineContent = { Text(app.label) },
                            supportingContent = { Text(app.packageName) },
                            leadingContent = {
                                AsyncImage(
                                    model = ImageRequest.Builder(LocalContext.current)
                                        .data(app.packageInfo)
                                        .crossfade(true)
                                        .build(),
                                    contentDescription = app.label,
                                    modifier = Modifier
                                        .padding(4.dp)
                                        .width(48.dp)
                                        .height(48.dp),
                                )
                            },
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Destination<RootGraph>
@Composable
fun DeviceProfileDetailScreen(
    navigator: DestinationsNavigator,
    packageName: String,
) {
    val profileViewModel = viewModel<DeviceProfileViewModel>()
    val snackBarHost = LocalSnackbarHost.current
    val scope = rememberCoroutineScope()
    val app = DeviceProfileViewModel.apps.firstOrNull {
        it.packageName == packageName && it.uid in 0 until ANDROID_UID_USER_RANGE
    }
    val title = app?.label ?: packageName
    val snapshot = profileViewModel.snapshot
    val busy = profileViewModel.isRefreshing || profileViewModel.isMutating
    val backendAvailable = snapshot != null && profileViewModel.errorMessage == null
    val configuredMutationsAllowed = backendAvailable && snapshot.status.configured
    var showCreateDialog by rememberSaveable { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<DeviceProfileSlot?>(null) }
    var navigationPending by remember { mutableStateOf(false) }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        navigationPending = false
    }

    val operationFailed = stringResource(R.string.profile_operation_failed)
    val createdMessage = stringResource(R.string.profile_created)
    val savedMessage = stringResource(R.string.profile_saved)
    val switchedMessage = stringResource(R.string.profile_switched)
    val stoppedMessage = stringResource(R.string.profile_stopped)
    val deletedMessage = stringResource(R.string.profile_deleted)

    suspend fun announce(result: Result<Unit>, successMessage: String) {
        val message = result.fold(
            onSuccess = { successMessage },
            onFailure = { throwable ->
                operationFailed.format(throwable.message?.take(300) ?: throwable.javaClass.simpleName)
            },
        )
        snackBarHost.showSnackbar(message)
    }

    suspend fun refresh(showFailure: Boolean = true) {
        val result = profileViewModel.refresh(packageName)
        if (showFailure && result.isFailure) announce(result, "")
    }

    LaunchedEffect(packageName) {
        if (!DeviceProfileCommandFactory.isValidPackageName(packageName)) {
            snackBarHost.showSnackbar(operationFailed.format("Invalid Android package name"))
            navigator.popBackStack()
        } else {
            refresh()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(title, maxLines = 1)
                        Text(
                            text = packageName,
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(
                        enabled = !navigationPending,
                        onClick = dropUnlessResumed {
                            if (navigationPending) return@dropUnlessResumed
                            navigationPending = true
                            navigator.popBackStack()
                        },
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back),
                        )
                    }
                },
                actions = {
                    IconButton(
                        enabled = !busy,
                        onClick = { scope.launch { refresh() } },
                    ) {
                        Icon(
                            Icons.Filled.Refresh,
                            contentDescription = stringResource(R.string.profile_refresh),
                        )
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackBarHost) },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            if (snapshot == null &&
                profileViewModel.errorMessage == null &&
                profileViewModel.isRefreshing
            ) {
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(32.dp),
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        CircularProgressIndicator()
                    }
                }
            }

            if (snapshot == null) {
                profileViewModel.errorMessage?.let { detail ->
                    item {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(24.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                text = stringResource(R.string.profile_unavailable),
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Text(stringResource(R.string.profile_controller_required))
                            Text(
                                text = detail.take(300),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }

            snapshot?.let { current ->
                if (!current.status.configured) {
                    item {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(24.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                text = stringResource(R.string.profile_first_slot_required),
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Text(stringResource(R.string.profile_first_slot_hint))
                        }
                    }
                }

                item {
                    Text(
                        text = stringResource(R.string.profile_status),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    )
                    ListItem(
                        headlineContent = {
                            Text(
                                if (current.status.configured) {
                                    stringResource(R.string.profile_configured)
                                } else {
                                    stringResource(R.string.profile_not_configured)
                                }
                            )
                        },
                        supportingContent = {
                            Column {
                                Text(
                                    stringResource(
                                        R.string.profile_active_slot_value,
                                        current.status.activeSlot ?: stringResource(R.string.profile_none),
                                    )
                                )
                                current.status.activeGeneration?.let { generation ->
                                    Text(stringResource(R.string.profile_generation_value, generation))
                                }
                            }
                        },
                    )
                }

                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Button(
                            modifier = Modifier.weight(1f),
                            enabled = configuredMutationsAllowed && !busy && current.status.running,
                            onClick = {
                                scope.launch {
                                    announce(profileViewModel.save(packageName), savedMessage)
                                }
                            },
                        ) {
                            Text(stringResource(R.string.profile_save_active))
                        }
                        OutlinedButton(
                            modifier = Modifier.weight(1f),
                            enabled = configuredMutationsAllowed && !busy && current.status.running,
                            onClick = {
                                scope.launch {
                                    announce(profileViewModel.stop(packageName), stoppedMessage)
                                }
                            },
                        ) {
                            Text(stringResource(R.string.profile_stop_active))
                        }
                    }
                }

                item {
                    Button(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        enabled = backendAvailable && !busy,
                        onClick = { showCreateDialog = true },
                    ) {
                        Icon(Icons.Filled.Add, contentDescription = null)
                        Text(
                            text = stringResource(R.string.profile_create_slot),
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }
                }

                item {
                    Text(
                        text = stringResource(R.string.profile_slots),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    )
                }

                if (current.slots.isEmpty()) {
                    item {
                        Text(
                            text = stringResource(R.string.profile_no_slots),
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 24.dp),
                        )
                    }
                } else {
                    items(current.slots, key = { it.name }) { slot ->
                        ListItem(
                            headlineContent = { Text(slot.name) },
                            supportingContent = {
                                Text(
                                    if (slot.active) {
                                        stringResource(R.string.profile_active)
                                    } else {
                                        stringResource(R.string.profile_stored)
                                    }
                                )
                            },
                            trailingContent = {
                                Row {
                                    IconButton(
                                        enabled = configuredMutationsAllowed && !busy && !slot.active,
                                        onClick = {
                                            scope.launch {
                                                announce(
                                                    profileViewModel.switch(packageName, slot.name),
                                                    switchedMessage,
                                                )
                                            }
                                        },
                                    ) {
                                        Icon(
                                            Icons.Filled.PlayArrow,
                                            contentDescription = stringResource(R.string.profile_switch),
                                        )
                                    }
                                    IconButton(
                                        enabled = configuredMutationsAllowed && !busy,
                                        onClick = { pendingDelete = slot },
                                    ) {
                                        Icon(
                                            Icons.Filled.Delete,
                                            contentDescription = stringResource(R.string.profile_delete),
                                        )
                                    }
                                }
                            },
                        )
                    }
                }
            }
        }
    }

    if (showCreateDialog) {
        CreateProfileDialog(
            captureCurrent = snapshot?.slots.isNullOrEmpty(),
            busy = busy,
            mutationsAllowed = backendAvailable,
            onDismiss = { showCreateDialog = false },
            onCreate = { slotName ->
                scope.launch {
                    val result = profileViewModel.create(packageName, slotName)
                    if (result.isSuccess) showCreateDialog = false
                    announce(result, createdMessage)
                }
            },
        )
    }

    pendingDelete?.let { slot ->
        AlertDialog(
            onDismissRequest = { if (!busy) pendingDelete = null },
            title = { Text(stringResource(R.string.profile_delete_title)) },
            text = { Text(stringResource(R.string.profile_delete_message, slot.name)) },
            confirmButton = {
                TextButton(
                    enabled = configuredMutationsAllowed && !busy,
                    onClick = {
                        scope.launch {
                            val result = profileViewModel.delete(packageName, slot.name)
                            if (result.isSuccess) pendingDelete = null
                            announce(result, deletedMessage)
                        }
                    },
                ) {
                    Text(stringResource(R.string.profile_delete))
                }
            },
            dismissButton = {
                TextButton(
                    enabled = !busy,
                    onClick = { pendingDelete = null },
                ) {
                    Text(stringResource(R.string.profile_cancel))
                }
            },
        )
    }
}

@Composable
private fun CreateProfileDialog(
    captureCurrent: Boolean,
    busy: Boolean,
    mutationsAllowed: Boolean,
    onDismiss: () -> Unit,
    onCreate: (String) -> Unit,
) {
    var slotName by rememberSaveable { mutableStateOf("") }
    val normalizedName = slotName.trim()
    val valid = DeviceProfileCommandFactory.isValidSlotName(normalizedName)

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(stringResource(R.string.profile_create_slot)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = slotName,
                    onValueChange = { slotName = it },
                    enabled = !busy,
                    singleLine = true,
                    label = { Text(stringResource(R.string.profile_slot_name)) },
                    isError = slotName.isNotEmpty() && !valid,
                    supportingText = {
                        if (slotName.isNotEmpty() && !valid) {
                            Text(stringResource(R.string.profile_invalid_slot_name))
                        }
                    },
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = captureCurrent,
                        onCheckedChange = null,
                        enabled = false,
                    )
                    Column {
                        Text(stringResource(R.string.profile_capture_current))
                        Text(
                            text = stringResource(
                                if (captureCurrent) {
                                    R.string.profile_capture_first_hint
                                } else {
                                    R.string.profile_capture_later_hint
                                }
                            ),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = mutationsAllowed && valid && !busy,
                onClick = { onCreate(normalizedName) },
            ) {
                Text(stringResource(R.string.profile_create))
            }
        },
        dismissButton = {
            TextButton(enabled = !busy, onClick = onDismiss) {
                Text(stringResource(R.string.profile_cancel))
            }
        },
    )
}

private const val ANDROID_UID_USER_RANGE = 100_000
