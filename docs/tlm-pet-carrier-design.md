# 女仆跨世界载体（TLM-Pet）设计文档

| 项目 | 内容 |
|---|---|
| 状态 | 设计已定稿，待实现 |
| 目标版本 | Touhou Little Maid `1.5.3-forge+mc1.20.1`（MC 1.20.1 / Forge 47.2.0） |
| 形态 | 独立附属模组 + 桌面程序（桌宠） |
| 基线仓库 | TLM 官方源码 checkout（分支 `1.20`），本地路径任意 |
| 文档性质 | 实现依据。所有"既有能力"条目均带 `路径:行号` 证据 |

> 本文档与 `mod/` 工程在同一个仓库（`tlm-pet`）中维护。文中所有 `路径:行号` 证据均指 TLM 官方源码。

---

## 1. 背景与目标

### 1.1 动机

女仆是玩家在 Minecraft 世界中的长期陪伴。但女仆的全部状态绑定在单个存档内 —— 玩家前往新世界（新存档、新整合包）时，女仆只能孤独地留在旧世界。

### 1.2 目标

1. **抽离**：把某个女仆的完整身份与记忆从当前存档中取出，形成可移植数据包。
2. **载体**：桌宠程序作为女仆在存档之间的"家"，持久保存她的数据与冒险记忆。
3. **注入**：把数据注入新存档，让她继续陪伴玩家。
4. **记忆沉淀**：冒险数据在桌宠侧沉淀为知识库，使每位玩家拥有独一无二的、跨世界连续的女仆。

### 1.3 非目标（明确不做）

- **不携带任何物品、装备、背包内容**（见 D2）。
- **不做模组侧的内置 HTTP 服务器**（见 D5）。
- **不做云端同步 / 账号系统** —— 数据只存在玩家本机，不出本机。
- 不改造 TLM 本体源码，全部通过官方扩展点实现。

---

## 2. 已确认的决策

| 编号 | 决策 | 理由 |
|---|---|---|
| **D1** | 形态为**独立附属模组**，不改 TLM 源码 | 可发布、可跟随上游更新 |
| **D2** | **不携带任何物品** | ① 避免跨存档复制 / 刷物品；② 避免整合包间装备不兼容；③ 与"玩家进入新世界两手空空"语义一致 |
| **D3** | **完整保留好感度与属性**（方案 B） | 好感度是不可兑换的情感数值，是"精神寄托"的载体；保留好感度 + 保留 `Attributes` 二者天然自洽，无需重算 |
| **D4** | 逻辑身份 ID 与实体 UUID **分离** | 逻辑 ID 永不变（桌宠主键、记忆索引）；注入时重新分配实体 UUID，避免跨档冲突 |
| **D5** | 桥接通道：**桌宠侧本地 HTTP 为主 + 文件交换兜底**；不做模组侧服务器 | 与既有架构一致（`Player2` 先例）；避免防火墙弹窗与端口暴露 |
| **D6** | 抽离语义为**"收走"**而非"复制" | 与叙事一致，且天然防复制 |
| **D7** | 每位玩家**绝对唯一**一个女仆身份（singleton） | 她是"那一个她"。跨存档、跨世界恒为同一身份；**不存在"第二只"，也不存在自动重置** |
| **D8** | **禁止在新世界收服新女仆** | 防"串味"：若新世界能再驯服一只，就会出现两个女仆身份，D7 与 D3（羁绊完整性）同时被破坏 |
| **D9** | 本模组**最小接管**原版对应成就（5 个，见 §13.3） | 玩法已变（一生一只、跨世界重逢），这几个成就语义脱节或被结构性破坏，由我们的成就树取代；`challenge/*` 与 `favorability/*` 保持不动 |
| **D10** | 唯一性**只能**通过「**放手仪式**」重置（§12.6） | 把"不可逆"转化为有叙事重量的主动抉择。**修订：以她的胶卷举行，不要求她在场** —— 这样玩家在任何世界都能告别，顺带解决 R17 的锁死风险 |
| **D11** | 接受引入 **mixin** | 创造模式绕过（D7 必需，§12.3）与成就接管方案 A（§13.4）都依赖 mixin，附属模组需配置 MixinGradle |
| **D12** | 成就附加**实质奖励**，用于打造**跨世界召回物品**（§14） | 让成就从收集品变成玩法阶梯；同时把"世界内召回"留给既有 `ItemServantBell`，避免重复实现 |

> **D7/D8/D9 是后续追加的收紧约束**，详见 §12 与 §13。D8 实质上强化了 D6 ——
> 抽离必须是"收走"，且新世界不得成为"第二只女仆"的来源。

### 2.1 D3 的连带结论

选定方案 B 后，以下字段**必须保留**，不得因为"防作弊"而顺手清空：

- `MaidFavorability`（好感度原始值）
- `Attributes`（含 `MAX_HEALTH` / `ATTACK_DAMAGE` 的 base 值）
- `StruckByLightning`（雷击标记，见 §5.2）
- `FavorabilityManagerCounter`（好感度冷却计数器）

原因：好感度等级与 `Attributes` base 值是通过 `setBaseValue` 绑定的，二者必须同时保留才能保持一致。任何"只改其一"的操作都会产生数值不一致（见 §5.3）。

---

## 3. 技术底座：既有能力盘点

实现此功能所需的能力，TLM 大半已经具备。**以下全部经源码核实。**

### 3.1 抽离端已经完整存在

`world/backups/MaidBackupsManager.java` 是一套生产中的女仆快照 / 回灌系统。

| 能力 | 证据 |
|---|---|
| 存储结构：`<存档>/data/maid_backups/<owner_uuid>/index.dat` + `<owner_uuid>/<maid_uuid>/yyyy-MM-dd-HH-mm-ss.dat` | 类注释 `MaidBackupsManager.java:36-48` |
| 序列化：`maid.saveAsPassenger(entityData)`（**全量快照**） | `MaidBackupsManager.java:130` |
| gzip 压缩写盘 + 数据版本标记 | `NbtIo.writeCompressed` `:309`、`NbtUtils.addCurrentDataVersion` `:308` |
| 自动备份：每 180 秒按 UUID 哈希错峰，1.4.2 起强制开启 | `EntityMaid.java:553-559`、`ServerConfig.java:30`（默认 `60*3`，范围 5~MAX） |
| 保留策略：滚动删除，**默认只留 3 份** | `removeOldBackups` `:319-338`、`ServerConfig.java:33`（默认 3，范围 1~64） |
| public 读取 API | `getMaidIndexMap(player)` `:159`、`getMaidBackupFiles(player, maidUuid)` `:174`、`getMaidBackFile(player, maidUuid, fileName)` `:194` |
| 既有回灌路径 | `/tlm backup get <player> <maid_uuid> <file>` → `ItemCamera.spawnMaidPhoto` → 照片物品 → 放置即重生女仆（`BackupCommand.java:143-161`、`ItemCamera.java:68-87`） |

**结论：抽离端无需自研**。`maid.saveAsPassenger(new CompoundTag())` 一行即为全量快照（`Entity.saveAsPassenger` 为 public）。

### 3.2 本地桌面程序桥接已有生产先例

`ai/service/llm/DefaultLLMSite.java:19-22`：

```java
public static LLMOpenAISite PLAYER2 = createSite("player2",
        "http://127.0.0.1:4315/v1/chat/completions", false,
        Map.of("player2-game-key", "TouhouLittleMaid"),
        "default");
```

`Player2` 本身就是一个桌面程序，模组通过 `127.0.0.1` 的本地 HTTP 与之通信。同类先例还有：

- `ai/service/stt/player2/STTPlayer2Site.java:90` — `http://127.0.0.1:4315/v1/stt`
- `ai/service/tts/player2/TTSPlayer2Site.java:99` — `http://127.0.0.1:4315/v1/tts/speak`
- `ai/service/tts/gptsovits/TTSGptSovitsSite.java:163` — `http://127.0.0.1:9880/tts`

**结论：「模组 ↔ 玩家本机桌面程序」是本仓库已验证的架构，不是新发明。**

### 3.3 可复用的 HTTP 技术栈

全部基于 JDK `java.net.http.HttpClient`（**无 OkHttp / HttpURLConnection**）。

| 可复用项 | 说明 |
|---|---|
| `LLMSite.LLM_HTTP_CLIENT` `:25-29` | 预配置单例：10s connectTimeout + `ConfigProxySelector` + HTTP/1.1 |
| `ConfigProxySelector` `:11/:18/:27/:37` | 代理选择器，配置格式 `host:port`，**不支持 SOCKS** |
| `Client.GSON` `:16` | 共享 `Gson` 实例 |
| `Client.isSuccessful(HttpResponse<?>)` `:21` | 状态码判定 |
| 错误码枚举 `ai/service/ErrorCode.java` | 统一错误语义 |
| 线路降级范式 `InfoGetManager.java:164-169` | `exceptionallyCompose` 切备用线路 |
| `LLMClient.handleResponse(...)` `:31` | 统一处理非 2xx / `JsonSyntaxException` |

### 3.4 注入端的规范路径

`item/ItemCamera.java:68-87` 揭示了项目自身的标准加载路径：

```java
Optional<Entity> optional = EntityType.create(data, worldIn);   // 自动调 readAdditionalSaveData
if (optional.isEmpty() || !(optional.get() instanceof EntityMaid maid)) return;
maid.setHomeModeEnable(false);                                  // 关闭"家"模式语义
maid.saveWithoutId(maidTag);
maidTag.putString("id", "touhou_little_maid:maid");             // id 标签是必需的
```

两个关键事实：

1. **`EntityType.create(tag, level)` 要求 NBT 内存在 `id` 标签**。`ItemFilm` 也在 `:42` 手动补 `id`。
2. **`saveAsPassenger` 自带 `id`；`saveWithoutId` 不带**。因此抽离基底应使用 `saveAsPassenger`，对跨版本重载更稳。

又：`MaidBackupsManager` 的索引以**文件夹名**（`maid.getStringUUID()`，`:348`）为 key，说明 `saveAsPassenger` 产物内**不含 `UUID`**。这与 D4 天然吻合 —— 重新注入自然获得新 UUID。

### 3.5 其它可直接复用

| 能力 | 位置 | 用途 |
|---|---|---|
| 分享码范式（Deflate + Base64 URL-safe + 逗号分段） | `api/game/gomoku/GomokuCodec.java:24/67` | 生成"女仆导出码"供纯文本分享 |
| 跨平台共享目录 | `util/SystemAppDataUtil.java:18/64` | `%APPDATA%` / macOS / Linux XDG / Android 全覆盖。**当前全仓库无调用者** |
| 事件钩子（转换女仆 ↔ 物品） | `api/event/MaidAndItemTransformEvent.java`（`ToItem` / `ToMaid`） | 抽离/注入时抛出，供整合包介入 |
| 剪贴板写入 | `event/CopyEntityIdEvent.java:40` | 复制导出码 |
| 自定义 tooltip（含 3D 女仆预览） | `client/tooltip/ClientMaidTooltip.java:32/128` + `client/init/InitClientTooltip.java:14` | 数据包物品的展示 |
| 列表式选择界面骨架 | `client/gui/entity/maid/ai/settings/AIChatSettingsHubScreen.java:32/69/113/120/151/189` | 迎回界面 |

### 3.6 明确**不要**复用的三处

**（1）不要注册成 `Site` 抽象。**
虽然技术上可经 `registerAIChatSerializer`（`api/ILittleMaid.java:115`）注册，但 `Site` 会出现在 AI 对话的站点列表里被当作"可选大模型"，语义错误。且 `ServiceType` 是 **enum（`ai/service/ServiceType.java:3`，仅 LLM/STT/TTS）**，附属模组**无法新增服务类型**。
**正确做法：复用其技术（`HttpClient` 单例 + `ConfigProxySelector` + Gson），不复用其抽象。**

**（2）不要照抄 `ItemFilm.removeMaidSomeData`（`ItemFilm.java:80-101`）。**
该清单**遗漏了两个物品栏**：

| 遗漏字段 | 字面量 | 格数 | 证据 |
|---|---|---|---|
| `MAID_HIDE_INVENTORY_TAG` | `MaidHideInventory` | **1 格** | `EntityMaid.java:274` `new ItemStackHandler(1)` |
| `MAID_TASK_INVENTORY_TAG` | `MaidTaskInventory` | **9 格** | `EntityMaid.java:276` `new ItemStackHandler(9)` |

