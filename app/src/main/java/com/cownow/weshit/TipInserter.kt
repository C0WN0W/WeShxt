package com.cownow.weshit

import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.result.MethodData
import java.lang.reflect.Constructor
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 在会话里插入一条 type = 10000 的系统消息（微信原生的灰色居中提示条）。
 *
 */
object TipInserter {

    private const val TYPE_SYSTEM = 10000

    /** 同一会话同一文案 3 秒内只插一次，防止多重重载各插一条 */
    private val recent = ConcurrentHashMap<String, Long>()

    class Session(
        val msgClass: Class<*>,
        val ctor: Constructor<*>,
        val insert: Method,
        val insertStatic: Boolean,
        val insertParamCount: Int,
        val msgIdGetter: Method?,
    ) {
        @Volatile
        var spec: Spec? = null
            private set

        @Volatile
        var storage: Any? = null
            private set

        private val probing = AtomicBoolean(false)
        private val probed = AtomicBoolean(false)

        fun onInsert(param: XC_MethodHook.MethodHookParam) {
            param.thisObject?.let { storage = it }
            param.args?.firstOrNull()?.let { tryProbe(it) }
        }

        /** 任意来源的 MsgInfo 样本都可以拿来反推 getter/setter */
        fun tryProbe(sample: Any) {
            if (probed.get() || !probing.compareAndSet(false, true)) return
            try {
                spec = Probe.build(msgClass, sample)
                probed.set(spec != null)
            } catch (t: Throwable) {
                XposedBridge.log("[WeShit] tip probe failed: $t")
            } finally {
                probing.set(false)
            }
        }

        fun insert(talker: String, text: String): Long? {
            val s = spec ?: run {
                XposedBridge.log("[WeShit] tip skipped: no MsgInfo sample yet")
                return null
            }
            val receiver = if (insertStatic) null else storage
            if (!insertStatic && receiver == null) {
                XposedBridge.log("[WeShit] tip skipped: storage instance not captured yet")
                return null
            }
            val msg = ctor.newInstance()
            s.setContent.invoke(msg, text)
            s.setTalker.invoke(msg, talker)
            s.setType?.invoke(msg, TYPE_SYSTEM)
            s.setCreateTime?.invoke(msg, System.currentTimeMillis())
            val args = if (insertParamCount >= 2) arrayOf<Any?>(msg, false) else arrayOf<Any?>(msg)
            val returned = insert.invoke(receiver, *args)
            val returnId = (returned as? Number)?.toLong()
            // 有些版本的 insert 返回 void，此时看数据库是否回填了 localId
            val msgId = runCatching { msgIdGetter?.invoke(msg) as? Long }.getOrNull()
            XposedBridge.log("[WeShit] tip insert call: return=$returned msgId=$msgId")
            return msgId ?: returnId
        }
    }

    class Spec(
        val setContent: Method,
        val setTalker: Method,
        val setType: Method?,
        val setCreateTime: Method?,
    )

    private object Probe {

        fun build(cls: Class<*>, sample: Any): Spec? {
            val methods = allMethods(cls)
            val getters = methods.filter {
                it.parameterCount == 0 && it.returnType == String::class.java
            }
            if (getters.isEmpty()) {
                XposedBridge.log("[WeShit] probe: no String getter on ${cls.name}")
                return null
            }

            val values = LinkedHashMap<Method, String?>()
            for (g in getters) {
                values[g] = runCatching { g.isAccessible = true; g.invoke(sample) as? String }.getOrNull()
            }
            values.forEach { (g, v) ->
                XposedBridge.log("[WeShit] probe getter ${g.name} = ${v?.take(40)?.replace("\n", "\\n")}")
            }

            val talkerGetter = values.entries.firstOrNull { Talker.isTalker(it.value) }?.key
            if (talkerGetter == null) {
                XposedBridge.log("[WeShit] probe: talker getter not found")
                return null
            }

            val candidates = values.entries.filter {
                it.key != talkerGetter && !it.value.isNullOrEmpty()
            }
            val contentGetter = candidates
                .filter { !it.value!!.startsWith("<") }
                .maxByOrNull { it.value!!.length }?.key
                ?: candidates.maxByOrNull { it.value!!.length }?.key
            if (contentGetter == null) {
                XposedBridge.log("[WeShit] probe: content getter not found")
                return null
            }

            val setTalker = setterFor(cls, talkerGetter)
            val setContent = setterFor(cls, contentGetter)
            if (setTalker == null || setContent == null) {
                XposedBridge.log(
                    "[WeShit] probe: setter missing (talker=$setTalker content=$setContent)"
                )
                return null
            }

            val typeGetter = methods.firstOrNull {
                it.name == "getType" && it.parameterCount == 0 && it.returnType == Int::class.javaPrimitiveType
            }
            val setType = typeGetter?.let { intSetterFor(cls, it) }

            val timeGetter = methods.firstOrNull {
                it.name == "getCreateTime" && it.parameterCount == 0 && it.returnType == Long::class.javaPrimitiveType
            }
            val setCreateTime = timeGetter?.let { longSetterFor(cls, it) }

            XposedBridge.log(
                "[WeShit] tip probe ok: talker=${talkerGetter.name}->${setTalker.name} " +
                    "content=${contentGetter.name}->${setContent.name} " +
                    "type=${setType?.name} time=${setCreateTime?.name}"
            )
            return Spec(setContent, setTalker, setType, setCreateTime)
        }

