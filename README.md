# MixinLib

取消了Starsector对反射的禁用，提供Mixin框架

### 安装

1. 解压，将`MixinLib`文件夹放入`Starsector/mods/`
2. 运行`install.bat`
3. 正常启动 Starsector

### 卸载

运行 `uninstall.bat`

## 开发

### 添加 Mixin 配置

在 Mod jar 根目录创建 `xxx.mixin.json`

`your.mixin.json`：

```json
{
    "required": true,
    "minVersion": "0.8",
    "package": "your.mixin.package",
    "compatibilityLevel": "JAVA_8",
    "mixins": [
        "YourMixinClass"
    ]
}
```

## 日志与诊断

日志写入 **`mixinlib.log`**，目录优先取系统属性`com.fs.starfarer.settings.paths.logs`，未设置时为游戏工作目录（`user.dir`）。

### 每次启动轮转

每次启动时（Launcher JVM 的 agent 阶段）新建一份空的 `mixinlib.log`，  
上一次的日志归档为 `mixinlib.log.1`，更老的依次后移：**`.1` 是最近一次、`.5` 是最老一次**。  
默认保留近 5 次历史，即目录中最多有 `mixinlib.log` 加 `mixinlib.log.1` … `.5`共 6 个文件。  
由系统属性 `mixinlib.logLimit` 调整保留次数；<=0表示不保留历史（会清掉旧日志）。

若第三方启动器直接拉起游戏 JVM、不经过 Launcher阶段，则不会轮转，日志会追加到当前`mixinlib.log`文件。

### 日志内容

| 标签                           | 含义                                                               |
| ---------------------------- | ---------------------------------------------------------------- |
| `[Mixin:<loggerId>/<LEVEL>]` | Mixin 框架与 MixinExtras 的日志                                        |
| `[Injector]`                 | 启动时输出已注册的注入器注解数量与清单、以及每个 config 声明的 plugin 类                     |
| `[SelfCheck]`                | 首次类织入之后核对声明了 plugin 的 config 是否拿到实例，`OK` / `MISSING` / `UNKNOWN` |
| `[Attribution]`              | 每个 config 的来源 jar 与所属 mod id                                     |
| `[Audit]`                    | 原有的类级审计；其中 `from mod <id>` 现在会标注来源                               |

`from mod <id>` 的 mod id 由 Launcher 从启用列表读取，并随启动参数 `--mod-id`  
交接给游戏 JVM。旧格式或读取失败时降级为 `(unknown)`，同时在 `[Attribution]`  
行给出原因，不影响启动。

### 开关

| 系统属性                       | 默认      | 作用                                                                 |
| -------------------------- | ------- | ------------------------------------------------------------------ |
| `mixinlib.logLimit`        | `5`     | 保留近 n 次启动日志历史（槽位 `1..n`）                                           |
| `mixinlib.mixin.logLevel`  | `INFO`  | Mixin 日志最低级别：`OFF` / `ERROR` / `WARN` / `INFO` / `DEBUG` / `TRACE` |
| `mixinlib.mixin.logFilter` | 空       | loggerId 前缀白名单，逗号分隔                                                |
| `mixinlib.mixin.log`       | `true`  | 整体关闭 Mixin 日志                                                      |
| `mixinlib.plugin.strict`   | `false` | 自检发现 plugin 缺失时是否硬失败；默认 false 只告警                                  |

### 已知项

- **一条既有的 ERROR**：`Initialising mixin subsystem after game pre-init phase! Some mixins may be skipped. `  
  由 `getInitialPhase()` 返回 `Phase.DEFAULT`引起。
- **负对照**：如果在 config 里写一个不存在的 plugin 类，日志中应同时出现：  
  `Error loading companion plugin class ...` 与 `[SelfCheck] ... status=MISSING`；
- 两条 bootstrap service 不可用的 DEBUG 行默认被 `INFO` 级别过滤：   <span style="color:rgb(248, 136, 37)">DEBUG</span>|mixin|Mixin bootstrap service <span style="color:rgb(41, 114, 244)">org.spongepowered.asm.service.mojang.MixinServiceLaunchWrapperBootstrap</span> is not available: LaunchWrapper is not available   <span style="color:rgb(248, 136, 37)">DEBUG</span>|mixin|Mixin bootstrap service <span style="color:rgb(41, 114, 244)">org.spongepowered.asm.service.modlauncher.MixinServiceModLauncherBootstrap</span> is not available: ModLauncher is not available
- 此外还有5条DEBUG 行：   <span style="color:rgb(69, 176, 118)">[info]</span>   call: <span style="color:rgb(248, 136, 37)">DEBUG</span>|mixin|Initialising Mixin Platform Manager   <span style="color:rgb(69, 176, 118)">[info]</span>   call: <span style="color:rgb(248, 136, 37)">DEBUG</span>|mixin|Adding mixin platform agents for container ContainerHandleVirtual(S1Probe:7edf8412)   <span style="color:rgb(69, 176, 118)">[info]</span>   call: <span style="color:rgb(248, 136, 37)">DEBUG</span>|mixin|Instancing new MixinPlatformAgentDefault for ContainerHandleVirtual(S1Probe:7edf8412)   <span style="color:rgb(69, 176, 118)">[info]</span>   call: <span style="color:rgb(248, 136, 37)">DEBUG</span>|mixin|MixinPlatformAgentDefault accepted container ContainerHandleVirtual(S1Probe:7edf8412)   <span style="color:rgb(69, 176, 118)">[info]</span>   call: <span style="color:rgb(248, 136, 37)">DEBUG</span>|mixin|Processing prepare() for PlatformAgent[MixinPlatformAgentDefault:ContainerHandleVirtual(S1Probe:7edf8412)]
