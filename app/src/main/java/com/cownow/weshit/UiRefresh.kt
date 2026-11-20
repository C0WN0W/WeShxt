package com.cownow.weshit

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge

/**
 * 改完 DB 后让界面立刻反映：
 *   - 当前在聊天页 → recreate()，从 DB 重新加载消息；
 *   - 图片/语音这类无法改文案的 → Toast 提示一下。
 */
object UiRefresh {

    @Volatile
    private var current: Activity? = null

    @Volatile
    private var appContext: Context? = null

    private val main = Handler(Looper.getMainLooper())

    fun install() {
        runCatching {
            XposedBridge.hookAllMethods(Activity::class.java, "onResume", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val activity = param.thisObject as? Activity ?: return
                    current = activity
                    appContext = activity.applicationContext
                }
            })
            XposedBridge.hookAllMethods(Activity::class.java, "onPause", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (current === param.thisObject) current = null
                }
            })
        }.onFailure {
            XposedBridge.log("[WeShit] ui tracker failed: $it")
        }
    }

    /** 只在聊天页重建，避免把别的界面刷掉 */
    fun refreshChat() {
        val activity = current ?: return
        if (!activity.javaClass.name.contains("ChattingUI")) return
        main.post {
            runCatching { activity.recreate() }
                .onFailure { XposedBridge.log("[WeShit] recreate failed: $it") }
        }
        XposedBridge.log("[WeShit] chat ui recreated")
    }

    fun toast(message: String) {
        val context = appContext ?: return
        main.post {
            runCatching { Toast.makeText(context, message, Toast.LENGTH_SHORT).show() }
        }
    }
}
