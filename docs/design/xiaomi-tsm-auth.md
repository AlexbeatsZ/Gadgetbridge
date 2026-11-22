# 路线 A：独立账号授权、TSM 认证和只读资格

## 目标和当前判定

本路线回答「K70 复旦大学联名交通卡能否经正式 TSM 流程迁入 S4」。研究代码只在 `tools/research/xiaomi-tsm-auth`，与路线 B 射频代理分开。没有改生产 `app/`、安装/发布 Gadgetbridge APK，也没有调用订单、冻结、迁出、写卡或激活接口。

2026-11-22 America/Los_Angeles 的新结果：用户扫码确认后，独立官方QR/STS认证成功；使用GB自身包名和实际版本，K70源卡记录、TRANSFER_OUT资格、S4的TRANSFER_IN资格与同产品目录均得到HTTP200/业务200。源卡产品SPTC_MOT、K70的SE、批准账号匹配，卡号尾8213。**本阶段只读服务资格通过；真实迁移尚未执行，复旦校园身份能否随迁移保留仍未验证，不能报告公交卡功能完成。** 此前系统Binder拒绝与未扫码超时是历史证据，不再是本路线当前阻塞。

最新证据：`docs/evidence/xiaomi-tsm-eligibility-2026-11-22.json`。最终五个请求的UTC时间11:50:19–20，对应客户端11-22 03:50:19–20 America/Los_Angeles、K70 11-22 19:50:19–20 Asia/Shanghai。历史证据仍保留。

证据：`docs/evidence/xiaomi-tsm-auth-2026-11-22.json`。手机日志使用 Asia/Shanghai，Binder 实测为 11-22 15:46:45–46；对应客户端 11-21 23:46:45–46。

## 所用实际材料

- 小米运动健康 `com.mi.health` 3.61.0/code361000，已有原 APK 和反编译目录在 Mac `/tmp/.agents/gadgetbridge-transit-research/mifitness`。
- K70 安装的系统账号 APK `com.xiaomi.account` R-24.05.28.12，APK 只读拉取至上述临时目录的 `xiaomi-account.apk`，反编译到 `account-system`。没有读账号数据库或偏好文件。
- AccountSDK 复杂方法有 jadx 反编译错误；这里引用的签名、TokenManager、Binder 权限链和关键线性分支可读。不能用其余方法反编译失败作为接口不存在的证据。

## 实际认证调用链：login 与 oauth 必须区分

1. `defpackage.fbt.x0()` 调 `MiPayService.initialize(context, "", country)`，并设置 `new com.xiaomi.fitness.nfc.sdk.MiOAuthImpl(TokenManager)`。
2. `MiPayService.initialize` 的空 clientId 选择 `EnvironmentConfig.initialize(context)`，保留默认 `sIsLoginAuth=true`。只有非空 clientId 重载才设为 false；Mi Fitness 这条链是 **login** 模式，不能因接口名字叫 `IMiOAuth` 就当成公网 OAuth。
3. `TSMAccountManager` 请求 SID `tsm-auth`；`OAuthAccountManager` 在 login 模式把 SID 传入 `MiOAuthImpl.getAccessToken`。
4. `TokenManagerImpl.getToken()` → `MiAccountInternalManager.getServiceToken(sid, refresh)` → `XiaomiAccountManager.getServiceToken`。
5. 正常结果包含 `userId/serviceToken/security`；`MiOAuthImpl` 把 `serviceToken + "," + security` 放进 Bundle 的 `accessToken`。`OAuthAccountManager` 用 `ExtendedAuthToken.parse` 拆开，保留原串为 `authToken`。
6. 401 的官方重试会使 token 失效并刷新；研究模块没有调用失效接口、没有更改其他 App 的会话。

官方 SDK 非同签名应用使用 `VisibleSystemXiaomiAccountManager`，通过 action `com.xiaomi.account.action.SERVICE_TOKEN_OP` 绑定 `PassportServiceTokenService`。所以「GB 的 AccountManager 可见账号数为 0」不足以排除此 Binder 认证路径。

### K70 Binder 实测与具体拒绝位置

独立研究 DEX 通过临时 attach-agent 在**已安装 GB 本身的 UID 10261**执行，没有借用系统账号或小米运动健康 UID，没有伪造包名/签名、改变账号可见性或读 token 值。

