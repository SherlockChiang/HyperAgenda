# HyperAgenda / 锁屏日程

一个面向 Xiaomi HyperOS 4 的实验性 LSPosed 模块，在系统锁屏上以 Pixel At a Glance 风格显示接下来的日历日程。

## 工作方式

- 应用进程持有 `READ_CALENDAR` 权限并查询 `CalendarContract.Instances`。
- 导出的只读 `ContentProvider` 只接受应用自身与 `com.android.systemui` UID 的请求。
- LSPosed 作用域仅为 `com.android.systemui`；通过 `KeyguardClockContainer` 发现锁屏，日程作为原生 `keyguard_info_layer` 的真实子视图注入，不挂到 `NotificationShadeWindowView` 根层。
- 每条日程以“时间 + 标题 / 地点”一至两行显示；可在设置中选择是否允许点击后经系统解锁流程打开小米日历对应事件。
- 针对 SystemUI 的窗口触摸入口，仅将命中可见日程行的触摸交给该行；开始拖动、多指操作或日程隐藏后交还系统手势。窗口只用于触摸路由，日程视图仍属于原生信息层。
- 日程位置读取 foreground 层内 `clock_animation_container` 的实时可见下沿，在通知变化动画中同步跟随时钟；时钟内容不存在或不可见时不显示。
- 日程文字会继承当前锁屏日期/天气文本的字体、颜色、字距与阴影，并保留事件信息的层级字号。
- PIN、密码、图案、SIM PIN、锁定和备用认证界面出现时隐藏日程。
- 找不到已知锁屏锚点或根布局不兼容时停止注入，不修改解锁逻辑。

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
