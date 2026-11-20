package com.cownow.weshit

/** 纯字符串逻辑，便于单元测试；不依赖 Xposed / 微信 */
object TipText {

    const val MARK = "撤回了一条消息"
    const val SUFFIX = " (已阻止)"
    const val DEFAULT = "对方撤回了一条消息"

    /**
     * 从微信原生文案里取出"xxx撤回了一条消息"，再拼上后缀。
     * 原生文案可能带额外说明（如"请升级版本查看"），只保留到标记处。
     */
    fun build(nativeText: String?): String {
        if (nativeText == null) return DEFAULT + SUFFIX
        val index = nativeText.indexOf(MARK)
        if (index < 0) return DEFAULT + SUFFIX
        return nativeText.substring(0, index + MARK.length) + SUFFIX
    }

    /** 自己撤回时微信文案以"你"开头，这种不拦，保留原生行为 */
    fun isSelfRevoke(nativeText: String?): Boolean =
        nativeText != null && nativeText.startsWith("你")
}

/** 会话 ID 识别：单聊 wxid、群聊 xxx@chatroom、公众号 gh_、文件传输助手 */
object Talker {

    private val PATTERN = Regex(
        "^(wxid_[A-Za-z0-9_-]+|\\d+@chatroom|gh_[A-Za-z0-9_-]+|filehelper|@placeholder_foldgroup)$"
    )

    fun find(args: Array<Any?>): String? =
        args.filterIsInstance<String>().firstOrNull { PATTERN.matches(it) }

    fun isTalker(value: String?): Boolean =
        value != null && PATTERN.matches(value)
}
