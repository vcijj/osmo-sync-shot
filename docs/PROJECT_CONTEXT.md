# 项目上下文 / 交接文档（PROJECT_CONTEXT）

> **用途**：在任何新对话中，把本文件内容提供给 AI 助手（或自己阅读），即可无缝续接本项目的工作。
> 最后更新：2026-10-08（v1.18）

---

## 1. 项目概览

- **是什么**：安卓 App「大疆同步连拍」（osmo-sync-shot）——一台手机通过 BLE 遥控多台运动相机（大疆 Osmo Action / Osmo 360 + 影石 Insta360），支持定时同步连拍、多手机协同、GPS 注入、轨迹地图、照片导入等。
- **GitHub**：https://github.com/vcijj/osmo-sync-shot （公开，MIT，账号 vcijj）
- **本地路径**：`C:\Users\w\Desktop\安卓手机控制大疆相机`（路径含中文，gradle.properties 已加 `android.overridePathCheck=true`）
- **当前版本**：v1.18（versionCode 19）
- **技术栈**：Kotlin + Jetpack Compose + Material3，minSdk 26 / targetSdk 34，CameraX，osmdroid，kotlinx-coroutines
- **包名**：`com.osmosync.app`
- **用户的相机**：Osmo Action 5 Pro（0xFF44）+ Osmo 360 一代（0xFF66）；测试手机小米 17（HyperOS/Android 16）

## 2. 模块结构（app/src/main/java/com/osmosync/app/）

```
protocol/  DJI R SDK 协议层：Crc（CRC16/32，初始值 0x3AA3）、DjiFrame（帧封装解析）、
           Dji（指令 payload 构造解析；已用官方已知好帧字节级验证）
ble/       OsmoCamera（单相机 GATT 连接/握手状态机/指令收发）、CameraManager（扫描识别/
           多机管理/批量指令/GPS 推送循环/唤醒编排/相机记忆）、WakeAdvertiser（WKP 唤醒广播）、
           RemoteIdentity（手机持久随机 device_id/proto MAC/配对表）
insta360/  Insta360Remote（模拟影石 GPS 遥控器：GATT Server 0xCE80 + 广播，相机主动连手机）
shooter/   IntervalShooter（定时连拍调度：绝对开始时刻、每张回调、会话结束回调）
phone/     PhoneCameraController（CameraX 拍照 + GPS EXIF + 预览接续）
gps/       GpsProvider（定位 + 卫星数）、OrientationProvider（罗盘朝向 azimuth）
track/     TrackStore（轨迹点 JSON 持久化）、GeoConv（WGS84→GCJ02）
mesh/      MeshManager（多手机协同主从：UDP 47016 发现 + TCP 47017 JSON + NTP 式时钟校准）
importer/  CameraImporter（SAF 树扫描 DJI_*.JPG → MediaStore 导入）
service/   CaptureService（连拍前台服务 + WakeLock）
ui/        MainActivity（权限门 + 4 页签）、DevicesScreen、ShootScreen、MeshScreen、TrackScreen
util/      CrashGuard（崩溃捕获，下次启动弹窗）、LogExport（拍摄清单 JSON 导出）
```

## 3. 大疆 DJI R SDK 协议速查（全部经过实现验证）

来源：官方开源 https://github.com/dji-sdk/Osmo-GPS-Controller-Demo （MIT）

### 3.1 BLE 层
- 服务 `0xFFF0`；**写特征 0xFFF5**（手机→相机）、**通知特征 0xFFF4**（相机→手机，需订阅 CCCD 0x2902）
- 广播识别：厂商字段第 0/1/4 字节 = `0xAA / 0x08 / 0xFA`（注意 Android 的 getManufacturerSpecificData() 不含 Company ID，需自行重组：`[cid低, cid高, data...]`）
- 帧以 `0xAA` 开头才处理；**Action 5 Pro 会推 0x55 开头的帧（忽略）**，Action 4 不会
- MTU 请求 247；帧最长 ~250 字节

### 3.2 帧格式（小端）
```
[0]SOF=0xAA [1..2]Ver/Len([15:10]=0,[9:0]=帧长) [3]CmdType [4]ENC=0 [5..7]RES
[8..9]SEQ [10..11]CRC16(覆盖0..9) [12]CmdSet [13]CmdID [14..]payload [尾4]CRC32(覆盖0..DATA末)
```
- CRC16/32：反射表格法，**初始值均 0x3AA3**，无输出翻转（官方 custom_crc16/32.c）
- CmdType：`0x00` 无需应答 / `0x01` 要应答但缺了没事 / `0x02` 必须应答；bit5=1 为应答帧（`0x20+`）；应答帧回带相同 SEQ
- 最短帧 16 字节

