# 路线 A：独立账号/TSM 认证研究

2026-11-22已由用户扫码完成官方QR/STS认证并执行真实只读资格查询：源K70卡记录匹配、TRANSFER_OUT、S4/o62的TRANSFER_IN和同产品目录均HTTP200/errCode200；云端待迁入卡查询成功但为空。详见`docs/design/xiaomi-tsm-auth.md`和`docs/evidence/xiaomi-tsm-eligibility-2026-11-22.json`。这不是实际移卡完成，校园身份随迁移保留仍未验证。

- `tsm_protocol.py`：login模式AES/签名、现代/旧请求封装；四个精确只读路径。
- `passport.py`：全新官方QR → 用户本人批准 → STS的独立内存会话；不从已安装App提取凭证。支持interactive stdin保持会话；退出清除Cookie/Token，批准后即删QR和账号SSO Cookie。
- `read_only.py`：ACL、已发行卡、产品和云端卡的固定allowlist HTTP transport；禁止跳转携带凭证、冒用其他App身份及交易参数；响应只保留脱敏字段和确定的业务码。
- `device_plan.py`：实际手机版本/源SE元数据、独立官方公开设备alias、已有受控SPP目标元数据生成私有查询计划；不读卡密钥、余额、账号数据库或完整卡号。
- `android/`：此前在现有debug GB UID里访问官方ServiceToken Binder实测errorCode3，无授权Intent。独立QR已绕开此系统账号应用准入阻塞，无白名单修改。
- `verify_reference.py`：用本地反编译AccountSDK方法体独立检查合成向量；参考代码只生成在临时目录，不进入仓库。

没有生产App改动、APK构建/安装、UI跳转、订单或SE写接口。

## 环境

现有Python/uv；测试依赖只用隔离缓存。已测试cryptography50.0.2、qrcode8.2/Pillow13.0.0。Android验证使用现有JDK21、SDK36/build-tools36.0.0、NDK28.2.13676358。不要安装全局工具或重建GB APK。

```sh
UV_CACHE_DIR=/tmp/.agents/gadgetbridge-transit-research/uv-cache uv run --no-project --with cryptography==50.0.2 python -m unittest discover -s tools/research/xiaomi-tsm-auth -p test_protocol.py -v

python3 tools/research/xiaomi-tsm-auth/verify_reference.py \
  --sources /tmp/.agents/gadgetbridge-transit-research/mifitness/sources \
  --java-home /Users/meta/.local/share/mise/installs/java/zulu-21 \
  --work-dir /tmp/.agents/gadgetbridge-transit-research/tsm-auth-live/java-reference
```

Binder runner先核对实际ADB serial，传入当前transport；它不修改账号可见性，不调用invalidate，也不提供任意SID/交易命令。研究DEX/so退出时删除。已加载的代码无hook，驻留内存至原进程自然结束；不为清理而强制杀GB。

```sh
python3 tools/research/xiaomi-tsm-auth/android/run.py \
  --serial 192.168.31.118:5555 \
  --sdk /Users/meta/Library/Android/sdk \
  --ndk /Users/meta/Library/Android/sdk/ndk/28.2.13676358 \
  --java-home /Users/meta/.local/share/mise/installs/java/zulu-21 \
  --output /tmp/.agents/gadgetbridge-transit-research/tsm-auth-live/result.log
```

只做匿名初始化，不产生可用登录会话：

```sh
python3 tools/research/xiaomi-tsm-auth/passport.py \
  --evidence /tmp/.agents/gadgetbridge-transit-research/tsm-auth-live/passport-bootstrap.json
```

用户可以即时在官方账号端核对并批准时才运行QR窗口。生成的是短期官方challenge，不是占位页，也不启动小米运动健康。二维码临时文件窗口结束自动删除；证据只记录HTTP状态、字段名、业务码和cookie名称，不包含challenge URL或凭证值。

```sh
UV_CACHE_DIR=/tmp/.agents/gadgetbridge-transit-research/uv-cache uv run --no-project --with cryptography==50.0.2 --with 'qrcode[pil]==8.2' python tools/research/xiaomi-tsm-auth/passport.py \
  --evidence /tmp/.agents/gadgetbridge-transit-research/tsm-auth-live/passport-authorization.json \
  --qr-image /tmp/.agents/gadgetbridge-transit-research/tsm-auth-live/authorization-qr.png \
  --approve-window 300 --interactive
```

