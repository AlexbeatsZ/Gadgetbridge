# 小米手表 S4 日常功能

## 范围与证据

- 本轮针对个人 fork 的天气、闹钟和相邻日常功能。S4 使用 `XiaomiWatchS4Coordinator` 和通用 Xiaomi protobuf 服务，经典蓝牙配对继续沿用现有状态。
- 官方协议依据为国区小米运动健康 3.59.2 / 359002 的静态代码，以及 AstroBox-NG-Module-Pb 的 `wear_weather.proto`、`wear_clock.proto`。官方 APK 临时分析目录为 `/tmp/.agents/s4-official-audio/decompiled`。
- 官方闹钟调用在 `DeviceModelExtKt.addOrUpdateProtoAlarmData`、`syncProtoAlarm`、`getAlarmsByProto`。生成模型 `mh4.a` 和 `mh4.b` 提供字段号，`AlarmSettingItem.parse` 提供字段含义。
- 社区协议入口为 [AstroBox-NG-Module-Pb](https://github.com/AstralSightStudios/AstroBox-NG-Module-Pb/tree/main/protos/xiaomi)。Gadgetbridge 天气广播约定见 [Weather app support](https://gadgetbridge.org/internals/development/weather-support/)。

## 天气数据来源

- 原有 `GenericWeatherReceiver`、LineageOS 等接收器和 WeatherSpec 缓存继续承担外部天气来源。K70 检查时没有 `cache/weatherCache.bin`，手机已安装 Google 天气。
- 实际安装的 Google 天气版本为 `1.1.20260716.979549101.release` / 10009803。其 Provider 列表只有 FileProvider 和 BugReportContentProvider。当前天气缓存存放于当前 Android 用户的 `com.google.android.apps.weather/cache`。
- 个人 fork 增加默认关闭的“使用 Google 天气（Root）”。启用后 WorkManager 每 30 分钟只读查询天气缓存，设置页另有手动同步入口。数据来源仍为手机 Google 天气，用户打开该 App 可刷新它自己的缓存。
- `GoogleWeatherSource` 调用系统 `/system/bin/sqlite3 -readonly`。普通 su 找不到库时按既有小米配对导入经验重试 `--mount-master`。只查询两个已核对的缓存文件和已知列。操作限定于当前 Android 用户。
- 数据库 `SqliteKeyValueCache:NoAccountEssentialsWeatherCache:Singleton.db` 的 `cache_table.response_data` 为 protobuf。读取 `invalid_flag=0` 的最近记录。当前实现选择语义类型为当前位置且实况时间在未来 5 分钟至过去 6 小时之内的数据。
- 相邻的 `LocalAddressCache` 提供坐标对应的城市、区名称。使用接近当前坐标的缓存记录，地点缺失时显示经纬度。输出不包含账户数据。
- `google_weather_cache.proto` 是字段子集。官方生成类 `weather.d.c.h` 对应根响应，`b` 对应实况，`f` 对应预报集合，`d` 对应预报条目。根字段 6 是逐小时，7 是逐日。
- 温度单位以每个字段自己的 enum 为准。真实缓存即使请求语言含 `mu-celsius`，数值仍可使用 Fahrenheit。转换时兼容 Fahrenheit、Celsius、Kelvin。WeatherSpec 温度为 Kelvin、风速为 km/h、压力为 hPa。
- 未来逐日预报按来源地点的时区过滤，今天的高低温单独写入 today 字段。预报范围为未来 6 天和最多 24 个逐小时条目。Xiaomi 服务的既有小时下发上限为 23 条。
- Google 条件枚举来自 `com.google.s.a.a.a.a`，映射为 WeatherSpec 使用的 OpenWeatherMap 条件码。复合描述按主要天气状态归类，原始本地化描述同时保留。
- Google 缓存读取、解析或有效期检查失败时保留现有 WeatherSpec，手动操作显示失败提示。此格式绑定已核对的 Google 天气缓存版本，升级后若字段结构改变需重新核对。

## 天气下发

- 数据到达或用户保存闹钟时，如首次读取曾失败，会重新请求相应列表再同步。
- 初始化先查询城市列表。成功解析首次列表后下发现有天气缓存，结束时重新查询列表只刷新状态，避免形成查询与下发循环。
- 没有手机天气数据时保留手表城市。错误应答或缺少 payload 时保持尚未初始化，避免把失败响应当空列表并删除城市。
- 地点请求可仅包含 city key。按 key 或名称匹配缓存，明确指定且没有缓存的城市只记录缺数据。
- `WeatherMetadata.cityName` 填入来源地点名称。此前为空串，S4 获得地点名称的字段不完整。

## 闹钟与提醒

- 命令类型 17。读取为子命令 0，新增 1，更新 2，批量删除 4，单纯启用/停用 5。官方 App 的 `syncProtoAlarm` 使用 5。
- `AlarmDetails.label` 为字段 6。列表字段 3 是节假日支持能力，字段 4 是智能唤醒支持能力。智能唤醒选项按返回能力展示。
- 命令全局非零 status、Schedule 字段 8 的 clockError、缺失列表 payload 都不能用于覆盖本地数据。列表里缺失 ID 或时间同样整体拒绝。
- 读取后补齐本地槽位再同步，包含数据库此前没有的手表闹钟。`DBHelper.getAlarmsWithDefaults` 使用可修改副本，兼容设备尚未写入数据库时的空列表。
- 缓存保存设备原始 AlarmDetails。编辑时复用原消息，保留当前 UI 不能表达的特殊重复规则及未知字段。用户明确改变重复星期后再替换规则。
- 字段 9 是设备计算的下次响铃时间，官方 App 编辑时省略此字段。本实现编辑时同样清除此派生值，让手表按新时间重新计算。
- 标准重复模式为单次 0、每天 1、每周 5。每周标志从星期一的 bit 0 到星期日的 bit 6。
- 变更以设备回读为准，等待当前批次的新增、更新、开关、删除应答后再读取，期间的下一次设置请求等待回读。删除先于新增下发，以释放槽位。
- 假日感知的工作日等规则在设备端保存，当前通用闹钟 UI 的重复选择仍只有标准星期规则。其已有规则可随开关、时间等编辑保留。
- 相同日程服务中的每周提醒也使用星期 bitmask。此前周三等日期误发为星期序号，现改为 `1 << weekdayIndex`。

## 其他日常能力核查

- S4 已缓存 10 个闹钟槽位、40 个提醒槽位、多城市天气能力，以及通知、显示项目、睡眠计划等能力声明。单独的能力缓存只证明初始化链路已获得响应，显示和交互仍需真机验收。
- 世界时钟协调器当前返回 0 个槽位，源码只映射 Lisbon/Sydney。该功能仍待完整城市映射和设备容量核对。
- 日历服务和手机端日历同步已经存在。当前任务只修改重复提醒星期编码，没有改动通知、媒体控制或语音链路。

## 验证入口

- 定向测试为 `XiaomiScheduleServiceTest`、`XiaomiWeatherServiceTest`、`GoogleWeatherSourceTest`。包含无本地闹钟时的导入、错误响应保护、开关命令、特殊重复保留、标准重复模式、星期三提醒、天气首次推送、空缓存保护、city key 请求和 Google 缓存单位转换。
- `google-weather-cache.hex` 来自安装版本的脱敏天气结构，地点替换为合成城市及坐标，移除了来源链接、位置和无关字段，保留用于字段/单位回归的天气数值。
- 真机验收从设置 → 集成 → 天气 → 使用 Google 天气（Root）及“立即同步”进入。确认日志包含导入数量和 Xiaomi 当前天气/逐日/逐小时应答。再检查手表显示及必要的闹钟新增、开关、编辑、删除。

## 2026-11-16 验证记录

- 最终定向测试 30 项全部通过，含本次 22 项天气/闹钟/提醒回归和 8 项 SettingsAppearanceTest。
- 扩大回归原始 287 项中有 2 项既有 RpkMessageFlow 失败及 2 项跳过。排除该既有测试类后，283 项中 281 通过、2 跳过。原测试要求连接请求无消息时保持静默，与先前 interconnect 连接应答修复的语义冲突。本次未修改相关代码和测试。
- 完整 lint 为 12 errors、1642 warnings、2 hints。错误均位于已有 Garmin API33、Manifest AppLink、Galician 格式化和 Hebrew plural 条目，数量与既有记录一致。
- 当前 root 查询在 K70 成功返回 6 条天气记录和 13 条地址记录。脱敏数据经实际字段解析测试得到 16°C、72% 湿度、1021.72 hPa、6 天未来预报及 24 条逐小时数据。
- 用户选择先发布，K70 保留当前语音实验分支构建。因此本轮未安装日常功能版，S4 屏幕天气显示、闹钟实际增删改和 30 分钟后台周期执行仍待安装后验收。