### 3.3 指令集
| CmdSet/CmdID | 功能 | payload 要点 |
|---|---|---|
| 0x00/0x19 | 连接请求 | 33B：deviceId(4)+macLen(1)+mac(16,前6B)+fw(4,填0屏蔽升级)+conidx(1)+verifyMode(1)+verifyData(2)+res(4)；verifyMode 1=首配(相机弹窗显示校验码) 0=重连 2=相机回发的校验结果(verifyData 0=允许)；手机应答 9B：deviceId+retcode+res(首4B=相机编号，多机 1..N，单机 0) |
| 0x00/0x11 | 按键上报 | 4B：keyCode+mode+keyValue。**拍录键0x01**短按(mode1,value0)=拍照模式拍照/视频模式开始停录像；拍录键**长按(value1)=关机/进入休眠**；QS键0x02；快照键0x03（官方已弃用此法，见 Q&A#6） |
| 0x00/0x17 | GPS 推送 | 48B：ymd(4)+hms(4)+lonE7(4)+latE7(4)+高度mm(4)+北向速cm/s float(4)+东向(4)+下降(4)+垂直精度mm(4)+水平精度mm(4)+速度精度cm/s(4)+卫星数(4)。官方建议**连接后立即 10Hz 推送**（本 App 用 1Hz） |
| 0x00/0x1A | 电源模式 | 1B：0=正常 3=休眠。**休眠后禁止再向链路发任何数据**；链路保持 |
| 0x1D/0x02 | 状态推送 | 38B：mode(1)+status(1)+res+recordTime(2@5)+容量MB(4@15)+剩余照片(4@19)+剩余秒(4@23)+…+powerMode(1@28)+…+电量%(1@37)。status: 0息屏/1待机/2回放/3拍摄中。**订阅：0x1D/0x05，payload=pushMode(3=周期+变化)+freq(固定20=2Hz)** |
| 0x1D/0x03 | 拍录控制 | 9B：deviceId+ctrl(0开/1停)+res(4)。**只在视频模式生效**；官方更推荐用按键上报拍录键 |
| 0x1D/0x04 | 模式切换 | 9B：deviceId+mode+res(**官方固定值 01 47 39 36**)。模式：0x00慢动作 0x01视频 0x02静止延时 **0x05拍照** 0x0A运动延时 0x1A直播… |

- 设备号：Action 4=0xFF33、**Action 5 Pro=0xFF44、Action 6=0xFF55、Osmo 360=0xFF66**（相机握手时回发，低16位）
- 休眠/唤醒官方规则（Q&A#2 原文见 §5）：
  - **休眠后禁止向链路发任何数据**（v1.13/v1.14 的教训：0x001A 唤醒指令会被忽略）
  - **唤醒时蓝牙会短暂断开**，遥控器需自动重连（App 重试 5 次×2.5s）
  - 广播唤醒前提：近期成功连接过该相机（App 的相机记忆满足）
- **唤醒广播包（已与官方实拍图逐字节核对一致）**：AD 结构 `len=10, type=0xFF, data=57 4B 50 + 反序MAC`（即 companyId 0x4B57（"WK"）+ 'P' + mac[5..0]）。Android AdvertiseData.addManufacturerData(0x4B57, "P"+反序MAC) 自动补 companyId/Flags 头，效果一致。官方样例：OA4 `0AFF574B50E711601F6060`、OA5 Pro `0AFF574B509650C71F6060`。广播必须 **setConnectable(true)**（ADV_TYPE_IND）
- 唤醒后 snapshot 官方流程（Q&A#6）：广播唤醒 → 重连 → 发**普通快门单击** → 拍完自动休眠

### 3.4 已验证的已知好帧（用于回归测试协议层）
```
模式切运动延时: AA 1B 00 01 00 00 00 00 05 00 57 EE 1D 04 00 00 33 FF 0A 01 47 39 36 F4 FA E1 D0
快照键上报:     AA 16 00 01 00 00 00 00 00 12 8C 23 00 11 03 01 00 00 26 3B 43 CC
```

## 4. 影石 Insta360 协议速查（社区逆向，未实测）

