import { cp, mkdir, readFile, rename, rm, stat, writeFile } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import path from 'node:path';
import { spawn } from 'node:child_process';
import { createHash } from 'node:crypto';
import electron from 'electron';

if (process.platform !== 'win32' || process.arch !== 'x64') throw new Error('当前打包脚本需要 Windows x64。');
const root = fileURLToPath(new URL('..', import.meta.url));
const release = path.join(root, 'release');
const output = path.join(release, 'WineFoxPet');
const metadata = JSON.parse(await readFile(path.join(root, 'package.json'), 'utf8'));
const archive = path.join(release, `WineFoxPet-${metadata.version}-win-x64.zip`);
for (const target of ['dist/index.html', 'dist-electron/electron/main.js', 'dist-electron/electron/preload.cjs', 'personas/winefox.md', 'prompts/desktop.md', 'third-party/GPL-3.0.txt', 'third-party/LGPL-3.0.txt', 'node_modules/uiohook-napi/prebuilds/win32-x64/uiohook-napi.node']) {
  if (!(await stat(path.join(root, target))).isFile()) throw new Error(`缺少 ${target}，请先运行 npm run build。`);
}
await mkdir(release, { recursive: true });
// Only replace the exact generated directory under this project's release folder.
if (path.dirname(output) !== release || path.basename(output) !== 'WineFoxPet') throw new Error('打包输出路径不正确。');
// Preserve edited external prompts while upgrading defaults from the previous build.
let previousDefaults = {
  'personas/winefox.md': 'bf035d3cf5b2afacd630b36943d345685878aa56e31941e8d3f04145f95e6f8b',
  'prompts/desktop.md': 'e36f058667ac40fdd153c800ed99f07d8dbfeea91099eac3ef5e215ab77cfa9d',
};
try { previousDefaults = JSON.parse(await readFile(path.join(output, '.prompt-defaults.json'), 'utf8')); }
catch (error) { if (error.code !== 'ENOENT') throw error; }
const customPrompts = new Map();
const newDefaults = {};
const hash = content => createHash('sha256').update(content).digest('hex');
for (const filename of ['personas/winefox.md', 'prompts/desktop.md']) {
  newDefaults[filename] = hash(await readFile(path.join(root, filename)));
  try {
    const content = await readFile(path.join(output, filename));
    if (hash(content) !== previousDefaults[filename]) customPrompts.set(filename, content);
  } catch (error) { if (error.code !== 'ENOENT') throw error; }
}
await rm(output, { recursive: true, force: true });
await cp(path.dirname(electron), output, { recursive: true });
await rename(path.join(output, 'electron.exe'), path.join(output, '酒狐.exe'));
await rm(path.join(output, 'resources', 'default_app.asar'), { force: true });
const appRoot = path.join(output, 'resources', 'app');
await mkdir(appRoot, { recursive: true });
await writeFile(path.join(appRoot, 'package.json'), JSON.stringify({
  name: metadata.name, productName: 'WineFoxPet', version: metadata.version,
  type: 'module', main: metadata.main, private: true,
}, null, 2));
// Whitelist application assets; never copy local settings, keys, history or test profiles.
for (const folder of ['dist', 'dist-electron']) await cp(path.join(root, folder), path.join(appRoot, folder), { recursive: true });
// Native Node-API module, its loader, licenses and matching source/build files.
const modules = {
  'uiohook-napi': ['package.json', 'LICENSE', 'README.md', 'dist', 'prebuilds/win32-x64', 'src', 'libuiohook', 'binding.gyp'],
  'node-gyp-build': ['package.json', 'LICENSE', 'index.js', 'node-gyp-build.js'],
};
for (const [name, files] of Object.entries(modules)) {
  for (const filename of files) {
    const target = path.join(appRoot, 'node_modules', name, filename);
    await mkdir(path.dirname(target), { recursive: true });
    await cp(path.join(root, 'node_modules', name, filename), target, { recursive: true });
  }
}
const nbtRuntimeModules = ['prismarine-nbt', 'protodef', 'lodash.reduce', 'protodef-validator', 'readable-stream', 'ajv', 'abort-controller', 'buffer', 'events', 'process', 'string_decoder', 'fast-deep-equal', 'fast-json-stable-stringify', 'json-schema-traverse', 'uri-js', 'event-target-shim', 'base64-js', 'ieee754', 'safe-buffer'];
for (const name of nbtRuntimeModules) {
  await cp(path.join(root, 'node_modules', name), path.join(appRoot, 'node_modules', name), { recursive: true });
}
await cp(path.join(root, 'third-party'), path.join(output, 'third-party'), { recursive: true });
for (const folder of ['personas', 'prompts']) await cp(path.join(root, folder), path.join(output, folder), { recursive: true });
for (const [filename, content] of customPrompts) await writeFile(path.join(output, filename), content);
await writeFile(path.join(output, '.prompt-defaults.json'), JSON.stringify(newDefaults, null, 2));
await cp(path.join(root, 'public/assets/CREDITS.md'), path.join(output, '角色素材说明.md'));
await cp(path.join(root, '../docs/wine-fox-creation-permissions.md'), path.join(output, '作者二创声明.md'));
await writeFile(path.join(output, '使用说明.txt'), `酒狐桌宠 ${metadata.version} · Windows x64 免安装版

1. 解压整个文件夹，双击「酒狐.exe」启动。无需安装 Node.js 或 Electron。
2. 点击酒狐打招呼，拖动她来移动窗口；下方按钮可以聊天、休息和记录记忆。
3. 隐藏后双击系统托盘的狐狸图标恢复。右键托盘图标可显示、隐藏、重启或退出。
4. 人格文件是 personas\\winefox.md，桌面规则是 prompts\\desktop.md。
   编辑后保存，下一条 AI 对话生效，无需重启。离线预设回应不受人格文件影响。
5. 默认离线陪伴；需要开放聊天时，在设置中填写自己的 AI 服务地址、模型与可选密钥。
6. 数据保存在 %APPDATA%\\WineFoxPet\\data\\state.json，兼容此前开发版的聊天和记忆。
   密钥由系统加密后保存。更新程序不会复制、覆盖或删除这些数据。
7. 请保留整个文件夹，勿单独移动 exe；它依赖旁边的运行库和 resources 文件夹。
8. 点击顶部「游戏」，酒狐会坐到小桌前，跟随你的键盘和鼠标。
   键盘包含 Q/W/E/A/S/D、Shift、空格、Ctrl；鼠标跟随移动、左/右/中键和滚轮。
   点击「键鼠同步」可关闭或开启；打开面板、休息、拖动或隐藏桌宠时会暂停。
   同步只用于本机动画，不保存按键、不上传、不替你操作电脑。
   建议游戏使用无边框窗口；独占全屏游戏可能遮挡桌宠。

游戏存档支持只读扫描；当前记忆可以由你手动添加或从世界存档导入。本版本没有数字签名，来源为本地项目构建。
酒狐为非商业二创，素材说明和作者声明随包附带。
`, 'utf8');
const psQuote = value => `'${value.replaceAll("'", "''")}'`;
await new Promise((resolve, reject) => {
  const child = spawn('powershell.exe', ['-NoProfile', '-NonInteractive', '-Command',
    `Compress-Archive -LiteralPath ${psQuote(output)} -DestinationPath ${psQuote(archive)} -CompressionLevel Optimal -Force`],
  { stdio: 'inherit', windowsHide: true });
  child.on('error', reject);
  child.on('exit', code => code === 0 ? resolve() : reject(new Error(`压缩失败，退出码 ${code}`)));
});
console.log(`可双击启动：${path.join(output, '酒狐.exe')}`);
console.log(`分享压缩包：${archive}`);
