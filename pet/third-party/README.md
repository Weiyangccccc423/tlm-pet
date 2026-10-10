# 输入同步依赖

- `uiohook-napi` 1.5.5：Copyright (c) 2020 Alexander Drozdov，MIT。
  来源：https://github.com/SnosMe/uiohook-napi
- `node-gyp-build` 4.8.4：Copyright (c) 2017 Mathias Buus，MIT。
  来源：https://github.com/prebuild/node-gyp-build
- 原生模块内的 `libuiohook`：Copyright (C) 2006–2023 Alexander Barker，LGPL-3.0-or-later。
  来源：https://github.com/kwhat/libuiohook

MIT 原文位于 `resources/app/node_modules/` 中对应模块的 `LICENSE`。
LGPL 与 GPL 原文附在本目录。随包保留 npm 1.5.5 发布物中的 C 源码、头文件和
`binding.gyp`，位于 `resources/app/node_modules/uiohook-napi/`。
本项目没有修改这些原生库；预编译文件来自该 npm 发布物。

可安装 Node.js、Python、Visual Studio C++ build tools 与 node-gyp，
在模块目录运行 `node-gyp rebuild` 重新编译；把生成的 `.node` 文件放到
`prebuilds/win32-x64/uiohook-napi.node` 后重启桌宠即可使用替换后的模块。
Electron 使用的 Node-API 版本可在其发行说明中查询。
本项目不限制为调试这些库的修改而进行的逆向工程。
