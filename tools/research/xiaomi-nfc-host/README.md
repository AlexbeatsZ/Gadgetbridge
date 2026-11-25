# 路线 B：原生 NFC Host 接口研究

这是独立研究目录，**2026-11-25已实机通过固定测试AID的射频APDU往返，SPP与RF串联仍待验证**。详细地址、真实失败回调、边界和下一实验见 `docs/design/xiaomi-s4-nfc-host.md`。

当前优先路线为蓝牙代理（先Classic SPP），A真实迁移暂停。**已实机取得完整旧路由、controller AID能力、测试AID-first路由增加/回读/重启保持/完整恢复，以及spare discovery slot同步add/delete。已用外部K70验证固定SELECT→S4本机S4TEST9000两次，RTT92.73/92.22ms，原路由/槽/回调恢复和模块卸载通过；SPP固定后端另行实测3/3通过，RTT122/123/146ms；RF/SPP尚未串联。**AID-last曾FAILED03并清空表，已恢复；不能重复使用错误顺序或假定失败没有副作用。详见设计和独立失败/成功证据。

| 工具 | 用途 |
| --- | --- |
| `firmware.py` | 离线字符串/指针引用/Thumb反汇编；PC-relative literal注释 |
| `verify_stock_ce.py` | 原始ARM代码 + 合成RAM离线验证，清楚标记不是实机RF |
| `verify_rf_data.py` | 原RF receive/raw send/当前丢弃callback，14项合成执行；不连接硬件 |
| `verify_nci_routing.py` | 原routing构造器和raw NCI queue/response，8项合成执行；不写真实路由 |
| `verify_getroute_probe.py` | 实际编译的只读探针+原队列/响应与6种拒绝条件，7项离线执行 |
| `watch-abi.lua` | 只读ROM表/控制元数据、fd和IPC；不读真实NCI buffer |
| `rf-metadata.lua` | 只读DM/EE动作表、既有callback指针及ROM指纹；输出文件名复用原companion allowlist |
| `stock-ce-rejection.c/.ld/.lua.in` | 小型、零导入、受版本和状态保护的真实拒绝分支验证 |
| `get-routing-status.c/.lua.in` | 常量只读GET_ROUTING状态查询；异步回调未清除禁止卸载 |
| `build_stock_probe.py` | 用现有NDK编译上述诊断，`--probe get-routing-status`选只读查询，未定义导入即拒绝产物 |
| `watch-ops.cpp` | 现有debug GB连接的临时JNI生命周期控制；固定probe包/face ID，必须明确watch地址 |
| `compile_watchface.ps1` | 使用现有隔离LuaDevTemplate编译器，重用固定face ID并修正格式头；不安装/刷机 |

继续复用 `../xiaomi-nfc-probe/watch/companion/src`；若需要采集新文件，只调整临时构建副本的file allowlist，不增加测试表盘包名/ID。ROMFS/OTA/APK/ELF/RPK/face等大文件不提交。

## 离线复现

适用S4 3.201.79原AP镜像，基址0x2c1c0000；其他版本须重新核对，不能直接调用这些地址。已测试capstone5.0.9、Unicorn2.1.4，使用隔离uv缓存。

公开OTA来源为AstralSightStudios/MiWearFirmwareArchives的`firmware-mijia.watch.o62-v3.201.79-88e9c536` release，asset `mijia.watch.o62_v3.201.79_full_f86aa1eb.bin`。此bin实为ZIP，必须从中解出`vela_ap.bin`（本次9,568,256字节），不能将整个OTA当作AP地址空间。下载/解压只放任务临时目录，不提交固件。

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

UV_CACHE_DIR=/tmp/.agents/gadgetbridge-transit-research/uv-cache uv run --no-project --with unicorn==2.1.4 python tools/research/xiaomi-nfc-host/verify_rf_data.py \
  /tmp/.agents/gadgetbridge-transit-research/firmware/vela_ap.bin

UV_CACHE_DIR=/tmp/.agents/gadgetbridge-transit-research/uv-cache uv run --no-project --with unicorn==2.1.4 python tools/research/xiaomi-nfc-host/verify_nci_routing.py \
  /tmp/.agents/gadgetbridge-transit-research/firmware/vela_ap.bin

python3 tools/research/xiaomi-nfc-host/build_stock_probe.py \
  --ndk /Users/meta/Library/Android/sdk/ndk/28.2.13676358 \
  --output /tmp/.agents/gadgetbridge-transit-research/host-abi-live \
  --probe get-routing-status

