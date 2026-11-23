# Fudan identity / migration research (route A)

这是研究模块，不是生产移卡功能。无生产app改动，无APK；不调用迁出/迁入/冻结/开卡/激活/支付。完整证据与实施方案见 `docs/design/xiaomi-transit-migration.md` 和 `docs/evidence/xiaomi-campus-migration-2026-11-22.json`。

`public_identity.py` 只可GET代码内固定的4个发行方公共静态URL，不发送账号、Cookie或Token，不调用H5的业务API；从公开配置提取精确“复旦大学”条目和字段allowlist，并给出JS锚点字符偏移。其卡号规范化函数仅实现已观察的11位→19位查询表示，不证明校园映射主键，也不读取真实卡号。

```sh
cd /Users/meta/Developer/Gadgetbridge-inject/tools/research/xiaomi-transit-migration
python3 public_identity.py \
  --assets-root /tmp/.agents/gadgetbridge-transit-research/campus-migration \
  --fetch-public
python3 -m unittest discover -v
```

去掉 `--fetch-public` 可对保留文件离线复核。16个锚点均需找到；若服务端版本变更则非零退出，需人工复核，不自动把新版本内容当作旧证据。2026-11-22结果：4项HTTP200，FDU产品SPTC.MOT.SH.FDU/partner06000047，16锚点全部找到。公开字段不代表本人联名卡已完成个性化查询。

`state_model.py` 完全离线，不能联网/ADB/蓝牙/发APDU，只接受 `TEST_` 合成标识和确切 `SimulationBackend` 类型。它检验拟议的协调/恢复策略，**不是对官方协议完整性的认证**。假后台的权威对账oracle、空闲目标、余额/校园验证等只为测试fixture；不能当作生产已有能力。JSON journal仅保存TEST占位值，不是可处理真实恢复材料的加密存储。precheck按可能写入处理，默认校园条件不满足而停止。

```sh
python3 state_model.py --scenario unknown-campus
python3 state_model.py --scenario success
python3 state_model.py --scenario multiple-batches
python3 state_model.py --scenario lost-report
python3 state_model.py --scenario lost-confirm
python3 state_model.py --scenario lost-apdu
```

判据：unknown-campus→BLOCKED且actions为空；lost-apdu→HOLD且对应写卡仅一次；lost-report→用同一session/scriptsId/原合成响应补报、写卡仅一次；multiple-batches→每批pull/write/report顺序完成；其余合成成功场景→COMPLETE。模型把precheck内部可能的交互式多步APDU抽象为一个不可盲重放动作；不声称这部分真实脚本/ABI已经实现。模拟3007的3次上限是本地测试策略，不是推定的官方参数。

28项测试通过，包括进程在副作用后退出、已保存响应跨重启、响应丢失、未知对账、身份变化、不同云端来源、收费/卡状态/目标/校园验证失败。生产实现还需正式源SE写访问、发行方授权/脚本、真实session状态语义和实卡验收。本模块没有放宽原 `xiaomi-tsm-auth` 四条只读HTTP路径。
