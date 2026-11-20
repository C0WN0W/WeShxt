package com.cownow.weshit

import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.result.MethodData
import java.lang.reflect.Method

/**
 * 本地防撤回。
 *
 * 默认 EDIT_CONTENT = true：
 *   整个撤回方法跳过（消息保留），再把这条消息的 content 改成
 *   "原内容\n\n(已撤回)"。不构造 MsgInfo。
 *
 * EDIT_CONTENT = false：
 *   旧方案：拦截后用 [TipInserter] 插入 type=10000 灰条。
 */
object AntiRevoke {

    private const val EDIT_CONTENT = true

    private const val MARKER = "doRevokeMsg xmlSrvMsgId=%d talker=%s isGet=%s"
    private const val MARKER_PREFIX = "doRevokeMsg xmlSrvMsgId="

    fun install(classLoader: ClassLoader, apkPath: String) {
        val start = System.currentTimeMillis()
        RevokeGuard.install(classLoader)
        UiRefresh.install()
        try {
            DexScanner.withBridge(apkPath) { bridge ->
                val targets = findTargets(bridge)
                if (targets.isEmpty()) {
                    XposedBridge.log("[WeShit] hook point not found, marker=$MARKER")
                    return@withBridge
                }

                val tip = if (EDIT_CONTENT) null else TipInserter.resolve(bridge, classLoader, targets.first())
                val methods = DexScanner.toReflectMethods(targets, classLoader)
                var hooked = 0
                for (method in methods) {
                    if (hook(method, tip)) hooked++
                }
                XposedBridge.log(
                    "[WeShit] ready: $hooked/${methods.size} method(s), " +
                        "edit=$EDIT_CONTENT, ${System.currentTimeMillis() - start} ms"
                )
            }
        } catch (t: Throwable) {
            XposedBridge.log("[WeShit] dex scan failed: $t")
        }
    }

    private fun findTargets(bridge: DexKitBridge): List<MethodData> {
        val exact = DexScanner.findMethods(bridge, MARKER)
        if (exact.isNotEmpty()) return exact
        XposedBridge.log("[WeShit] exact marker miss, retry with contains")
        return DexScanner.findMethods(bridge, MARKER_PREFIX, exact = false)
    }

    private fun hook(method: Method, tip: TipInserter.Session?): Boolean {
        return try {
            XposedBridge.hookMethod(method, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val args = param.args ?: return
                    val nativeText = args.filterIsInstance<String>()
                        .firstOrNull { it.contains(TipText.MARK) }

                    // 自己撤回：文案以"你"开头时放行，保留微信原生行为
                    if (TipText.isSelfRevoke(nativeText)) return

                    val talker = Talker.find(args)

                    if (EDIT_CONTENT) {
                        val serverId = args.filterIsInstance<Long>().firstOrNull { it > 0 }
                        block(param)
                        if (serverId == null) {
                            XposedBridge.log("[WeShit] edit skipped: no serverId in args")
                            return
                        }
                        XposedBridge.log("[WeShit] blocked revoke: serverId=$serverId talker=$talker")
                        Thread({
                            try {
                                Thread.sleep(300)
                                when (RevokeGuard.appendContent(serverId, talker, "\n\n(已撤回)")) {
                                    RevokeGuard.R_TEXT -> UiRefresh.refreshChat()
                                    RevokeGuard.R_NON_TEXT ->
                                        UiRefresh.toast("对方撤回了一条消息（已撤回）")
                                }
                            } catch (t: Throwable) {
                                XposedBridge.log("[WeShit] edit failed: $t")
                            }
                        }, "WeShit-Edit").start()
                        return
                    }

                    tip?.let { session ->
                        args.firstOrNull { session.msgClass.isInstance(it) }?.let { session.tryProbe(it) }
                    }
                    block(param)
                    tip?.let { TipInserter.schedule(it, talker, nativeText) }
                }
            })
            XposedBridge.log("[WeShit] hooked ${method.declaringClass.name}#${method.name}")
            XposedBridge.log(
                "[WeShit] revoke params: " + method.parameterTypes.joinToString(",") { it.name }
            )
            true
        } catch (t: Throwable) {
            XposedBridge.log("[WeShit] hook failed on ${method.declaringClass.name}#${method.name}: $t")
            false
        }
    }

    private fun block(param: XC_MethodHook.MethodHookParam) {
        val type = (param.method as? Method)?.returnType ?: return
        param.result = if (type == Void.TYPE || !type.isPrimitive) null else zeroOf(type)
    }

    private fun zeroOf(type: Class<*>): Any = when (type) {
        java.lang.Boolean.TYPE -> false
        java.lang.Byte.TYPE -> 0.toByte()
        java.lang.Character.TYPE -> '\u0000'
        java.lang.Short.TYPE -> 0.toShort()
        java.lang.Integer.TYPE -> 0
        java.lang.Long.TYPE -> 0L
        java.lang.Float.TYPE -> 0f
        java.lang.Double.TYPE -> 0.0
        else -> 0
    }
}
