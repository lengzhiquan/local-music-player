# 本地音乐

简体中文的本地音乐播放器，版本 1.4.0（versionCode 7）。没有广告，没有账号。支持 mp3、flac、m4a/aac、ogg、wav。播放列表通过系统文件选择添加，可在后台播放。

## 功能

- 曲库可按歌名或歌手的拼音排序，也可搜索歌名、歌手或文件名。拉丁字母不区分大小写，数字按数值比较。
- 播放队列可以点某一首从开头播放，也可以上移、下移、移出。移出队列不会删除音乐文件。
- 切换重复、随机，或调整非当前曲目时，不重新准备当前这首，播放进度保持。
- 动态范围压缩在曲库页和播放页进入。默认关闭。六个参数是阈值 Threshold、压缩比 Ratio、启动时间 Attack、释放时间 Release、拐点软硬 Knee、补偿增益 Make-up Gain。关闭并稳定后，PCM 原样通过。

歌词先读文件内嵌，再读同名 `.lrc`，需要时再手动向 LRCLIB 查询。能量随机在本机抽样分析，不上传音频。

## 环境与构建

需要 JDK 17 和 Android SDK（compileSdk 35，build-tools 35.0.0）。在本机 `local.properties` 里写 `sdk.dir`，这个文件已被忽略。

```bash
./gradlew :app:testDebugUnitTest
./gradlew :app:assembleRelease
```

## 1.4.0 压缩器

压缩在 16-bit PCM 上进行，没有前瞻。Media3 的浮点输出会跳过这条处理链，因此发布配置关闭了浮点输出。关闭压缩并完成大约 10 毫秒淡出后，处理块按字节原样送出，满幅保护只作用于已开启的处理结果。

## 签名

仓库里没有密钥库，也没有口令。覆盖安装必须使用当初安装该应用的同一把密钥。

1. 把密钥库放在本机，不要提交。
2. 复制 `signing/keystore.properties.example` 为 `signing/keystore.properties`。
3. 填入本机的 `storeFile`、`storePassword`、`keyAlias`、`keyPassword`。

有这个文件时，release 用它签名。没有时，release 不签名，debug 仍使用 Android 默认调试签名。`signing/keystore.properties` 和任何 `.keystore` / `.jks` 都在 `.gitignore` 中。