UV_CACHE_DIR=/tmp/.agents/gadgetbridge-transit-research/uv-cache uv run --no-project --with unicorn==2.1.4 python tools/research/xiaomi-nfc-host/verify_getroute_probe.py \
  /tmp/.agents/gadgetbridge-transit-research/firmware/vela_ap.bin \
  --llvm /Users/meta/Library/Android/sdk/ndk/28.2.13676358/toolchains/llvm/prebuilt/darwin-x86_64/bin \
  --build-dir /tmp/.agents/gadgetbridge-transit-research/host-abi-live

python3 tools/research/xiaomi-nfc-host/firmware.py \
  /tmp/.agents/gadgetbridge-transit-research/firmware/vela_ap.bin calls 0x2c34e291
```

`calls`扫描半字对齐的Thumb BL候选，可越过线性反汇编误读的inline table；候选仍需从确认的代码边界反汇编。原routing构造器候选调用为file offsets0x1824e6/0x182708。不得把候选搜索当成控制流证明。

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

### GET_ROUTING只读状态探针

该实验已经成功，不必为了确认重复执行。先完成上述离线三组29例，再用`get-routing-status.lua.in`，把`@MODULE_HEX@`替换为本轮`get-routing-status.elf`的十六进制。**先启动companion创建ready目录，再装入测试表盘**；同ID覆盖被拒时需恢复原表盘、删除旧测试项后重装，仍只用362991121。

模块仅发送固定GET_ROUTING；不允许用这个探针替换任意命令。加载前检查callback槽/忙标志/队列/窗口/discovery/eSE元数据；加载后Lua读取自身结果和原栈的callback/busy。state2为pending，**不能卸载、移除仍被引用的模块或把无返回记成“不支持”**。成功时state3、event0x42、len4、status0；随后callback=0/busy=0才rmmod。state10/11/12为guard/分配拒绝，没有发送成功。

读到`41 02 01 00`只说明控制器接受查询，不含真实路由表；不得拿合成旧路由覆盖真实表。新实机证据为`xiaomi-s4-get-routing-status-2026-11-22.json`，回调元数据和离线记录分别另存RF metadata/offline文件。

## 已验证判据

- B1/B2：ROM操作表与镜像一致；CE系统注册地址与动作表实机一致；fd/IPC可实际读取。TML句柄与proc fd映射仍未完全恢复。
- B5原入口：真实S4执行测试AID注册请求，回调event0x15/status3/handle0xFFFF，失败。注销和ISO-DEP配置为空函数。eSE flags前后0x8101，模块卸载成功。
- B6/B7：外部K70 ReaderMode实读生效、ISO-DEP连接/Host激活通过；固定SELECT阶段TagLostException，未取得S4TEST响应，不能称完整实机RF通过。
- B8/B9：尚未进行SPP APDU代理、延迟/WTX/恢复测量。既有wiredSE管理通道不能替代这些判据。

测试AID为F0010203040506。整个目录不包含公交卡密钥、真实卡数据或任意SE APDU发送接口。

## 新路由与Host实验工具

- `get-routing-capture.c/.lua.in`、`verify_route_capture.py`：只捕获本次6102通知；原HAL转发/自撤销/队列barrier后卸载。实机路由通知已经取得。
- `routing_table.py`、`test_routing_table.py`：完整序列/TLV校验、无损旧项备份、AID-first测试计划，离线输出命令；未知项/超单包/已有测试AID拒绝写计划。
- `controller-metadata.lua`、`discovery-slots.lua`：只读能力、两个RF discovery槽、回调元数据。
- `discovery-control.c`、`discovery-roundtrip.lua.in`、`verify_discovery.py`：原NFA event10f/10e停止/恢复；入队不等于完成，必须读回DM状态。
- `test-route-write.c`、`test-route-roundtrip.lua.in`、`verify_test_route.py`：仅原两项+F0010203040506的常量增加/恢复，状态/RF空闲/队列/能力guard。失败后也必须回读并恢复。
- `discovery-slot-check.c/.lua.in`、`verify_discovery_slots.py`：原NFA任务内同步add/delete空闲slot1，完整40字节恢复；无RF收发。
- `background-check.lua`：无NFC的NSH后台执行验证，避免长Lua等待导致SPP超时。
- `host-test-session.c`、`host-test-control.c`、`verify_host_session.py`：**已实机启用/自动恢复，但固定APDU响应未通过的研究原型**；只允许测试SELECT→S4TEST9000，其余交旧sink，不记录未知payload。使用独立空闲slot、选择性ISO-DEP分发、原send路径；退出须停止本测试连接、还原原路由/回调/槽并做原队列barrier。控制ELF的SESSION_ADDRESS绑定占位必须唯一，仅能绑定实读的本session data，默认无效。
- `S4TestReader.java`：K70 shell UID2000 app_process临时测试reader，无APK；ReaderMode实读生效、外部ISO-DEP连接通过，固定SELECT报TagLostException；错误日志区分connect/test_select阶段。固定SELECT和预期响应，不读UID/卡密钥，未知响应只记长度；退出flags0恢复ReaderMode。

构建新增模块使用已有`build_stock_probe.py --probe <stem>`，choices见`--help`。`discovery-stop/start`和`test-route-append/restore`来自同一受限源码的编译宏；append在此指增加逻辑测试项，发送顺序实际AID-first。ARM离线验证器共同参数为`<vela_ap.bin> --llvm <existing-ndk-llvm-bin> --build-dir <isolated-build> --output <evidence.json>`；先构建对应stem，不能误用旧.o文件。Host三个control stem为host-test-arm/remove/stop，resident stem为host-test-session。

当前Host原型已通过无人操作自动超时恢复预演，也取得外部Host激活证据；固定响应尚未通过，不称功能完成。真实card/余额/校园/配对不得操作。

`render_host_session.py`渲染setup或`--collect` Lua，不安装；`--window-seconds`只接受0..300，`--run-id`限制独立实验标记。setup使用后台NSH执行原栈路由/发现/绑定动作，零窗口**省略sleep**（NSH sleep0报错且终止整段脚本）。collector先核对全部槽/回调/状态、HAL owner和在途计数，再读原表并经过原队列barrier后才卸载；失败必须保留resident继续调查，不能强卸载。

结果前32 words为session header，随后40字节为原discovery两槽、16 words为RF事件/状态/长度元数据、16 words为HAL类型/长度/固定SELECT命中计数。HAL观察器转发所有报文，只计数、不保存未知payload、UID或卡内容；STOP/GET响应owner内自撤销。`verify_host_session.py`16项离线检查（新增原NCI DATA分支及接收队列/drain）不替代现场RF证据。最近实机记录为`docs/evidence/xiaomi-s4-host-session-2026-11-22.json`。

```sh
python3 tools/research/xiaomi-nfc-host/render_host_session.py \
  --build-dir /tmp/.agents/gadgetbridge-transit-research/routing-notify \
  --run-id host-example --window-seconds 0 \
  --output /tmp/.agents/gadgetbridge-transit-research/routing-notify/host-example.lua
