package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.example.ui.screens.CalibrateScreen
import com.example.ui.screens.DashboardScreen
import com.example.ui.screens.GuideScreen
import com.example.ui.theme.JumpAssistTheme
import com.example.viewmodel.MainViewModel

private enum class AppDestination(
    val route: String,
    val label: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
    val testTag: String
) {
    DASHBOARD("dashboard", "Setup", Icons.Filled.Dashboard, Icons.Outlined.Dashboard, "nav_dashboard"),
    CALIBRATE("calibrate", "Calibrate", Icons.Filled.Tune, Icons.Outlined.Tune, "nav_calibrate"),
    GUIDE("guide", "Guide", Icons.AutoMirrored.Filled.MenuBook, Icons.AutoMirrored.Outlined.MenuBook, "nav_guide")
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            JumpAssistTheme {
                JumpAssistApp()
            }
        }
    }
}

@Composable
fun JumpAssistApp(viewModel: MainViewModel = viewModel()) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route ?: AppDestination.DASHBOARD.route

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.refreshPermissions()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val permissions by viewModel.permissions.collectAsStateWithLifecycle()
    val config by viewModel.config.collectAsStateWithLifecycle()
    val liveAnalysis by viewModel.liveAnalysis.collectAsStateWithLifecycle()
    val logs by viewModel.logs.collectAsStateWithLifecycle()
    val trainingRecords by viewModel.trainingRecords.collectAsStateWithLifecycle()
    val sampleBitmap by viewModel.sampleBitmap.collectAsStateWithLifecycle()
    val sampleAnalysis by viewModel.sampleAnalysis.collectAsStateWithLifecycle()
    val isEyedropperActive by viewModel.isEyedropperActive.collectAsStateWithLifecycle()
    val isDebugVisionEnabled by viewModel.isDebugVisionEnabled.collectAsStateWithLifecycle()

    fun navigateTo(route: String) {
        navController.navigate(route) {
            popUpTo(navController.graph.findStartDestination().id) {
                saveState = true
            }
            launchSingleTop = true
            restoreState = true
        }
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val useRail = maxWidth >= 600.dp

        Scaffold(
            contentWindowInsets = WindowInsets.safeDrawing,
            containerColor = MaterialTheme.colorScheme.background,
            bottomBar = {
                if (!useRail) {
                    NavigationBar(
                        containerColor = MaterialTheme.colorScheme.surface,
                        modifier = Modifier.navigationBarsPadding()
                    ) {
                        AppDestination.entries.forEach { dest ->
                            val selected = currentRoute == dest.route
                            NavigationBarItem(
                                selected = selected,
                                onClick = { navigateTo(dest.route) },
                                icon = {
                                    Icon(
                                        imageVector = if (selected) dest.selectedIcon else dest.unselectedIcon,
                                        contentDescription = dest.label
                                    )
                                },
                                label = { Text(dest.label) },
                                modifier = Modifier.testTag(dest.testTag)
                            )
                        }
                    }
                }
            }
        ) { innerPadding ->
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                if (useRail) {
                    NavigationRail(
                        containerColor = MaterialTheme.colorScheme.surface
                    ) {
                        AppDestination.entries.forEach { dest ->
                            val selected = currentRoute == dest.route
                            NavigationRailItem(
                                selected = selected,
                                onClick = { navigateTo(dest.route) },
                                icon = {
                                    Icon(
                                        imageVector = if (selected) dest.selectedIcon else dest.unselectedIcon,
                                        contentDescription = dest.label
                                    )
                                },
                                label = { Text(dest.label) },
                                modifier = Modifier.testTag(dest.testTag)
                            )
                        }
                    }
                }

                NavHost(
                    navController = navController,
                    startDestination = AppDestination.DASHBOARD.route,
                    modifier = Modifier.weight(1f)
                ) {
                    composable(AppDestination.DASHBOARD.route) {
                        DashboardScreen(
                            permissions = permissions,
                            config = config,
                            liveAnalysis = liveAnalysis,
                            logs = logs,
                            trainingRecords = trainingRecords,
                            isDebugVisionEnabled = isDebugVisionEnabled,
                            onStartCapture = viewModel::startScreenCaptureService,
                            onStopCapture = viewModel::stopScreenCaptureService,
                            onToggleOverlay = viewModel::toggleOverlayService,
                            onToggleDebugVision = viewModel::toggleDebugVisionEnabled,
                            onToggleAutoLoop = viewModel::toggleAutoLoop,
                            onTriggerSingleStep = viewModel::triggerSingleStep,
                            onCooldownChange = viewModel::updateJumpCooldown,
                            onTapXChange = viewModel::updateTapX,
                            onTapYChange = viewModel::updateTapY,
                            onToggleAutoTrain = viewModel::updateAutoTrainEnabled,
                            onToggleHumanizeTiming = viewModel::updateHumanizeTiming,
                            onToggleUltraPrecision = viewModel::updateUltraPrecisionMode,
                            onManualFeedback = viewModel::applyManualFeedback,
                            onBuildReport = viewModel::buildTelemetryReport,
                            onClearTrainingHistory = viewModel::clearTrainingHistory,
                            onLogReportCopied = viewModel::logReportCopied
                        )
                    }

                    composable(AppDestination.CALIBRATE.route) {
                        CalibrateScreen(
                            config = config,
                            sampleBitmap = sampleBitmap,
                            analysis = sampleAnalysis,
                            isEyedropperActive = isEyedropperActive,
                            onToggleEyedropper = viewModel::setEyedropperActive,
                            onSamplePawnColor = viewModel::samplePawnColorAtNormalized,
                            onPickScreenshot = viewModel::loadScreenshotFromUri,
                            onGenerateSynthetic = viewModel::generateNextSyntheticFrame,
                            onFormulaModeChange = viewModel::updateFormulaMode,
                            onKFactorChange = viewModel::updateKFactor,
                            onOffsetMsChange = viewModel::updateOffsetMs,
                            onQuadFactorChange = viewModel::updateQuadFactor,
                            onPawnToleranceChange = viewModel::updatePawnTolerance,
                            onBgThresholdChange = viewModel::updateBgThreshold,
                            onRoiTopChange = viewModel::updateRoiTop,
                            onRoiBottomChange = viewModel::updateRoiBottom,
                            onResetDefaults = viewModel::resetDefaults
                        )
                    }

                    composable(AppDestination.GUIDE.route) {
                        GuideScreen()
                    }
                }
            }
        }
    }
}
