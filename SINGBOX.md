# sing-box 集成说明

项目已内置自动下载并打包 sing-box Android 二进制。

## 默认行为

- 构建前会自动下载官方 release 对应版本的 Android 二进制。
- 下载后会放入：
  - `app/src/main/jniLibs/arm64-v8a/libsing-box.so`
  - `app/src/main/jniLibs/armeabi-v7a/libsing-box.so`
  - `app/src/main/jniLibs/x86_64/libsing-box.so`
  - `app/src/main/jniLibs/x86/libsing-box.so`
- 运行时直接从 `nativeLibraryDir` 执行，避免部分设备对 app 私有目录的 `noexec` 限制。

## 版本与开关

在命令行可覆盖：

- `-PSING_BOX_VERSION=1.12.22`
- `-PSING_BOX_AUTO_DOWNLOAD=true|false`

示例：

`gradle assembleDebug -PSING_BOX_VERSION=1.12.22 -PSING_BOX_AUTO_DOWNLOAD=true`

## 说明

- 下载来源：`https://github.com/SagerNet/sing-box/releases`
- 默认版本在 `app/build.gradle.kts` 中定义。
- 若网络受限，可先手动准备 `jniLibs` 目录下对应 ABI 的 `libsing-box.so` 文件，再设置 `SING_BOX_AUTO_DOWNLOAD=false`。
