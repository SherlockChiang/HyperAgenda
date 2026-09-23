package com.vince.hyperagenda

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Color as AndroidColor
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp
import com.vince.hyperagenda.data.AgendaContract
import java.text.DateFormat
import java.util.Date
import kotlin.math.roundToInt
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SliderPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.basic.Check
import top.yukonga.miuix.kmp.icon.basic.Close

class MainActivity : ComponentActivity() {
    private lateinit var preferences: SharedPreferences
    private var statusRevision by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        preferences = getSharedPreferences(AgendaContract.PREFS, Context.MODE_PRIVATE)

        setContent {
            val darkMode = isSystemInDarkTheme()
            DisposableEffect(darkMode) {
                enableEdgeToEdge(
                    statusBarStyle = SystemBarStyle.auto(
                        AndroidColor.TRANSPARENT,
                        AndroidColor.TRANSPARENT,
                    ) { darkMode },
                    navigationBarStyle = SystemBarStyle.auto(
                        AndroidColor.TRANSPARENT,
                        AndroidColor.TRANSPARENT,
                    ) { darkMode },
                )
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    window.isNavigationBarContrastEnforced = false
                }
                onDispose { }
            }

            val themeController = remember { ThemeController(ColorSchemeMode.MonetSystem) }
            MiuixTheme(controller = themeController) {
                SettingsScreen(statusRevision)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        statusRevision++
    }

    @Composable
    private fun SettingsScreen(revision: Int) {
        var enabled by remember(revision) {
            mutableStateOf(preferences.getBoolean(AgendaContract.KEY_ENABLED, true))
        }
        var showLocation by remember(revision) {
            mutableStateOf(AgendaContract.readShowLocation(preferences))
        }
        var openOnLockscreenClick by remember(revision) {
            mutableStateOf(
                preferences.getBoolean(AgendaContract.KEY_OPEN_ON_LOCKSCREEN_CLICK, true),
            )
        }
        var maxEvents by remember(revision) {
            mutableIntStateOf(preferences.getInt(AgendaContract.KEY_MAX_EVENTS, 1).coerceIn(1, 3))
        }
        var lookaheadDays by remember(revision) {
            mutableIntStateOf(
                preferences.getInt(AgendaContract.KEY_LOOKAHEAD_DAYS, 7).coerceIn(1, 30),
            )
        }
        var clockGapDp by remember(revision) {
            mutableIntStateOf(readClockGapDp())
        }

        val calendarGranted = checkSelfPermission(Manifest.permission.READ_CALENDAR) ==
            PackageManager.PERMISSION_GRANTED
        val permissionLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission(),
        ) {
            notifyChanged()
            statusRevision++
        }
        val openPermission = {
            if (calendarGranted) {
                startActivity(
                    Intent(
                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:$packageName"),
                    ),
                )
            } else {
                permissionLauncher.launch(Manifest.permission.READ_CALENDAR)
            }
        }

        val lastHook = preferences.getLong(AgendaContract.KEY_LAST_HOOK_TIME, 0L)
        val systemUiVersion = preferences.getString(AgendaContract.KEY_SYSTEMUI_VERSION, "").orEmpty()
        val hookSummary = if (lastHook == 0L) {
            "尚未检测到"
        } else {
            val time = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                .format(Date(lastHook))
            listOf(systemUiVersion, time).filter { it.isNotBlank() }.joinToString(" · ")
        }
        val hookAgeMillis = System.currentTimeMillis() - lastHook
        val injectionHealthy = lastHook > 0L && hookAgeMillis in 0L..HOOK_HEALTH_WINDOW_MS
        val statusTitle = if (injectionHealthy) {
            "SystemUI 注入正常"
        } else {
            "SystemUI 注入未生效"
        }
        val statusSummary = when {
            !injectionHealthy -> "请检查 LSPosed 是否启用，并将作用域设为系统界面"
            !calendarGranted -> "注入已连接，需要授予日历读取权限"
            !enabled -> "注入已连接，锁屏日程当前已暂停"
            else -> "锁屏日程正在运行"
        }
        val scrollBehavior = MiuixScrollBehavior()

