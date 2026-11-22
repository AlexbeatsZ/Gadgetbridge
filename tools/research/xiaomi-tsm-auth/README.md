# 路线 A：独立账号/TSM 认证研究

当前是可执行研究模块，尚未完成TSM认证或复旦联名卡资格查询。详见 `docs/design/xiaomi-tsm-auth.md` 和脱敏实测证据；不要把离线编解码通过或二维码初始化成功当成认证成功。

- `tsm_protocol.py`：login模式AES/签名、现代/旧请求封装；仅允许两个只读资格路径。
- `passport.py`：全新官方QR → 用户本人批准 → STS的独立内存会话；不从已安装App提取凭证。当前QR初始化实测，成功批准/STS尚未实测。
- `android/`：在现有debug GB UID里访问官方ServiceToken Binder；只记录固定错误码，不读凭证值。2026-11-22实测errorCode3，尚无资格HTTP请求。
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
  --approve-window 240
```

CLI即使取得会话也只报告固定结果并丢弃，不调用卡片接口。下一阶段应把获准session直接传给内存内只读transport，补齐经验证的真实源/目标元数据；不要把Token复制到命令行、JSON、日志或仓库。

## 判据

- 编解码：合成向量与参考Java逐字段一致、11项测试通过。
- 应用认证：Binder返回ERROR_NONE或官方QR/STS真正签发tsm-auth session；bootstrap code0不算。当前未通过。
- 迁移资格：获准会话下只读查询同时给出具体源卡迁出与S4同产品迁入资格；还需区别交通产品与校园联名身份。当前未执行。
- 以下接口没有任何调用入口：prepare/start/confirm transfer、createSession/startSeOperation、pull/perso/activate。
