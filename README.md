# tlm-pet

把《东方小女仆》(Touhou Little Maid) 的女仆数据抽离出来，经由桌宠载体带到新的世界重新注入，
让女仆跨越存档继续陪伴玩家。

## 仓库结构

| 目录 | 内容 |
|---|---|
| `docs/` | 设计文档与环境说明 |
| `mod/` | Minecraft Forge 1.20.1 附属模组（Java 17 / Gradle） |
| `pet/` | 桌宠程序（技术栈待定） |
| `protocol/` | 数据包 schema 与通信协议定义，供 mod 与桌宠共用 |

## 快速开始

前置：JDK 17。环境配置与网络注意事项见 [`docs/environment.md`](docs/environment.md)。

```bat
cd mod
gradlew.bat build
gradlew.bat runClient
```

## 设计文档

- [`docs/tlm-pet-carrier-design.md`](docs/tlm-pet-carrier-design.md) — 完整设计（14 节 + 2 附录）
- [`docs/environment.md`](docs/environment.md) — 开发环境、代理与镜像配置

## 与上游的关系

`TouhouLittleMaid` 本体的源码在另一个目录，仅作为**只读参考**使用 —— 设计文档里的每条结论都标注了
具体的 `文件:行号`，改动上游会让这些引用失效。本仓库不修改它，而是通过 CurseMaven 依赖其发布产物。

## 关键设计决策速查

| 编号 | 决策 |
|---|---|
| D3 | 好感度方案 B：完整保留 `MaidFavorability` **与** `Attributes` |
| D4 | 逻辑身份 ID (`maidId`) 与实体 UUID 分离 |
| D7 | 绝对唯一：每个玩家只有一个女仆，不存在自动重置 |
| D8 | 新世界不再允许收服新女仆 |
| D9 | 最小接管 5 个原版成就 |
| D10 | 唯一性只能通过「放手仪式」重置，以她的胶卷举行 |
| D11 | 接受引入 mixin |
| D12 | 成就附加实质奖励，用于打造跨世界召回物品 |

详见设计文档第 2 节。
