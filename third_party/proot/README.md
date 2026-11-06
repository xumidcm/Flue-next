# PRoot 二进制

`app/src/main/jniLibs/arm64-v8a/` 下的两个文件来自 [termux/proot](https://github.com/termux/proot)：

| 文件 | 上游产物 |
|---|---|
| `libproot_exec.so` | `proot` 可执行文件 |
| `libproot_loader.so` | `loader` / `loader32` 加载器 |

重命名为 `lib*.so` 是为了让 Android 把它们当作原生库打包；因为 Flue 用
`ProcessBuilder` 直接执行 `libproot_exec.so`，所以 `app/build.gradle.kts` 里必须保留
`jniLibs.useLegacyPackaging = true`，否则系统不会在 `nativeLibraryDir` 里落成可执行文件。

## 许可证

PRoot 由 STMicroelectronics 以 GPL-2.0-or-later 发布。Flue 以 GPL-3.0 分发，
GPLv2-or-later 允许升级到 GPLv3，两者兼容。

## 对应源码

按 GPLv2 §3(b)，本项目维护者提供**书面要约**：

> 自本二进制首次分发之日起三年内，任何持有该二进制的个人或组织，均可通过在本仓库
> 提交 issue 的方式索取完整的对应源码，包括 PRoot 上游源码、Termux 补丁，以及生成
> 上述两个产物的构建脚本与 NDK 参数。我们会在收到请求后提供，不额外收费。

## 维护者备注

如果之后把上游源码直接 vendor 进本目录，可以删除上面的书面要约，改为随仓库分发源码
（GPLv2 §3(a)），这是更省事也更不容易出错的做法。建议至少：

```text
third_party/proot/
  README.md          ← 本文件
  UPSTREAM.md        ← 上游仓库地址与所用提交 SHA
  build.sh           ← NDK 构建脚本
  patches/           ← Termux 补丁
```

仓库里的二进制体积约 280 KB，直接放源码的代价可以接受。
