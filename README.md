# 保存助手

把分享链接里的作品**原样存到自己手机上**：抖音（含图文、多图、实况图）与微信视频号。
提供**保存**与**传输**两种模式——保存到相册，或经局域网直传到电脑（不经过任何第三方服务器）。

> 本项目只用于**备份你自己有权保存的内容**。请阅读文末的[使用须知](#使用须知)。

## 功能

### 保存

- 粘一条分享链接即可解析，支持抖音视频、图文、多图、实况图（动图），也支持微信视频号。
- **保存完整音乐**：直接落地平台 CDN 上的原始音频文件，不重新编码，音质与源文件一致；
  格式按文件头字节判定（mp3 / m4a / flac / wav / ogg / aac），写入系统音乐库并播报时长。
- **可选把标题烧进画面**（左下角），或把作者封面写进 MP4 元数据（`moov/udta/meta/ilst/covr`），
  不重新编码，相册缩略图就是作者那张封面。
- **可选保存封面**、可选包含贴纸；有坐标时按原位置合成。
- 长视频流式写入相册，不把整段文件塞进内存。
- 自动防重复保存；最近保存记录保留一个月。

### 传输（手机 → 电脑，仅局域网）

- 手机与电脑在同一 Wi-Fi 下，用 UDP 广播自动发现电脑，也可手动填 `IP:端口`。
- 勾选手机里的视频 / 图片 / 音频，带进度地传到电脑。
- 电脑端是一个零依赖的 Python 小工作台（`pc-tool/`），浏览器里看进度、看接收到的文件。
- **数据只在局域网内流动，不经过外网。**

## 环境要求

| 项目 | 版本 |
|---|---|
| Android | 10（API 29）及以上 |
| 编译 SDK | 36 |
| JDK | 17 |
| Gradle | 见 `gradle/wrapper/gradle-wrapper.properties` |

## 构建

```bash
# 1) 告诉 Gradle 你的 Android SDK 在哪（不要提交这个文件）
echo "sdk.dir=/path/to/android-sdk" > local.properties

# 2) 编译
./gradlew :app:assembleDebug        # 调试包
./gradlew :app:assembleRelease      # 发布包（需要自己的签名，见下）
```

### 关于签名

`app/build.gradle` 从环境变量读取发布签名，仓库里**不含任何密钥**：

```bash
export VIDEO_SAVER_KEYSTORE=/path/to/your.jks
export VIDEO_SAVER_STORE_PASSWORD=...
export VIDEO_SAVER_KEY_ALIAS=...
export VIDEO_SAVER_KEY_PASSWORD=...
```

自己打包时请生成**自己的** keystore：

```bash
keytool -genkeypair -v -keystore release-signing.jks -alias mykey \
        -keyalg RSA -keysize 3072 -validity 10000
```

### 两个可选模块

- `editor/` —— 独立的视频剪辑测试版（包名 `com.zgtools.videoeditor`，可与主应用同时安装）。
- `pc-tool/` —— 电脑端传输工作台，纯 Python 标准库，无第三方依赖：

  ```bash
  cd pc-tool
  python server.py        # 然后浏览器打开 http://127.0.0.1:18765/
  ```

  > 首次在 Windows 上运行会被防火墙拦截。**防火墙的入站允许规则是按程序路径匹配的**：
  > 你用 `python.exe` 放行过，换成 `pythonw.exe` 启动就还是不通（反之亦然）。
  > 手机连不上时先查这一条。

## 微信视频号需要一次登录态

视频号的作品地址只在登录态下下发，而应用无法代替你登录。所以有两种方式：

1. **在应用内粘贴一次**（推荐）：按界面里的「怎么获取」指引，从浏览器复制一次请求头贴进去。
   凭据只保存在设备本地的 `SharedPreferences`，不会上传到任何地方。
2. 自行构建私有版本时，把 `BuiltInChannelCredential.JSON` 换成你自己抓的请求头 JSON。

**本仓库中的 `BuiltInChannelCredential.java` 是空的占位实现**（`JSON = "{}"`），不含任何凭据。
应用会据此判定「未配置」，正常提示你去粘贴，不影响抖音相关功能。

## 已知限制

- 平台页面结构与字段会变，**解析可能随平台更新而暂时失效**。
- 视频号登录态**会过期**，失效后重新粘贴即可。
- 抖音图文里「标记 → 自定义」生成的小圆点文字气泡属于 App 专属交互层，各条数据通道都不下发其文字与坐标，
  纯免登录客户端取不到，本项目不做这个功能（只静默保存无标记原图）。
- 部分机型相册的媒体查询范围不同，可能影响传输页的列表内容。

## 第三方代码

`DouyinSign.java` 中的 `a_bogus` 签名实现移植自
[Chenwenwen1007/WeChat-ShuiYin](https://github.com/Chenwenwen1007/WeChat-ShuiYin)（MIT License）。
完整声明见 [`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md)。

## 使用须知

- 本项目**仅供个人备份**用途。请只保存你自己发布的内容，或已获得作者授权、以及法律允许下载的内容。
- **不要**用它批量抓取、二次分发或商业利用他人作品。
- 下载平台内容可能违反相应平台的服务条款，**使用风险由使用者自行承担**。
  公开托管此类工具也可能收到平台的删除请求。
- 请遵守你所在地区的法律法规与平台规则。

## License

[MIT](LICENSE)

## 说明

本仓库为公开发布版，**已移除全部私密材料**：不含签名密钥、不含任何账号凭据、不含内部开发记录。
