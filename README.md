# 时间守护（App Time Limiter）

一个用于限制手机应用使用时间的 Android 应用。纯 Java 编写，无第三方 SDK。

## 功能

1. **多应用限制**：可为任意多个 App 分别设置「每天可用时长」与「强制关闭后再次打开的最小间隔」。
2. **自动计时 + 通知栏倒计时**：一旦检测到目标 App 进入前台，自动开始计时，常驻通知秒级刷新剩余时间，并附「结束使用」按钮可手动提前收工。
3. **超时拦截**：时间用尽后自动退出目标 App、弹窗提醒，并在设定的冷却期内禁止再次打开；冷却期内再次点开会被立即踢出。

## 技术实现

| 模块 | 说明 |
| --- | --- |
| 前台检测 | `UsageStatsManager.queryEvents()` 读取最近的前台切换事件（Android 5.0+，需「使用情况访问权限」） |
| 计时累加 | 700 ms 轮询 + `SystemClock.elapsedRealtime()` 增量累加；每 15 s 与 UsageStats 的真实前台时长对账，修正漏计 |
| 后台守护 | 前台服务（`specialUse` 类型），通知常驻，支持开机自启与进程被回收后恢复 |
| 超时弹窗 | 优先用 `WindowManager` 悬浮窗（需悬浮窗权限）；无权限时降级为高优先级通知 |
| 强制关闭 | 发送 `CATEGORY_HOME` 回到桌面，使目标 App 退到后台/退出 |
| 数据存储 | `SharedPreferences`（规则 + 每日用量），按「凌晨 3 点」作为自然日边界 |
| 防作弊 | 手动结束或超时后都会写入冷却时间戳，冷却期内打开即拦截 |

### 关键设计取舍

- **为什么用 UsageStats 而不是无障碍服务**：无障碍权限在国产 ROM 上容易被杀，且用户授权门槛更高。UsageStats 权限只需在系统设置里点一下开关。
- **为什么秒级刷新不耗电**：计时靠 `elapsedRealtime()` 差值，通知每秒重绘一次（`IMPORTANCE_LOW`，无声音无振动），轮询间隔 700 ms 只读取一次事件游标。
- **Android 10+ 后台弹窗限制**：系统禁止后台 `startActivity`，所以超时提醒首选悬浮窗；这也是为什么主界面会提示授予「悬浮窗权限」。
- **屏幕熄灭不计时**：`PowerManager.isInteractive()` 为 false 时暂停累加。

## 构建

环境要求：Android Studio（AGP 9.x）、JDK 21+、Android SDK Platform 36。

```bash
# 命令行构建
./gradlew assembleDebug        # 产物：app/build/outputs/apk/debug/app-debug.apk
```

或在 Android Studio 中直接 `File > Open` 选择本目录，等待同步后点 Run。

> 项目使用 AGP 9.3.0 + Gradle 9.5.0 + compileSdk 36，与本机已安装的 Android Studio 2026.1.3 匹配。
> 若你的 Gradle 版本更低，可将 `gradle/wrapper/gradle-wrapper.properties` 与根 `build.gradle` 中的 AGP 版本一同降级（AGP 8.x 需 Gradle 8.x，且删除 AGP 9 专属配置）。

## 首次使用

1. 打开 App，点「添加」选择一个要限制的应用，设置每天时长与冷却间隔。
2. 回到首页，打开顶部「使用守护」开关，按提示授予：
   - **使用情况访问权限**（必需，用于识别前台应用）
   - **悬浮窗权限**（强烈建议，用于超时弹窗）
   - **通知权限**（Android 13+ 必须）
   - 电池优化白名单 / 自启动（国产 ROM 建议开启，防止后台被杀）
3. 正常使用目标 App，通知栏会显示剩余时间。
4. 用完后自动退出并弹窗；在冷却期内再打开会被立即关闭。

## 目录结构

```
app/src/main/java/com/timelimiter/app/
├── App.java                       应用入口，初始化通道与数据
├── data/
│   ├── Rule.java                  单个 App 的规则模型（JSON 序列化）
│   ├── UsageState.java            当日用量与冷却状态
│   └── LimitRepository.java       规则/用量的统一存取（SharedPreferences）
├── service/
│   └── GuardService.java          ★ 核心：前台检测、计时、通知、拦截
├── receiver/
│   ├── BootReceiver.java          开机自启
│   └── NotificationActionReceiver.java  通知栏按钮
├── ui/
│   ├── MainActivity.java          主界面（守护开关、规则列表、权限引导）
│   ├── AppPickerActivity.java     应用选择（支持搜索）
│   ├── RuleAdapter.java           规则列表适配器
│   └── LimitDialog.java           时长/间隔设置弹窗
└── util/
    ├── NotificationHelper.java    通知构建
    ├── PermissionUtil.java        权限跳转
    ├── AppInfoUtil.java           已安装应用查询
    └── TimeFormat.java            时间格式化
```

## 已知限制

- **UsageStats 有数秒延迟**：部分 ROM 的事件上报有 1~3 秒滞后，因此极短时间内（<2 秒）开关 App 可能不计入用量。已通过对账逻辑缓解。
- **强制关闭不是真正的 `force-stop`**：普通 App 无权杀其他进程。当前采用「回桌面」使目标应用退到后台；部分应用（如微信）可能较快恢复，但冷却期检测会持续把它踢出。
- **国产 ROM 后台保活**：MIUI / EMUI / ColorOS 等需要手动开启自启动与「允许后台活动」，否则服务可能被清理。
- **无法拦截系统设置页**：用户可自行关闭守护，属于自我约束工具的固有边界。

## 许可

仅供个人学习使用。