来源：pchwalek/insta360_ble_esp32（MIT）、TheAngryRaven/insta360-ble-gps-spec
- **架构与大疆相反：相机是 BLE 主设备，主动连接遥控器**。手机模拟 "Insta360 GPS Remote" 外设
- 手机开 GATT Server：服务 `0xCE80`（CE81 写 / CE82 通知 / CE83 读，值 0x0201）+ 识别服务 `D0FF-3C17-D293-8E48-14FE2E4DA212`（FFD1 写/FFD2 读/FFD3 读=0x301E9001/FFD4 读=0x18002001/FFD5、FFD8 写/FFF1 读/FFF2 写/FFE0 读）
- 以 "Insta360 GPS Remote" 为蓝牙名广播（临时改 adapter.name，停止时恢复）
- 指令 = 对 CE82 发 9 字节通知：`FC EF FE 86 00 03 01 [键][事件]`：**快门 02 00**（拍照模式拍/视频模式开停录）、**切模式 01 00**、**息屏 00 00**、**关机 00 03**
- 未做：唤醒关机相机（需每机型 iBeacon 魔数：X3=37 4B 43 4D 54 4B、RS 1寸=38 51 53 4A 38 52，嵌在 manuf_data[14..19]）、WiFi 传文件（protobuf 协议，参考 RigacciOrg/insta360-wifi-api）
- 注意：android-34 平台 jar 的 BluetoothGattServerCallback 回调签名**无 server 参数**（设备对象在前，javap 验证过）

## 5. 官方 Q&A 文档（全文收录）

> 来源：https://github.com/dji-sdk/Osmo-GPS-Controller-Demo/blob/main/docs/Q&A_CN.md （MIT License, © SZ DJI Technology Co., Ltd.）

# 常见问题与解答

本文档旨在解答开发过程中可能遇到的一些常见问题。

提示：本 Demo 代码仅供参考。如遇到 BUG，建议将复现过程的视频或照片、输出日志及复现方法一并提交至 issue，我们将尽快处理。同时也欢迎您提交 PR，参与修复与优化。

## 1. 遥控器与相机通信时的特性值

| **特性** | **说明**                           |
| -------- | ---------------------------------- |
| 0xFFF4   | 相机发送，遥控器接收，需要使能通知 |
| 0xFFF5   | 相机接收，遥控器发送               |

参考代码：

```c
/* 这里定义想要过滤的 Service/Characteristic UUID，供搜索使用 */
#define REMOTE_TARGET_SERVICE_UUID   0xFFF0
#define REMOTE_NOTIFY_CHAR_UUID      0xFFF4
#define REMOTE_WRITE_CHAR_UUID       0xFFF5
```

连接建立后，仅需处理以 `0xAA` 开头的帧（DJI R SDK Protocol），其他广播数据可直接忽略。

## 2. 如何休眠与唤醒相机？

相机既可以通过长按电源按键进入休眠状态，也可以通过遥控器操作实现休眠。长按遥控器上的拍录键，相机会进入休眠模式。休眠状态下，单击任意按键可唤醒相机；如果单击的是拍录键，相机将被唤醒并立即开始拍摄，拍摄结束后自动重新进入休眠状态。

请注意以下几点：

- 相机进入休眠后，请停止向相机发送任何数据。
- 收到相机的休眠应答后，即可视为相机已成功休眠，此时蓝牙连接仍会保持。
- 在相机休眠过程中，可能仍会推送几帧状态数据，可安全忽略。
- 当相机被唤醒时，其蓝牙连接会短暂断开，遥控器需自动完成重连。
- 想要广播唤醒目标相机，前提是最近一段时间先使用遥控器成功连接该相机。

详细实现参考：DATA 数据段详细文档 中的 **相机电源模式设置（001A）** 功能。

## 3. 如何屏蔽升级？

在连接请求协议中的 `fw_version` 字节位置填入 0，该操作需相机已更新至最新固件版本方可支持。

详细实现参考：DATA 数据段详细文档 中的 **连接请求（0019）** 功能。

## 4. 如何识别相机广播？

可通过判断厂商字段的特定字节来识别是否为支持的相机，判断逻辑如下：当厂商字段的第 0、1 和 4 字节分别为 0xAA、0x08 和 0xFA 时，表示该相机为支持设备。

详细实现参考 `ble.c` 文件中的 `bsp_link_is_dji_camera_adv` 函数。

## 5. 模拟 GPS 命令帧推送但是仪表盘数据异常？

不建议使用模拟数据方式构造 DATA 数据段详细文档 中的 **GPS 数据推送（0017）** 命令帧。因为仪表盘功能依赖这些数据，使用非真实数据可能导致 APP 中仪表盘显示异常。

真实构造的 GPS 命令帧可参考 `test_gps.c` 文件。在 `app_main.c` 中调用 `start_ble_packet_test(1)` 可实现以 1Hz 频率循环推送，供测试使用。后续我们将持续完善这些测试数据，也欢迎大家提交更多真实有效的测试样本。

## 6. 唤醒后 snapshot 功能实现

