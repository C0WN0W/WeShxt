package com.cownow.weshit

import android.util.Log
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.callbacks.XC_LoadPackage

/**
 * 模块入口：LSPosed 依据 assets/xposed_init 里的类名反射实例化本类。
 * 只处理微信主进程，且在后台线程做 dex 检索，避免拖慢微信冷启动。
 */
class Entry : IXposedHookLoadPackage {

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName != WECHAT_PACKAGE) return
        // 撤回流程在主进程；push/appbrand 进程不重复挂，省一次 dex 解析
        val process = lpparam.processName
        if (process != null && process != WECHAT_PACKAGE) return

        log("loading in ${process ?: WECHAT_PACKAGE}")
        Thread({
            try {
                AntiRevoke.install(lpparam.classLoader, lpparam.appInfo.sourceDir)
            } catch (t: Throwable) {
                log("install failed:\n${Log.getStackTraceString(t)}")
            }
        }, "WeShit-Init").start()
    }

    private fun log(msg: String) = XposedBridge.log("[WeShit] $msg")

    companion object {
        const val WECHAT_PACKAGE = "com.tencent.mm"
    }
}
