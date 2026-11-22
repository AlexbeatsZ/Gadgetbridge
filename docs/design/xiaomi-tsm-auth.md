# 路线 A：独立账号授权、TSM 认证和只读资格

## 目标和当前判定

本路线回答「K70 复旦大学联名交通卡能否经正式 TSM 流程迁入 S4」。研究代码只在 `tools/research/xiaomi-tsm-auth`，与路线 B 射频代理分开。没有改生产 `app/`、安装/发布 Gadgetbridge APK，也没有调用订单、冻结、迁出、写卡或激活接口。

2026-11-21 至 11-22 America/Los_Angeles 的结果：请求签名和两套网络封装已定位并用合成数据验证；K70 系统 ServiceToken Binder 实测拒绝 GB 应用权限；独立官方二维码认证初始化成功，但本轮等待窗口没有收到完成授权的响应。**尚未完成认证，尚未查询源卡/目标产品资格，不能回答该联名卡可以或不可以迁入。**

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

### 官方二维码路径：本轮做到哪里

`XMPassport.getQRLoginUrl` → `GET /longPolling/loginUrl?sid=tsm-auth` → 用户扫码并确认 → `loginByLongPolling(lp)` → `processLoginContent` → `parseLoginResult` → STS。

| 请求 | 本轮真实结果 |
| --- | --- |
| `GET /pass/serviceLogin?sid=tsm-auth&_json=true`，无账号 Cookie | HTTP 200，业务码70016；字段 `_sign/qs/callback/location/securityStatus/...`；没有 `ssecurity/serviceToken` |
| `GET /longPolling/loginUrl?sid=tsm-auth` | HTTP 200，业务码0；字段 `loginUrl/lp/qr/timeout/timeInterval/qrTips/sl/...` |
| 二维码轮询 | 等待窗口内5次客户端读取超时；**没有 HTTP 响应状态**，不能记成服务器401或资格拒绝；没有得到已批准登录结果 |

独立 `passport.py` 生成官方 challenge QR，没有启动小米运动健康，没有读现有浏览器 Cookie，没有输入/记录密码或复制其他 App Token。二维码临时文件在会话结束时删除。已展示的二维码失效，不要继续扫描。

批准后预计的官方 STS 流程（静态已定位，**尚未实测成功**）：从获准响应/Extension-Pragma 取得 `ssecurity/nonce/location`；`clientSign=Base64(SHA1("nonce="+nonce+"&"+ssecurity))`；访问可信 STS location，附 `_userIdNeedEncrypt=true`；从同域响应取 `tsm-auth_serviceToken` 或 `serviceToken`，可选 `_ph/_slh`。研究模块只允许列明的 HTTPS 账号/TSM 主机，未知跳转或附加风险验证会停下，不绕过。Token、security、Cookie 只保留在进程内存。

## 设备证明和 SE 会话的边界

已检查的两个资格查询封装含 CPLC、deviceModel、firmwareVersion、ROM/Android/应用元数据，不存在已定位的独立硬件 attestation 参数。`tsv.createSession` 发送 SeInfo protobuf：CPLC、deviceType、哈希后的deviceId、APP_CLIENT、DeviceInfo、nfcCapable，返回 TsmSessionInfo。它与账号服务登录是不同会话；只读资格前不需要为了探路调用 createSession。本轮没有创建 SE 会话。

这**不能证明服务端没有额外设备证明或发行方策略**：`startSeOperation`、云端 CAPDU/响应、后续个性化可能包含 SE 认证。SDK 的 `dkAttestation/terminatedKeyAttestation` 出现在数字车钥匙 DKF 路线，不能直接套到公交卡迁移。后续拿到真实服务响应后再根据挑战字段确定，不伪造设备证明。

## 只读查询设计及通过判据

模块仅允许以下两个精确路径，其他路径（包括 `se/createSession`）拒绝；订单/authentication_code/transferOutToken/tsmSessionId 参数拒绝。当前只实现其可测试的编解码与路径检查，尚未接入获准会话执行查询。

1. `POST /api/login/acl/checkServiceAvailable`：按官方 `ServiceAvailableInfo`，`cardName` 为真实发行方产品名，`cardActionType=TRANSFER_OUT`（目标为TRANSFER_IN），`deviceModel/miuiRomType/miuiSystemVersion/os=ANDROID`，加 SecureRequest 通用元数据/CPLC。不能把 enum 内部数字4/5替代实际 `.toString()` 发出的字符串。
2. `POST /api/login/spcard/v2/queryShiftInCardProducts`：目标 S4 的 CPLC、产品类型 `type`、实际deviceModel、客户端版本、supportCardTransfer，可选cardName/location。返回产品列表只是一层资格，不能自动证明已有源卡及校园身份都可迁移。

通过 A 的判据：获准的独立账号/TSM会话 → HTTP 与业务认证成功 → 源卡/具体联名产品的迁出许可 → 同产品/S4 迁入许可，分别保存脱敏字段与明确业务错误码，并排除任何订单和SE写入。当前认证未完成，以上判据均没有被假定通过。

下一步最小实验：在用户能即时确认的窗口重新生成官方二维码，保留长轮询至批准响应，记录 STS HTTP/业务结果。如果正式认证仍拒绝，记录其具体包名/应用授权或设备挑战；再选择注册 GB 自有 OAuth/TSM 接入身份，不能借用官方应用身份。如果会话签发成功，才补齐源/目标元数据并在内存内调用上述只读 allowlist。

## 可复现命令与来源

见 `tools/research/xiaomi-tsm-auth/README.md`，包括合成向量、系统Binder runner和二维码认证。公网一手文档：

- https://dev.mi.com/docs/passport/authorization-code/
- https://dev.mi.com/docs/passport/faq/
- https://dev.mi.com/xiaomihyperos/documentation/detail?pId=2262

这些文档证明正常第三方账号接入要求，不能替代私有 TSM 的实测准入结果。