        private fun allMethods(cls: Class<*>): List<Method> {
            val out = LinkedHashMap<String, Method>()
            var c: Class<*>? = cls
            while (c != null && c != Any::class.java) {
                for (m in c.declaredMethods) {
                    val key = m.name + "/" + m.parameterTypes.joinToString(",") { it.name }
                    out.putIfAbsent(key, m)
                    runCatching { m.isAccessible = true }
                }
                c = c.superclass
            }
            return out.values.toList()
        }

        /** 逐个试 (String)->void，写入标记后能被 getter 读回即命中 */
        private fun setterFor(cls: Class<*>, getter: Method): Method? {
            val marker = "WeShitProbe${System.nanoTime()}"
            val setters = allMethods(cls).filter {
                it.parameterCount == 1 &&
                    it.parameterTypes[0] == String::class.java &&
                    it.returnType == Void.TYPE
            }
            for (s in setters) {
                val probe = runCatching { cls.getDeclaredConstructor().newInstance() }.getOrNull() ?: return null
                runCatching { s.isAccessible = true; s.invoke(probe, marker) }
                val got = runCatching { getter.invoke(probe) as? String }.getOrNull()
                if (got == marker) return s
            }
            return null
        }

        private fun intSetterFor(cls: Class<*>, getter: Method): Method? {
            val marker = 0x5A5A
            val setters = allMethods(cls).filter {
                it.parameterCount == 1 &&
                    it.parameterTypes[0] == Int::class.javaPrimitiveType &&
                    it.returnType == Void.TYPE
            }
            for (s in setters) {
                val probe = runCatching { cls.getDeclaredConstructor().newInstance() }.getOrNull() ?: return null
                runCatching { s.isAccessible = true; s.invoke(probe, marker) }
                val got = runCatching { getter.invoke(probe) as? Int }.getOrNull()
                if (got == marker) return s
            }
            return null
        }

        private fun longSetterFor(cls: Class<*>, getter: Method): Method? {            val marker = 0x5A5A5A5AL
            val setters = allMethods(cls).filter {
                it.parameterCount == 1 &&
                    it.parameterTypes[0] == Long::class.javaPrimitiveType &&
                    it.returnType == Void.TYPE
            }
            for (s in setters) {
                val probe = runCatching { cls.getDeclaredConstructor().newInstance() }.getOrNull() ?: return null
                runCatching { s.isAccessible = true; s.invoke(probe, marker) }
                val got = runCatching { getter.invoke(probe) as? Long }.getOrNull()
                if (got == marker) return s
            }
            return null
        }
    }