两者都被序列化（`:1363-1364`）与还原（`:1459-1463`）。照抄会给"不带物品"政策留下 **10 个格子的洞**。

**（3）不要把桌宠凭证放进 `Site` 的 `secret_key`。**
站点配置是 `config/touhou_little_maid/sites/*.json` 中的**明文** json（`LLMOpenAISite.java:186` → `AvailableSites.saveSites():91` → `LLMSite.writeSites:56`），**且会经 `SyncAISitesMessage:30/101` 下发到客户端**。

---

## 4. 数据地图与四态政策

### 4.1 存储位置总览

| 层 | 内容 | 位置 |
|---|---|---|
| 实体 NBT | 女仆的**几乎全部**状态（含记忆、记录、好感度、配置、物品栏） | `EntityMaid.addAdditionalSaveData` `:1344-1387` |
| 世界级 SavedData | 女仆位置索引与墓碑索引（owner → 列表） | `world/data/MaidWorldData.java:25`（**全仓库唯一的 SavedData**） |
| 玩家 Capability | `MaidNumCapability`（女仆数量上限）、`PowerCapability`、`ChatTokensCapability` | `capability/`（三个 `ICapabilitySerializable`） |
| 存档内文件 | 女仆快照 `.dat` | `<world>/data/maid_backups/` |

`MaidWorldData` 与 `MaidNumCapability` 是**最容易遗漏的两处非实体存储**，注入时必须分别"重新登记"与"检查自增"。

### 4.2 四态政策表

处理动作分四类：**剥**（移除）/ **留**（保留）/ **重映射**（改值）/ **清理+登记**（注入后调用 API）。

| 数据 | NBT 字面量 | 可见性 | 动作 |
|---|---|---|---|
| 主物品栏 | `MaidInventory` | public | **剥** |
| 饰品 | `MaidBaubleInventory` | public | **剥** |
| 隐藏栏（1 格） | `MaidHideInventory` | public | **剥** ← 上游清单遗漏 |
| 任务栏（9 格） | `MaidTaskInventory` | public | **剥** ← 上游清单遗漏 |
| 背包类型 | `MaidBackpackType` | public | **剥** |
| 背包内部数据（熔炉/储罐） | `MaidBackpackData` | private | **剥** |
| 护甲 / 手持 | `ArmorItems` / `HandItems` | vanilla | **剥** |
| 拴绳 / 乘客 | `Leash` / `Passengers` | vanilla | **剥** |
| **经验** | `MaidExperience` | public | **剥**（可换附魔之瓶，属物品等价物，见 §4.3） |
| 状态：血量/火/空气/摔落/药水/冰冻 | `Health`、`Fire`、`Air`、`FallDistance`、`ActiveEffects`、`TicksFrozen`、`HasVisualFire`、`HurtTime`、`DeathTime`、`HurtByTimestamp` | vanilla | **剥**（满状态回归） |
| 位置与运动 | `Pos` / `Motion` | vanilla | **剥**（按新坐标 setPos） |
| **日程坐标** | `MaidSchedulePos`（含 `Work`/`Idle`/`Sleep`/`Dimension`） | `SchedulePos.java:82` 字面量 | **剥**（绝对坐标，不清她会往旧坐标跑） |
| **限制点** | `HomePos` / `HomeRadius` | vanilla（Mob） | **剥**（对应 `maid.clearRestriction()`） |
| 结构生成标记 | `StructureSpawn` | private | **重置为 false** |
| 家模式开关 | `MaidIsHome`（在 `MaidSubConfig` 同级） | private | **重置为 false**（同 `ItemCamera.java:76` 做法） |
| 名字 | `CustomName` | vanilla | **留** |
| 模型 ID / 音效包 | `ModelId` / `SoundPackId` | public | **留**（资源包需另外同步，见 §11 R1） |
| YSM 模型数据 | `YsmModelId`、`YsmModelTexture`、`YsmModelName`、`YsmRouletteAnim`、`YsmRoamingVars`、`YsmRoamingUpdateFlag`、`IsYsmModel` | public | **留** |
| 工作模式 | `MaidTask` | private | **留**（解析失败回落 idle，容错见 `:1428`） |
| 日程模式 | `MaidScheduleMode` | private | **留** |
| 子配置 | `MaidSubConfig`（8 个子字段） | private | **留** |
| 饥饿 | `MaidHunger` | private | **留** |
| **好感度** | `MaidFavorability` | private | **留**（D3） |
| **属性** | `Attributes` | vanilla | **留**（D3，含 80 血 / 6 攻 base 值） |
| 好感度冷却计数器 | `FavorabilityManagerCounter` | `FavorabilityManager.java:58` | **留** |
| 雷击标记 | `StruckByLightning` | private | **留**（见 §5.2） |
| 不死标记 | `Invulnerable` | private | **剥**（避免把作弊状态带过去） |
| **记忆：对话历史** | `MaidHistoryChat`（≤512 条） | protected | **留**（知识库原料） |
| **记忆：压缩摘要** | `MaidHistorySummary` | protected | **留**（跨世界连续性通道，见 §9） |
| Token 计量 | `MaidLastChatTokenUsage` | protected | **重置为 0** |
| AI 站点配置 | `MaidAIChat`（8 字段） | `MaidAIChatSerializable.java:61/75` | **留**（站点缺失自动回落默认站点，`MaidAIChatData.java:92-105`） |
| **AI 提示词里的主人名** | `MaidAIChat` → `OwnerName` | 同上 | **重映射**（否则她用旧主人的称呼，见 §4.4） |
| 棋局记录 | `MaidGameSkillData`（内含 `Gomoku` 等） | `MaidGameRecordManager.java:12-13` | **留** |
| 击杀记录 | `KillRecord`（含 `TotalCount`/`Slime`/`Wither`/`EnderDragon`） | `MaidKillRecordManager.java:15-19` | **留** |
| 主人 | `Owner`（UUID） | vanilla（TamableAnimal） | **重映射**为注入者 |
| 坐姿 | `Sitting` | vanilla | **重置为 false** |
| 女仆数量上限 | — | `MaidNumCapability` | **检查 `canAdd()` → 成功后 `add()`** |
| 世界索引 | — | `MaidWorldData` | **注入后 `addInfo(maid)`** |

### 4.3 为什么经验必须剥离

`MaidExperience` 不在物品栏里，字面上不属"物品"，但它**可以直接变成物品**：

```java
// event/maid/GetExpBottleEvent.java:34-48 —— 拿玻璃瓶右键女仆
int count = maid.getExperience() / PER_BOTTLE_XP;   // 12 XP = 1 瓶
maid.setExperience(maid.getExperience() - costNum);
ItemStack xpBottles = new ItemStack(Items.EXPERIENCE_BOTTLE, count);
```

另有两条出口：`getExperienceReward()` 使其参与死亡掉落（`EntityMaid.java:1676`），以及 `compat/sbackpack` 的液体经验互换。若不剥离，就存在"存档 A 攒经验 → 桌宠 → 存档 B 换附魔之瓶"的完整资源转移通道。

### 4.4 重映射 `OwnerName` 是情感关键项

`MaidAIChat` 内的 `OwnerName` 被用作 LLM 提示词中的玩家称呼：

- `ai/manager/setting/papi/PapiReplacer.java:57-61`
- `ai/agent/context/tools/UserContexts.java:35-39`

若只带记忆不带称呼，女仆会用**上一个玩家的名字**称呼新玩家。这是本功能中"最容易被漏掉、但体验上最出戏"的一项。

---

## 5. 好感度方案 B 的落实

### 5.1 保留的数值与后果

`entity/favorability/FavorabilityManager.java:116-207`：

| 好感度等级 | 阈值 | 最大生命 | 攻击力 | 攻击距离 | 横扫范围 |
|---|---|---|---|---|---|
| 0 | 0 | 20 | 2 | +0 | 1 |
| 1 | 64 | 30 | 3 | +1 | 2 |
| 2 | 192 | 40 | 4 | +3 | 3 |
| 3 | 384 | **80** | **6** | +5 | 4 |

**方案 B 的直接后果：一个满羁绊女仆进入新世界即具备 80 血 / 6 攻击**，远高于"两手空空"的新玩家（20 血 / 1 攻击）。这是玩家已确认接受的取舍 —— 情感连续性优先于前期数值平衡。

### 5.2 雷击标记为何保留且无副作用

`FavorabilityManager.java:246` 中 `+20` 血的条件是 `maid.isStruckByLightning()`，且**该逻辑只在好感度等级发生变化时（`add()` 内）执行**（`:237` `if (levelBefore < levelAfter)`）。

由于：
- 满级女仆不会再升级；
- 已获得的 +20 血已固化在 `Attributes` 的 base 值中；

保留 `StruckByLightning` 只是保留一条"未来若降级再升级时"的规则，**无实际副作用**。故保留。

### 5.3 明确不启用好感度折算

本文档此前的分析中曾给出折算函数（用于方案 A/C）。**方案 B 下该逻辑不启用**，且**不得**在剥离流程中修改 `MaidFavorability` 或 `Attributes`。

原因（重要，记录以备未来改方案）：

`FavorabilityManager.add()` 中，**所有属性更新都位于 `if (levelBefore < levelAfter)` 分支内**（`FavorabilityManager.java:237-268`）—— 即只在好感度**等级上升**时才写属性 base 值：

```java
int levelBefore = getLevel();
int result = Mth.clamp(favorability + addPoint, 0, LEVEL_3_POINT);
maid.setFavorability(result);          // ← 只改数值
int levelAfter = getLevel();

if (levelBefore < levelAfter) {        // ← 只有升级才重算属性
    attack.setBaseValue(this.getAttackByLevel(levelAfter));
    health.setBaseValue(this.isStruckByLightning() ? getHealthByLevel(levelAfter) + 20
                                                   : getHealthByLevel(levelAfter));
    ...
}
```

而 `setFavorability` 本身**不会**触碰 `Attributes`；同时 `Attributes` 的 base 值**又被序列化进实体 NBT**。

因此存在**两个方向**的不一致风险，方案 B 恰好同时规避：

| 错误做法 | 后果 |
|---|---|
| 改小 `MaidFavorability` 但不重算 `Attributes` | **好感度 0，却依然 80 血 / 6 攻** |
| 保留 `MaidFavorability` 但剥离 `Attributes` | **好感度 3 级，却只有 20 血 / 2 攻**（因为没有任何代码会去重算它） |

**结论：方案 B 必须同时保留 `MaidFavorability` 与 `Attributes`，二者不可分割。** 这是方案 B 的一个隐藏优点 —— 无需任何重算逻辑。

### 5.4 无副作用确认

- **不会误触发成就**：`InitTrigger` 的触发点（`:255`/`:260`/`:262`）同样位于 `if (levelBefore < levelAfter)` 分支内，且额外要求 `maid.getOwner() instanceof ServerPlayer`（`:254`/`:259`）。我们通过 `readAdditionalSaveData` → `setFavorability` 直接赋值，**既不经过 `add()`，注入时 owner 也不是 `ServerPlayer`**，双重条件下不会误触发 `MAID_100_HEALTHY` / `FAVORABILITY_INCREASED` 等成就。
- **不会误触发事件**：`MaidFavorabilityLevelChangeEvent` 由 `onFavorabilityLevelChange(levelBefore, levelAfter)`（`:267`）触发，同样在该分支内。
- **好感度不可兑换**：经核实，好感度仅影响 `MAX_HEALTH` / `ATTACK_DAMAGE` / 攻击距离 / 横扫范围，**没有任何物品兑换出口**（对比 §4.3 的经验可直接换附魔之瓶）。故保留它不构成经济漏洞。

---

## 6. 数据包 schema

### 6.1 设计原则

1. **双层结构**：可读 JSON（供桌宠建索引与人类阅读）+ base64 压缩 NBT（供游戏无损还原）。
2. **不写死上游字段名**：`FavorabilityManager` 类头挂着 `FIXME：这个好感度机制太落伍了，未来需要重新设计一个更合理的好感度系统`（`:23`），且大量 NBT key 是 `private`/`protected`，附属模组无法符号引用。故数据包使用**自己的抽象字段名**，由映射层转换。
3. **派生值标注为 cache**：如 `bond.level` 由 `favorability` 推导，阈值可能变化，不作为权威。
4. **保留 `carry` 字段占位**：方案 B 下恒为 `null`，但保留字段为未来配置化留出兼容空间。

