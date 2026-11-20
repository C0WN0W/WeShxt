package com.cownow.weshit

import android.database.Cursor
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.util.concurrent.atomic.AtomicInteger

/**
 * 数据库辅助：
 *   - 挂 WCDB / SQLCipher / 系统 SQLite 的写入口，顺手记下 message 表所在的 db 实例；
 *   - 撤回被拦下后，直接改原消息的 content（末尾加空行 + 标记）。
 *
 * 不构造 MsgInfo、不插新消息，只对已存在的行做一次 UPDATE。
 */
object RevokeGuard {

    @Volatile
    private var messageDb: Any? = null

    const val R_FAILED = 0
    const val R_TEXT = 1
    const val R_NON_TEXT = 2
    const val R_NOT_FOUND = 3

    private val logs = AtomicInteger(0)

    fun install(classLoader: ClassLoader) {
        listOf(
            "com.tencent.wcdb.database.SQLiteDatabase",
            "net.sqlcipher.database.SQLiteDatabase",
            "android.database.sqlite.SQLiteDatabase",
        ).forEach { hookDatabase(classLoader, it) }
        XposedBridge.log("[WeShit] db hooks installed")
    }

    private fun hookDatabase(classLoader: ClassLoader, className: String) {
        val clazz = runCatching { XposedHelpers.findClass(className, classLoader) }.getOrNull() ?: return

        runCatching {
            XposedBridge.hookAllMethods(clazz, "delete", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    remember(param.thisObject)
                    val table = param.args.getOrNull(0) as? String ?: return
                    if (isMessage(table)) remember(param.thisObject)
                }
            })
        }
        runCatching {
            XposedBridge.hookAllMethods(clazz, "update", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    remember(param.thisObject)
                    val table = param.args.getOrNull(0) as? String ?: return
                    if (isMessage(table)) remember(param.thisObject)
                }
            })
        }
        runCatching {
            XposedBridge.hookAllMethods(clazz, "execSQL", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    remember(param.thisObject)
                    val sql = param.args.getOrNull(0) as? String ?: return
                    if (sql.contains("message", ignoreCase = true)) remember(param.thisObject)
                }
            })
        }
    }

    private fun remember(db: Any?) {
        if (db == null || messageDb != null) return
        val path = runCatching {
            db.javaClass.getMethod("getPath").invoke(db) as? String
        }.getOrNull()
        if (path != null && path.contains("EnMicroMsg", ignoreCase = true)) {
            messageDb = db
            XposedBridge.log("[WeShit] message db captured: ${db.javaClass.name} ($path)")
        }
    }

    private fun isMessage(table: String) = table.equals("message", ignoreCase = true)

    /**
     * 给指定消息的 content 追加内容。
     * @param serverId 撤回 XML 里的 msgSvrId
     * @param talker   会话 ID
     * @return 更新后的 content；失败返回 null
     */
    fun appendContent(serverId: Long, talker: String?, suffix: String): Int {
        val db = messageDb ?: run {
            log("edit skipped: message db not captured yet")
            return R_FAILED
        }
        val args = if (talker != null) arrayOf(serverId.toString(), talker) else arrayOf(serverId.toString())
        val where = if (talker != null) "msgSvrId=? AND talker=?" else "msgSvrId=?"

        val old = queryRow(db, where, args)
        if (old == null) {
            log("edit skipped: message not found (msgSvrId=$serverId)")
            return R_NOT_FOUND
        }
        val (content, type) = old
        if (type != 1) {
            // 图片/视频/语音的 content 是 XML，直接追加会破坏解析
            log("edit skipped: non-text message type=$type (message kept)")
            return R_NON_TEXT
        }
        val updated = content + suffix
        val ok = execSql(
            db,
            "UPDATE message SET content=? WHERE $where",
            if (talker != null) arrayOf(updated, serverId.toString(), talker)
            else arrayOf(updated, serverId.toString()),
        )
        if (!ok) {
            log("edit failed: UPDATE returned false")
            return R_FAILED
        }
        log("edited msgSvrId=$serverId len=${content.length}->${updated.length}")
        return R_TEXT
    }

    private fun queryRow(db: Any, where: String, args: Array<String>): Pair<String, Int>? {
        return runCatching {
            val rawQuery = db.javaClass.methods.firstOrNull {
                it.name == "rawQuery" && it.parameterTypes.size == 2 &&
                    it.parameterTypes[0] == String::class.java && it.parameterTypes[1].isArray
            } ?: return null
            val cursor = rawQuery.invoke(db, "SELECT content,type FROM message WHERE $where", args) as? Cursor
                ?: return null
            cursor.use {
                if (!it.moveToFirst()) return null
                it.getString(0) to it.getInt(1)
            }
        }.getOrElse {
            log("query failed: $it")
            null
        }
    }

    private fun execSql(db: Any, sql: String, args: Array<String>): Boolean {
        return runCatching {
            val method = db.javaClass.methods.firstOrNull {
                it.name == "execSQL" && it.parameterTypes.size == 2 &&
                    it.parameterTypes[0] == String::class.java && it.parameterTypes[1].isArray
            } ?: return false
            method.invoke(db, sql, args)
            true
        }.getOrElse {
            log("execSQL failed: $it")
            false
        }
    }

    private fun log(msg: String) {
        if (logs.incrementAndGet() <= 12) XposedBridge.log("[WeShit] $msg")
    }
}
