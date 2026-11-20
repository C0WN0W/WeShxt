package com.cownow.weshit

import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.result.MethodData
import java.lang.reflect.Method

/**
 * 用 DexKit 在微信 APK 里按字符串定位方法。
 *
 * 微信的方法名每个版本都在混淆，但开发期写死的日志串不会变，
 * 所以拿日志串当"锚点"比硬编码方法名稳定得多。
 */
object DexScanner {

    init {
        // DexKit 的检索核心是 native 实现，必须先加载 .so
        System.loadLibrary("dexkit")
    }

    /** 打开一次 bridge，把"找撤回方法"和"找插入方法"放在同一次 APK 解析里完成 */
    fun <T> withBridge(apkPath: String, block: (DexKitBridge) -> T): T =
        DexKitBridge.create(apkPath).use(block)

    /**
     * @param exact true = 完全相等；false = 包含匹配（用于版本微调后的兜底）
     */
    fun findMethods(bridge: DexKitBridge, marker: String, exact: Boolean = true): List<MethodData> =
        bridge.findMethod {
            matcher {
                if (exact) {
                    // usingEqStrings 是"完全相等"，不是包含匹配
                    usingEqStrings(marker)
                } else {
                    usingStrings(marker)
                }
            }
        }.toList()

    fun toReflectMethods(data: List<MethodData>, classLoader: ClassLoader): List<Method> =
        data.mapNotNull { methodData ->
            runCatching { methodData.getMethodInstance(classLoader) }.getOrNull()
        }.distinctBy { method ->
            method.declaringClass.name + "#" + method.name +
                method.parameterTypes.joinToString(",") { it.name }
        }
}
