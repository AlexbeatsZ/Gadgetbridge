# Xiaomi Watch S4 公交卡开通与迁移

核对日期为 2026-11-21。实机为 Redmi K70 和 Xiaomi Watch S4，手表通过 Gadgetbridge 保持现有绑定。

## 复旦大学联名卡

- 通过无线 ADB 查看 K70 的 NFC 页面。卡名为「复旦大学联名卡」，卡种标为「上海公共交通 · 交通联合」。当前移植客户端的页面没有显示移卡入口。
- [复旦大学的联名卡公告](https://news.fudan.edu.cn/2026/0527/c5a149385/page.htm)将 NFC 机型范围指向上海公共交通卡公司的[支持名单](https://spa-prod-1255822635.cos.ap-shanghai.myqcloud.com/wind/nfc-device-sptcc.html)。已核对名单版本 `v20261015.0.1`，列有 Redmi K70 和 Apple Watch，未列出 Xiaomi Watch S4 或小米手表。该名单不能确认联名卡能发到 S4。
- [小米 S4 产品规格](https://www.mi.com/prod/xiaomi-watchS4/specs)单独确认 S4 支持 NFC。通用 NFC 或公交卡支持不能判断某张联名卡在目标表上的移入资格。

## 小米运动健康的官方流程

从[小米官网下载入口](https://hlth.io.mi.com/download?appId=mifitness)取得国区 APK，`aapt2` 确认为 `com.mi.health` 3.61.0（361000）。用 `aapt2` 和 jadx 分别核对 Manifest、中文资源与卡列表逻辑。

1. 小米运动健康的卡包管理页使用当前穿戴设备。卡列表加载公交卡配置和当前场景支持的卡，另外查询云端待移入卡片。界面区分「立即开通」「移入开通」「无法移入」。
2. 「自由迁移」说明将符合条件的卡移出后，卡号、余额和交易记录由官方云端保留，再由支持的设备移入。资源中的说明面向小米运动健康设备之间的迁移，未承诺手机卡可跨到穿戴设备。
3. `CardManagerActivity` 和 `XiaomiPayActivity` 是 App 内部页面。Manifest 中的对外发卡链接进入 `IssuerActivity`，面向发行方提供的流程，仍使用小米运动健康的当前穿戴设备。它无法作为 Gadgetbridge 的通用卡包入口。尚未找到文档化的第三方接口，可在现有 Gadgetbridge 绑定下查询联名卡的资格或启动完整迁移。

## 实机探测与剩余条件

- 独立 Vela 探测包用 `system.nfc` 和 `system.hce` 测试公开快应用运行时。S4 安装记录和蓝牙上报均已确认，收到 `nfc=null,hce=null`。这限定了当前快应用方案的能力，S4 自带卡包的能力仍由表端固件提供。
- K70 当前未安装国区小米运动健康。官方流程若要继续实机验收，应先向发卡方确认「复旦大学联名卡」是否支持从手机迁入 S4，并确认原卡号与余额的保留条件。此轮没有移出原卡、开新卡或改变手表绑定。
- 本轮产出为调研记录。Gadgetbridge 的可用功能仍取决于官方提供的迁移入口与发卡方支持，暂无可验证的移卡实现。
