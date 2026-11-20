# We泄 ♂

极简微信防撤回模块，微X风格提示 `xxx撤回了一条消息 (已阻止)`

## 构建 / 安装

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

LSPosed 启用模块、作用域勾选微信，强停微信后重开。查看日志：

```powershell
adb logcat -s LSPosed-Bridge:* | Select-String "WeShit"
```

## 说明

- 防撤回仅对本地聊天记录生效；重新登录/更换设备/清除缓存后消息仍会消失。
- 自己撤回不拦截。
- 已知bug: 部分微信版本消息保留，无提示；排查见日志 `tip resolved` / `tip disabled`。

## 参考与致谢

- [LSPosed](https://github.com/LSPosed/LSPosed)
- [DexKit](https://github.com/LuckyPray/DexKit)
- [HdShare/WAuxiliary_Public](https://github.com/HdShare/WAuxiliary_Public)
- [yylsping/wechat-anti-recall](https://github.com/yylsping/wechat-anti-recall)
- [HighCapable/YukiHookAPI](https://github.com/HighCapable/YukiHookAPI)、[KavaRef](https://github.com/HighCapable/KavaRef)
