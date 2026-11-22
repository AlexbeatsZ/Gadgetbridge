# 路线 B：原生 NFC Host 接口研究

这是独立研究目录，**没有实现射频APDU往返**。详细地址、真实失败回调、边界和下一实验见 `docs/design/xiaomi-s4-nfc-host.md`。

| 工具 | 用途 |
| --- | --- |
| `firmware.py` | 离线字符串/指针引用/Thumb反汇编；PC-relative literal注释 |
| `verify_stock_ce.py` | 原始ARM代码 + 合成RAM离线验证，清楚标记不是实机RF |
| `watch-abi.lua` | 只读ROM表/控制元数据、fd和IPC；不读真实NCI buffer |
| `stock-ce-rejection.c/.ld/.lua.in` | 小型、零导入、受版本和状态保护的真实拒绝分支验证 |
| `build_stock_probe.py` | 用现有NDK编译上述诊断，未定义导入即拒绝产物 |
| `watch-ops.cpp` | 现有debug GB连接的临时JNI生命周期控制；固定probe包/face ID，必须明确watch地址 |
| `compile_watchface.ps1` | 使用现有隔离LuaDevTemplate编译器，重用固定face ID并修正格式头；不安装/刷机 |

继续复用 `../xiaomi-nfc-probe/watch/companion/src`；若需要采集新文件，只调整临时构建副本的file allowlist，不增加测试表盘包名/ID。ROMFS/OTA/APK/ELF/RPK/face等大文件不提交。

## 离线复现

适用S4 3.201.79原AP镜像，基址0x2c1c0000；其他版本须重新核对，不能直接调用这些地址。已测试capstone5.0.9、Unicorn2.1.4，使用隔离uv缓存。

```sh
UV_CACHE_DIR=/tmp/.agents/gadgetbridge-transit-research/uv-cache uv run --no-project --with capstone==5.0.9 python tools/research/xiaomi-nfc-host/firmware.py \
  /tmp/.agents/gadgetbridge-transit-research/firmware/vela_ap.bin \
  disasm 0x180eb4 0x180ee8

UV_CACHE_DIR=/tmp/.agents/gadgetbridge-transit-research/uv-cache uv run --no-project --with unicorn==2.1.4 python tools/research/xiaomi-nfc-host/verify_stock_ce.py \
  /tmp/.agents/gadgetbridge-transit-research/firmware/vela_ap.bin \
  --output /tmp/.agents/gadgetbridge-transit-research/host-abi-live/stock-ce-offline.json

python3 tools/research/xiaomi-nfc-host/build_stock_probe.py \
  --ndk /Users/meta/Library/Android/sdk/ndk/28.2.13676358 \
  --output /tmp/.agents/gadgetbridge-transit-research/host-abi-live
```

## 实机复现范围

本轮已真实执行，无需为了确认重复注册失败。

1. 核对当前原表盘/已有probe列表。保留原9个表盘，原活动ID120917345464；只使用测试ID362991121及包com.meta.s4nativeprobe。
2. companion建立其私有files目录与ready.txt，然后自动退出让Lua表盘运行。模板路径 `/data/quickapp/files/com.meta.s4nativeprobe` 只属于该probe。
3. ABI只读采样用 `watch-abi.lua`。拒绝分支实验将本轮构建 `stock-ce.elf` 的hex替换 `stock-ce-rejection.lua.in` 中`@MODULE_HEX@`。
4. ROG编译资源使用 `compile_watchface.ps1`（ASCII脚本），LuaDevTemplate只放ROG临时目录。编译器可能报告No Errors后非零退出；需要校验文件格式及实际watch安装结果。不能仅凭产物称成功。
5. `watch-ops.cpp`由现有NDK编译，必须`-static-libstdc++`，模式为`<allowlisted-mode>@<明确watch MAC>`；生命周期固定包/face ID。数据本身不走真实卡APDU。此工具不修改已安装APK字节码。
6. stock probe guard核对表槽、空函数机器码、CE注册指针、flags0x8101及vendor handler为空。任一不符只写模块内拒绝码；不调用原函数。guard_result2才表示确认预期的**注册失败**，不是HCE成功。
7. 结果通过lsmod取得该模块data地址，只xd模块自己的48字节；随后rmmod并确认lsmod为空。禁止卸载仍有注册回调/handler的模块；本probe只调用同步拒绝路径，不留callback。
8. 退出时复原原表盘、删除唯一测试表盘/RPK、移除probe私有文件与手机暂存so/DEX。保存源代码和脱敏证据，不发布APK。

## 已验证判据

- B1/B2：ROM操作表与镜像一致；CE系统注册地址与动作表实机一致；fd/IPC可实际读取。TML句柄与proc fd映射仍未完全恢复。
- B5原入口：真实S4执行测试AID注册请求，回调event0x15/status3/handle0xFFFF，失败。注销和ISO-DEP配置为空函数。eSE flags前后0x8101，模块卸载成功。
- B6/B7：尚未进行外部手机SELECT/固定应答，不能称实机RF通过。
- B8/B9：尚未进行SPP APDU代理、延迟/WTX/恢复测量。既有wiredSE管理通道不能替代这些判据。

测试AID为F0010203040506。整个目录不包含公交卡密钥、真实卡数据或任意SE APDU发送接口。
