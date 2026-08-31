package me.bmax.apatch.ui.screen

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.PersonPin
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.PersonPin
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.ui.graphics.vector.ImageVector
import com.ramcosta.composedestinations.generated.destinations.APModuleScreenDestination
import com.ramcosta.composedestinations.generated.destinations.DeviceProfilesScreenDestination
import com.ramcosta.composedestinations.generated.destinations.HomeScreenDestination
import com.ramcosta.composedestinations.generated.destinations.SettingScreenDestination
import com.ramcosta.composedestinations.spec.DirectionDestinationSpec
import me.bmax.apatch.R

enum class BottomBarDestination(
    val direction: DirectionDestinationSpec,
    @param:StringRes val label: Int,
    val iconSelected: ImageVector,
    val iconNotSelected: ImageVector,
    val kPatchRequired: Boolean,
    val aPatchRequired: Boolean,
) {
    Home(
        HomeScreenDestination,
        R.string.home,
        Icons.Filled.Home,
        Icons.Outlined.Home,
        false,
        false
    ),
    AModule(
        APModuleScreenDestination,
        R.string.apm,
        Icons.Filled.Apps,
        Icons.Outlined.Apps,
        false,
        true
    ),
    DeviceProfiles(
        DeviceProfilesScreenDestination,
        R.string.device_profiles,
        Icons.Filled.PersonPin,
        Icons.Outlined.PersonPin,
        false,
        false
    ),
    Settings(
        SettingScreenDestination,
        R.string.settings,
        Icons.Filled.Settings,
        Icons.Outlined.Settings,
        false,
        false
    )
}
