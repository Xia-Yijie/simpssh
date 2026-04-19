# Windows Distribution

当前桌面端分发采用 Windows `setup.exe` 安装器。

## 产物

- 安装包：`desktop/src-tauri/target/release/bundle/nsis/*.exe`
- 裸可执行文件：`desktop/src-tauri/target/release/simpssh.exe` 或 `simpssh-desktop.exe`

正式分发优先使用安装包，不建议直接把裸 `exe` 发给外部用户。

## 当前策略

- 安装器类型：NSIS `setup.exe`
- WebView2 安装方式：内置 Evergreen Bootstrapper，缺失时静默安装
- 安装范围：当前用户
- 安装语言：简体中文、英文

当前安装器会把 `MicrosoftEdgeWebview2Setup.exe` 一起打进安装包。它体积小，但如果目标机器缺少 WebView2，安装时仍然需要联网拉取运行时。

如果以后要做离线分发，可以把 bootstrapper 换成微软的 `Evergreen Standalone Installer`。

## 打包命令

先构建应用：

```powershell
$env:CARGO_HOME='E:\rust\cargo'
$env:RUSTUP_HOME='E:\rust\rustup'
$env:Path='E:\rust\cargo\bin;' + $env:Path
npm run tauri build -- --no-bundle
```

再生成安装包：

```powershell
npm run dist:setup
```

或者直接一条命令完成构建、打包并复制到仓库根目录 `release/`：

```powershell
npm run release:windows
```

工作目录：

```text
E:\simpssh\desktop
```

## 说明

- `release` 构建不会弹黑色控制台窗口。
- 如果后续正式公开发布，建议增加代码签名，减少 Windows SmartScreen 拦截。
- `src-tauri/tauri.conf.json` 里的 Tauri NSIS 配置也已经补上；如果后面官方 bundler 下载恢复正常，也可以继续回到 `tauri build -- --bundles nsis` 这条路径。
- 一键发布后的默认产物路径是 `E:\simpssh\release\simpssh_0.1.0_x64-setup.exe`。
