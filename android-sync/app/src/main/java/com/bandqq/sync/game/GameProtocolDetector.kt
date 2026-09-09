package com.bandqq.sync.game

/**
 * 游戏邀请/组队检测：从群消息中识别常见组队关键词（简化重建版）。
 */
object GameProtocolDetector {
    private val patterns = listOf(
        Regex("(?:邀请|邀请你)(?:加入|加入群?)?(?:你)?(?:一起)?(?:玩|游戏)"),
        Regex("(?:组队|开黑|车队|上号|带飞)"),
        Regex("(?:房间号|房号)[::]?\\s*\\d{4,8}"),
        Regex("(?:联机|局域网|服务器)\\s*(?:地址|ip)?[::]?"),
        Regex("inivite|invite you to play", RegexOption.IGNORE_CASE)
    )

    fun detect(content: String): Boolean = patterns.any { it.containsMatchIn(content) }

    fun isGameInvite(content: String): Boolean = detect(content)
}
