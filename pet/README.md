# 酒狐桌宠 · 普通模式与游戏模式

TypeScript + Electron + Three.js。复用本项目已有的酒狐模型、贴图与动画。

## 启动

### 双击启动（Windows x64）

打包后打开 `release/WineFoxPet/`，双击 **酒狐.exe**，无需安装 Node.js。
整个文件夹可以移动到你喜欢的位置，请保留其中的运行库、`resources/`、`personas/` 和 `prompts/`。
可分享最新 release 目录里的 ZIP，使用前完整解压。
聊天、记忆与设置仍保存在 `%APPDATA%\WineFoxPet`，与开发版共用。

右键系统托盘中的狐狸图标，可以选择 **重启酒狐** 或 **退出**；双击托盘图标可恢复隐藏的窗口。

### 源码启动

需要 Node.js 22.12+（本次使用 Node.js 24），在 Windows 下开发和验证。

```powershell
cd D:\tlm-pet\pet
npm install
npm run build
npm run desktop
```

首次安装 Electron 下载失败时，可尝试镜像后重新安装：

```powershell
$env:ELECTRON_MIRROR = 'https://npmmirror.com/mirrors/electron/'
npm install
```

网页预览：`npm run dev`，访问终端给出的本机地址。网页用于查看界面，桌面拖动、置顶与点击穿透需要 Electron。
修改前端后重新 build 并启动桌面程序；网页预览支持热更新。

## 已实现

- 透明无边框窗口、置顶开关、透明区域点击穿透、拖动角色移动窗口。
- 站立、坐下、招手、闭眼休息、耳朵及尾巴动画、鼠标注视，最多 30 帧/秒。
- 点击角色或爱心打招呼；月亮按钮依次切换坐下、休息、站立。
- 聊天面板，默认无需联网的简单预设回应。
- 手动添加、删除记忆；聊天与记忆跨重启保存。
- 可配置 AI 服务，Agent 可检索记忆与切换动作。
- 托盘显示、隐藏、退出；单实例运行。隐藏后双击托盘图标恢复。
- 动画开关、角色大小与角色素材署名。
- 普通/游戏模式切换，游戏模式的小桌、椅子、迷你键盘与鼠标。
- 世界存档只读导入：选择 Minecraft 实例、saves 目录或单个世界，查看世界来源、酒狐投影、游戏内摘要、对话、好感度、击杀和五子棋记录。
- Windows 全局键鼠同步：按键下压与高亮、手部动作、鼠标移动与点击反馈。

## 游戏模式

点击顶部「游戏」，酒狐坐到小桌前陪你玩电脑；点击「普通」返回日常陪伴。
键盘只保留 Q、W、E、A、S、D、Shift、空格和 Ctrl，支持同时按键和左右两侧 Shift/Ctrl。
键盘布局与文字面向酒狐，空格/修饰键靠近她。键帽使用大号深色字形和高分辨率贴图。
按键手通过肩肘关节平滑移动到对应键位；同时按多个键时追随最近按下的键，全部松开后回到 S 键上方。
鼠标显示左/右键按下，中键与滚轮有轮子反馈，移动映射到鼠标垫内有限的位移。

桌面版通过 `uiohook-napi` 监听 Windows 全局输入，游戏获得焦点后仍会同步。
只映射以上输入到动画，不保存按键、不上传输入、不自动点击或替你操作电脑。
点击「键鼠同步」可开关，设置面板也有开关；打开面板、拖动、招手、休息或隐藏时暂停，恢复后继续。
模式与同步开关跨重启保存；旧版资料自动补上默认设置，保留原有聊天和记忆。

建议游戏使用无边框窗口模式。独占全屏游戏可能覆盖桌宠；本版没有管理员权限模式或自动识别游戏进程。
网页预览只跟随本页面内的键鼠，不支持全局同步。

## AI 接入

