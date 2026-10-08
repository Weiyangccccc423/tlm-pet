# 开发环境说明

本文件记录本机**实测**得到的工具链与网络配置，以及为什么必须这样配。
换机器时按本文重做即可。

## 1. 工具链

| 项 | 值 |
|---|---|
| JDK | Temurin **17.0.20.1+1** |
| JDK 路径 | `D:\tools\jdk-17` |
| `JAVA_HOME` | 已写入用户级环境变量，指向上面路径 |
| Gradle | wrapper **8.8**（`mod/gradle/wrapper/gradle-wrapper.properties`） |
| Gradle 缓存 | `%USERPROFILE%\.gradle` |
| 本机系统 | Windows 11 10.0 amd64 |

### JDK 是怎么装的

本机原本**没有任何 JDK**（PATH、`JAVA_HOME`、常见安装目录全空），且当前会话**无管理员权限**，
因此不能走 `winget install`（MSI 会弹 UAC，非交互环境会卡住）。

实际做法是下载 portable ZIP 解压：

```powershell
# 从清华 TUNA 镜像取 Temurin 17（Adoptium 官方源在国内实测仅 85 KB/s）
curl.exe -L -o jdk17.zip `
  "https://mirrors.tuna.tsinghua.edu.cn/Adoptium/17/jdk/x64/windows/OpenJDK17U-jdk_x64_windows_hotspot_17.0.20.1_1.zip"
tar.exe -xf jdk17.zip -C <目标目录>
```

> JDK 9+ 没有 `lib\tools.jar` 是正常的（该类库已移除），不要据此判断安装失败。

## 2. 网络：必须走代理

**这是本仓库最关键的环境约束。**

直连各构建仓库的**实测吞吐**（2026 年，本机、本网络）：

| 仓库 | 直连 | 经代理 `127.0.0.1:7897` |
|---|---|---|
| `services.gradle.org`（Gradle 发行版） | **0 KB/s，完全不可达** | **14 MB/s** |
| `maven.minecraftforge.net`（Forge） | **1 KB/s** | 894 KB/s |
| `repo.maven.apache.org`（Maven Central） | 64 KB/s | 可用 |
| `libraries.minecraft.net`（Mojang 库） | 128 KB/s | 可用 |
| `www.cursemaven.com`（TLM 依赖） | 2 KB/s | 可用 |
| 腾讯云 Gradle 镜像 | 14 MB/s | — |

按直连速度，一次完整构建需要**数天**且大量超时重试，实际上不可能完成。

### 代理配置方式

本机运行 **Clash Verge**（进程 `verge-mihomo`，监听 `127.0.0.1:7897`）。
已写入 `%USERPROFILE%\.gradle\gradle.properties`：

```properties
systemProp.http.proxyHost=127.0.0.1
systemProp.http.proxyPort=7897
systemProp.https.proxyHost=127.0.0.1
systemProp.https.proxyPort=7897
```

**这是用户级全局配置，会影响本机所有 Gradle 项目**（也包括 `TouhouLittleMaid` 本体）。
若要撤销，删掉该文件即可；文件里也写了同样的说明。

> 注意：Gradle wrapper 在下载 Gradle 发行版**之前**就会读取 `GRADLE_USER_HOME/gradle.properties`，
> 所以把代理放在这里是有效的，不需要额外设 `GRADLE_OPTS`。

### 为什么不用国内镜像替代代理

试过 **BMCLAPI**（`bmclapi2.bangbang93.com`）：Forge 路径实测 667 KB/s（比官方快 600 倍），
但 `libraries` 与 `assets` 路径**不稳定/失败**。既然代理能让官方源跑到 14 MB/s，
就没有必要引入镜像这一层不确定性和维护负担。

顺带一提：**TLM 本体自己的 `build.gradle:6-9` 已经内置了阿里云镜像**（注释写着「方便国内开发」），
说明上游维护者遇到过同样的网络问题。

## 3. 常用命令

所有命令都在 `D:\tlm-pet\mod` 下执行，且需要 `JAVA_HOME` 已设置：

```bat
cd D:\tlm-pet\mod

