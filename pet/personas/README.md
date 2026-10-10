# 人格文件

`winefox.md` 是桌宠运行时读取的人格文件，独立于桌面规则、记忆和聊天服务代码。
每次新的 AI 对话会重新读取，保存修改后下一条对话生效，不需要重新构建或重启。
同一轮对话的多次工具调用使用这一轮开始时的版本。

## 来源与适配

- 原作者字段：`tartaric_acid`（酒石酸菌 / TartaricAcid）。
- 本地原文：[winefox.yml](../../mod/run/tlm_custom_pack/touhou_little_maid-1.0.0/assets/geckolib/settings/winefox.yml)。
- 上游：[TouhouLittleMaid 的内置 winefox.yml](https://github.com/TartaricAcid/TouhouLittleMaid/blob/1.20/src/main/resources/assets/touhou_little_maid/tlm_custom_pack/touhou_little_maid-1.0.0/assets/geckolib/settings/winefox.yml)。
- 适配日期：2026-10-10。

保留原设定中的外观、善良可爱、细心照顾、呆萌、番茄布丁、喜欢酒和拒绝不当接触的特点。
本项目将其整理为中文，增加自然对话的表达建议；原文「不是程序」的回答被改为角色扮演与实际软件能力分别如实表达。
这些属于桌宠适配，不是作者官方新增设定。

模型包角色衍生内容按 [CC BY-NC-SA 4.0](https://creativecommons.org/licenses/by-nc-sa/4.0/deed.zh-hans) 分享并保留署名。
来源与作者声明见 [资源鸣谢](../public/assets/CREDITS.md) 和 [查阅记录](../../docs/wine-fox-creation-permissions.md)。

## 文件边界

- `personas/winefox.md`：身份、外观、性格、爱好、说话习惯。
- `prompts/desktop.md`：本体与投影的设定、普通模式的行为和内容规则。
- `src/agent/service.ts`：提供实际连接状态、手动记忆、历史与可用工具。

来源说明在本 README 中，不作为人设发送给 AI。运行时直接读取两个 Markdown 正文，没有变量替换或 YAML 解析。
离线模式的回应仍然是代码中的预设规则，人格文件只影响已配置的 AI 对话。
文件缺失、留空或超过 24,000 字符时会提示错误，避免无声地用错误人设继续聊天。
