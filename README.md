# tlm-pet

以桌宠酒狐为本体，让她读取并记住《东方小女仆》(Touhou Little Maid) 中，
各个世界存档里的酒狐与玩家一起冒险的数据。

设定上，游戏中的所有酒狐都是桌宠酒狐的数据投影分身。每个世界可以继续自己的冒险，
桌宠把这些经历汇集成共同的记忆。

当前方向见 [`docs/wine-fox-memories-design.md`](docs/wine-fox-memories-design.md)。
`pet/` 已有可运行的普通模式和游戏模式桌宠，游戏模式支持本机键鼠实时同步；桌宠支持游戏存档只读导入。
`mod/` 仍是此前的跨世界迁移原型。

## 仓库结构

| 目录 | 内容 |
|---|---|
| `docs/` | 设计文档与环境说明 |
| `mod/` | 已实现的跨世界迁移原型；后续可复用数据读取代码（Java 17 / Forge 1.20.1） |
| `pet/` | 普通/游戏模式桌宠（TypeScript / Electron / Three.js），键鼠同步、聊天与手动记忆 |
| `protocol/` | 数据格式与通信协议的预留目录，目前为空 |

## 运行桌宠

需要 Node.js 22.12+。

```powershell
cd pet
npm install
npm run build
npm run desktop
```

支持透明置顶、拖动、打招呼、坐下休息、聊天与手动记忆。
顶部「游戏」切换到小桌、键盘和鼠标；全局输入驱动按键与手部动画。
默认离线预设回应，可在设置中接入 AI 服务。隐藏后双击托盘图标恢复。
详细配置与验证方法见 [pet/README.md](pet/README.md)。
酒狐资源使用说明见 [素材鸣谢](pet/public/assets/CREDITS.md) 和 [作者二创声明记录](docs/wine-fox-creation-permissions.md)。

## 运行现有模组原型

下面的命令运行旧版迁移原型，其中仍包含单女仆限制与放手仪式。
这些机制已不属于新的产品方向，代码尚待调整。

前置：JDK 17。环境配置与网络注意事项见 [`docs/environment.md`](docs/environment.md)。

```bat
cd mod
gradlew.bat build
gradlew.bat runClient
```

## 设计文档

- [`docs/wine-fox-memories-design.md`](docs/wine-fox-memories-design.md) — 当前方向：桌宠本体与各世界投影的共同记忆
- [`docs/tlm-pet-carrier-design.md`](docs/tlm-pet-carrier-design.md) — 旧版迁移设计，保留作数据字段与上游代码参考
- [`docs/phase1-acceptance.md`](docs/phase1-acceptance.md) — 现有迁移原型的验收记录
- [`docs/environment.md`](docs/environment.md) — 开发环境、代理与镜像配置

## 与上游的关系

`TouhouLittleMaid` 本体的源码在另一个目录，仅作为**只读参考**使用 —— 设计文档里的每条结论都标注了
具体的 `文件:行号`，改动上游会让这些引用失效。本仓库不修改它，而是通过 CurseMaven 依赖其发布产物。

## 当前目标

先做一个能读取多个存档、展示酒狐冒险记录的桌宠。
各世界里的酒狐可以同时存在，经历按来源保存，统一成为桌宠酒狐的回忆。
读取经历不需要把游戏中的酒狐移除，也不需要先执行导出、召回或放手仪式。
