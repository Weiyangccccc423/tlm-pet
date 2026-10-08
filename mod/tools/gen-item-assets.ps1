# 生成物品模型 JSON 与 16x16 贴图。
#
# 为什么用脚本生成像素图而不是画好再提交：8 张图若各自手绘，风格必然漂移；
# 用同一套调色板 + 同一套轮廓写法（x 表示深色描边）能保证它们看起来是一套东西。
#
# ⚠️ 调色板的键**不能用大小写区分**：PowerShell 的哈希表是大小写不敏感的，
#    同时写 R 与 r 会直接报「哈希文本中不允许包含重复的键」。所以次级色阶用数字
#    （1/2/3/4），而不是同字母的小写。
#
# 注意：本文件必须保存为 UTF-8 **带 BOM**。Windows PowerShell 5.1 在没有 BOM 时
# 会按系统 ANSI（中文环境即 GBK）读取 .ps1，中文字面量会被拆坏并导致语法错误。
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Drawing

$res = Join-Path $PSScriptRoot '..\src\main\resources\assets\tlm_pet'
$modelDir = Join-Path $res 'models\item'
$texDir = Join-Path $res 'textures\item'
foreach ($d in @($modelDir, $texDir)) { New-Item -ItemType Directory -Force -Path $d | Out-Null }

$utf8NoBom = New-Object System.Text.UTF8Encoding($false)
function Write-Text($path, $text) { [System.IO.File]::WriteAllText($path, $text, $utf8NoBom) }

# 每个 sprite 是 16 行 x 16 列，'.' 表示透明。x 一律是深色描边。
# 脚本会逐行校验列数，写错一位就当场报错，不会安静地生成一张歪图。
$sprites = @{}

$sprites['bond_core'] = @{
  palette = @{ x = '7A0B1E'; R = 'FF3B5C'; '1' = 'FF9AAE' }
  pixels = @(
    '................',
    '................',
    '...xxx....xxx...',
    '..xRRRx..xRRRx..',
    '.xRRRRRxxRRRRRx.',
    '.xRRRRRRRRRRRRx.',
    '.xRRRRRRRRRRRRx.',
    '..xRRRRRRRRRRx..',
    '..xRRRRRRRRRRx..',
    '...xRR1RR1RRx...',
    '....xR1RR1Rx....',
    '.....xR1R1Rx....',
    '......xRRRx.....',
    '.......xxx......',
    '................',
    '................')
}

$sprites['homing_jade'] = @{
  palette = @{ x = '0C3B2E'; G = '1E8A63'; '2' = '5FD3A0'; W = 'D8FFF0' }
  pixels = @(
    '................',
    '.....xxxxxx.....',
    '...xxGGGGGGxx...',
    '..xGG222222GGx..',
    '.xGG22WWWW22GGx.',
    '.xG22WWWWWW22Gx.',
    'xG22WWWWWWWW22Gx',
    'xG2WWWWWWWWWW2Gx',
    'xG2WWWWWWWWWW2Gx',
    'xG22WWWWWWWW22Gx',
    '.xG22WWWWWW22Gx.',
    '.xGG22WWWW22GGx.',
    '..xGG222222GGx..',
    '...xxGGGGGGxx...',
    '.....xxxxxx.....',
    '................')
}

$sprites['tracking_charm'] = @{
  palette = @{ x = '6B4A12'; Y = 'F5DFA3'; R = 'C22B2B' }
  pixels = @(
    '................',
    '....xxxxxxxx....',
    '....xYYYYYYx....',
    '....xYYYYYYx....',
    '....xYYRRYYx....',
    '....xYYRRYYx....',
    '....xYYYYYYx....',
    '....xYRRRRYx....',
    '....xYRRRRYx....',
    '....xYYYYYYx....',
    '....xYYRRYYx....',
    '....xYYRRYYx....',
    '....xYYYYYYx....',
    '....xxxxxxxx....',
    '................',
    '................')
}

$sprites['sanzu_key'] = @{
  palette = @{ x = '6B4A12'; K = 'F2C14E'; '3' = 'FFF0B8' }
  pixels = @(
    '................',
    '.....xxxx.......',
    '....xKKKKx......',
    '....xK33Kx......',
    '....xKKKKx......',
    '.....xKKx.......',
    '.....xKKx.......',
    '.....xKKx.......',
    '.....xKKx.......',
    '.....xKKxx......',
    '.....xKKKx......',
    '.....xKKx.......',
    '.....xKKxx......',
    '.....xKKKx......',
    '.....xxxx.......',
    '................')
}