| 项目 | 实际结果 |
| --- | --- |
| 服务绑定 | `bindAccepted=true`，`com.xiaomi.account/.PassportServiceTokenService` |
| 查询能力 | `supportServiceTokenUIResponse=true`，`supportAccessAccount=true` |
| 请求 | descriptor `com.xiaomi.passport.IPassportServiceTokenService`，transaction 1，SID `tsm-auth` |
| 返回 | 455ms；`errorCode=3 / ERROR_APP_PERMISSION_FORBIDDEN` |
| 可继续交互 | `interactionIntentPresent=false` |
| 凭证处理 | 未读取 `serviceToken/security/userId` 值；响应 Bundle 清空，服务解除绑定 |

系统 APK 的 `PassportServiceTokenService.h.b()` → `i7.c`（`AppWhiteListManager`）用 Binder calling UID 查包名、证书哈希和 SID 类别/白名单。返回可区分无权限、签名无效、频率限制、在线白名单下载错误。本次 `errorMessage` 为空，只能确定应用权限拒绝，不能把它细化成未经证明的「证书无效」。

其在线策略由 `i7.d` 从 `https://account.xiaomi.com/appConf/getWhiteList` 获取。本轮匿名只读 GET 实得 HTTP 200、`code=0/result=ok`，JSON 为 `data.list`（123 项），entry 字段包括 `packageName/hash/sids/passtoken/createTime/updateTime`，未发现 GB 包名。系统代码另有预置信任证书分支，因此不能因某官方包没出现在此在线列表就判断其无权限。没有修改本地/在线策略或白名单注入路径。

## TSM 请求密码学与会话

### AccountSDK login 签名

- `security` 为服务登录取得的 Base64 AES key，所检查实现按 AES-128 使用；它不是公交卡密钥。
- 普通参数值按 UTF-8 → AES/CBC/PKCS5Padding（16 字节块对应 PKCS7）→ Base64，无换行；IV 为 ASCII `0102030405060708`。
- 以下划线 `_` 开头的参数不加密；null key/value 跳过。
- 签名原文按 `METHOD大写 & URL encodedPath & 按key排序的加密后k=v ... & 原security字符串` 拼接，SHA-1 后 Base64，无换行，放入 `signature`。URL 的 host/query 不进入此签名。
- 对响应字符串使用同一个 AES coder 解密。
- 登录初始化响应中的 `_sign`、STS 的 `clientSign`、TSM 请求的 `signature` 是三个不同层次，不能混用。

`verify_reference.py` 临时提取本地反编译的 `SecureRequest.encryptParams` 和 `Coder.generateSignature` 方法体，用 JDK 的 `javax.crypto` 独立运行合成向量，与 Python 的 5 个输出字段一致。仅补回 jadx 丢失的 TreeMap 泛型；参考代码不提交。11 项测试覆盖 UTF-8、块边界、参数排序、encodedPath、允许路由、写接口拒绝和响应脱敏。该结果只验证编解码，**不是服务器认证通过**。

### 两套请求封装

| 调用路径 | 封装 |
| --- | --- |
| `TSMAuthManager.queryCloudTransitCard` → `BaseAuthManager` → `TSMAuthClient` | `/api/login/...`；Cookie 为 `userId/serviceToken`；参数包括 `userId`；用 SecureRequest 加密/签名；不自动附加 `token` |
| `pdw` → `common.net.request.SecureRequest` → `OkHttpClientImpl.CryptInterceptor` | 除通用设备/应用元数据，加 `userId`、可选 `tsm-auth_ph/clientId`、`token=serviceToken,security` 后加密签名；Cookie 由 CookieInterceptor 附加 |
| 真正的 `OAuthClient` | `/api/oauth/...`；使用获准的 `clientId/token/userId`，这里没有走上面的 service-token AES 封装 |

公开小米 OAuth 需要注册应用与获准回调。公共账号 OAuth token 或其 MAC key 是否获准访问 TSM，仍需发行方/TSM 明确授权和实际成功响应，不能用普通 scope 推定。

### 官方二维码路径：此前未确认阶段

`XMPassport.getQRLoginUrl` → `GET /longPolling/loginUrl?sid=tsm-auth` → 用户扫码并确认 → `loginByLongPolling(lp)` → `processLoginContent` → `parseLoginResult` → STS。