    fun resolve(bridge: DexKitBridge, classLoader: ClassLoader, revoke: MethodData?): Session? {
        if (revoke == null) return null

        val insertData = findInsert(bridge, classLoader, revoke) ?: run {
            dumpInvokes(revoke)
            XposedBridge.log("[WeShit] tip disabled: insert method not found")
            return null
        }

        val msgTypeName = insertData.paramTypeNames.firstOrNull() ?: return null
        val msgClass = runCatching { Class.forName(msgTypeName, false, classLoader) }.getOrNull()
        if (msgClass == null) {
            XposedBridge.log("[WeShit] tip disabled: cannot load $msgTypeName")
            return null
        }
        if (msgClass.declaredMethods.none { it.name == "getType" }) {
            XposedBridge.log("[WeShit] tip disabled: $msgTypeName does not look like MsgInfo")
            return null
        }

        val ctor = runCatching {
            msgClass.getDeclaredConstructor().apply { isAccessible = true }
        }.getOrNull() ?: run {
            XposedBridge.log("[WeShit] tip disabled: no no-arg constructor on $msgTypeName")
            return null
        }

        val insert = runCatching { insertData.getMethodInstance(classLoader) }.getOrNull() ?: return null
        val insertStatic = java.lang.reflect.Modifier.isStatic(insertData.modifiers)

        val session = Session(
            msgClass, ctor, insert, insertStatic, insertData.paramCount,
            msgClass.methods.firstOrNull {
                it.name == "getMsgId" && it.parameterCount == 0 &&
                    it.returnType == Long::class.javaPrimitiveType
            }?.apply { isAccessible = true },
        )
        runCatching {
            XposedBridge.hookMethod(insert, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    session.onInsert(param)
                }
            })
        }.onFailure {
            XposedBridge.log("[WeShit] tip disabled: cannot hook insert: $it")
            return null
        }

        XposedBridge.log(
            "[WeShit] tip armed: msg=${msgClass.name} insert=${insert.name} static=$insertStatic " +
                "params=${insertData.paramCount} (waiting for a sample message)"
        )
        return session
    }

    // region 插入方法发现

    private fun findInsert(bridge: DexKitBridge, loader: ClassLoader, revoke: MethodData): MethodData? {
        revoke.invokes.firstOrNull { isCandidate(it, loader) }?.let {
            XposedBridge.log("[WeShit] insert via direct call: ${signature(it)}")
            return it
        }

        for (callee in revoke.invokes) {
            val hit = runCatching { callee.invokes.firstOrNull { isCandidate(it, loader) } }.getOrNull()
            if (hit != null) {
                XposedBridge.log("[WeShit] insert via depth-2 (${callee.name}): ${signature(hit)}")
                return hit
            }
        }

        val msgClass = findMsgInfoClass(bridge, loader) ?: run {
            XposedBridge.log("[WeShit] MsgInfo class not found")
            return null
        }
        XposedBridge.log("[WeShit] MsgInfo resolved: ${msgClass.name}")

        val longInsert = runCatching {
            bridge.findMethod {
                matcher {
                    paramTypes(msgClass.name)
                    returnType("long")
                }
            }.filter { it.paramCount in 1..2 }
                .sortedWith(
                    compareByDescending<MethodData> { it.name.equals("insert", ignoreCase = true) }
                        .thenByDescending { it.name.contains("insert", ignoreCase = true) }
                        .thenByDescending { it.className.startsWith("com.tencent.mm.storage") }
                        .thenByDescending { java.lang.reflect.Modifier.isStatic(it.modifiers) }
                        .thenBy { it.paramCount }
                )
                .firstOrNull()
        }.getOrNull()
        if (longInsert != null) {
            XposedBridge.log("[WeShit] insert via class lookup: ${signature(longInsert)}")
            return longInsert
        }

        val voidInsert = runCatching {
            bridge.findMethod {
                matcher {
                    paramTypes(msgClass.name)
                    returnType("void")
                }
            }.filter { it.paramCount in 1..2 && it.name.contains("insert", ignoreCase = true) }
                .firstOrNull()
        }.getOrNull()
        if (voidInsert != null) {
            XposedBridge.log("[WeShit] insert via void lookup: ${signature(voidInsert)}")
        }
        return voidInsert
    }

    private fun isCandidate(m: MethodData, loader: ClassLoader): Boolean {
        if (m.paramCount !in 1..2) return false
        if (m.returnTypeName != "long" && m.returnTypeName != "void") return false
        val firstName = m.paramTypeNames.firstOrNull() ?: return false
        return looksLikeMsgInfo(firstName, loader)
    }

    private fun looksLikeMsgInfo(typeName: String, loader: ClassLoader): Boolean {
        val cls = runCatching { Class.forName(typeName, false, loader) }.getOrNull() ?: return false
        val names = cls.declaredMethods.map { it.name }.toSet()
        return names.contains("getType") && (names.contains("getCreateTime") || names.contains("getMsgId"))
    }

    private fun findMsgInfoClass(bridge: DexKitBridge, loader: ClassLoader): Class<*>? {
        val data = runCatching {
            bridge.findClass {
                searchPackages("com.tencent.mm.storage")
                matcher {
                    methods {
                        add {
                            name = "getType"
                            returnType = "int"
                        }
                        add {
                            name = "getCreateTime"
                            returnType = "long"
                        }
                    }
                }
            }.firstOrNull()
        }.getOrNull() ?: return null
        return runCatching { data.getInstance(loader) }.getOrNull()
    }

    private fun signature(m: MethodData): String =
        "${m.returnTypeName} ${m.className}.${m.name}(${m.paramTypeNames.joinToString(",")})"

    private fun dumpInvokes(revoke: MethodData) {
        XposedBridge.log("[WeShit] revoke method: ${signature(revoke)}")
        val list = runCatching { revoke.invokes.take(25) }.getOrDefault(emptyList())
        XposedBridge.log("[WeShit] revoke invokes (${list.size}):")
        list.forEach { XposedBridge.log("[WeShit]   ${signature(it)}") }
    }

    // endregion

    /** 撤回回调里插库会拖慢原流程，延迟到调用栈退出后执行 */
    fun schedule(session: Session, talker: String?, nativeText: String?) {
        if (talker == null) {
            XposedBridge.log("[WeShit] tip skipped: talker not found in args")
            return
        }
        val text = TipText.build(nativeText)
        if (!allow(talker, text)) return
        Thread({
            try {
                Thread.sleep(400)
                if (session.spec == null) {
                    XposedBridge.log("[WeShit] tip skipped: spec not ready")
                    return@Thread
                }
                val id = session.insert(talker, text)
                XposedBridge.log("[WeShit] tip insert done: id=$id talker=$talker text=$text")
            } catch (t: Throwable) {
                XposedBridge.log("[WeShit] tip insert failed: $t")
            }
        }, "WeShit-Tip").start()
    }

    private fun allow(talker: String, text: String): Boolean {
        val now = System.currentTimeMillis()
        if (recent.size > 64) {
            recent.entries.removeIf { now - it.value > 10_000 }
        }
        val last = recent.put("$talker|$text", now)
        return last == null || now - last > 3_000
    }
}