### 6.2 正式定义

```jsonc
{
  "format": "tlm-pet-maid",
  "schemaVersion": 1,

  // ---- 身份（永不变）----
  "maidId": "<逻辑 UUID，桌宠主键>",
  "generation": 3,                      // 抽离次数，单调递增，用于防重放
  "nonce": "<一次性随机串>",
  "exportedAt": 1700009999,             // epoch millis
  "origin": {
    "worldName": "单人存档-幻想乡",
    "dimension": "minecraft:overworld",
    "pos": [128, 64, -256]              // 仅作纪念，不参与还原
  },

  // ---- 兼容性 ----
  "compat": {
    "mcVersion": "1.20.1",
    "tlmVersion": "1.5.3",
    "nbtDataVersion": 3465,
    "requiredMods": ["touhou_little_maid"]
  },

  // ---- 可读画像（供桌宠索引，非权威）----
  "profile": {
    "name": "灵梦",
    "modelId": "touhou_little_maid:reimu",
    "soundPackId": "...",
    "isYsmModel": false
  },
  "bond": {
    "favorability": 384,                // 权威值
    "level": 3                          // 派生 cache，仅供展示
  },
  "duty": {
    "task": "touhou_little_maid:fishing",
    "schedule": "DAY"
  },

  // ---- 记忆（知识库原料）----
  "memory": {
    "history": [
      { "role": "user",      "content": "..." },
      { "role": "assistant", "content": "..." }
    ],
    "summary": "上一段旅程的长期摘要",
    "lastTokenUsage": 0
  },
  "records": {
    "kill": { "total": 143, "slime": 20, "wither": 1, "enderDragon": 0 },
    "game": { "gomoku": { } }
  },

  // ---- 穿越履历（本功能的叙事核心）----
  "lineage": [
    {
      "worldName": "单人存档-幻想乡",
      "dimension": "minecraft:overworld",
      "enteredAt": 1699999999,
      "leftAt": 1700009999,
      "stats": { "kills": 143, "gamesWon": 12, "favorabilityGain": 37 }
    }
  ],

  // ---- 物品携带（方案 B 下恒为 null）----
  "carry": null,

  // ---- 权威还原载荷 ----
  "nbtBackup": "<base64(gzip(剥离后的完整实体 NBT))>",
  "checksum": "sha256:<...>"
}
```

### 6.3 说明

- `nbtBackup` 是**唯一权威的还原载荷**；其余可读字段仅供桌宠索引与展示。二者由同一次抽离产生，保证一致。
- `nbtBackup` 使用 **gzip + base64**，与 `MaidBackupsManager` 的压缩方式一致（`NbtIo.writeCompressed` `:309`）。体积估计：512 条对话历史约 200–400 KB 原始，gzip 后通常 < 100 KB。
- `checksum` 是**完整性检查**，不是安全措施（无法防止有意的篡改）。
- `lineage` 由桌宠在每次抽离时追加，是"冒险回忆"的结构化载体，也是未来展示"她游历过几个世界"的数据来源。

---

## 7. 通信协议

### 7.1 通道选择

| 通道 | 定位 | 说明 |
|---|---|---|
| **本地 HTTP（主）** | 实时交互 | 桌宠侧监听 `127.0.0.1`，模组作客户端。**严格绑定 loopback**，绝不监听 `0.0.0.0` |
| **文件交换（兜底）** | 桌宠未运行时 | 模组导出到 `config/touhou_little_maid/pet/outbox/`；桌宠写入 `.../inbox/` 供模组读取 |

不使用模组侧 HTTP 服务器：全仓库无任何 server/socket 代码，且需处理防火墙例外、端口冲突、线程模型（`com.sun.net.httpserver` 在 Forge 模块化启动下的可用性亦未验证）。

### 7.2 端点定义

默认端口建议 **4317**（避开 Player2 的 `4315` 与 GPT-SoVITS 的 `9880`）。

| 方法 | 路径 | 用途 |
|---|---|---|
| `GET` | `/v1/pet/handshake` | 探测桌宠是否在线。返回 `{ "app": "tlm-pet", "protocolVersion": 1, "maidCount": 3 }` |
| `POST` | `/v1/pet/maids` | 提交抽离数据包（body 为 §6.2 的 JSON） |
| `GET` | `/v1/pet/maids` | 拉取女仆列表（供游戏内迎回界面展示） |
| `GET` | `/v1/pet/maids/{maidId}` | 取回单个数据包（注入时用） |
| `GET` | `/v1/pet/maids/{maidId}/memory` | 取回知识库摘要（用于回填 `MaidHistorySummary`，见 §9） |

### 7.3 鉴权与配置

- 桌宠首次运行时生成随机 token，写入双方约定的**独立配置文件**（例如 `config/touhou_little_maid/pet.json`）。
- 请求携带 `X-TLM-Pet-Key: <token>`，模组侧校验。
- **不使用** `Site` 的 `secret_key` 机制（明文 json 且会下发客户端，见 §3.6）。

### 7.4 客户端实现要点

- 复用 `HttpClient` + `ConfigProxySelector` 的构造方式（`LLMSite.java:25-29`）；
- 超时：连接 1–2s（loopback 应极快），请求 30s；
- 探测失败时**静默降级到文件通道**，不向玩家刷错误提示；
- 探活范式可参考 `InfoGetManager.java:164-169` 的 `exceptionallyCompose` 降级写法。

### 7.5 传输规模注意事项

- **女仆快照不要走 Forge 网络包。** 网络包 id `0..58` 已被占满（下一个可用 59，`network/NetworkHandler.java:28-147`），且**全仓库无任何分包/分片机制**（音频都是整块 `writeByteArray`，`TTSAudioToClientMessage.java:25`）；单包大小上限未知。
- **正确分层**：服务端抽离 → 网络包（小体积元数据）→ 客户端 → 本地 HTTP（大体积数据包）。

---

## 8. 桌宠侧设计

### 8.1 存储布局

```
%APPDATA%/tlm-pet/                      # 或 macOS/Linux 对应路径
├── config.json                          # 端口、token
└── maids/
    └── <maidId>/
        ├── profile.json                 # 最新画像（name/modelId/bond/lineage）
        ├── snapshots/
        │   └── <generation>.tlmpet.json # 历次抽离的完整数据包（保留全部，不滚动删除）
        ├── timeline.jsonl               # append-only 冒险事件流
        ├── facts.md                     # persona：从对话抽取的稳定事实
        └── assets/                      # 该女仆的模型资源（geo/animation/texture）
```

**关键点：桌宠侧的快照不做滚动删除。** 这与游戏内 `MAID_BACKUP_MAX_COUNT` 默认仅为 3 形成对照 —— 游戏内备份是"回档保险"，桌宠才是"记忆仓库"。

### 8.2 知识库三层模型

| 层 | 载体 | 用途 |
|---|---|---|
| **Episodic（情景）** | `timeline.jsonl`，append-only，每条 `{t, worldId, kind, text}`，`kind ∈ {chat, kill, game, favor, travel}` | 「上个月我们在下界干了什么」 |
| **Semantic（语义）** | 对 timeline 分段摘要 + 向量化 | 「主人以前在哪找到过钻石」 |
| **Persona（人格）** | `facts.md`：称呼、偏好、承诺、关系 | 「她记得主人怕苦力怕」 |

### 8.3 增量同步

不要只在抽离时同步 —— 否则中途崩溃/删档即丢失记忆。建议：

- 模组侧每 N 分钟（或玩家主动）将**新增**的 `MaidHistoryChat` 增量推送到 `/v1/pet/maids/{maidId}/memory`；
- 桌宠以 `lastSyncedIndex` 去重；
- 抽离时做一次全量对齐。

---

## 9. 记忆回流：per-maid 与全局的正确分工

**这一节修正了早期设计中的一个过度乐观判断。**

### 9.1 事实：skill 机制是全局的，不是按女仆隔离的

核实结论（`ai/agent/skill/SkillLoader.java`）：

- `CONFIG_SKILLS` / `DATA_PACK_SKILLS` 是 **static 全局 map**（`:29-30`），无任何 per-maid 过滤；
- `getSkillSummary()` `:143-165` 把**全部** skill 拼成一个 `<available_skills>` XML；
- 该 XML 经 `PapiReplacer.java:35`（`map.put("available_skills", SkillLoader.getSkillSummary())`）注入设定模板；
- `UseSkillTool.java:49/72` 允许 LLM 按名字加载任意 skill。

**推论：如果把每个女仆的私密回忆写成 `config/touhou_little_maid/skills/<maidId>/skill.md`，所有女仆都会看到彼此的回忆 —— 会串味。早期"零代码接入知识库"的判断在这里需要修正。**

### 9.2 正确的三通道分工

| 通道 | 机制 | 适用内容 | 是否 per-maid |
|---|---|---|---|
| **A. 摘要回填** | 注入时把桌宠的长期记忆写入 `MaidHistorySummary`（`compressedSummary`） | **该女仆自己的跨世界回忆** | ✅ per-maid |
| **B. 主动检索** | 自定义 `ITool`（`recall_memory`），经 `registerAITool` 注册 | 该女仆的语义检索（向量查询桌宠知识库） | ✅ per-maid（`ITool` 的 `summary(maid)` / `parameters(root, maid)` / `trigger(maid, ...)` 都拿到 `EntityMaid`） |
| **C. 全局知识** | 写入 `config/touhou_little_maid/skills/<name>/skill.md` | **跨女仆共通的全局知识**（例如"主人游历过的世界总览"） | ❌ 全局共享 |

**通道 A 是最重要且成本最低的**：`MaidHistorySummary` 是 per-maid 字段，且 `MaidAIChatManager.buildMessage()`（`:182-188`）会把它作为 SYSTEM 消息插在对话历史之前：

```java
chatList.add(LLMMessage.systemChat(maid, setting));
this.historySummaryManager.appendSummaryMessage(chatList);   // ← 摘要在这里
history.getDeque().descendingIterator().forEachRemaining(chatList::add);
```

因此**把跨世界记忆回填进 `MaidHistorySummary`，"她记得上一个世界"就天然生效了**，无需写任何工具。

### 9.3 为什么必须由桌宠承担长期记忆

游戏内的记忆是**有损**的：

- 历史队列上限 512 条（`MaidAIChatData.java:43` `new CappedQueue<>(512)`）；
- token 超限时触发压缩，`HistorySummaryManager.java:73-79` 把旧消息压成一段摘要后，**从队列最旧端逐条 `pollLast()` 丢弃**，原文永久消失；
- 游戏内备份仅保留最近 3 份且按时间滚动删除。

**结论：女仆真的会忘记和玩家一起经历的事。桌宠是唯一不丢的记忆载体 —— 这不是锦上添花，而是补上了 TLM 自身设计中必然发生的信息损失。**

### 9.4 游戏内"聊天记录被压缩前"的抢救

由于压缩会丢弃原文，抽离/同步应尽量在压缩**之前**把新增原文取走。建议同步间隔显著小于压缩触发频率，并在抽离时立刻做一次全量拉取。

---

## 10. 阶段计划与验收

### Phase 0：环境与工程骨架（**已完成**）

实际做法与实测数据见 `docs/environment.md`。要点：

- **JDK 17**：开发机原本**没有任何 JDK**，且会话**无管理员权限**（`winget` 装 MSI 会弹 UAC）。
  改用清华 TUNA 镜像的 portable ZIP 解压到自定义目录（Temurin 17.0.20.1），`JAVA_HOME` 已写入用户级环境变量。
- **网络**：直连 Maven 系仓库实测仅 **1–64 KB/s**（Forge 官方 1 KB/s、`services.gradle.org` 完全不可达），
  实际不可能完成构建。已配置 Gradle 走本地代理（`127.0.0.1:<代理端口>`）后，Gradle 发行版达 **14 MB/s**。
- **工程骨架**：基于官方模板 `TLMAdditionExample` 建立 `mod/`，但做了三处必要改动：
  1. **补上 MixinGradle** —— 模板没有，而 D11（§12.3）与成就接管（§13.4）都依赖 mixin；
  2. **补上 `mods.toml` 对 `touhou_little_maid` 的 `AFTER` 依赖** —— 模板漏了；
  3. **显式设置资源过滤编码** `processResources { filteringCharset = 'UTF-8' }` —— 模板只设了
     `options.encoding`，而 JDK 17 在中文 Windows 上 `file.encoding` 仍是 GBK，
     不改会让 `mods.toml` 变量替换把中文写坏。