先通过广播唤醒数据以唤醒相机。

相机唤醒后，仅需上报快门键的单击事件。

相机拍摄 / 录制完成后将自动进入休眠状态。

详细实现参考：DATA 数据段详细文档 中的 **按键上报（0011）** 功能。

## 7. 倍率的计算

慢动作下，倍率等于帧率 / 30。

运动延时下，倍率等于延时间隔。

静止延时下，没有倍率显示，只有间隔时间。

## 8. 相机模式对应 UI 设计

参考：DATA 数据段详细文档 中的 **相机状态推送（1D02）** 和 **新相机状态推送（1D06）** 命令集，后面有详细给出不同相机模式下，参数如何显示，如何与帧字段对应。

也可参考 DJI Osmo Action 蓝牙遥控器的界面设计。

## 9. 在什么时候进行 GPS 数据推送？

遥控器在成功连接至相机后，应立即以 10Hz 的频率开始推送 GPS 信息，而不是等到视频开始录制时再推送。同时，建议增加指示灯，用于显示当前是否已获取到 GPS 信号。

## 10. 按键上报 QS 键和统一模式切换命令的区别？

**按键上报 QS 键** 相当于短按相机上的 QS 键（通常是电源键），切换到的具体模式由相机内部设置决定，遥控器无需指定目标模式，仅需发送按键上报命令。按下一次将进入快速切换列表中的第一个模式，继续短按可依次切换后续模式。

**统一模式切换** 则支持直接切换到指定的拍摄模式，适用于"一键进入 XX 模式"的场景，更加明确和快捷。

## 11. 如何实现拍录控制？

实现该功能有两种方案：

一种是通过 **拍录控制命令（1D03）** 来控制；但我们更推荐使用 **按键上报命令（0011）** 中的拍录键方式实现，该方式等效于短按相机的拍摄按钮，无需判断当前是否处于录制状态，逻辑更为简单可靠。

## 12. 无法连接相机？

在相机中下拉菜单，点击设置，进入无线连接，确保无线连接已开启，并重置连接。

## 13. FFF4 NOTIFY 接收不到消息？

在 FFF4 NOTIFY 下，Osmo Action 4 与 Osmo Action 5 Pro 的表现略有差异：Osmo Action 5 Pro 会推送一些以 `0x55` 开头的帧（可忽略，只需要解析 0xAA 开头的帧），而 Osmo Action 4 不会。

建议使用蓝牙调试工具实时监听 Osmo Action 4 的 FFF4 NOTIFY，并向 FFF5 发送命令进行测试。测试所用的命令帧可参考 `connect_cmd_frame.txt` 文件中的连接请求帧，也可通过 `connect_cmd_frame_builder.c` 构造并进行验证。

---

## 6. 版本历史（全部已发布至 GitHub Releases，APK 附带）

| 版本 | 内容 |
|---|---|
| v1.0 | 初版：DJI 协议层（好帧验证）+ BLE 多机 + 定时连拍 + 手机同步拍摄 + 前台服务 |
| v1.1 | 修复点连接闪退（LazyColumn key 冲突）；CrashGuard 崩溃捕获；**用户真机确认可用** |
| v1.2 | 多手机协同（主从/时钟校准）；相机休眠+广播唤醒；拍摄清单导出 |
| v1.3 | GPS 注入（0x0017 1Hz 推相机 + 手机 EXIF） |
| v1.4 | 系统分享（可选用互传联盟直传；OA5 Pro 固件 v01.03.0330 起支持 MTA） |
| v1.5 | 轨迹页（osmdroid 高德/OSM、拍摄点朝向箭头、轨迹线、JSON 持久化） |
| v1.6 | 相机照片 USB 导入（SAF，会话时间窗筛选/去重） |
| v1.7 | 相机记忆（重启恢复列表）+ 一键唤醒并重连 |
| v1.8 | 修复轨迹页画布满屏（weight+clipToBounds）；默认高德瓦片 |
| v1.9 | 唤醒广播改 setConnectable(true)（官方 ADV_TYPE_IND）+ 多轮广播 + 错误码透出 |
| v1.10 | 修复无法切录像（0x1D03 只在视频模式生效；先切模式再录像；reserved=01 47 39 36） |
| v1.11 | 已连接时禁用唤醒按钮（后在 v1.13 修正为智能分流） |
| v1.12 | 影石 Insta360 遥控模式（GATT Server 模拟 GPS 遥控器） |
| v1.13 | 认知修正：休眠≠断开（链路保持）；唤醒按状态分流 |
| v1.14 | 休眠后链路指令被忽略——已连接休眠相机也走广播唤醒 |
| v1.15 | 关机（拍录键长按）+ 快照(关机拍) |
| v1.16 | 连接后不再自动发拍摄信号（移除快照排队补发） |
| v1.17 | 按官方 Q&A 全面修正：休眠禁发数据、唤醒断开重连（5次/2.5s）、快照=重连后普通快门单击；唤醒包与官方实拍逐字节核对一致 |
| v1.18 | 单机关机升级为"全部关机"（多机收工一键关） |

