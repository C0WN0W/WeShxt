package com.cownow.weshit

import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.result.MethodData
import java.lang.reflect.Method

/**
 * 本地防撤回 + 灰条提示。
 *
 * 原理：微信收到"对方撤回"后会调用一个处理撤回的方法（内部日志为
 * "doRevokeMsg xmlSrvMsgId=%d talker=%s isGet=%s"）。在该方法执行前直接
 * 返回，撤回流程就被跳过：本地消息不删除。
 *
 * 随后由 [TipInserter] 往会话里插入一条 type=10000 的系统消息，
 * 内容为 "xxx撤回了一条消息 (已阻止)"，即微信原生的灰色提示条。
 */
object AntiRevoke {

    /** 定位锚点：目标方法里唯一的一条日志串 */
    private const val MARKER = "doRevokeMsg xmlSrvMsgId=%d talker=%s isGet=%s"

    /** 兜底锚点：微信给日志加了/删了占位符时，前半段仍然不变 */
    private const val MARKER_PREFIX = "doRevokeMsg xmlSrvMsgId="

    fun install(classLoader: ClassLoader, apkPath: String) {
        val start = System.currentTimeMillis()
        try {
            DexScanner.withBridge(apkPath) { bridge ->
                val targets = findTargets(bridge)
                if (targets.isEmpty()) {
                    XposedBridge.log("[WeShit] hook point not found, marker=$MARKER")
                    return@withBridge
                }

                val tip = TipInserter.resolve(bridge, classLoader, targets.first())
                val methods = DexScanner.toReflectMethods(targets, classLoader)
                var hooked = 0
                for (method in methods) {
                    if (hook(method, tip)) hooked++
                }
                XposedBridge.log(
                    "[WeShit] ready: $hooked/${methods.size} method(s), " +
                        "tip=${tip != null}, ${System.currentTimeMillis() - start} ms"
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

    private fun hook(method: Method, tip: TipInserter.Spec?): Boolean {
        return try {
            XposedBridge.hookMethod(method, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val args = param.args ?: return
                    val nativeText = args.filterIsInstance<String>()
                        .firstOrNull { it.contains(TipText.MARK) }

                    // 自己撤回：文案以"你"开头时放行，保留微信原生行为
                    if (TipText.isSelfRevoke(nativeText)) return

                    val talker = Talker.find(args)

                    block(param)
                    tip?.let { TipInserter.schedule(it, talker, nativeText) }
                }
            })
            XposedBridge.log("[WeShit] hooked ${method.declaringClass.name}#${method.name}")
            true
        } catch (t: Throwable) {
            XposedBridge.log("[WeShit] hook failed on ${method.declaringClass.name}#${method.name}: $t")
            false
        }
    }

    /**
     * before 阶段写入返回值即等于"跳过原方法"。
     * void / 引用类型给 null；基本类型必须给对应零值，否则 Xposed 会抛类型错误。
     */
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