在设置里选择「AI 服务」，填写地址（通常包含 `/v1`）、模型名称与可选密钥。
服务需兼容 Chat Completions 及 `tools` / `tool_calls`；请求发送到 `${baseUrl}/chat/completions`。
Agent 只开放 `recall_memory` 和 `set_pose` 两个工具，姿势为 idle、sit、wave、sleep。
游戏模式的 idle 表示坐到电脑桌前。AI 会收到当前模式和同步开关，不会收到实际键鼠数据。
没有配置真实服务时，离线回应只是预设规则，不是本地大语言模型。

AI 服务会收到最近聊天与已保存的手动记忆。默认离线模式不发送这些数据。
真实 AI 服务未在本次开发中连接；工具调用流程使用本机模拟服务验证。

## 数据和文件

### 修改人格

直接编辑 [personas/winefox.md](personas/winefox.md)，调整性格、爱好与说话习惯。
默认人格基于游戏内 `tartaric_acid` 的酒狐人设整理为中文，来源与适配说明见 [personas/README.md](personas/README.md)。
桌面行为、本体与投影设定单独放在 [prompts/desktop.md](prompts/desktop.md)。

两个文件每次 AI 对话都会重新读取，保存后下一条对话生效，无需重新构建或重启。
同一轮工具调用保持同一份设定；历史与记忆保留，因此旧对话仍可能影响回复。
文件缺失或为空时提示错误。离线预设回应不受这些文件影响。
运行程序时保留 `pet/personas/` 和 `pet/prompts/` 目录；它们从程序目录读取，与启动命令的当前目录无关。

### 存储和模块

- 桌面版：`%APPDATA%\WineFoxPet\data\state.json`。密钥使用 Electron safeStorage 调用系统加密后保存；聊天及记忆为本地明文 JSON。
- 网页预览：`pet/.local-data/state.json`。密钥仅在预览服务内存中保留，重启服务需重新输入。
- `src/agent/`：聊天、工具调用、记忆和持久化，可独立于 Electron 扩展。
- `src/pet/`：Bedrock 动画适配、Three.js 场景与桌面外设。支持动作所需的简单 Molang 算术、时间、角度正弦/余弦，不是完整 Molang 引擎。
- `electron/`：窗口、托盘、受限 IPC、系统密钥存储与全局输入监听。
- `public/assets/CREDITS.md`：资源来源、作者与 CC BY-NC-SA 4.0 说明。

游戏存档读取支持只读扫描；记忆可以来自手动记录和已导入的世界记录。酒狐设定为桌宠本体、各世界酒狐为投影。
这一版提供 Windows 免安装包；系统操作、键盘自动输入和语音尚未实现。

## 打包

在 Windows x64 开发环境中运行：

```powershell
npm run package:win
npm run qa:package
npm run qa:game:package
```

打包使用已经安装的 Electron 运行库，不需要再次下载。输出为 `release/WineFoxPet/酒狐.exe` 与带完整目录的 ZIP。
复制程序、模型、提示词、Windows x64 原生输入模块及其源码与许可；用户密钥、聊天、记忆和本地预览数据不会进入包中。
打包前请退出正在运行的免安装版，脚本会重新生成 `release/WineFoxPet/`。
免安装版的人格文件位于 exe 旁边的 `personas/`，桌面规则位于 `prompts/`，编辑后下一条 AI 对话生效。
重新打包会保留这两个文件的用户修改，未修改的文件升级为新版默认内容。
输入库的许可与替换说明见 `third-party/README.md`。

## 验证

```powershell
npm test
npm run build
npm run qa
npm run qa:game
```

测试覆盖旧资料迁移、模式持久化、记忆持久化、密钥与快照隔离、Agent 工具调用和失败时的聊天记录、人格文件即时重读与错误处理、动画插值和 Molang 表达式。
QA 启动真实 Electron 窗口，用独立临时数据目录验证聊天、记忆、设置、点击穿透、窗口拖动与姿势，
截图写入 `qa-artifacts/`，退出后删除测试数据。
`qa:package` 对实际打包的 exe 执行同样的检查，并验证外置人格文件加载。
`qa:game` 在另一窗口获得焦点时发送真实 Windows 输入，验证全局键盘、九个键位的手部落点、键盘朝向、鼠标按键与移动、暂停/恢复和模式保存；`qa:game:package` 对打包程序执行同样检查。
