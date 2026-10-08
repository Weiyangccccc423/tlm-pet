# 生成成就、掉落表与语言文件。
# 用脚本而不是手写 18 个 JSON，是为了保证它们结构完全一致 ——
# 一个 JSON 里的逗号错位只会在运行时报一句难懂的解析错误。
#
# 注意：本文件必须保存为 UTF-8 **带 BOM**。Windows PowerShell 5.1 在没有 BOM 时
# 会按系统 ANSI（中文环境即 GBK）读取 .ps1，中文字面量会被拆坏并导致语法错误。
$ErrorActionPreference = 'Stop'

$res = Join-Path $PSScriptRoot '..\src\main\resources'
$advDir = Join-Path $res 'data\tlm_pet\advancements'
$lootDir = Join-Path $res 'data\tlm_pet\loot_tables\advancements'
$langDir = Join-Path $res 'assets\tlm_pet\lang'
foreach ($d in @($advDir, $lootDir, $langDir)) { New-Item -ItemType Directory -Force -Path $d | Out-Null }

$utf8NoBom = New-Object System.Text.UTF8Encoding($false)
function Write-Text($path, $text) { [System.IO.File]::WriteAllText($path, $text, $utf8NoBom) }

# 成就定义。root=$true 表示这是根节点（不写 parent，且需要 background）。
# loot 为 $null 表示没有物品奖励。
$advancements = @(
  @{ id='root';            parent='';               frame='task';      icon='tlm_pet:recall_bell';
     zh_t='最珍贵的行囊'; zh_d='把她带在身边，无论去哪个世界'; en_t='The Most Precious Luggage'; en_d='Bring her along, to any world';
     loot=$null; announce=$false; toast=$false; root=$true }

  @{ id='first_maid';      parent='tlm_pet:root';   frame='task';      icon='touhou_little_maid:maid_spawn_egg';
     zh_t='第一次把她带到身边'; zh_d='在祭坛上造出属于你的第一位女仆'; en_t='First Companion'; en_d='Create your first maid at the altar';
     loot=$null; announce=$true; toast=$true; root=$false }

  @{ id='she_chose_you';   parent='tlm_pet:first_maid'; frame='goal'; icon='touhou_little_maid:hakurei_gohei';
     zh_t='她选择了你'; zh_d='她不只是被造出来的 —— 她认定了你。一生只有一次'; en_t='She Chose You'; en_d='Not merely created - she decided on you. Only once in a lifetime';
     loot=$null; announce=$true; toast=$true; root=$false }

  @{ id='reunion';         parent='tlm_pet:root';   frame='goal';      icon='touhou_little_maid:servant_bell';
     zh_t='跨世界重逢'; zh_d='她穿过世界之间的缝隙，再次站在你面前'; en_t='Reunion Across Worlds'; en_d='She crossed the gap between worlds and stands before you again';
     loot=$null; announce=$true; toast=$true; root=$false }

  @{ id='found_her';       parent='tlm_pet:root';   frame='goal';      icon='touhou_little_maid:photo';
     zh_t='在世界结构中与她相遇'; zh_d='在掠夺者前哨站遇见并驯服一位女仆'; en_t='Met Her in the Wild'; en_d='Find and tame a maid in a pillager outpost';
     loot=@{ item='tlm_pet:tracking_charm'; count=2 }; announce=$true; toast=$true; root=$false }

  @{ id='first_carry';     parent='tlm_pet:root';   frame='goal';      icon='tlm_pet:bond_core';
     zh_t='第一次抽离'; zh_d='把她的数据取出来，收进随身的行囊。从这一刻起，你可以带她去任何世界'; en_t='First Extraction'; en_d='Extract her data into your luggage. From now on she can follow you anywhere';
     loot=@{ item='tlm_pet:bond_core'; count=1 }; announce=$true; toast=$true; root=$false }

  @{ id='first_reunion';   parent='tlm_pet:first_carry'; frame='goal'; icon='tlm_pet:homing_jade';
     zh_t='第一次迎回'; zh_d='在一个全新的世界里把她重新唤醒'; en_t='First Recall'; en_d='Awaken her again in a brand new world';
     loot=@{ item='tlm_pet:homing_jade'; count=1 }; announce=$true; toast=$true; root=$false }

  @{ id='three_worlds';    parent='tlm_pet:reunion'; frame='challenge'; icon='tlm_pet:sanzu_key';
     zh_t='走过三个世界'; zh_d='带着她走过三个不同的世界'; en_t='Three Worlds'; en_d='Travel through three different worlds together';
     loot=@{ item='tlm_pet:sanzu_key'; count=1 }; announce=$true; toast=$true; root=$false }

  @{ id='memory_keeper';   parent='tlm_pet:first_reunion'; frame='goal'; icon='tlm_pet:memory_shard';
     zh_t='记忆的守藏者'; zh_d='让她的记忆沉淀成一份完整的冒险回忆'; en_t='Keeper of Memories'; en_d='Let her memories settle into a complete record of your adventures';
     loot=@{ item='tlm_pet:memory_shard'; count=1 }; announce=$true; toast=$true; root=$false }

  @{ id='bond_preserved';  parent='tlm_pet:first_reunion'; frame='challenge'; icon='tlm_pet:undying_bond';
     zh_t='不灭之绊'; zh_d='跨过世界，羁绊依然满级'; en_t='Undying Bond'; en_d='Across worlds, the bond remains at its peak';
     loot=@{ item='tlm_pet:undying_bond'; count=1 }; announce=$true; toast=$true; root=$false }

  @{ id='let_go';          parent='tlm_pet:root';   frame='challenge'; icon='touhou_little_maid:film';
     zh_t='彼岸花开'; zh_d='在祭坛上与她正式告别。这不是失去，是你亲手选择的告别'; en_t='Higanbana Blooms'; en_d='Formally part with her at the altar. Not a loss - a farewell you chose yourself';
     loot=@{ item='tlm_pet:higanbana_keepsake'; count=1 }; announce=$true; toast=$true; root=$false }
)