> ### ⚠️ 模板的陷阱：它 pin 的 TLM 版本严重过期
>
> | | CurseMaven file id | 实际版本 |
> |---|---|---|
> | 官方模板 `TLMAdditionExample@master` | `6613821` | **TLM 1.3.5**（2023 年） |
> | **本项目采用** | **`8061847`** | **TLM 1.5.3-forge+mc1.20.1** |
>
> 照抄模板的 id 会缺 `ToolRegister`、`GameContextRegister`、`UseSkillTool`、`SkillLoader`、
> `MaidBackupsManager`、`MaidTamedEvent`、`MaidAndItemTransformEvent` —— 也就是 §3.1 与 §9.2 所依赖的**全部新 API**。
> 查证方式：`https://api.cfwidget.com/355044` 的 `files` 数组（CurseForge 站点 API 被 Cloudflare 拦，cfwidget 可用）。

**验收**：`gradlew.bat build` 通过（46 秒），产物 `tlm_pet-1.20.1-forge-0.1.0.jar`；
`MANIFEST.MF` 含 `MixinConfigs: tlm_pet.mixins.json`；TLM 1.5.3 的 mixin 目标类
（`MaidEventTrigger` / `AltarCraftTrigger` / `EntityMaid` / `ItemFilm` / `MaidNumCapability`）
在依赖 jar 中**已逐一确认存在**。

**验收：已通过。** `gradlew.bat runClient` 启动成功，游戏自身日志 `mod/run/logs/latest.log` 给出：

```
[13:13:22.875] [modloading-worker-0/INFO] [tlm_pet/]: Touhou Little Maid: Bond 正在初始化
[13:13:27.867] [Render thread/INFO] [...]: Enabled Gametest Namespaces: [tlm_pet]
[13:13:30.804] [Render thread/INFO] [tlm_pet/]: 已挂载到 Touhou Little Maid 的扩展点
```

第三行即 `TlmCompat` 构造函数被调用的证据 —— 确认 `@LittleMaidExtension` 经 `AnnotatedInstanceUtil`
扫描 `ModFileScanData` **自动发现，无需任何手动注册**。同时确认：

- 运行时解析到的 TLM 版本为 `1.5.3-forge+mc1.20.1`（与设计文档依据的源码 checkout 一致）；
- `Found 5 mod requirements (5 mandatory, 0 optional)`、`0 missing` —— `mods.toml` 的 `AFTER` 依赖成立；
- `Registering mixin config: tlm_pet.mixins.json`、`Preparing tlm_pet.mixins.json (0)` —— mixin 通道就绪（当前 0 个）；
- TLM 自身 23 个 mixin 全部 prepared，游戏正常进入主菜单。

> 注：开发环境下会有 `Reference map 'tlm_pet.refmap.json' ... could not be read` 警告，
> 这是**预期的** —— refmap 只在 `build` 产物里生成；`runClient` 直接跑 classes 目录时读不到。
> 该警告不影响开发环境，但**发布前必须确认 refmap 已随 jar 产出**（否则 §19 的私有方法 mixin 会静默失效）。


### Phase 1：抽离 / 注入政策层（2–4 天）

产出：
1. `TlmNbtKeys` —— NBT 字面量集中表（因大量上游常量是 private，只能用字面量，需单点维护）；
2. `MaidCarrierPolicy` —— §4.2 的剥离 / 重映射 / 世界绑定清理；
3. `MaidExtractor` —— `maid.saveAsPassenger(tag)` → 剥离 → 打包；
4. `MaidInjector` —— 解包 → `EntityType.create(tag, level)` → 世界绑定清理 → 登记；
5. 命令 `/tlm-pet export|import|list` 用于验证；
6. 抽离前先调用 `MaidBackupsManager.save(server, maid)`，利用既有系统留一条回滚路径。

**验收清单（唯一关键路径，逐条必过）**：

- [ ] 同存档导出→导入，名字 / 模型 / 好感度 / 记忆 / 击杀记录完全一致
- [ ] 导入后女仆**不会乱跑**（`MaidSchedulePos` 与 `HomePos/HomeRadius` 已清）
- [ ] 导入后**女仆列表里能看到她**（`MaidWorldData.addInfo` 已登记）
- [ ] **10 个格子确实为空**（`MaidHideInventory` + `MaidTaskInventory` 已清）
- [ ] 主物品栏 / 饰品 / 护甲 / 手持 / 背包 / 经验均为空或 0
- [ ] 好感度与 `Attributes` 数值**一致**（满羁绊 = 80 血 / 6 攻，且满血）
- [ ] 女仆用**新玩家**的名字称呼（`OwnerName` 已重映射）
- [ ] 超过 `OWNER_MAX_MAID_NUM` 时正确拒绝（可用 `/tlm maid_num` 调小来测）
- [ ] 跨存档导入坐标不越界、不掉入虚空
- [ ] `generation` 递增、`nonce` 不复用
- [ ] 抽离后原存档中该女仆已消失（D6"收走"语义）

### Phase 1b：单女仆规则、放手仪式与成就接管（3–4 天，与 Phase 1 并行）

**单女仆规则（D7/D8）验收清单 —— 必须逐条尝试"突破"才算通过：**

- [ ] 已有女仆身份时，**驯服**野生女仆被拒绝且有友好提示
- [ ] 已有女仆身份时，**祭坛合成 `spawn_box` 并放置**被拒绝（漏洞 3）
- [ ] 已有女仆身份时，**祭坛 `altar/reborn_maid`**（胶卷重生）被拒绝（漏洞 4）
- [ ] 已有女仆身份时，**照片还原**（`ItemPhoto`）被拒绝（漏洞 5）
- [ ] 已有女仆身份时，**胶卷还原**（`ItemFilm`）被拒绝（漏洞 6）
- [ ] 已有女仆身份时，**智能方块还原**被拒绝
- [ ] **创造模式**下以上全部同样被拒绝（mixin，见 §12.3）
- [ ] **新存档**（计数器归零）中以上全部同样被拒绝（验证 §12.4 的 config 侧记录真的生效）
- [ ] 我们的**抽离→注入**流程本身不被自己的拦截器误伤（`PendingInject` 放行标记生效）
- [ ] `EntityJoinLevelEvent` 兜底**不会误删已有女仆**（重点验证 `loadedFromDisk()` 跳过逻辑，见 §12.5 陷阱 1）
- [ ] 多人服务器场景下，服务端侧记录独立生效

**放手仪式（D10，胶卷版）验收清单：**

- [ ] `CARRIED` 状态下**能取出胶卷** → `soulState = FILM_HELD`，且桌宠侧不再提供迎回
- [ ] 胶卷**只能取出一张**：`FILM_HELD` 期间再次尝试取出被拒绝
- [ ] `FILM_HELD` 下使用胶卷 → 她在世界中现身，`soulState = IN_WORLD`
- [ ] **仪式不需要她在场即可举行**（本次修订的核心点，务必显式验证）
- [ ] 祭坛缺少胶卷、或缺少祭品时，仪式无法进行
- [ ] 二次确认未通过时，仪式无法进行
- [ ] 仪式完成后：胶卷消耗、`soulState = NONE`、桌宠数据移入 `_released/`、授予 `tlm_pet:let_go`
- [ ] 仪式完成后，玩家**可以**重新通过祭坛/驯服获得女仆，且新 `maidId` **不继承**任何旧数据
- [ ] `_released/` 归档不出现在可迎回列表中
- [ ] `FILM_HELD` 下胶卷被误丢 → 仍能进入宣告失散流程（§12.7 注意点 2）
- [ ] **宣告失散**流程：桌宠离线时**绝不**触发清除（§12.7 注意点 1）
- [ ] `/tlm-pet reset <player>`（权限 2）可用

**成就奖励与召回物品（D12）验收清单：**

- [ ] 每个成就的 `rewards.loot` 指向 `tlm_pet:advancement/<name>`，材料按预期发放
- [ ] 「迎回之铃」合成可用（需 `first_carry` 奖励的「羁绊之核」）
- [ ] `CARRIED` 时使用 → 她出现在玩家身边
- [ ] `IN_WORLD` 且本世界 → 传送到玩家身边
- [ ] `IN_WORLD` 但别的存档 → 提示她在哪个世界
- [ ] `FILM_HELD` / `NONE` → 给出对应提示，不报错
- [ ] **绑定键是 `maidId` 而非实体 UUID**：跨世界迎回后铃**依然有效**（关键回归测试，见 §14.3）
- [ ] 冷却生效；升级后冷却下降
- [ ] **未获得铃时，GUI 迎回路径依然可用**（§14.5 原则 1，防止漏成就就玩不下去）

**成就接管（D9）验收清单：**

- [ ] §13.6 验证 1 通过：两个 mixin 分别拦截到 `event` 与 `recipe_id`
- [ ] §13.6 验证 2 通过：`ci.cancel()` 确实阻止了原版成就与 toast
- [ ] 5 个被接管成就**均不再被授予**（含 `base/spawn_maid`、`maid_base/reborn_maid` 这两个走 `AltarCraftTrigger` 的）
- [ ] 我们的 `tlm_pet:*` 替代成就按预期授予，toast / 聊天公告正常
- [ ] 原版**未**接管的成就依然正常工作（尤其 `challenge/` 其余 8 个与 `favorability/` 全部 8 个）
- [ ] 成就 GUI 观感已确认（5 个原版成就会留下"永远拿不到"的条目，见 R16）

### Phase 2：桌宠最小载体（3–5 天）

桌宠先不做 3D 渲染，只做「数据柜 + 列表界面 + 本地 HTTP 服务」（§7.2 的 5 个端点）。
模组侧：独立 HTTP 客户端 + 「送往桌宠 / 从桌宠迎回」按钮 + 文件通道兜底。
界面继承 `Screen` 并照抄 `AIChatSettingsHubScreen` 的三段式（侧栏 + 列表 + 滚动条）。
**不要**继承 `AbstractMaidContainerGui` —— 其 `stillValid` 含 `canReach(maid, 3)`（`inventory/container/AbstractMaidContainer.java:58`），与"跨维度/全存档"语义冲突。

**验收**：游戏内点一下，女仆从世界消失并出现在桌宠列表；再点一下，她带着同样的记忆出现在新存档。

### Phase 3：桌宠渲染（2–4 周）

复用 TLM 的模型资源体系。路径约定：

| 资源 | 路径 |
|---|---|
| 动画 | `assets/<domain>/animation/<name>.animation.json` |
| 基岩版 geo 模型 | `assets/<domain>/models/entity/<name>.json` |
| 贴图 | `assets/<domain>/textures/entity/<name>.png` |
| 模型包清单 | `assets/<domain>/maid_model.json` |

- 自定义模型包根目录：`gameDir/tlm_custom_pack`（支持目录或 zip），加载器 `client/resource/CustomPackLoader.java:91-112/135+`。
- `maid_model.json` 字段：`pack_name` / `version` / `author[]` / `description[]` / `date` / `icon` / `model_list[]`；每个模型条目含 `model_id` / `name` / `model` / `is_gecko` / `animation[]` / `extra_textures[]` / `render_entity_scale` / `description[]`。
- 默认动画 `assets/touhou_little_maid/animation/maid.animation.json`：`format_version` `"1.8.0"`，`animations` 下约 **97** 个动画，约 361 KB（前若干 key：`death, idle, beg, jump, run, walk, attacked, sit, swim, chair, gomoku, ...`）。
- 注意 `geckolib3/` 是仓库**自带 vendored 分支**（178 个 java 文件），非 `software.bernie.geckolib` 依赖；且**只接受 `FormatVersion.NEW`**（`client/resource/GeckoModelLoader.java:55`）。
- 另注意仓库还有一套 **`.js` 动画脚本体系**（`client/animation/inner/*.java`），与外部 `.animation.json` 不同，外部渲染器无需实现。

**服务端分发模型包的既有机制**：`ServerConfig.CLIENT_PACK_DOWNLOAD_URLS`（`ServerConfig.java:10/24`）+ `ClientPackDownloadManager.downloadClientPack()`（`client/download/ClientPackDownloadManager.java:35`）—— 服务端配置 URL 列表 → 客户端用 `HttpUtil.downloadTo` 下载 zip → `CustomPackLoader.readModelFromZipFile` 热加载。桌宠场景把 URL 换成 `http://127.0.0.1:<port>/...` 即可复用整条链路。