gradlew.bat build              :: 完整构建（含 reobfJar）
gradlew.bat runClient          :: 启动带 TLM 的开发客户端
gradlew.bat runServer
gradlew.bat runData            :: 数据生成，输出到 src/generated/resources
gradlew.bat tasks --all        :: 查看全部任务
```

若在非交互 shell 中运行且代理配置被清掉，可临时补上：

```powershell
$env:GRADLE_OPTS = '-Dhttp.proxyHost=127.0.0.1 -Dhttp.proxyPort=7897 -Dhttps.proxyHost=127.0.0.1 -Dhttps.proxyPort=7897'
```

## 4. 首次构建的坑：冷缓存瞬时失败

**症状**：全新环境下第一次 `gradlew.bat build` 可能在配置阶段就失败：

```
A problem occurred configuring root project 'tlm_pet-1.20.1'.
> Invalid patcher dependency, could not resolve universal: null
    at net.minecraftforge.gradle.userdev.MinecraftUserRepo$Patcher.<init>(MinecraftUserRepo.java:1462)
```

**这不是配置错误，重跑就好。** 已实测确认：

| 实验 | 结果 |
|---|---|
| 冷缓存下构建本项目 | 失败，报上述错误，4m22s |
| 冷缓存下构建**未经改动的官方模板** | **BUILD SUCCESSFUL**，9m26s |
| 缓存预热后再次构建本项目 | **BUILD SUCCESSFUL**，26s |

原因是首次运行需要拉取 Forge `1.20.1-47.4.0` 的 installer/patcher 构件，拉到一半失败时
ForgeGradle 把「解析不到 universal」报告成了这个极具误导性的 `null` 错误，看起来像是版本号写错了。

**排查技巧**：这个错误发生在**配置阶段**（`afterEvaluate`），所以不必等完整构建。
用 `gradlew.bat help` 就能在几十秒内复现或确认修复：

```bat
cd D:\tlm-pet\mod
gradlew.bat help
```

另外可以用缓存反查到底是哪一步没下来：

```powershell
# 若这里没有 forge 目录，说明 Forge 本体从未解析成功
dir "$env:USERPROFILE\.gradle\caches\modules-2\files-2.1\net.minecraftforge"
# ForgeGradle 处理后的 Forge jar（成功时会存在，含 -injected / _mapped_）
dir "$env:USERPROFILE\.gradle\caches\forge_gradle\minecraft_user_repo\net\minecraftforge\forge"
```

### 4.1 首次 `runClient`：资源下载需要重跑

**症状**：第一次 `gradlew.bat runClient` 在 `downloadAssets` 阶段失败：

```
Failed to get asset: minecraft/sounds/item/goat_horn/call2.ogg
Failed to get asset: minecraft/sounds/mob/dolphin/jump1.ogg
...
Some assets failed to download or validate, try running the task again.
BUILD FAILED in 7m 14s
```

**同样重跑即可。** 实测第一次 1659 个资源里只有 **6 个**超时失败；`downloadAssets` 是增量的，
第二次运行只补这 6 个，然后直接进游戏。

资源站 `resources.download.minecraft.net` 实测只有 **29–40 KB/s**（走代理也没快多少），
但资源都是几 KB 的 `.ogg`，所以耗时主要是并发请求累积，不是带宽瓶颈。

顺带一提：`DownloadAssets` 走的是 `FileUtils.copyURLToFile` + 原生 `HttpURLConnection`，
它只认 JVM 的 `http.proxyHost` / `https.proxyHost` **系统属性** —— 而 `~/.gradle/gradle.properties`
里的 `systemProp.*` 正是往 Gradle JVM 注入这些属性，所以代理配置对它同样有效。

## 5. 编码注意事项

JDK 17 在中文 Windows 上 `file.encoding` 仍默认为 **GBK**（JDK 18 才改为 UTF-8）。
已做三处显式处理，改动构建脚本时**不要删掉**：

| 位置 | 设置 | 作用 |
|---|---|---|
| `mod/gradle.properties` | `org.gradle.jvmargs=... -Dfile.encoding=UTF-8` | Gradle 自身进程 |
| `mod/build.gradle` | `tasks.withType(JavaCompile) { options.encoding = 'UTF-8' }` | 中文注释的 Java 源码 |
| `mod/build.gradle` | `processResources { filteringCharset = 'UTF-8' }` | `mods.toml` / `pack.mcmeta` 的变量替换 |

最后一条是**官方模板缺失**的：模板只设了 `options.encoding`，但资源过滤仍走平台默认编码。
一旦 `mod_name` / `mod_description` 里出现中文，就会被写坏。