$sprites['memory_shard'] = @{
  palette = @{ x = '2E0B4A'; P = 'C79BF0'; '4' = '7A3FBF' }
  pixels = @(
    '................',
    '........xx......',
    '.......xPPx.....',
    '......xPPPPx....',
    '.....xPPPPPPx...',
    '....xPP4PPP4x...',
    '...xPP444PP4x...',
    '..xPP44444P4x...',
    '..xP44444444x...',
    '..xP4444444x....',
    '..xP444444x.....',
    '..xP44444x......',
    '...xP444x.......',
    '....xP4x........',
    '.....xx.........',
    '................')
}

$sprites['undying_bond'] = @{
  palette = @{ x = '6B0B14'; R = 'E23B4A'; '1' = 'FF8A96' }
  pixels = @(
    '................',
    '....xx....xx....',
    '...xRRx..xRRx...',
    '...xRRxxxxRRx...',
    '....xRRRRRRx....',
    '...xRRxxxxRRx...',
    '..xR1xxxxxx1Rx..',
    '..xRx......xRx..',
    '..xRx......xRx..',
    '..xR1xxxxxx1Rx..',
    '...xRRxxxxRRx...',
    '....xRRRRRRx....',
    '...xRRxxxxRRx...',
    '...xRRx..xRRx...',
    '....xx....xx....',
    '................')
}

$sprites['higanbana_keepsake'] = @{
  palette = @{ x = '5C0A12'; R = 'E8333F'; '1' = 'FF9BA3'; S = '2F7A34' }
  pixels = @(
    '................',
    '......xRRx......',
    '....xxRRRRxx....',
    '...xRRxRRxRRx...',
    '..xRRx1RR1xRRx..',
    '..xRx..RR..xRx..',
    '...x...SS...x...',
    '.......SS.......',
    '.......SS.......',
    '......xSSx......',
    '......xSSx......',
    '.......SS.......',
    '.......SS.......',
    '......xSSx......',
    '.......xx.......',
    '................')
}

$sprites['recall_bell'] = @{
  palette = @{ x = '6B4A12'; K = 'F2C14E'; '3' = 'FFF0B8'; D = '8A5A1E' }
  pixels = @(
    '................',
    '.......xx.......',
    '......x33x......',
    '.....xKKKKx.....',
    '....xK3333Kx....',
    '...xKK3333KKx...',
    '...xKKKKKKKKx...',
    '..xKKKKKKKKKKx..',
    '..xKKKKKKKKKKx..',
    '..xKKKKKKKKKKx..',
    '...xKKKKKKKKx...',
    '....xKKKKKKx....',
    '.....xKKKKx.....',
    '......xDDx......',
    '.......xx.......',
    '................')
}

$modelTemplate = @'
{
  "parent": "minecraft:item/generated",
  "textures": {
    "layer0": "tlm_pet:item/{{NAME}}"
  }
}
'@

$items = @('bond_core', 'homing_jade', 'tracking_charm', 'sanzu_key',
           'memory_shard', 'undying_bond', 'higanbana_keepsake', 'recall_bell')

foreach ($name in $items) {
  $sprite = $sprites[$name]
  $pixels = $sprite.pixels
  if ($pixels.Count -ne 16) { throw "$name 的行数不是 16：$($pixels.Count)" }

  $bmp = New-Object System.Drawing.Bitmap 16, 16
  for ($y = 0; $y -lt 16; $y++) {
    $row = $pixels[$y]
    if ($row.Length -ne 16) { throw "$name 第 $y 行不是 16 列：$($row.Length)" }
    for ($x = 0; $x -lt 16; $x++) {
      $ch = $row[$x]
      if ($ch -eq '.') {
        $bmp.SetPixel($x, $y, [System.Drawing.Color]::FromArgb(0, 0, 0, 0))
        continue
      }
      $hex = $sprite.palette["$ch"]
      if (-not $hex) { throw "$name 第 $y 行第 $x 列用到未定义的调色板字符「$ch」" }
      $r = [Convert]::ToInt32($hex.Substring(0, 2), 16)
      $g = [Convert]::ToInt32($hex.Substring(2, 2), 16)
      $b = [Convert]::ToInt32($hex.Substring(4, 2), 16)
      $bmp.SetPixel($x, $y, [System.Drawing.Color]::FromArgb(255, $r, $g, $b))
    }
  }
  $bmp.Save((Join-Path $texDir "$name.png"), [System.Drawing.Imaging.ImageFormat]::Png)
  $bmp.Dispose()

  Write-Text (Join-Path $modelDir "$name.json") ($modelTemplate.Replace('{{NAME}}', $name))
}

Write-Output ("物品 " + $items.Count + " 个：模型与贴图已生成")
Write-Output ("贴图目录：" + (Get-ChildItem $texDir).Count + " 个 PNG，模型目录：" + (Get-ChildItem $modelDir).Count + " 个 JSON")