**验收**：桌宠里显示的女仆与游戏内是同一个模型与动画。

### Phase 4：知识库与回流（持续迭代）

1. 桌宠侧索引 / 向量化 / 摘要管线；
2. **通道 A（最高优先）**：注入时把跨世界摘要回填 `MaidHistorySummary`；
3. **通道 B**：自定义 `ITool` `recall_memory`，经 `registerAITool`（`api/ILittleMaid.java:126`）注册，参照 `ai/agent/tool/implement/SwitchSitTool.java`；必要时用 `registerAIMaidContext`（`:146`）注册上下文分类 —— 注意 `registerContext` 前必须先 `registerCategory`，否则抛异常（`GameContextRegister.java:69-77`）；
4. **通道 C**：把跨女仆共通的全局知识写入 `config/touhou_little_maid/skills/<name>/skill.md`（自动加载，但**全局共享**）。

---

## 11. 风险登记

| 编号 | 风险 | 影响 | 缓解 |
|---|---|---|---|
| **R1** | 女仆的 `modelId` 指向的资源包在新世界/别人电脑上不存在 → 回落到默认模型，女仆"变形" | 高（体验命门） | 抽离时把模型资源一并存入桌宠 `assets/`；注入时校验并提示；必要时复用 `CLIENT_PACK_DOWNLOAD_URLS` 分发 |
| **R2** | 上游字段名/语义变更（`FavorabilityManager` 有 `FIXME` 待重做；大量 key 为 private） | 高 | 字面量集中在 `TlmNbtKeys` 单点维护；数据包用自有抽象字段名；带 `schemaVersion`；每次升级 TLM 版本对照 `EntityMaid.addAdditionalSaveData` 做 diff |
| **R3** | 物品剥离遗漏（如上游新增物品栏字段） | 中 | 除白名单剥离外，增加**黑名单校验**：导入后扫描 NBT 中所有 `*Inventory*` / `Items` 键并告警 |
| **R4** | 抽离时女仆处于特殊状态（被骑乘、坐在娱乐方块、有乘客、睡觉 `isSleeping`）→ 状态异常 | 中 | 抽离前先 dismount / 唤醒；`Passengers` 已剥 |
| **R5** | 墓碑状态的女仆走不同路径（`dropEquipment` 中的 `EntityTombstone`，`EntityMaid.java:1546+`） | 中 | 明确不支持对墓碑抽离，或单独处理 |
| **R6** | 多人服务器中女仆归属与权限 | 中 | 仅 owner 本人可抽离自己的女仆；命令需 OP 或 owner 校验（参考 `SaveMaidAIDataMessage.java:42-50` 的 `isOwnedBy` 模式） |
| **R7** | 新包进旧版本 → 静默丢字段 | 中 | `schemaVersion` 不匹配即拒绝并提示 |
| **R8** | 隐私：`memory.history` 含玩家与女仆的**聊天原文**，`MaidAIChat.customSetting` 也可能含个人信息 | 中 | 数据仅存本机；导出/分享前提示；提供"脱敏导出"（剥离 history）选项 |
| **R9** | 玩家手动拷贝桌宠数据文件绕过"收走"语义 | 低（本质无法根治） | `generation` + `nonce` 做尽力而为的防重放；文档明确"这不是安全边界" |
| **R10** | 大体积快照走网络包导致失败 | 中 | §7.5：仅小体积元数据走包，大数据走本地 HTTP / 文件 |
| **R11** | `MaidExperience` 之外，未来出现新的"数值型可兑换资源" | 低 | 依赖 R2 的版本 diff 流程发现 |
| **R12** | **单女仆规则若只依赖 `MaidNumCapability` 必然被绕过** —— 共 4 条获取路径完全无数量检查（祭坛 `spawn_box` / 祭坛 `reborn_maid` / 照片 / 胶卷，见 §12.2） | **高**（D7/D8 被直接破坏） | 使用我们自己的 `MaidCarrierState` 作为权威记录（§12.4）+ `EntityJoinLevelEvent` 兜底拦截（§12.5） |
| **R13** | **创造模式绕过** —— `cap.canAdd() \|\| player.isCreative()` 写在 TLM 方法体内（`EntityMaid.java:679`、`ItemSmartSlab.java:153`），事件无法覆盖 | 中 | 需 mixin 改写条件（§12.3）；或明确接受创造模式不受限 |
| **R14** | 成就接管方案 A 需要 mixin **内部类** `MaidEventTrigger`（非 `api` 包） | 中 | 上游变动时需同步维护；备选方案 B（事件 + revoke）；落地前先做 §13.5 的验证 |
| **R15** | `challenge/tamed_maid_from_structure` 在 D7/D8 下**永久不可获得**（结构性互斥） | 中（玩家会认为"成就坏了"） | **已解决**：纳入最小接管清单（§13.3），由 `tlm_pet:found_her` 替代其语义 |
| **R16** | 被接管的 5 个原版成就**仍显示在成就 GUI 中**，但永远拿不到 | 中（观感问题） | 方案 A 无法隐藏它们。若要彻底隐藏需方案 C（资源覆盖，优先级未实测）。**建议先接受，观察玩家反馈**再决定是否追加成本 |
| **R17** | 桌宠数据被误删 / 玩家换机 → 女仆"魂"丢失，在绝对唯一规则下玩家**永久无女仆** | **高** | **已设计完整缓解**：① §12.7「宣告失散」兜底流程；② `/tlm-pet reset <player>` OP 逃生口；③ 抽离前自动调用 `MaidBackupsManager.save()` 留档；④ 桌宠侧快照不滚动删除（§8.1）；⑤ **§12.6.3 胶卷不需要她在场 → 放手始终可行**，这是本次修订的最大收益 |
| **R18** | 「放手仪式」的祭品定价失当 —— 太便宜则重置失去重量，太贵（如龙蛋）则玩家可能永远无法重新开始 | 中 | 需实测手感；建议从下界之星起步，并把祭品做成可配置项 |
| **R19** | mixin 注入 `EntityMaid.tameMaid` / `ItemSmartSlab.spawnNewMaid` 等 **private** 方法，混淆映射变动会导致注入失败 | 中 | 在 `refmap` 生成后核对描述符；注入失败必须**失败可见**（启动时日志告警），否则单女仆规则会**静默失效** —— 这是本方案最危险的一类失败 |
| **R20** | `FILM_HELD` 状态机出现漏洞 → 玩家拿到**两张她的胶卷** → 「两只她」的复制路径 | **高**（直接破坏 D7） | 状态迁移必须单向且由服务端权威判定：`CARRIED → FILM_HELD` 后桌宠侧立即停止提供迎回；`FILM_HELD` 期间拒绝二次取卷；把这条写进 §12.6 的验收清单并做对抗性测试 |
| **R21** | 「迎回之铃」误用**实体 UUID** 作为绑定键 → 跨世界注入后她换了 UUID → 铃**永久失联** | 中 | 绑定 `maidId`（逻辑 ID，D4）。这是 `ItemServantBell` 的做法**不可照抄**的一点（§14.3），需专门回归测试 |
| **R22** | 成就奖励材料进入交易/复制流通 → 迎回能力扩散到未完成成就的玩家；或材料过于稀有导致普通玩家拿不到 | 中 | ① 迎回之铃**不是硬门槛**（GUI 路径始终可用，§14.5 原则 1），扩散不构成功能性破坏；② 关键材料（羁绊之核）应设计为**不可交易**或与 `maidId` 绑定 |

---

## 12. 单女仆规则（D7 / D8）

### 12.1 现有计数机制的语义

`capability/MaidNumCapability.java` 是一个 per-player 整数，上限取自 `MaidConfig.OWNER_MAX_MAID_NUM`（`config/subconfig/MaidConfig.java:113`，**默认 `Integer.MAX_VALUE`**）。

经全仓库检索 `MAID_NUM_CAP`，其全部触点如下：

| 触点 | 位置 | 行为 |
|---|---|---|
| 递增 | `EntityMaid.java:685`（驯服） | 仅 `!player.isCreative()` 时 `cap.add()` |
| 递增 | `ItemSmartSlab.java:155`（智能方块） | 仅 `!player.isCreative()` 时 `cap.add()` |
| 递减 | `MaidNumCommand.java:52` | **唯一递减入口**，需 OP 手动执行 `/tlm maid_num remove` |
| 读取 | `EntityMaid.java:679`、`ItemSmartSlab.java:153` | `cap.canAdd()` |
| 挂载/克隆 | `CapabilityEvent.java:35`、`:62-63` | 跨维度/重生时随玩家克隆复制 |
| 下发客户端 | `SyncCapabilityMessage.java:46` | 仅用于界面展示 |

**两个重要结论：**

1. **计数器从不自动递减** —— 女仆死亡、被收走、被删除都不会减少它。因此 `OWNER_MAX_MAID_NUM = 1` 的语义天然就是「**一生只能拥有一只**」，与 D7 完全吻合。这是既有机制给我们的礼物。
2. **但它不跨存档** —— 它是玩家数据（`CapabilityEvent.java:35` 挂载在 `Player` 上），新存档归零。也就是说：**新世界里 `canAdd()` 会返回 `true`，玩家可以再驯服一只 —— 这正是 D8 要禁止的行为，而 TLM 自身无法阻止。**

因此：**`MaidNumCapability` 可以作为一条防线，但不能作为权威记录。**

### 12.2 女仆获取途径与强制点全表（含 4 个已验证漏洞）

经逐条核实，女仆进入世界的途径共有 6 类，其中 **4 条完全没有数量检查**：

| # | 获取途径 | 代码位置 | 是否检查 `canAdd()` |
|---|---|---|---|
| 1 | 驯服野生/结构女仆 | `EntityMaid.tameMaid:677-704` | ✅ `:679` `cap.canAdd() \|\| player.isCreative()` |
| 2 | 智能方块还原 | `ItemSmartSlab.spawnNewMaid:151-177` | ✅ `:153` 同上 |
| 3 | **祭坛 `altar/spawn_box` → 放置** | `ItemEntityPlaceholder.useOn:90-104` → `AltarRecipe.spawnOutputEntity:137-154` | ❌ **无检查** |
| 4 | **祭坛 `altar/reborn_maid`** | 同上，`extraData != null` 分支 `:141-150` | ❌ **无检查** |
| 5 | **照片还原** | `ItemPhoto.java:77` `worldIn.addFreshEntity(maid)` | ❌ **无检查** |
| 6 | **胶卷还原** | `ItemFilm.java:67` `worldIn.addFreshEntity(maid)` | ❌ **无检查** |
| — | 结构生成（野生，未驯服） | `mixin/StructureTemplateMixin.java:31` | 不适用（不构成"拥有"） |
| — | OP 命令 | `/tlm maid_num set\|add` `MaidNumCommand.java:46/49` | 管理用途，不设防 |

**漏洞 3 的证据（关键）**：`AltarRecipe.spawnOutputEntity` 的第二个分支把玩家参数**显式写成了 `null`**：

```java
entityType.spawn(world, null, (Player) null, pos, MobSpawnType.SPAWN_EGG, true, true);
//                              ^^^^^^^^^^^^^ 没有玩家上下文，结构上无法做数量检查
```

而 `altar/spawn_box` 是**获取女仆的主路径**（`base/spawn_maid` 成就即以此为条件）：
配方输出一个 `box` 实体，其 `Passengers` 携带 `touhou_little_maid:maid`，成本仅 6 种基础材料（钻石/青金石/金锭/红石/铁锭/煤炭）。虽然盒子里的女仆**未驯服**（仍需驯服才会触发第 1 条的检查），但：

- 它可以被**无限次合成与放置**，世界里可以出现任意多个未驯服女仆；
- 若配合任意一条无检查的还原路径（3–6），即可得到**第二只已驯服女仆**。

**漏洞 4 的证据（更严重）**：`altar/reborn_maid` 配方从 `film` 物品复制 `MaidInfo`（`reborn_maid.json:6-12`），经 `copyIngredientTag`（`AltarRecipe.java:148/156-170`）灌入新实体。而 `ItemFilm.removeMaidSomeData`（`ItemFilm.java:80-101`）**并未剥离 `Owner`**。结果是：

> 该路径产出一只**已经属于玩家**的女仆，**既不检查也不递增** `MaidNumCapability`。

即：玩家可以用「女仆 → 胶卷 → 祭坛重生」的循环，绕开数量上限获得已驯服女仆。

