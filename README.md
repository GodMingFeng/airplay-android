# 峰神AirPlay

在 Android 手机、平板和电视盒子上运行的 AirPlay 镜像接收端。启动后设备会通过 Bonjour（mDNS）出现在 iPhone / iPad / Mac 的「屏幕镜像」列表里，选中即可把画面和声音投到 Android 屏幕上。

## 功能

- **屏幕镜像**：接收 H.264 视频流，交由 `MediaCodec` 硬件解码后渲染到 `SurfaceView`
- **同步音频**：解密并解码 AAC-ELD 音频，通过 `AudioTrack` 播放
- **FairPlay 配对**：完成 iOS 设备要求的配对与流密钥协商，无需在发送端做任何设置
- **自动命名**：广播的名称取自系统设备名（设置里的设备名 → 蓝牙名 → 机型），同一网络下多台设备不会重名
- **画面比例**：按视频原始宽高比做 letterbox，发送端切换分辨率或横竖屏时画面不会被拉伸
- **Android TV 支持**：注册了 Leanback 启动入口并带有 TV Banner，可直接在 TV 桌面启动；触摸屏等硬件需求均为可选，手机与电视盒子共用一个 APK
- **遥控器友好**：无操作时状态栏自动隐藏，按任意遥控器按键或触摸屏幕重新唤出

## 环境要求

| 项目 | 版本 |
| --- | --- |
| Android | 8.0 及以上（minSdk 26） |
| compileSdk / targetSdk | 33 |
| JDK | 11 |
| Gradle | 8.4（随 wrapper 提供） |
| Android Gradle Plugin | 8.1.4 |

## 构建

```bash
git clone git@github.com:GodMingFeng/airplay-android.git
cd airplay-android
./gradlew assembleDebug
```

产物位于 `app/build/outputs/apk/debug/`。安装到已连接的设备：

```bash
./gradlew installDebug
```

> `local.properties` 中的 `sdk.dir` 需指向本机的 Android SDK 路径。

## 使用

1. 确保 Android 设备与 iPhone / Mac 处于**同一局域网**，且路由器未拦截 mDNS 组播
2. 打开本应用，界面会显示设备名与本机 IP，服务随即自动启动
3. 在 iOS 的控制中心选择「屏幕镜像」，或在 macOS 的控制中心选择「屏幕镜像」，从列表中选中该设备
4. 点击「停止服务」可主动下线

镜像需要把画面解码到本应用的 Surface 上，因此**应用退到后台时接收端会停止广播**，设备随之从投屏列表中消失。回到应用即自动恢复。

## 工作原理

```
iPhone ──mDNS 发现──▶ jmDNS 广播 _airplay._tcp:7000 / _raop._tcp:5000
       ──RTSP 控制──▶ RtspControlServer (Netty, 端口 5000)
                        ├─ 配对与 FairPlay 密钥协商
                        ├─ SETUP 后按需拉起数据通道
                        ▼
       ──镜像数据──▶ MirroringReceiver ──解密──▶ H.264 ──▶ MediaCodec ──▶ SurfaceView
       ──音频数据──▶ AudioReceiver ──AES-128-CBC 解密──▶ AAC-ELD ──▶ AudioTrack
```

| 组件 | 职责 |
| --- | --- |
| `AirPlayService` | 前台服务，持有 multicast / Wi-Fi / wake 锁，管理服务器生命周期 |
| `AirPlayServer` | 组合 Bonjour 广播与 RTSP 控制服务 |
| `RtspControlServer` | 基于 Netty 处理 RTSP 交互，协商流参数并启动收流线程 |
| `MirroringReceiver` | 接收并解密镜像视频流，还原 SPS/PPS 与帧数据 |
| `AudioReceiver` | 接收 RTP 音频，解密后解码 AAC-ELD 为 PCM |
| `VideoHolder` | 持有 Surface 与解码器，衔接网络线程与渲染 |
| `jap2lib` | 配对、FairPlay、RTSP 报文等协议实现 |

服务运行在固定端口：AirPlay `7000`、AirTunes/RTSP `5000`。

设备在 mDNS 中的标识（伪 MAC 地址）由 `ANDROID_ID` 经 SHA-256 派生而来 —— 重启后保持不变，不同安装之间互不相同，iOS 借此把 `_airplay` 与 `_raop` 两条记录识别为同一台接收端。

## 已知限制

- 仅实现镜像（Mirroring）通道，不支持 AirPlay 视频串流（`_airplay` 的 video URL 播放模式）
- 应用必须处于前台可见状态，无法真正后台常驻接收
- 依赖局域网组播，跨网段、开启 AP 隔离或客户端隔离的网络下无法被发现

## 致谢

协议实现部分移植自 [serezhka/java-airplay-lib](https://github.com/serezhka/java-airplay-lib)，位于 `com.github.serezhka.jap2lib` 包下。
