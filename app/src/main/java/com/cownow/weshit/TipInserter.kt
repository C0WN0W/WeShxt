package com.cownow.weshit

import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.result.MethodData
import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * 在会话里插入一条 type = 10000 的系统消息（微信原生的灰色居中提示条）。
 *
 * 定位思路：
 * 1. 撤回方法内部一定会调用"把消息写进数据库"的方法，返回 long（新消息的 localId）；
 *    从 DexKit 的 invokes 里挑 `long xxx(MsgInfo)` 即得到插入方法。
 * 2. 该方法的参数类型就是 MsgInfo（类名被混淆，但 getter 名稳定）。
 * 3. 不依赖 setter（新版 setter 也被混淆）：用 getter 的 `usingFields` 反推出它读的字段，
 *    直接反射写字段；setter 只作为兜底。
 *
 * 插入方法可能是静态的（新版），也可能是实例方法（老版）。实例方法在第一次自然调用时
 * 用 Hook 缓存 receiver，后续复用。
 */
object TipInserter {

    private const val TYPE_SYSTEM = 10000

    @Volatile
    private var storage: Any? = null

    /** 同一会话同一文案 3 秒内只插一次，防止多重重载各插一条 */
    private val recent = java.util.concurrent.ConcurrentHashMap<String, Long>()

    class Spec(
        val ctor: Constructor<*>,
        val contentField: Field?,
        val talkerField: Field?,
        val typeField: Field?,
        val createTimeField: Field?,
        val isSendField: Field?,
        val setContent: Method?,
        val setTalker: Method?,
        val setType: Method?,
        val setCreateTime: Method?,
        val setIsSend: Method?,
        val insert: Method,
        val insertStatic: Boolean,
    ) {
        fun insert(talker: String, text: String): Long? {
            val receiver = if (insertStatic) null else storage
            if (!insertStatic && receiver == null) {
                XposedBridge.log("[WeShit] tip skipped: storage instance not captured yet")
                return null
            }
            val msg = ctor.newInstance()
            writeText(msg, contentField, setContent, text)
            writeText(msg, talkerField, setTalker, talker)
            writeInt(msg, typeField, setType, TYPE_SYSTEM)
            writeLong(msg, createTimeField, setCreateTime, System.currentTimeMillis())
            writeInt(msg, isSendField, setIsSend, 0)
            val id = insert.invoke(receiver, msg)
            return (id as? Number)?.toLong()
        }

        private fun writeText(msg: Any, field: Field?, setter: Method?, value: String) {
            if (field != null) field.set(msg, value) else setter?.invoke(msg, value)
        }

        private fun writeInt(msg: Any, field: Field?, setter: Method?, value: Int) {
            if (field != null) field.setInt(msg, value) else setter?.invoke(msg, value)
        }

        private fun writeLong(msg: Any, field: Field?, setter: Method?, value: Long) {
            if (field != null) field.setLong(msg, value) else setter?.invoke(msg, value)
        }
    }

    fun resolve(bridge: DexKitBridge, classLoader: ClassLoader, revoke: MethodData?): Spec? {
        if (revoke == null) return null

        val insertData = revoke.invokes.firstOrNull { m ->
            m.returnTypeName == "long" && m.paramCount == 1
        } ?: run {
            XposedBridge.log("[WeShit] tip disabled: no long-returning insert call inside revoke method")
            return null
        }

        val msgTypeName = insertData.paramTypeNames.firstOrNull() ?: return null
        val msgClass = runCatching { Class.forName(msgTypeName, false, classLoader) }.getOrNull()
        if (msgClass == null) {
            XposedBridge.log("[WeShit] tip disabled: cannot load $msgTypeName")
            return null
        }
        val methods = msgClass.declaredMethods
        // getType 在各版本都保留；内容可能只有 setter 可用，后面再判
        if (methods.none { it.name == "getType" }) {
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
        val insertStatic = Modifier.isStatic(insertData.modifiers)
        if (!insertStatic) {
            // 第一次自然插入（收/发任意消息）时缓存 storage 实例
            runCatching {
                XposedBridge.hookMethod(insert, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        param.thisObject?.let { storage = it }
                    }
                })
            }.onFailure {
                XposedBridge.log("[WeShit] tip disabled: cannot hook insert: $it")
                return null
            }
        }

        val contentField = fieldOf(bridge, msgClass, "getContent", "java.lang.String")
        val talkerField = fieldOf(bridge, msgClass, "getTalker", "java.lang.String")
        val typeField = fieldOf(bridge, msgClass, "getType", "int")
        val createTimeField = fieldOf(bridge, msgClass, "getCreateTime", "long")

        val setContent = setter(msgClass, "setContent", String::class.java)
        val setTalker = setter(msgClass, "setTalker", String::class.java)
        val setType = setter(msgClass, "setType", Int::class.javaPrimitiveType!!)
        val setCreateTime = setter(msgClass, "setCreateTime", Long::class.javaPrimitiveType!!)
        val setIsSend = setter(msgClass, "setIsSend", Int::class.javaPrimitiveType!!)

        XposedBridge.log(
            "[WeShit] tip resolved: msg=${msgClass.name} insert=${insert.name} static=$insertStatic " +
                "contentField=${contentField?.name} talkerField=${talkerField?.name} " +
                "setContent=${setContent != null} setTalker=${setTalker != null}"
        )

        if ((contentField == null && setContent == null) || (talkerField == null && setTalker == null)) {
            XposedBridge.log("[WeShit] tip disabled: cannot address content/talker")
            return null
        }

        return Spec(
            ctor, contentField, talkerField, typeField, createTimeField, null,
            setContent, setTalker, setType, setCreateTime, setIsSend, insert, insertStatic
        )
    }

    /**
     * 反推 getter 读的字段：getContent() 只会读"内容"字段，字段名被混淆也没关系。
     */
    private fun fieldOf(bridge: DexKitBridge, cls: Class<*>, getterName: String, typeName: String): Field? {
        val getter = bridge.findMethod {
            matcher {
                declaredClass(cls.name)
                name = getterName
            }
        }.firstOrNull() ?: return null
        val field = getter.usingFields
            .map { it.field }
            .firstOrNull { it.typeName == typeName } ?: return null
        return runCatching { cls.getDeclaredField(field.name).apply { isAccessible = true } }.getOrNull()
    }

    private fun setter(cls: Class<*>, name: String, vararg params: Class<*>): Method? =
        runCatching { cls.getDeclaredMethod(name, *params).apply { isAccessible = true } }.getOrNull()
            ?: runCatching { cls.getMethod(name, *params).apply { isAccessible = true } }.getOrNull()

    /** 撤回回调里插库会拖慢原流程，延迟到调用栈退出后执行 */
    fun schedule(spec: Spec, talker: String?, nativeText: String?) {
        if (talker == null) {
            XposedBridge.log("[WeShit] tip skipped: talker not found in args")
            return
        }
        val text = TipText.build(nativeText)
        if (!allow(talker, text)) return
        Thread({
            try {
                Thread.sleep(400)
                val id = spec.insert(talker, text)
                XposedBridge.log("[WeShit] tip inserted: localId=$id talker=$talker text=$text")
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