### 12.3 创造模式绕过（无法用事件解决，必须 mixin）

两处检查都写成 `cap.canAdd() || player.isCreative()`（`EntityMaid.java:679`、`ItemSmartSlab.java:153`），**且创造模式下不会执行 `cap.add()`**（`:683-686`、`:154-156`）。

后果：
- 创造模式玩家可无限驯服；
- 由于不递增，其计数器停留在旧值，退出创造后仍可再驯服一只。

这段条件写在 TLM 方法体内部，**附属模组无法用 Forge 事件覆盖**，只能靠 mixin 改写。

**已决定（D11）：接受引入 mixin。**

⚠️ **注意：不能只 mixin `MaidNumCapability.canAdd()`。** 因为 Java 从左到右求值，`cap.canAdd() || player.isCreative()` 中 `canAdd()` 虽然会先被调用，但一旦它返回 `false`，短路逻辑会继续看 `isCreative()` —— 创造模式照样绕过。**必须改动那两个调用点。**

需要 mixin 的两个目标（1.20.1）：

| 目标方法 | 位置 | 可见性 |
|---|---|---|
| `EntityMaid.tameMaid(ItemStack, Player)` | `EntityMaid.java:677` | `private` |
| `ItemSmartSlab.spawnNewMaid(UseOnContext, Player, Level, EntityMaid)` | `ItemSmartSlab.java:151` | `private` |

两种注入写法，推荐第一种：

1. **`@ModifyExpressionValue`** 作用在方法内的 `player.isCreative()` 调用上，强制返回 `false` —— 注入面最小，只改这一处判断。
2. **`@Redirect`** 到 `MaidNumCapability.canAdd()` 的调用，替换为 `MaidCarrierGuard.canAcquire(player)`，让我们自己的权威记录（§12.4）接管。

更彻底的做法是放弃修补原判断，直接把整个守卫替换为 `MaidCarrierGuard.canAcquire(player)` —— 创造模式与生存模式走同一条逻辑，规则只有一处。

⚠️ 两个目标都是 **private** 方法：mixin 可以注入 private 方法，但方法名/描述符依赖混淆映射。Parchment 映射下名字稳定，仍建议在 `refmap` 生成后核对一次。

⚠️ **不要**用 `EntityJoinLevelEvent` 兜底来替代这里的 mixin：驯服一只**已经存在于世界中**的野生女仆不会触发 `addFreshEntity`，因此**不经过该事件**，兜底拦不住创造模式驯服。

### 12.4 权威记录：跨存档身份

D7 要求「跨存档恒为同一个她」，而 `MaidNumCapability` 不跨存档，桌宠又是客户端侧概念。因此需要一层**我们自己的权威记录**：

```
MaidCarrierState {
    maidId            // 逻辑身份 ID（永不变，同 §6.2）
    soulState         // NONE | IN_WORLD | CARRIED | FILM_HELD（四态，定义见 §12.6.1）
    generation
    lastSeenWorldId   // 她最后所在的世界（用于提示"她还在哪个存档里"）
}
```

**双重存储（关键设计）：**

| 存储位置 | 覆盖场景 | 说明 |
|---|---|---|
| `config/touhou_little_maid/pet.json`（客户端 config 目录） | **单机跨存档** | 单人游戏的集成服务端与客户端同 JVM、同 `FMLPaths.CONFIGDIR`，**服务端可直接读**。这是主场景 |
| 服务端每玩家持久化数据（自定义 capability 或 `SavedData`，键为 player UUID） | **多人服务器** | 每个服务器各自维护"该玩家在此服务器上的唯一女仆"。语义上本就应当如此 |

判定函数：`hasMaidIdentity(player)` = 上述任一为真且 `soulState` 已知。

> **注意**：不要试图依赖 TLM 的 `OWNER_MAX_MAID_NUM` 配置来实现本规则。附属模组无法修改另一个模组的配置默认值，且该计数器不跨存档、且被 4 条路径绕过。它只能作为**辅助防线**。

### 12.5 拦截层设计

统一在**实体进入世界**这一层做兜底，是覆盖面最广的做法：

| 拦截点 | 可取消 | 用途 |
|---|---|---|
| `EntityJoinLevelEvent`（Forge 1.20.1，`@Cancelable`） | ✅ | **兜底**：实体是 `EntityMaid` 且已驯服、且其 owner 已有女仆身份且本次不是我们的注入 → 取消 |
| `PlayerInteractEvent.EntityInteract` | ✅ | 精准拦截驯服，可给出友好提示文案 |
| `PlayerInteractEvent.RightClickBlock` / `UseOnContext` | ✅ | 拦截 `ItemEntityPlaceholder` / `ItemPhoto` / `ItemFilm` / `ItemSmartSlab` 的放置还原 |

**关键细节：兜底拦截必须放行我们自己的注入。** 抽离/注入产生的女仆需要一个可识别的临时标记（例如注入时在 NBT 中写入一个一次性的 `PendingInject` 标记，落地后清除），否则我们的功能会被自己的拦截器拦掉。

**`EntityJoinLevelEvent` 的三个陷阱（已核实 Forge 1.20.1 源码）：**

`net/minecraftforge/event/entity/EntityJoinLevelEvent.java` 确认：

```java
@Cancelable
public class EntityJoinLevelEvent extends EntityEvent {
    private final boolean loadedFromDisk;
    public boolean loadedFromDisk() { return loadedFromDisk; }
}
```

1. ⚠️ **必须跳过 `loadedFromDisk() == true`**。该事件不仅在 `addFreshEntity` 时触发，也在**从磁盘加载区块**时触发（`PersistentEntitySectionManager#addNewEntity`）。若不跳过，玩家每次靠近已存在的女仆都会触发拦截 → **会把世界里已有的女仆删掉**。这是本方案最容易造成灾难性 bug 的一点。
2. ⚠️ 文档明确写着该事件**在客户端与服务端两侧都会触发**，处理器里必须判断 `!level.isClientSide`。
3. ⚠️ 文档警告：事件可能在被触发时 `LevelChunk` 尚未提升到 `ChunkStatus.FULL`，**不能在此做世界交互**（会死锁）。仅做标记判断，不要读写方块或调用会加载区块的 API。

因此兜底拦截的判定顺序应为：`服务端` → `!loadedFromDisk()` → `是 EntityMaid` → `已驯服且有 owner` → `owner 已有女仆身份` → `无 PendingInject 标记` → 取消。

**注意 `MaidTamedEvent` 不能用作拦截点**：它由 `MaidTamedEvent.java:12` 定义（`extends LivingEvent`），但触发位置在 `EntityMaid.java:701` —— 位于 `this.tame(player)` 与 `cap.add()` **之后**，太晚，只能用于登记/纠正，无法阻止。

---

### 12.6 唯一性的重置：放手仪式（D10，修订：以胶卷举行）

D7 是"绝对唯一"，但绝对不可逆会把玩家永久锁死。因此设置**唯一一个**重置入口，并把它做成有叙事重量的主动抉择。

> **修订要点**：仪式**不要求她在场**，而是以**她的胶卷**为核心。
> 这样玩家在任何世界、任何时候都能与她告别 —— 包括他已经不愿或无法回到那个旧存档的情况。

#### 12.6.1 「她的胶卷」与 soulState 状态机

把"她以胶卷形式存在于玩家手中"提升为一个**显式状态**，用它来锁死复制路径：

| `soulState` | 含义 | 允许的操作 |
|---|---|---|
| `NONE` | 未拥有女仆 | 可收服 / 祭坛合成新的女仆（全新 `maidId`） |
| `IN_WORLD` | 她以实体形式在某个世界 | 抽离（→ `CARRIED`）、仆从铃世界内召回 |
| `CARRIED` | 她在桌宠里 | 迎回（→ `IN_WORLD`）、**取出胶卷**（→ `FILM_HELD`） |
| `FILM_HELD` | 她以胶卷形式在玩家手中 | 使用胶卷召唤她（→ `IN_WORLD`）、**祭坛放手仪式**（→ `NONE`） |

**注意 `CARRIED` 下不能直接放手** —— 必须先取出胶卷（`→ FILM_HELD`），再用胶卷举行仪式。胶卷既是信物，也是放手仪式的钥匙。

**关键约束：同一时刻只能存在一张她的胶卷。** `CARRIED → FILM_HELD` 是单向的；进入 `FILM_HELD` 后桌宠侧不再提供"迎回"，从而杜绝「两张胶卷 = 两只她」的复制路径。

胶卷本身复用 `ItemFilm`（`item/ItemFilm.java:28`，内含 `MaidInfo`），所以：
- 右键使用胶卷 → 走既有 `filmToMaid`（`:52-78`）→ 她在世界中现身，`soulState → IN_WORLD`；
- 放到祭坛上 → 走我们的放手配方 → `soulState → NONE`。

⚠️ `ItemFilm.filmToMaid` 属于 §12.2 的漏洞 6（无数量检查）。我们的拦截层必须**放行"她自己的胶卷"**（owner 匹配 + 无其他女仆身份），只拦"会产生第二只"的情况。这与 §12.5 的 `PendingInject` 放行逻辑是同一类判断。

#### 12.6.2 仪式设计

| 要素 | 设计 | 理由 |
|---|---|---|
| 仪式材料 | **她的胶卷**（必需） | 不要求她在场，玩家的决定不受"能否回到旧存档"限制 |
| 地点 | TLM 既有祭坛多方块结构 | 复用 `build_altar` 玩法，零新增结构 |
| 载体 | 新增祭坛配方 `tlm_pet:altar/release_maid`，沿用 `touhou_little_maid:altar_crafting` 类型 | 与既有 `altar/reborn_maid.json` 完全同构 |
| 代价 | 一件象征"终结/不可复制"的稀有物品，默认 **下界之星**；备选 **龙蛋** | 让放手是重大决定而非随手操作 |
| 二次确认 | 专用 GUI，不可用单次右键完成 | 三重防线：胶卷 + 稀有祭品 + 显式确认 |
| 结果 | ① 胶卷消耗；② `soulState → NONE`；③ 桌宠把她的数据移入**只读归档** `maids/_released/<maidId>/`（`timeline` 保留为纪念，但**不再出现在可迎回列表**）；④ 授予 `tlm_pet:let_go`；⑤ **不可撤销** | 数据不完全销毁，保留"冒险回忆"的产品主题；但对游戏而言她已不可迎回 |

**仪式之后**：玩家恢复为 `NONE`，可重新通过祭坛 `spawn_box` / 驯服获得女仆 —— 但那是一个**全新的 `maidId`**，不继承任何旧记忆、好感度或记录。

#### 12.6.3 这个修订顺带解决了 R17

原设计（要求她在场）有一个致命缺口：如果她的数据留在旧存档里、而玩家不愿回去，就**既不能迎回也无法放手**，被绝对唯一规则永久锁死。

改为"取胶卷不需要她实体在场"之后：**放手始终可行**。这是本次修订最大的收益。

> **可选进阶能力（暂不纳入核心，仅登记）**：`MaidBackupsManager` 的备份是磁盘上的 gzip NBT 文件（`<存档>/data/maid_backups/<owner_uuid>/<maid_uuid>/*.dat`，见 §3.1）。理论上可以**不打开旧存档**，直接从磁盘读取她的最新备份并抽离 —— 这能解决"玩家不愿再回旧世界"的痛点。代价是需要玩家指定存档目录，且备份有最长 180 秒的延迟。建议作为后续版本的进阶功能。

### 12.7 失散兜底（数据丢失的逃生口）

R17 的残余场景：`soulState == CARRIED` 或 `FILM_HELD`（身份记录说"她还在"），但桌宠侧查无她的数据（桌宠数据被删、玩家换机、胶卷被误丢）。

此时玩家既不能迎回（无数据），也不能收服新的（绝对唯一）。处理流程：

```
检测到「身份存在，但桌宠无对应数据」
        ↓
提示「她已失散」→ 提供「宣告失散」操作
        ↓
清除 MaidCarrierState（soulState → NONE）
桌宠侧写入墓碑 maids/_lost/<maidId>/（仅 profile.json + 最后一份 timeline，不可迎回）
        ↓
玩家恢复为「未拥有」状态，可以重新开始
```

**三个必须注意的点：**