# {{PARENTLINE}} 与 {{REWARDS}} 都自带结尾逗号，这样两种情形下 JSON 都合法。
$advTemplate = @'
{
{{PARENTLINE}}  "criteria": {
    "tlm_pet": {
      "trigger": "minecraft:impossible"
    }
  },
  "display": {
    "announce_to_chat": {{ANNOUNCE}},
{{BACKGROUND}}    "description": {
      "translate": "{{DESC}}"
    },
    "frame": "{{FRAME}}",
    "hidden": false,
    "icon": {
      "item": "{{ICON}}"
    },
    "show_toast": {{TOAST}},
    "title": {
      "translate": "{{TITLE}}"
    }
  },
  "requirements": [
    [
      "tlm_pet"
    ]
  ]{{REWARDS}}
  "sends_telemetry_event": false
}
'@

$lootTemplate = @'
{
  "type": "minecraft:advancement_reward",
  "pools": [
    {
      "rolls": 1,
      "entries": [
        {
          "type": "minecraft:item",
          "name": "{{ITEM}}"{{COUNT}}
        }
      ]
    }
  ]
}
'@

$langZh = [ordered]@{}
$langEn = [ordered]@{}

foreach ($a in $advancements) {
  $titleKey = "advancements.tlm_pet.$($a.id).title"
  $descKey  = "advancements.tlm_pet.$($a.id).description"
  $langZh[$titleKey] = $a.zh_t
  $langZh[$descKey]  = $a.zh_d
  $langEn[$titleKey] = $a.en_t
  $langEn[$descKey]  = $a.en_d

  # 根节点不能有 parent 字段 —— 空字符串会被当成一个不存在的成就 ID
  $parentLine = ''
  if (-not $a.root) {
    $parentLine = '  "parent": "' + $a.parent + '",' + "`n"
  }

  $background = ''
  if ($a.root) {
    # 复用 TLM 自带的成就背景贴图，避免为了一个背景再打包一张图
    $background = '    "background": "touhou_little_maid:textures/advancements/backgrounds/stone.png",' + "`n"
  }

  $rewards = ','
  if ($null -ne $a.loot) {
    $rewards = ",`n  `"rewards`": {`n    `"loot`": [`n      `"tlm_pet:advancements/$($a.id)`"`n    ]`n  },"

    $count = ''
    if ($a.loot.count -gt 1) {
      $count = ",`n          `"functions`": [`n            {`n              `"function`": `"minecraft:set_count`",`n              `"count`": $($a.loot.count)`n            }`n          ]"
    }
    Write-Text (Join-Path $lootDir "$($a.id).json") ($lootTemplate.
      Replace('{{ITEM}}', $a.loot.item).
      Replace('{{COUNT}}', $count))
  }

  Write-Text (Join-Path $advDir "$($a.id).json") ($advTemplate.
    Replace('{{PARENTLINE}}', $parentLine).
    Replace('{{ANNOUNCE}}', $a.announce.ToString().ToLower()).
    Replace('{{TOAST}}', $a.toast.ToString().ToLower()).
    Replace('{{BACKGROUND}}', $background).
    Replace('{{FRAME}}', $a.frame).
    Replace('{{ICON}}', $a.icon).
    Replace('{{TITLE}}', $titleKey).
    Replace('{{DESC}}', $descKey).
    Replace('{{REWARDS}}', $rewards))
}

# 物品名与创造标签页标题
$items = @(
  @{ id='bond_core';           zh='羁绊之核';     en='Bond Core' },
  @{ id='homing_jade';         zh='归乡灵玉';     en='Homing Jade' },
  @{ id='tracking_charm';      zh='寻踪符';       en='Tracking Charm' },
  @{ id='sanzu_key';           zh='三途之钥';     en='Sanzu Key' },
  @{ id='memory_shard';        zh='回忆碎片';     en='Memory Shard' },
  @{ id='undying_bond';        zh='不灭之绊';     en='Undying Bond' },
  @{ id='higanbana_keepsake';  zh='彼岸花纪念物'; en='Higanbana Keepsake' },
  @{ id='recall_bell';         zh='迎回之铃';     en='Recall Bell' }
)
foreach ($i in $items) {
  $langZh["item.tlm_pet.$($i.id)"] = $i.zh
  $langEn["item.tlm_pet.$($i.id)"] = $i.en
}
$langZh['itemGroup.tlm_pet.main'] = '女仆羁绊'
$langEn['itemGroup.tlm_pet.main'] = 'Maid Bond'

function ConvertTo-JsonOrdered($ordered) {
  $sb = New-Object System.Text.StringBuilder
  [void]$sb.AppendLine('{')
  $keys = @($ordered.Keys)
  for ($k = 0; $k -lt $keys.Count; $k++) {
    $comma = if ($k -lt $keys.Count - 1) { ',' } else { '' }
    [void]$sb.AppendLine(('  "{0}": "{1}"{2}' -f $keys[$k], $ordered[$keys[$k]], $comma))
  }
  [void]$sb.Append('}')
  return $sb.ToString()
}

Write-Text (Join-Path $langDir 'zh_cn.json') (ConvertTo-JsonOrdered $langZh)
Write-Text (Join-Path $langDir 'en_us.json') (ConvertTo-JsonOrdered $langEn)

Write-Output ("成就 " + $advancements.Count + " 个，掉落表 " + (Get-ChildItem $lootDir).Count + " 个，语言文件 2 个")