## 7. 调试经验教训（避免重蹈覆辙）

1. **休眠≠断开**：大疆休眠只是息屏，GATT 链路保持；但休眠后应用层挂起，链路指令全部无效，唤醒必须广播
2. **Compose LazyColumn**：两个 items() 用相同 key 会直接崩溃（编译期发现不了）
3. **连接后绝不自动发拍摄信号**（用户明确要求）：唤醒/重连/快照排队都可能造成意外照片
4. **CameraX 1.4**：OutputFileOptions 无 setLocation，用 `.setMetadata(Metadata().apply { location = ... })`
5. **osmdroid 6.1.18**：没有 UrlTileSource，用 XYTileSource；OSM 瓦片国内不可达，默认高德 + GCJ02 转换
6. **android-34 平台 jar**：BluetoothGattServerCallback 回调签名无 server 参数
7. AGP 对中文路径报错：`android.overridePathCheck=true`
8. 功能假设要与协议现实核对（休眠/断开/排队），用户反馈的问题常源于状态假设错误

## 8. 本机构建环境（Windows，有坑）

- Android SDK：`D:\adk\android-sdk`；JDK 17：`D:\adk\jdk`；Gradle 8.10.2 解压：`D:\adk\gradle-8.10.2`
- **本机 Java 访问 services.gradle.org 会超时**（curl 却能下载）：wrapper 报错时，把 `gradle/wrapper/gradle-wrapper.properties` 的 distributionUrl 临时改为 `file:///D:/adk/gradle-8.10.2-bin.zip`（**仓库里必须保持官方 URL**，已吃过亏）
- 构建命令：`export JAVA_HOME="D:\adk\jdk" && cd 项目目录 && cmd //c "D:\adk\gradle-8.10.2\bin\gradle.bat :app:assembleDebug"`
- 产物：`app/build/outputs/apk/debug/app-debug.apk` → 复制为桌面 `大疆同步连拍.apk` 交付用户
- **模拟器不可用**（AEHD 驱动未装、WHPX 未启用），BLE 功能只能真机测试
- GitHub 凭据在 Windows 凭据管理器（`git credential fill` 可取，token 可调 API；`gh` 未安装）
- aapt2 校验 APK：`D:\adk\android-sdk\build-tools\34.0.0\aapt2.exe dump badging xxx.apk`

## 9. 当前状态与待办

**待用户真机验证（截至 v1.18）**：
- [ ] 休眠唤醒全流程（v1.17 官方流程：广播→断开重连）——Action 5 Pro + Osmo 360 分别测
- [ ] 快照(关机拍)：广播唤醒→重连→自动快门→自动休眠
- [ ] 录像切换（先切视频模式再开始录像）
- [ ] Insta360 遥控模式（用户尚未提供影石型号/测试结果）
- [ ] GPS 注入后相机照片是否带 GPS
- [ ] 轨迹页显示（v1.8 修复后未再反馈）
- [ ] USB 导入
- [ ] 多手机协同（需两台手机）

**已明确不做 / 受协议限制**：
- 蓝牙传照片（DJI R SDK 无文件传输指令）；WiFi 传照片需逆向 Mimo protobuf（未排期）
- 互传联盟无公开 SDK，只能系统分享面板调起
- 影石唤醒关机相机（缺各机型 iBeacon 魔数）

**可能的后续方向**：GPS 10Hz 推送（官方建议）、拍摄中状态订阅联动、1D06 新协议解析、批量固件屏蔽升级确认（fw_version=0 已做）。

## 10. 新会话续接指南

1. 把本文件全文发给 AI 助手，或让它读取 `docs/PROJECT_CONTEXT.md`
2. 说明本次要做什么（新功能 / 修 bug / 用户反馈转发）
3. 构建验证按 §8 的命令与坑执行；发布流程 = 版本号+1 → `git push` → 打 tag → GitHub Release 附 APK（凭据在 Windows 凭据管理器）
4. 用户偏好备忘：中文交流；原生 Kotlin（已确认过技术栈）；功能行为必须可预期（反感"意外拍照"类副作用）；反馈问题时常一句话描述，需要主动追问现象/提示文字/型号