1. ⚠️ **不能把"桌宠没启动"误判为"数据丢失"。** 判定失散的前提是**桌宠明确在线并明确返回"无此 maidId"**。桌宠离线时只能提示"桌宠未运行，暂时无法迎回"，**绝不能触发任何清除**。
2. `FILM_HELD` 状态下如果胶卷被误丢（掉落物消失 / 玩家手动丢弃），也应能进入宣告失散流程 —— 因为桌宠侧已标记为 `FILM_HELD`，不会重复发卷。
3. 语义上「宣告失散」不是「放手」—— 她不是被放弃，而是**承认已经失去**。应有完全不同的文案，且**不需要祭品**（因为她已经不在了）。

**额外的管理员逃生口**：保留 `/tlm-pet reset <player>`（需权限 2），供服务器管理与调试。它是刻意保留的后门，需在文档与命令帮助里标明。

---

## 13. 成就接管（D9）

### 13.1 原版成就的机制

| 事实 | 证据 |
|---|---|
| 全部女仆成就**共用同一个 trigger ID**：`touhou_little_maid:maid/tamed_maid` | `advancements/maid/MaidEventTrigger.java:11` `ID`，`:24-26` `getId()` |
| 判定完全依靠 criteria 里的 **`event` 字符串** | `:18-21` `GsonHelper.getAsString(json, "event")`；`:40-42` `this.eventName.equals(eventNameIn)` |
| 触发入口 | `:28-30` `trigger(ServerPlayer, String eventName)`；调用方为 `InitTrigger.MAID_EVENT.trigger(player, TriggerType.X)` |
| `TriggerType` 共 **44** 个事件常量 | `advancements/maid/TriggerType.java:5-64` |
| 成就定义数量 | `src/generated/resources/.../advancements/` 下 **52** 个（`base` 10 / `challenge` 9 / `favorability` 8 / `maid_base` 25），另有 `src/main/resources/.../advancements/` 下 2 个手写文件 |
| 另有独立触发器 | `ALTAR_CRAFT`（`advancements/altar/AltarCraftTrigger.java`）、`GIVE_SMART_SLAB_CONFIG`、`GIVE_PATCHOULI_BOOK_CONFIG`（`init/InitTrigger.java:10-15`） |
| 成就树生成器 | `datagen/advancement/{BaseAdvancement,ChallengeAdvancement,FavorabilityAdvancement,MaidBaseAdvancement}.java` + `datagen/AdvancementGenerator.java` |

**「一个 trigger ID + `event` 字符串区分」这一设计对我们是重大利好**：接管点收敛到了一个方法上。

### 13.2 Forge 侧的事件能力（已核实）

读取 Forge `1.20.1` 分支源码 `net/minecraftforge/event/entity/player/AdvancementEvent.java`：

- `AdvancementEvent` 的字段类型是 `net.minecraft.advancements.Advancement`（**1.20.1 用 `Advancement`，不是 `AdvancementHolder`** —— 后者是 1.20.2+ 的变化，引用时不要写错）。
- `AdvancementEarnEvent` 与 `AdvancementProgressEvent` **均被明确标注为「not cancellable」**。
- `AdvancementProgressEvent` 提供 `getProgressType()`，取值 `GRANT` / `REVOKE`。
- javadoc 引用 `PlayerAdvancements#award(Advancement, String)` 与 `#revoke(Advancement, String)`，说明二者存在且可调用。

**结论：无法"取消"成就授予，只能"事后撤销"。**

### 13.3 受新规则影响的原版成就与接管范围（已定：最小接管）

**已决定（D9）：最小接管，共 5 个。** 处置方式如下表。

| 原版成就 | 所在分组 | 触发条件 | 在 D7/D8 下的问题 | 处置 |
|---|---|---|---|---|
| `spawn_maid` | `base/` | `altar_craft` 且 `recipe_id = touhou_little_maid:altar/spawn_box`（`base/spawn_maid.json:4-9`） | **直接与 D8 冲突** —— 新世界必须禁止再造女仆，此成就此后不可获得 | 🔴 **接管** |
| `tamed_maid` | `base/` | `event = tamed_maid` | 变为**一生一次**的必然事件，失去渐进性 | 🔴 **接管** |
| `tamed_maid_from_structure` | `challenge/` | `event = tamed_maid_from_structure` | **永久不可获得（结构性互斥）**：女仆主路径是祭坛 `spawn_box`，产出的不是结构女仆；而已驯服一只后无法再驯服第二只 | 🔴 **接管** |
| `reborn_maid`、`shrine_reborn_maid` | `maid_base/` | 重生女仆 | 语义应被「**跨世界重逢**」取代 —— 恰是本模组的核心叙事 | 🔴 **接管** |
| `photo_maid` | `maid_base/` | 给女仆拍照 | 仍可获得，语义变为「给她拍第一张照片」 | ⚪ 保留 |
| `build_altar`、`craft_gohei`、`craft_chair`、`pickup_power_point`、`kill_maid_fairy`、`change_maid_model/sound`、`change_chair_model` | `base/`（其余 8 个） | 建造 / 合成 / 交互类 | 不受影响 | ⚪ 保留 |
| `challenge/` 其余 **8** 个 | `challenge/` | 战斗 / 附魔 / 雷击 / 满血 | 不受影响；`maid_100_healthy` 因好感度完整保留（D3）而**依然可达成** | ⚪ 保留 |
| `favorability/` 全部 **8** 个 | `favorability/` | 好感度与娱乐 | 不受影响，且因跨世界保留而**更有意义** | ⚪ 保留 |
| `maid_base/` 其余 **23** 个 | `maid_base/` | 任务 / 背包 / 棋局等 | 不受影响 | ⚪ 保留 |

> ⚠️ **一处需要留意的分类细节**：`tamed_maid_from_structure` 虽然在 `challenge/` 目录下，但它属于"因玩法改变而失效"的一类，因此**在本方案的接管清单内**。也就是说 `challenge/` 下 9 个成就中有 1 个被接管、8 个保留。

**接管后的替代设计（5 个）**：把「获得女仆」这件事从"资源流程"改写为"关系流程" ——
`spawn_box` 的成就语义从"造出了一只女仆"变为"第一次把她带到身边"；`tamed_maid` 从"驯服"变为"她选择了你"（一生一次）；`tamed_maid_from_structure` 的语义本身不再成立，建议改造为"在世界结构中与她相遇"（可重新定义触发条件，不再要求"驯服结构女仆"）；两个 `reborn_maid` 合并为「跨世界重逢」。

**其中 `challenge/tamed_maid_from_structure` 是被新规则真正打破的成就**，必须在文档层面记录，并决定处置方式（由我们的成就替代 / 或放宽规则允许结构女仆例外）。

### 13.4 覆盖机制的精确接管点（已核实）

把 §13.3 的 5 个成就逐一对照 JSON 后，得到**精确的拦截清单** —— 只需 mixin **两个**触发器类：

| 被接管成就 | 触发器类 | 判别键 | 判别值 |
|---|---|---|---|
| `base/spawn_maid` | `AltarCraftTrigger` | `recipe_id` | `touhou_little_maid:altar/spawn_box` |
| `maid_base/reborn_maid` | `AltarCraftTrigger` | `recipe_id` | `touhou_little_maid:altar/reborn_maid` |
| `base/tamed_maid` | `MaidEventTrigger` | `event` | `tamed_maid` |
| `challenge/tamed_maid_from_structure` | `MaidEventTrigger` | `event` | `tamed_maid_from_structure` |
| `maid_base/shrine_reborn_maid` | `MaidEventTrigger` | `event` | `shrine_reborn_maid` |

> ⚠️ **注意一个容易搞错的点**：`reborn_maid` 走的**不是** `MAID_EVENT`，而是 `AltarCraftTrigger`（`maid_base/reborn_maid.json:4-9` 的 `trigger` 是 `touhou_little_maid:altar/altar_craft`）。只有 `shrine_reborn_maid` 才是 `MAID_EVENT`（`shrine_reborn_maid.json:4-9`）。若只 mixin `MaidEventTrigger` 会漏掉 `reborn_maid`。

两个 mixin 目标都是 **public 类 + public 方法**，注入面极小：

| 目标 | 签名 | 位置 |
|---|---|---|
| `MaidEventTrigger.trigger` | `public void trigger(ServerPlayer, String eventName)` | `advancements/maid/MaidEventTrigger.java:28` |
| `AltarCraftTrigger.trigger` | `public void trigger(ServerPlayer, ResourceLocation recipeId)` | `advancements/altar/AltarCraftTrigger.java:24` |

注入方式：`@Inject(method = "trigger", at = @At("HEAD"), cancellable = true)` —— 命中清单则 `ci.cancel()`（阻止原版成就），随后调用我们自己的触发器授予替代成就。

### 13.5 四方案对比与选型（已定）

| 方案 | 做法 | 优点 | 缺点 |
|---|---|---|---|
| **A. 触发器 mixin**（**已采用**，D11） | `@Inject` 到上表两个 `trigger` 方法，命中则取消并转发到我们自己的触发器 | 确定性最高；无 toast 闪烁；**只需 2 个 mixin 目标、5 条判别规则** | 依赖内部类（非 `api` 包），随上游变动需维护 |
| **B. 事件 + 撤销** | 监听 `AdvancementProgressEvent`(`GRANT`)，对 TLM 成就 `revoke` 后授予我们的 | 零 mixin | 事件**不可取消**，toast 可能已发出 → 闪烁；原成就永久锁定但仍在 GUI 中**可见**，UX 差 |
| **C. 资源覆盖** | 我们的 JSON 放在相同路径 `data/touhou_little_maid/advancements/...` | 零代码；GUI 与行为同时被替换 | **mod 之间的资源覆盖优先级未实测**，依赖加载顺序，脆弱 |
| **D. 只新增不覆盖** | 原版保持不变，我们另建成就树 | 零风险 | 不满足 D9 |

**已采用 A（D11：接受 mixin）。** B 作为上游变动导致 A 不可维护时的退路。

**选 A 的一个附带好处**：因为拦截发生在触发器层，原版成就**从未被授予**，所以不存在"授予后又撤销"的闪烁，也不会污染 `sends_telemetry_event`（该字段在这些 JSON 中均为 `true`）。

### 13.6 落地前必须先做的验证（半天内可完成）

因为 §13.4 已把拦截点收敛到 2 个类 / 5 条规则，验证成本很低：

1. **验证 mixin 可行性**：写两个最小 mixin 分别注入 `MaidEventTrigger.trigger` 与 `AltarCraftTrigger.trigger`，在游戏里
   - 驯服一只女仆 → 确认日志打印出 `event = tamed_maid`；
   - 在祭坛合成一次 `spawn_box` → 确认日志打印出 `recipe_id = touhou_little_maid:altar/spawn_box`。
   这是整个 D9 的技术前提。
2. **验证 `ci.cancel()` 真的阻止了成就**：在 mixin 里对 `tamed_maid` 无条件 `cancel()`，确认驯服后**没有**获得 `base/tamed_maid` 成就、也没有 toast。
3. **验证创造模式 mixin**（§12.3）：注入 `EntityMaid.tameMaid` 的 `player.isCreative()` 表达式，确认创造模式下第二只女仆被拒绝。
4. **可选验证 C 的优先级**：若想省掉 mixin，可在附属模组放一个同路径的 `base/tamed_maid.json`（改成不可达条件），实测哪个生效。

### 13.7 我们的成就树（建议结构）

```
tlm_pet:root
├── tlm_pet:first_carry        # 第一次把她收进桌宠
├── tlm_pet:first_reunion      # 在新世界迎回她（接管原 reborn_maid / shrine_reborn_maid 的语义）
├── tlm_pet:found_her          # 在世界结构中与她相遇（接管原 tamed_maid_from_structure 的语义）
├── tlm_pet:three_worlds       # 她陪你走过 3 个世界（读 lineage 长度）
├── tlm_pet:memory_keeper      # 桌宠侧知识库累积超过 N 条回忆
├── tlm_pet:bond_preserved     # 跨世界后好感度等级保持 3 级
├── tlm_pet:carry_no_item      # 在"不带任何物品"的前提下完成一次跨越（教学性质）
└── tlm_pet:let_go             # 放手仪式：主动与她告别（D10，叙事上最重的一个）
```

触发器由我们自己经 `CriteriaTriggers.register`（`init/InitTrigger.java:18-22` 的同款写法，Forge 的 `CriteriaTriggers.register` 是 public）注册。