看到QR_READY后必须**直接展示图片并等待用户**，不能结束任务。小米官方提示使用「设置→小米账号→右上角扫一扫」，也可用已登录的米家扫码；由用户核对账号并确认登录。二维码过期就重开窗口，不把尚未操作视为授权拒绝。看到AUTHORIZATION_READY_SESSION_IN_MEMORY才可查询。stdin只传私有计划路径，不传Token、Cookie或完整CPLC；输入`close`结束内存会话。默认不加interactive时仍只认证不查卡。

### 准备真实设备元数据和计划

只需为当前会话读取一次目标CPLC。此路径复用现有探针和连接，执行open→CPLC GET DATA→close，不注册AID、不改射频路由、不发真实卡交易APDU。`--verified-model`必须先核对官方匿名产品目录（S4实际alias为o62，不是硬件型号M2425W1或完整model mijia.watch.o62）。不要猜其他设备的alias。

```sh
PATH=/Users/meta/Library/Android/sdk/platform-tools:$PATH python3 tools/research/xiaomi-nfc-probe/run.py \
  --serial '<verified-wireless-serial>' --address '<actual-S4-address>' \
  --ndk /Users/meta/Library/Android/sdk/ndk/28.2.13676358 \
  --java-home /Users/meta/.local/share/mise/installs/java/zulu-21 \
  --metadata --verified-model o62 \
  --private-device-metadata /tmp/.agents/gadgetbridge-transit-research/tsm-auth-live/watch-device-private.json \
  --output /tmp/.agents/gadgetbridge-transit-research/tsm-auth-live/watch-query-metadata.json

python3 tools/research/xiaomi-tsm-auth/device_plan.py \
  --adb /Users/meta/Library/Android/sdk/platform-tools/adb \
  --serial '<verified-wireless-serial>' \
  --watch-metadata /tmp/.agents/gadgetbridge-transit-research/tsm-auth-live/watch-device-private.json \
  --catalog-reference-java /tmp/.agents/gadgetbridge-transit-research/mifitness/sources/com/xiaomi/fitness/device/manager/api/DeviceApiService.java \
  --output-dir /tmp/.agents/gadgetbridge-transit-research/tsm-auth-live
```

匿名catalog使用官方SDK明确loginPolicy=3分支的静态应用配置，不使用个人凭证；参考成熟实现lzghzr/ha_miband的get_config_info_by_category.py。只选取model/alias/pd_id/pd_name，不保存完整catalog或其他蓝牙配置。检查指定手机/现有卡确为本任务设备后再运行。生成的五个计划是0600临时文件，内含SE标识而非卡密钥；会话结束必须删除。程序拒绝覆盖已有计划，避免误用旧SE数据。

授权进程stdin依次输入（无需再扫码）：

```json
{"plan_file":"/tmp/.agents/gadgetbridge-transit-research/tsm-auth-live/current-source-issued-plan.json"}
{"plan_file":"/tmp/.agents/gadgetbridge-transit-research/tsm-auth-live/current-source-out-plan.json"}
{"plan_file":"/tmp/.agents/gadgetbridge-transit-research/tsm-auth-live/current-target-in-plan.json"}
{"plan_file":"/tmp/.agents/gadgetbridge-transit-research/tsm-auth-live/current-target-product-plan.json"}
{"plan_file":"/tmp/.agents/gadgetbridge-transit-research/tsm-auth-live/current-target-cloud-plan.json"}
```

`AuthRequestBuilder`通用参数不能漏；缺失曾导致203。Cloud查询把tsmclientVersion设为调用App版本，其他封装保留已安装TSMClient版本。所有参数都取实际值，不借用官方App包名/版本。STS凭证只在内存中，HTTP记录保存字段名、实际状态码、有限业务值、时延和脱敏匹配布尔值。

## 判据

- 编解码：合成向量与参考Java逐字段一致；原11项加HTTP/响应脱敏等回归均通过。
- 应用认证：官方QR/STS真正签发tsm-auth session，已通过；bootstrap code0单独不算。
- 只读服务资格：源卡归属匹配、源迁出/S4迁入服务及同产品目录，均真实HTTP200/errCode200；已通过。ACL成功data=null正常；Cloud data=[]不表示目标不支持。
- 完整迁移/校园联名身份保留：未执行/未验证，不能用上述通用SPTC_MOT资格代替。
- 以下接口没有任何调用入口：prepare/start/confirm transfer、createSession/startSeOperation、pull/perso/activate。
