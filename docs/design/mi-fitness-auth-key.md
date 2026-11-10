# 从小米运动健康导入配对密钥

在添加设备时，支持导入的小米设备会在认证页显示「从小米运动健康导入（Root）」。用户点按后，Gadgetbridge 调用 `su` 并使用系统的 `/system/bin/sqlite3` 读取设备库。查询在手机本地完成，结果只填入认证页。用户提交后沿用现有的设备密钥保存和蓝牙配对流程。

- 读取当前 Android 用户下的 `com.mi.health` 和 `com.xiaomi.wearable` 的 `databases/device_db`。查询用只读模式打开 SQLite，避免复制数据库或依赖日志内容。
- 若普通 `su` 看不到数据库，尝试用 `su --mount-master` 在系统挂载命名空间执行同一条只读查询。K70 的 APatch 默认继承 Gadgetbridge 的应用挂载命名空间，即使 UID 已提升为 0，原路径仍可能被 Android 的应用数据隔离隐藏。重试只对当前子进程生效。
- 从 `device.detail` 的 JSON 取蓝牙地址，以当前选中设备的完整地址匹配。接受大小写和常见分隔符差异。优先使用有效的 32 位十六进制 `token`，其次使用 `encrypt_key`。重复值合并，同地址出现不同的有效密钥时交由用户判断。
- 入口适用于使用小米协议的设备，以及通过小米运动健康绑定并写入相同设备库的小米手环 6、7。依赖官方 App 已在同一 Android 用户中写入设备记录及密钥。无可用结果时仍可手动填入。
- 密钥在认证页以掩码显示，导入过程无需联网或使用 ADB。实机验证时应保留官方 App 的绑定记录，解绑或重置可能使原密钥失效。

设备库字段参考 [Mi Band 9 的实机配对记录](https://github.com/glasses666/miband9-imu-recovery/blob/main/docs/recovery/GADGETBRIDGE_XIAOMI_BIND_FLOW.md#current-phone-evidence)。密钥使用与解绑注意事项见 [Gadgetbridge 官方配对文档](https://gadgetbridge.org/basics/pairing/huami-xiaomi-server/#entering-key)。