        Scaffold(
            topBar = {
                TopAppBar(
                    title = "锁屏日程",
                    largeTitle = "锁屏日程",
                    subtitle = "HyperOS 4 · LSPosed",
                    scrollBehavior = scrollBehavior,
                )
            },
        ) { innerPadding ->
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MiuixTheme.colorScheme.background)
                    .nestedScroll(scrollBehavior.nestedScrollConnection),
                contentPadding = PaddingValues(
                    top = innerPadding.calculateTopPadding(),
                    bottom = innerPadding.calculateBottomPadding() + 28.dp,
                ),
            ) {
                item(key = "status-title") {
                    SmallTitle(text = "当前状态")
                }
                item(key = "status") {
                    Card(modifier = Modifier.padding(horizontal = 12.dp)) {
                        BasicComponent(
                            title = statusTitle,
                            summary = statusSummary,
                            startAction = {
                                InjectionStatusIcon(success = injectionHealthy)
                            },
                        )
                    }
                }

                item(key = "display-title") {
                    SmallTitle(
                        text = "锁屏显示",
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
                item(key = "display") {
                    Card(modifier = Modifier.padding(horizontal = 12.dp)) {
                        ArrowPreference(
                            title = "日历权限",
                            summary = if (calendarGranted) "已授权" else "未授权",
                            onClick = openPermission,
                        )
                        SwitchPreference(
                            title = "显示接下来的日程",
                            summary = "仅在系统锁屏可见",
                            checked = enabled,
                            onCheckedChange = {
                                enabled = it
                                putBoolean(AgendaContract.KEY_ENABLED, it)
                            },
                        )
                        SwitchPreference(
                            title = "显示地点",
                            checked = showLocation,
                            onCheckedChange = {
                                showLocation = it
                                putBoolean(AgendaContract.KEY_SHOW_LOCATION, it)
                            },
                        )
                        SwitchPreference(
                            title = "点击日程打开日历",
                            summary = if (openOnLockscreenClick) {
                                "点击锁屏日程后打开对应事件"
                            } else {
                                "锁屏日程仅展示，不响应点击"
                            },
                            checked = openOnLockscreenClick,
                            onCheckedChange = {
                                openOnLockscreenClick = it
                                putBoolean(AgendaContract.KEY_OPEN_ON_LOCKSCREEN_CLICK, it)
                            },
                        )
                    }
                }

                item(key = "range-title") {
                    SmallTitle(
                        text = "范围与位置",
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
                item(key = "range") {
                    Card(modifier = Modifier.padding(horizontal = 12.dp)) {
                        SliderPreference(
                            title = "显示条数",
                            value = maxEvents.toFloat(),
                            valueText = "$maxEvents 条",
                            valueRange = 1f..3f,
                            steps = 1,
                            onValueChange = {
                                val value = it.roundToInt().coerceIn(1, 3)
                                if (value != maxEvents) {
                                    maxEvents = value
                                    putInt(AgendaContract.KEY_MAX_EVENTS, value)
                                }
                            },
                        )
                        SliderPreference(
                            title = "未来范围",
                            value = lookaheadDays.toFloat(),
                            valueText = "$lookaheadDays 天",
                            valueRange = 1f..30f,
                            steps = 28,
                            onValueChange = {
                                val value = it.roundToInt().coerceIn(1, 30)
                                if (value != lookaheadDays) {
                                    lookaheadDays = value
                                    putInt(AgendaContract.KEY_LOOKAHEAD_DAYS, value)
                                }
                            },
                        )
                        SliderPreference(
                            title = "距时钟间距",
                            value = clockGapDp.toFloat(),
                            valueText = "$clockGapDp dp",
                            valueRange = 0f..64f,
                            steps = 63,
                            onValueChange = {
                                val value = it.roundToInt().coerceIn(0, 64)
                                if (value != clockGapDp) {
                                    clockGapDp = value
                                    putInt(AgendaContract.KEY_CLOCK_GAP_DP, value)
                                }
                            },
                        )
                    }
                }

                item(key = "module-title") {
                    SmallTitle(
                        text = "模块",
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
                item(key = "module") {
                    Card(modifier = Modifier.padding(horizontal = 12.dp)) {
                        BasicComponent(
                            title = "SystemUI Hook",
                            summary = hookSummary,
                        )
                        BasicComponent(
                            title = "LSPosed 作用域",
                            summary = "系统界面 (com.android.systemui)",
                        )
                        BasicComponent(
                            title = "版本",
                            summary = BuildConfig.VERSION_NAME,
                        )
                    }
                }
                item(key = "bottom-space") {
                    Spacer(modifier = Modifier.height(4.dp))
                }
            }
        }
    }

    @Composable
    private fun InjectionStatusIcon(success: Boolean) {
        val statusColor = if (success) {
            MiuixTheme.colorScheme.primary
        } else {
            MiuixTheme.colorScheme.error
        }
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(statusColor.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = if (success) {
                    MiuixIcons.Basic.Check
                } else {
                    MiuixIcons.Basic.Close
                },
                contentDescription = if (success) "注入正常" else "注入未生效",
                modifier = Modifier.size(24.dp),
                tint = statusColor,
            )
        }
    }

    private fun readClockGapDp(): Int {
        return AgendaContract.readClockGapDp(preferences)
    }

    private fun putBoolean(key: String, value: Boolean) {
        preferences.edit().putBoolean(key, value).apply()
        notifyChanged()
    }

    private fun putInt(key: String, value: Int) {
        preferences.edit().putInt(key, value).apply()
        notifyChanged()
    }

    private fun notifyChanged() {
        contentResolver.notifyChange(AgendaContract.CONTENT_URI, null)
    }

    private companion object {
        const val HOOK_HEALTH_WINDOW_MS = 5L * 60L * 1000L
    }
}