```

2026-11-25 rf6 live: HAL received 3 exact synthetic SELECT NCI DATA frames (16 bytes); RF6003 remained 0 with the stock sink owner, and the reader got no response. Guarded recovery restored the original table/entries/callbacks and unloaded all modules. See the independent live evidence in `docs/evidence/xiaomi-s4-host-rf6-2026-11-25.json`.

The next test-only responder copies `S4TEST9000` into an original `0x10c` NFA queue message from HAL only for the exact complete synthetic SELECT, conn0 and its own active slot1. It forwards all input unchanged, keeps asynchronous messages free of module pointers, and avoids a second reply if the RF callback receives the same frame. `rf_meta[13]` records phase/state/own-active/handle at the attempt, `[14]` counts queue submissions, and `[15]` marks same-frame suppression. Submission is not RF acceptance. The 33-case offline verifier covers the original send action after a stock-sink overwrite, duplicate suppression, inactive rejection, allocation failure and non-test/eSE guards. Live validation is still required.

RF7 live located a more specific condition: exact DATA arrived in state5 with own-listener inactive and handle low12=FFF (the old diagnostic truncated the upper four bits), so the queue guard submitted zero replies. The next guarded fallback tracks only complete controller Host ISO-DEP Listen-A activation notifications (61/05, interface2/protocol4/mode80), cleared by deactivation/STOP/GET. It permits handleFFFF only with that Host marker and both preserved original eSE and owned test-slot checks; handle0 and other modes remain refused. RF8 corrects `rf_meta[13]`: bits0..3 phase, 4..7 state, bit8 own-listener-active, bit9 controller Host marker, bits16..31 full handle. This fallback has offline coverage but still requires a successful external RAPDU.

Independent SPP acceptance on 2026-11-25: three watch-generated fixed SELECTs reached a bounded K70 `app_process` backend; the original GB connection returned S4TEST9000 through the allowlisted JNI message/session builders; the watch acknowledged all three (122/123/146ms). No Mac per-request relay, APK or real SE. See `xiaomi-s4-spp-fixed-backend-2026-11-25.json`. `S4SppTestBackend.java` requires the current GB PID and stops after 30 seconds or 3 ACKs. The companion's explicit synthetic start mode waits for synchronous `getReadyState()` rather than sending before session connection; default diagnostic mode still exits promptly. This is separate from the RF acceptance; native/quickapp IPC and a combined RF+SPP test remain outstanding.