| 请求 | 本轮真实结果 |
| --- | --- |
| `GET /pass/serviceLogin?sid=tsm-auth&_json=true`，无账号 Cookie | HTTP 200，业务码70016；字段 `_sign/qs/callback/location/securityStatus/...`；没有 `ssecurity/serviceToken` |
| `GET /longPolling/loginUrl?sid=tsm-auth` | HTTP 200，业务码0；字段 `loginUrl/lp/qr/timeout/timeInterval/qrTips/sl/...` |
| 二维码轮询 | 等待窗口内5次客户端读取超时；**没有 HTTP 响应状态**，不能记成服务器401或资格拒绝；没有得到已批准登录结果 |

独立 `passport.py` 生成官方 challenge QR，没有启动小米运动健康，没有读现有浏览器 Cookie，没有输入/记录密码或复制其他 App Token。二维码临时文件在会话结束时删除。已展示的二维码失效，不要继续扫描。

批准后的官方 STS 流程（2026-11-22已实测成功）：从获准响应/Extension-Pragma取得`ssecurity/nonce/location`；`clientSign=Base64(SHA1("nonce="+nonce+"&"+ssecurity))`；访问可信STS location，附`_userIdNeedEncrypt=true`；从同域响应取`tsm-auth_serviceToken`或`serviceToken`，可选`_ph/_slh`。本轮实际账号主机分配为`c3.account.xiaomi.com/c3.lp.account.xiaomi.com`，补入精确允许主机后生成二维码。QR长轮询HTTP200/code0，STS到tsmapi.pay.xiaomi.com也HTTP200，签发新会话。没有复制已装App凭证或借用其包名/UID。未知跳转或额外风险验证停下，不绕过；凭证只留进程内存，会话已关闭。

本轮第一次批准后，脱敏器遗漏了数字`errCode`，故虽然两次只读响应HTTP200且可解密，业务值没有被保留；没有推断为失败。修正后明确展示新码，由用户再次批准，并用同一内存会话完成后续查询。证据记录第二个会话的17次只读TSM请求，包含参数排查及最终五项；第一个会话另有2次ACL只读请求，无写操作。新CLI支持保持会话期间更新解析器，批准后立即删除二维码并清除不再使用的账号SSO Cookie。

## 设备证明和 SE 会话的边界

已检查的两个资格查询封装含 CPLC、deviceModel、firmwareVersion、ROM/Android/应用元数据，不存在已定位的独立硬件 attestation 参数。`tsv.createSession` 发送 SeInfo protobuf：CPLC、deviceType、哈希后的deviceId、APP_CLIENT、DeviceInfo、nfcCapable，返回 TsmSessionInfo。它与账号服务登录是不同会话；只读资格前不需要为了探路调用 createSession。本轮没有创建 SE 会话。

这**不能证明服务端没有额外设备证明或发行方策略**：`startSeOperation`、云端 CAPDU/响应、后续个性化可能包含 SE 认证。SDK 的 `dkAttestation/terminatedKeyAttestation` 出现在数字车钥匙 DKF 路线，不能直接套到公交卡迁移。后续拿到真实服务响应后再根据挑战字段确定，不伪造设备证明。

## 只读查询设计及通过判据

模块仅允许以下四个精确路径，其他路径（包括`se/createSession`）拒绝；订单/authentication_code/transferOutToken/tsmSessionId参数拒绝。已经接入获准的内存会话，查询结果只输出字段结构、业务码、有限资格值及匹配布尔值。

1. `POST /api/login/acl/checkServiceAvailable`：按官方 `ServiceAvailableInfo`，`cardName` 为真实发行方产品名，`cardActionType=TRANSFER_OUT`（目标为TRANSFER_IN），`deviceModel/miuiRomType/miuiSystemVersion/os=ANDROID`，加 SecureRequest 通用元数据/CPLC。不能把 enum 内部数字4/5替代实际 `.toString()` 发出的字符串。
2. `POST /api/login/spcard/v2/queryShiftInCardProducts`：目标 S4 的 CPLC、产品类型 `type`、实际deviceModel、客户端版本、supportCardTransfer，可选cardName/location。返回产品列表只是一层资格，不能自动证明已有源卡及校园身份都可迁移。
3. `POST /api/login/spcard/v2/queryCardProduct`：`TSMAuthManager.queryCardProduct`的产品元数据接口；返回S4支持的SPTC_MOT产品，不创建发行订单。
4. `POST /api/login/spcard/queryIssuedCardList`：官方`hsv extends SecureRequest`只读已发行列表；本轮只在源K70查询，比较账号/CPLC/产品，卡号只保留尾4位。