**成就树的分组建议**：把被接管的 5 个原版成就**放在我们自己的命名空间下重新实现**，而不是试图修改 TLM 的文件。即：原版那 5 个成就永远不再被授予（mixin 拦截），玩家收集的是 `tlm_pet:*`。这样与上游的耦合只剩 2 个 mixin 点，而不是一份 JSON 副本。

---

## 14. 成就奖励与召回物品（D12）

### 14.1 既有的奖励范式（可直接复用）

成就发实质奖励在本仓库已是成熟模式：

| 事实 | 证据 |
|---|---|
| 掉落表常量 | `datagen/LootTableGenerator.java:36-37` 定义 `touhou_little_maid:advancement/power_point` 与 `advancement/cake` |
| 掉落表构建 | `:63` `LootTable.lootTable().withPool(...)` |
| 成就引用掉落表 | `base/spawn_maid.json:33-37` 的 `"rewards": {"loot": ["touhou_little_maid:advancement/cake"]}` |
| 已有奖励的成就 | `base/spawn_maid`、`base/craft_gohei`、`base/build_altar`、`challenge/kill_100`、`challenge/kill_slime_300`、`challenge/all_netherite_equipment`、`favorability/maid_sit_joy`（共 7 个） |

我们的附属模组照做即可，掉落表用 `tlm_pet:advancement/<name>` 命名空间。

### 14.2 先划清分工：世界内召回已有现成实现

`item/ItemServantBell.java` 已经完整实现了「**世界内**召回」：

| 能力 | 证据 |
|---|---|
| 把女仆绑定到物品上 | `ServantBellUuid` 标签 `:44`、`recordMaidInfo` `:55-61` |
| 长按 20 tick 充能后触发 | `MIN_USE_DURATION` `:49`、`:102` |
| 她在本维度 → 传送到玩家身边 + 发光 | `teleportMaid` `:135-145` |
| 她不在本维度 → 从 `MaidWorldData` 查出维度与坐标并提示 | `showMaidInfo` `:147-172` |
| 冷却与音效 | `:119`（`BELL_BLOCK`）、`:123`（20 tick） |
| 绑定设置界面 | `ServantBellSetScreen`，经 `interactLivingEntity` 打开 `:77-84` |

**结论：不要重复实现世界内召回。** 我们的新物品只负责**跨世界迎回**（桌宠 → 当前世界），世界内定位与传送交给既有仆从铃。职责切分清晰，新增代码量最小。

### 14.3 新物品：「迎回之铃」

设计上直接继承 `ItemServantBell` 的交互范式（长按充能 + 音效 + 冷却 + 绑定），但有一处**必须不同**：

> ⚠️ **绑定键必须是 `maidId`（逻辑身份 ID），不能照抄 `ItemServantBell` 的实体 UUID。**
> 因为 D4 规定每次注入都会重新分配实体 UUID，若绑 UUID，她在跨世界后会"失联"。
> `ItemServantBell` 绑 UUID 是**世界内**召回的合理做法（同一存档内 UUID 稳定），跨世界场景不适用。

使用时的分支：

| 当前 `soulState` | 行为 |
|---|---|
| `CARRIED`（她在桌宠里） | **迎回**：从桌宠取数据注入当前世界，落在玩家身边 |
| `IN_WORLD` 且在本世界 | 传送到玩家身边（与仆从铃重叠，但无害且更顺手） |
| `IN_WORLD` 但在别的存档 | 提示"她还在「<世界名>」里"，并给出该存档名（读 `lastSeenWorldId`） |
| `FILM_HELD` | 提示"她现在是胶卷，就在你背包里" |
| `NONE` | 提示"你还没有她" |

### 14.4 成就 → 材料 → 迎回之铃

| 成就 | 奖励物（掉落表） | 用途 |
|---|---|---|
| `tlm_pet:first_carry`（第一次抽离） | 「**羁绊之核**」×1 | 迎回之铃的**核心合成材料** —— 即"解锁"召回能力 |
| `tlm_pet:first_reunion`（第一次迎回） | 「**归乡灵玉**」×1 | 迎回之铃**升级**：显著降低冷却 |
| `tlm_pet:found_her`（在世界结构中与她相遇） | 「**寻踪符**」×2 | 辅助：让仆从铃/桌宠列表更快定位她 |
| `tlm_pet:three_worlds`（走过 3 个世界） | 「**三途之钥**」 | 进阶：允许在迎回时保留目标世界的坐标记忆 |
| `tlm_pet:memory_keeper`（记忆累积） | 「**回忆碎片**」 | 进阶：扩展桌宠侧记忆容量 |
| `tlm_pet:bond_preserved`（羁绊保持满级） | 「**不灭之绊**」 | 纪念性饰品 |
| `tlm_pet:let_go`（放手仪式） | 「**彼岸花纪念物**」（记录她的名字与存在过） | **纯纪念，不可用于召回** —— 她已离开 |

### 14.5 三条设计原则

1. **迎回之铃是"便利"，不是硬门槛。** 通过 GUI 迎回的路径**始终可用**；铃只是让重复跨世界的玩家更省事。这样即使玩家没拿到成就，功能也不会被锁死 —— 对一个"情感寄托"型功能，绝不能因为漏了成就就玩不下去。
2. **奖励形成正反馈阶梯。** 第一次抽离解锁召回能力，第一次迎回让它变快，走过更多世界再获得进阶能力 —— 成就因此有了实质意义，而不是纯粹的收集品。
3. **不要在放手（`let_go`）上给可再用资源。** 否则"放手"会变成一种刷材料的操作，破坏它的叙事重量。只给纪念物。

---

## 附录 A：NBT 字段字面量表

因大量上游常量是 `private`/`protected`，附属模组**无法符号引用**，必须在 `TlmNbtKeys` 中集中使用字面量。下表标注可见性以便判断哪些可以引用常量。

### A.1 `EntityMaid` — public（可直接引用常量）

| 常量 | 字面量 |
|---|---|
| `IS_YSM_MODEL_TAG` | `IsYsmModel` |
| `YSM_MODEL_ID_TAG` | `YsmModelId` |
| `YSM_MODEL_TEXTURE_TAG` | `YsmModelTexture` |
| `YSM_MODEL_NAME_TAG` | `YsmModelName` |
| `YSM_ROULETTE_ANIM_TAG` | `YsmRouletteAnim` |
| `YSM_ROAMING_VARS_TAG` | `YsmRoamingVars` |
| `YSM_ROAMING_UPDATE_FLAG_TAG` | `YsmRoamingUpdateFlag` |
| `MODEL_ID_TAG` | `ModelId` |
| `SOUND_PACK_ID_TAG` | `SoundPackId` |
| `MAID_BACKPACK_TYPE` | `MaidBackpackType` |
| `MAID_INVENTORY_TAG` | `MaidInventory` |
| `MAID_BAUBLE_INVENTORY_TAG` | `MaidBaubleInventory` |
| `MAID_HIDE_INVENTORY_TAG` | `MaidHideInventory` |
| `MAID_TASK_INVENTORY_TAG` | `MaidTaskInventory` |
| `EXPERIENCE_TAG` | `MaidExperience` |

### A.2 `EntityMaid` — private（必须用字面量）

| 常量 | 字面量 |
|---|---|
| `TASK_TAG` | `MaidTask` |
| `STRUCK_BY_LIGHTNING_TAG` | `StruckByLightning` |
| `INVULNERABLE_TAG` | `Invulnerable` |
| `HUNGER_TAG` | `MaidHunger` |
| `FAVORABILITY_TAG` | `MaidFavorability` |
| `SCHEDULE_MODE_TAG` | `MaidScheduleMode` |
| `BACKPACK_DATA_TAG` | `MaidBackpackData` |
| `STRUCTURE_SPAWN_TAG` | `StructureSpawn` |
| `RESTRICT_CENTER_TAG` | `MaidRestrictCenter`（`@Deprecated`，仅存档迁移用） |

### A.3 其它类

| 来源 | 字面量 |
|---|---|
| `SchedulePos.save()` `:82` | `MaidSchedulePos`（内含 `Work` / `Idle` / `Sleep` / `Dimension` / `Configured`） |
| `MaidAIChatData` `:30-32`（protected） | `MaidHistoryChat`、`MaidHistorySummary`、`MaidLastChatTokenUsage` |
| `MaidAIChatSerializable` `:61/75` | `MaidAIChat`（内含 `LLMSite`、`LLMModel`、`TTSSiteName`、`TTSModel`、`TTSLanguage`、`ChatLanguage`、`OwnerName`、`CustomSetting`） |
| `MaidConfigManager` `:12-24` | `MaidIsPickup`、`MaidIsHome`、`MaidIsRideable`、`MaidSubConfig`（内含 `BackpackShow`、`BackItemShow`、`ChatBubbleShow`、`SoundFreq`、`PickupType`、`OpenDoor`、`OpenFenceGate`、`ActiveClimbing`） |
| `FavorabilityManager` `:58` | `FavorabilityManagerCounter` |
| `MaidGameRecordManager` `:12-13` | `MaidGameSkillData`（内含 `Gomoku` 等） |
| `MaidKillRecordManager` `:15-19` | `KillRecord`（内含 `TotalCount`、`Slime`、`Wither`、`EnderDragon`） |
| `MaidBackupsManager` 索引 `:152-155` | `Name`、`Dimension`、`Pos`、`Timestamp` |
| Vanilla | `Owner`、`Sitting`、`Health`、`Attributes`、`Pos`、`Motion`、`ArmorItems`、`HandItems`、`ActiveEffects`、`Fire`、`Air`、`FallDistance`、`TicksFrozen`、`HasVisualFire`、`HurtTime`、`DeathTime`、`HurtByTimestamp`、`Leash`、`Passengers`、`HomePos`、`HomeRadius`、`CustomName`、`id` |

---

## 附录 B：可复用代码索引

| 需求 | 位置 |
|---|---|
| 全量快照 | `EntityMaid.saveAsPassenger` / `MaidBackupsManager.java:130` |
| 从 NBT 建女仆 | `EntityType.create(tag, level)`（用法见 `ItemCamera.java:72`） |
| 剥离物品的参考（**不完整，需补 2 项**） | `ItemFilm.removeMaidSomeData` `:80-101` |
| 女仆 ↔ 物品转换事件 | `api/event/MaidAndItemTransformEvent.java` |
| 照片物品回灌 | `ItemCamera.spawnMaidPhoto` `:68-87`、`ItemPhoto.java:28` |
| HTTP 客户端构造 | `LLMSite.java:25-29`、`ConfigProxySelector.java:11-50` |
| 请求降级 | `InfoGetManager.java:164-169` |
| 分享码（Deflate + Base64） | `GomokuCodec.java:24/67` |
| 跨平台共享目录 | `SystemAppDataUtil.java:18/64` |
| 世界索引登记 | `MaidWorldData.java:33/131` |
| 女仆数量上限 | `MaidNumCapability.java:11/15/42`、`MaidNumCapabilityProvider.java:15` |
| 清限制点 | `EntityMaid.clearRestriction()` `:2141` |
| 清日程坐标 | `EntityMaid.getSchedulePos().clear(maid)` `:2249`、`SchedulePos.java:139` |
| 列表式界面骨架 | `AIChatSettingsHubScreen.java:32/69/113/120/151/189` |
| 界面按钮注入（无需 mixin） | `MaidContainerGuiEvent`、`AbstractMaidContainerGui.getEventAddButtons()` `:713` |
| 复制到剪贴板 | `CopyEntityIdEvent.java:40` |
| 自定义 tooltip + 3D 预览 | `ClientMaidTooltip.java:32/73/128`、`InitClientTooltip.java:14` |
| 注册自定义 AI 工具 | `api/ILittleMaid.java:126`、`ai/agent/tool/ToolRegister.java:33` |
| 注册 AI 上下文 | `api/ILittleMaid.java:146`、`GameContextRegister.java:55/68` |
| 全局知识（skill.md） | `ai/agent/skill/SkillLoader.java:17-48/143` |
| gzip NBT 读写 | `NbtIo.writeCompressed` / `readCompressed`（`MaidBackupsManager.java:309/205`） |
| 网络包注册（下一个 id 59） | `network/NetworkHandler.java:28-147` |
| C2S 包模板 | `network/message/ai/SaveMaidAIDataMessage.java:13/22/27/34` |
| S2C 包模板 | `network/message/ai/SyncMaidAIDataMessage.java:23/31/39/48` |
