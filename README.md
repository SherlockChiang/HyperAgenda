# HyperAgenda / 锁屏日程

一个面向 Xiaomi HyperOS 4 的实验性 LSPosed 模块，在系统锁屏上以 Pixel At a Glance 风格显示接下来的日历日程。

<p align="center">
<img src="docs/icon.png" alt="HyperAgenda 应用图标" width="144">
</p>

## 工作方式

- 应用进程持有 `READ_CALENDAR` 权限并查询 `CalendarContract.Instances`。
- 导出的只读 `ContentProvider` 只接受应用自身与 `com.android.systemui` UID 的请求，并按当前隐私模式决定 SystemUI 能拿到哪些字段。
- LSPosed 作用域仅为 `com.android.systemui`；通过 `KeyguardClockContainer` 发现锁屏，日程作为原生 `keyguard_info_layer` 的真实子视图注入，不挂到 `NotificationShadeWindowView` 根层。
- 默认只显示下一条日程，共两行：上排是日程名称，下排左侧相对时间、右侧地点（如有）；用户手动加条数后，其余日程压缩为单行。锁屏不显示日历或账户来源。
- 时间使用相对表达：`10 分钟后`、`进行中 · 还剩 25 分钟`、`明天 09:30`、`周三 09:30`，跨天日程在右下角补结束时间（如 `明天 01:00 结束`）。
- 可在设置中选择是否允许点击后经系统解锁流程打开小米日历对应事件；按下时日程行会下沉反馈，启动失败时自动恢复日程显示。
- 日程出现与消失跟随锁屏状态淡入淡出，透明度与时钟同步。
- 针对 SystemUI 的窗口触摸入口，仅将命中可见日程行的触摸交给该行；开始拖动、多指操作或日程隐藏后交还系统手势。窗口只用于触摸路由，日程视图仍属于原生信息层。
- 日程位置读取 foreground 层内 `clock_animation_container` 的实时可见下沿，在通知变化动画中同步跟随时钟；时钟内容不存在或不可见时不显示。
- 日程文字会继承当前锁屏日期/天气文本的字体、颜色、字距与阴影，并保留事件信息的层级字号；长标题最多两行、辅助信息一行，超出按阅读方向省略。
- PIN、密码、图案、SIM PIN、锁定和备用认证界面出现时隐藏日程。
- 找不到已知锁屏锚点或根布局不兼容时停止注入，不修改解锁逻辑。

## 锁屏隐私

设置页的「锁屏隐私」提供四种模式，越靠后越保守：

| 模式 | 锁屏可见内容 |
| --- | --- |
| 锁屏显示完整信息 | 日程名称、地点 |
| 隐藏地点 | 日程名称 |
| 只显示「有日程」 | 相对时间与「有 N 项日程」 |
| 认证后显示详情 | 设备设有锁屏密码时等同「只显示有日程」；没有安全锁时等同完整信息 |

过滤发生在应用进程：不符合当前模式的标题与地点不会传给 SystemUI。

## 状态诊断

设置页「当前状态」会在注入信息之外说明锁屏为什么可能是空的：模块暂停、日历权限失效、系统没有可见日历、日历分组被全部关闭，或未来范围内确实没有日程。

## 构建

需要 JDK 17 或更高版本，以及 Android SDK `android-37.0`；项目自带 Gradle Wrapper：

```powershell
$env:ANDROID_HOME = 'C:\Users\your-name\AppData\Local\Android\Sdk'
.\gradlew.bat :app:assembleDebug
```

## 安装

1. 安装 APK 并授予日历读取权限。
2. 在 LSPosed 中启用“锁屏日程”。
3. 作用域只选择“系统界面 (`com.android.systemui`)”。
4. 重启系统界面或重启手机。

## 兼容性说明

SystemUI 属于小米私有实现，类名可能随系统更新变化。首次适配需要提供手机型号、完整系统版本和 SystemUI 版本；若设置页一直显示“尚未检测到”，请从 LSPosed 日志中导出包含 `HyperAgenda` 的行。