### 2026-11-22实际只读结果

| 检查 | 实际参数与脱敏结果 | 判定 |
| --- | --- | --- |
| 源卡已发行记录 | K70/vermeer，SPTC_MOT；HTTP200/errCode200；1条；账号、CPLC、产品匹配均true；faceNo尾8213 | 通过账号/源SE/产品归属核验 |
| 源端迁出服务 | vermeer、TRANSFER_OUT、实际源CPLC；HTTP200/errCode200、success | 产品/SE的迁出服务资格通过 |
| 目标迁入服务 | o62、3.201.79、TRANSFER_IN、实际目标CPLC；HTTP200/errCode200、success | 产品/SE的迁入服务资格通过 |
| 目标产品目录 | o62、SPTC_MOT；HTTP200/errCode200；1项上海公共交通卡·交通联合版，发行方上海公共交通卡股份有限公司 | 同产品在目标目录存在 |
| 云端待迁入卡 | 同一账号、o62、SPTC_MOT；HTTP200/errCode200；data=[] | 查询成功，当前无该过滤条件下的云端待迁入卡；不能判为目标不支持 |

官方`CommonResponseInfo.isSuccess()`和`BaseAuthClient.handleResult()`均以errCode==200为成功，不是errCode==0。ACL的成功data可以是null，不能据此判失败。`TSMSDKService.getCloudTransitCardList`调用`queryShiftInCardProducts`来构造CLOUD_TRANSIT条目，故空数组不能替代目标产品支持判据。

两个具体排查点：

- `DeviceInfoImpl.init/getDeviceModel`使用官方产品的alias。匿名官方目录`/app/v1/product/get_config_info_by_category`（SDK明确loginPolicy=3的分支）实得HTTP200/code0，S4为model=mijia.watch.o62、alias=o62、pd_id19969。只保留这些公共元数据，未保存目录中的其他蓝牙配置。最初以硬件型号M2425W1/完整产品名查询ACL返回219；核实alias后同样CPLC的ACL成功。这不是更换设备身份。
- 仅照抄`queryCloudTransitCard`方法体的显式参数会漏掉`AuthRequestBuilder.newBuilder`的通用ROM/Android/appPackageName/app版本/产品字段。本轮缺字段时HTTP200/203无效参数；补齐真实字段后200/data=[]。没有逐个字段消融，不能把203唯一归因于某一个字段。Cloud封装最后把tsmclientVersion覆盖成调用App版本272，ACL/已发行/产品请求保留实际安装TSMClient4230；没有冒充Mi Fitness361000。

只读阶段通过判据已满足：独立获准会话、真实HTTP及业务成功、匹配源卡记录、源迁出/目标迁入服务资格、目标同产品存在。**完整迁移判据仍未满足**：目前没有移出订单/目标个性化/激活结果，返回的通用SPTC_MOT元数据也没有对复旦校园身份保持作出承诺。

复旦官方指南描述同品牌手机迁出云端再迁入，并不单独承诺K70到S4的校园绑定保留；当前发行方支持设备页列出小米手机，未显式列出S4。缺少S4条目不是硬件不支持的证明。后续用户允许尝试真实迁出，同时要求校园身份未知时不要迁移；当前仍保持只读。新的发行方H5证据、身份分层和完整迁移/恢复方案见`xiaomi-transit-migration.md`；不得为得到云端卡而先迁出。

可复现工具：`device_plan.py`读取实际版本/选定源CPLC与产品名、官方公开alias，加上受控SPP元数据生成0600临时请求计划；`passport.py --interactive`的stdin只传计划路径，Token不落盘。计划内完整CPLC属于SE标识而不是卡密钥，本轮仅临时私有文件使用，结束删除；仓库证据仅记录其长度和相等比较。没有更改账号可见性、原卡、余额、校园绑定、NFC路由或手表配对。

## 可复现命令与来源

见 `tools/research/xiaomi-tsm-auth/README.md`，包括合成向量、系统Binder runner和二维码认证。公网一手文档：

- https://dev.mi.com/docs/passport/authorization-code/
- https://dev.mi.com/docs/passport/faq/
- https://dev.mi.com/xiaomihyperos/documentation/detail?pId=2262

这些文档证明正常第三方账号接入要求，不能替代私有 TSM 的实测准入结果。
