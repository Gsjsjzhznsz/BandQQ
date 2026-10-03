package io.github.gsjsjzhznsz.bandqq.onebot

import io.github.gsjsjzhznsz.bandqq.config.ConfigHolder
import io.github.gsjsjzhznsz.bandqq.sync.LogBus
import io.github.gsjsjzhznsz.bandqq.sync.LogLevel
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser

data class OneBotMessage(
    val messageType: String,  // "private" | "group"
    val targetId: String,     // group_id 或 user_id
    val senderId: String,
    val senderName: String,
    val content: String,
    val time: Long,
    val isSelf: Boolean = false,
    val messageId: String = "",  // OneBot message_id（供撤回定位；自发送链路可为空）
    val atMe: Boolean = false    // CQ:at 码指向自己或全体（手环端高亮展示，借鉴 Stapxs）
)

/** 消息撤回事件（friend_recall / group_recall）——借鉴 Stapxs-QQ-Lite-X */
data class OneBotRecall(
    val targetId: String,
    val messageId: String,
)

/**
 * 拍一拍（戳一戳）事件（v2.9.0）：OneBot v11 notice.notify.poke。
 * 群聊拍一拍：{notice_type:"notify", sub_type:"poke", group_id, user_id, target_id}
 * 私聊拍一拍：{notice_type:"notify", sub_type:"poke", user_id, target_id}（无 group_id）
 * 兼容旧协议端：notice_type="group_poke"/"friend_poke" 直接携带。
 * QQ 业务规则：私聊没有 @只有拍一拍，群聊才有 @ —— 两者是互补的强提醒通道。
 */
data class OneBotPoke(
    val chatType: String,   // "private" | "group"
    val targetId: String,   // 会话 ID：私聊=拍人者 QQ，群聊=群号
    val senderId: String,   // 拍人者 QQ
    val senderName: String, // 拍人者名称（notice 事件无 sender 对象时为 QQ 号，broker 层用联系人缓存解析）
    val pokeMe: Boolean     // target_id == self_id：拍的是我（拍别人不提醒）
)

/** 快捷回复：label 为手环按钮上的纯文本（已剥离 CQ 码），content 为实际发送内容（保留 CQ 码） */
data class QuickReply(val label: String, val content: String)

class OneBotParser {

    companion object {
        private val EMOJI = Regex("\\p{So}|\\p{Sk}|[\\x{2600}-\\x{27BF}\\x{2B00}-\\x{2BFF}\\x{1F000}-\\x{1FAFF}\\x{FE0F}\\x{200D}]")
        private val CQ_CODE = Regex("\\[CQ:[^\\]]*\\]")
        private val CQ_FACE = Regex("\\[CQ:face,id=(\\d+)[^\\]]*\\]")

        /**
         * QQ 系统表情 id → Unicode emoji（v2.6.0 表情支持）。
         * id→名称对照以 NapCat 从 QQ NT 客户端提取的官方数据为准（0=惊讶、13=呲牙、
         * 14=微笑…），名称→emoji 按语义映射；未收录的 id 回退为「[表情]」占位。
         * 映射后的 emoji 与文本内原有 emoji 一并透传到手环（Vela 原生表情渲染）。
         */
        private val FACE_EMOJI: Map<Int, String> = mapOf(
            0 to "😮",   // 惊讶
            1 to "😞",   // 撇嘴
            2 to "😍",   // 色
            3 to "😐",   // 发呆
            4 to "😎",   // 得意
            5 to "😢",   // 流泪
            6 to "😊",   // 害羞
            7 to "🤐",   // 闭嘴
            8 to "😴",   // 睡
            9 to "😭",   // 大哭
            10 to "😅",  // 尴尬
            11 to "😠",  // 发怒
            12 to "😜",  // 调皮
            13 to "😁",  // 呲牙
            14 to "🙂",  // 微笑
            15 to "🙁",  // 难过
            16 to "🕶",  // 酷
            18 to "🤯",  // 抓狂
            19 to "🤮",  // 吐
            20 to "🤭",  // 偷笑
            21 to "🥰",  // 可爱
            22 to "🙄",  // 白眼
            23 to "😤",  // 傲慢
            24 to "🤤",  // 饥饿
            25 to "😪",  // 困
            26 to "😨",  // 惊恐
            27 to "😓",  // 流汗
            28 to "😆",  // 憨笑
            29 to "😌",  // 悠闲
            30 to "💪",  // 奋斗
            31 to "🤬",  // 咒骂
            32 to "❓",   // 疑问
            33 to "🤫",  // 嘘
            34 to "😵",  // 晕
            35 to "😫",  // 折磨
            36 to "😩",  // 衰
            37 to "💀",  // 骷髅
            38 to "🔨",  // 敲打
            39 to "👋",  // 再见
            41 to "🥶",  // 发抖
            42 to "💘",  // 爱情
            43 to "🏃",  // 跳跳
            46 to "🐷",  // 猪头
            49 to "🤗",  // 拥抱
            53 to "🍰",  // 蛋糕
            55 to "💣",  // 炸弹
            56 to "🔪",  // 刀
            59 to "💩",  // 便便
            60 to "☕",  // 咖啡
            63 to "🌹",  // 玫瑰
            64 to "🥀",  // 凋谢
            66 to "❤",   // 爱心
            67 to "💔",  // 心碎
            74 to "☀",   // 太阳
            75 to "🌙",  // 月亮
            76 to "👍",  // 赞
            77 to "👎",  // 踩
            78 to "🤝",  // 握手
            79 to "✌",   // 胜利
            85 to "😘",  // 飞吻
            86 to "😡",  // 怄火
            89 to "🍉",  // 西瓜
            96 to "😰",  // 冷汗
            97 to "😥",  // 擦汗
            98 to "👃",  // 抖鼻
            99 to "👏",  // 鼓掌
            100 to "😬", // 糗大了
            101 to "😏", // 坏笑
            102 to "😤", // 左哼哼
            103 to "😤", // 右哼哼
            104 to "🥱", // 哈欠
            105 to "😒", // 鄙视
            106 to "🥺", // 委屈
            107 to "😢", // 快哭了
            108 to "😈", // 阴险
            109 to "😗", // 左亲亲
            110 to "😱", // 吓
            111 to "🙏", // 可怜
            112 to "🔪", // 菜刀
            114 to "🏀", // 篮球
            116 to "💖", // 示爱
            118 to "🙏", // 抱拳
            119 to "💋", // 勾引
            178 to "🤣", // 斜眼笑
            181 to "🤔", // 戳一戳
            182 to "😂", // 笑哭
            187 to "👻", // 幽灵
        )

        /** QQ face id → emoji，未收录回退「[表情]」；emojiNative=false 时整体降级占位 */
        fun faceEmoji(id: Int): String {
            if (!ConfigHolder.config.emojiNative) return "[表情]"
            return FACE_EMOJI[id] ?: "[表情]"
        }

        fun stripEmoji(s: String): String = s.replace(EMOJI, "")

        fun markEmoji(s: String): String = s.replace(EMOJI, "[表情]")

        /** 剥离 CQ 码得到纯文本（用于手环按钮标签，避免显示一堆格式代码） */
        fun stripCq(s: String): String = s.replace(CQ_CODE, "").trim()

        // ================= v2.14.0 智能自动渲染器 =================
        // 背景：AstrBot / 频道机器人 / 分享链路大量使用 json 卡片、markdown、合并转发等
        // 富消息段，旧渲染器一律降级「[其他]」，手环上只见占位符。本段把常见段型
        // 逐一提取出人话摘要，并加长度护栏防长文冲击手环渲染。

        private const val RENDER_MAX_LEN = 800

        /** OneBot v11 CQ 码反转义（&#91; &#93; &#44; &amp;） */
        fun cqUnescape(s: String): String = s
            .replace("&#91;", "[")
            .replace("&#93;", "]")
            .replace("&#44;", ",")
            .replace("&amp;", "&")

        private fun JsonObject?.str(k: String): String? =
            this?.get(k)?.takeIf { it.isJsonPrimitive }?.asString

        /** 渲染长度护栏：超长截断加省略号（手环 DOM/存储双保护） */
        private fun capLen(s: String): String =
            if (s.length > RENDER_MAX_LEN) s.take(RENDER_MAX_LEN) + "…" else s

        /**
         * JSON 卡片智能提取人话摘要（v2.14.0）。优先级：
         * meta.prompt（QQ 官方生成的「[分享]我看到...」）→ meta.{news,music,app,...}.title
         * （组合 describe/singer）→ 顶层 title/desc → config.desc → 兑底「[卡片消息]」。
         */
        fun jsonCardSummary(jsonStr: String): String {
            return try {
                val obj = JsonParser.parseString(jsonStr).asJsonObject
                val meta = obj.getAsJsonObject("meta")
                if (meta != null) {
                    meta.str("prompt")?.trim()?.takeIf { it.isNotBlank() }?.let { return it }
                    for (key in listOf("news", "music", "app", "software", "structured")) {
                        val m = meta.getAsJsonObject(key) ?: continue
                        val t = m.str("title")?.trim()?.takeIf { it.isNotBlank() } ?: continue
                        val d = m.str("describe") ?: m.str("singer") ?: m.str("from")
                        return "[卡片] " + t + (if (!d.isNullOrBlank() && d != t) " · $d" else "")
                    }
                }
                val t = obj.str("title")?.trim()?.takeIf { it.isNotBlank() }
                val d = obj.str("desc")?.trim() ?: obj.str("description")?.trim()
                if (t != null) return "[卡片] " + t + (if (!d.isNullOrBlank() && d != t) " · $d" else "")
                if (!d.isNullOrBlank()) return "[卡片] $d"
                val cfg = obj.getAsJsonObject("config")
                val cd = cfg?.str("desc")?.trim() ?: cfg?.str("from")?.trim()
                if (!cd.isNullOrBlank()) return "[卡片] $cd"
                "[卡片消息]"
            } catch (_: Exception) {
                "[卡片消息]"
            }
        }

        /** XML 卡片提取 title/brief（老版结构化消息：〈msg brief=..〉〈title〉..〈/title〉） */
        fun xmlCardSummary(xml: String): String {
            return try {
                val brief = Regex("brief=\"([^\"]+)\"").find(xml)?.groupValues?.get(1)
                val title = Regex("<title[^>]*>([\\s\\S]*?)</title>", RegexOption.IGNORE_CASE)
                    .find(xml)?.groupValues?.get(1)?.trim()
                val best = listOf(title, brief).firstOrNull { !it.isNullOrBlank() }
                if (best != null) "[卡片] " + best.take(60) else "[卡片消息]"
            } catch (_: Exception) {
                "[卡片消息]"
            }
        }

        /**
         * Markdown 降纯文本（v2.14.0，AstrBot 回复常用 markdown）：
         * 去标题#/加粗星号/斜体星号/行内码反引号/链接括号（保留锚文本）/图片→[图片]/列表符号→·，压平多余空行。
         */
        fun markdownToPlain(md: String): String {
            var s = md
            s = s.replace("```", "\n")
            s = s.replace(Regex("^#{1,6}\\s*", setOf(RegexOption.MULTILINE)), "")
            s = s.replace(Regex("!\\[([^\\]]*)\\]\\([^)]*\\)"), "[图片]")
            s = s.replace(Regex("\\[([^\\]]+)\\]\\([^)]*\\)"), "$1")
            s = s.replace(Regex("\\*\\*([^*]+)\\*\\*"), "$1")
            s = s.replace(Regex("__([^_]+)__"), "$1")
            s = s.replace(Regex("\\*([^*\\n]+)\\*"), "$1")
            s = s.replace(Regex("`([^`\n]+)`"), "$1")
            s = s.replace(Regex("^[ \\t]*[-*+]\\s+", setOf(RegexOption.MULTILINE)), "· ")
            s = s.replace(Regex("\n{3,}"), "\n\n")
            return s.trim()
        }

        /** 文件类段型带文件名渲染（文件名过长截 24 字符） */
        private fun fileLabel(name: String?, fallback: String): String {
            val n = name?.trim()?.takeIf { it.isNotBlank() } ?: return fallback
            return "$fallback ${if (n.length > 24) n.take(24) + "…" else n}"
        }

        /**
         * 将用户配置的快捷回复原文解析为 QuickReply 列表。
         * 标签 = 剥离 CQ 码 + 降级 emoji + 截短到 6 字（手环按钮宽度有限）；
         * 内容 = 保留原文（CQ 码原样发给 OneBot，保证表情等能正确发送）。
         */
        fun parseQuickReplies(rawList: List<String>): List<QuickReply> {
            val out = mutableListOf<QuickReply>()
            for (raw in rawList) {
                val text = raw.trim()
                if (text.isEmpty()) continue
                var label = stripEmoji(stripCq(text))
                    .replace("\\s+", " ").trim()
                if (label.isEmpty()) label = "回复"
                if (label.length > 6) label = label.take(5) + "…"
                out.add(QuickReply(label = label, content = text))
                if (out.size >= 6) break
            }
            return out
        }

        /** 构建下发手环的快捷回复帧 */
        fun buildQuickRepliesFrame(list: List<QuickReply>, seq: Int): String {
            val obj = JsonObject()
            obj.addProperty("type", "quick_replies")
            obj.addProperty("seq", seq)
            val arr = JsonArray()
            for (q in list) {
                val o = JsonObject()
                o.addProperty("label", q.label)
                o.addProperty("content", q.content)
                arr.add(o)
            }
            obj.add("list", arr)
            return obj.toString()
        }
    }

    fun parseMessageEvent(json: String): OneBotMessage? {
        val obj = try {
            JsonParser.parseString(json).asJsonObject
        } catch (e: Exception) {
            LogBus.log("OneBotParser", LogLevel.WARN, "parse error: $e")
            return null
        }
        if (obj.get("post_type")?.asString != "message") return null
        val messageType = obj.get("message_type")?.asString ?: return null
        val sender = obj.getAsJsonObject("sender")
        val senderId = obj.get("user_id")?.asLong?.toString() ?: return null
        val targetId = when (messageType) {
            "group" -> obj.get("group_id")?.asLong?.toString()
            "private" -> senderId
            else -> return null
        } ?: return null
        val content = degradeContent(obj.get("message"))
        val selfId = obj.get("self_id")?.let { if (it.isJsonPrimitive) it.asString else it.toString() }
        // @我 检测（v2.5.0 修复：同时支持 CQ 字符串格式与数组段格式）：
        // 数组格式（NapCat/SnowLuma 默认）的 message 是 JSON 数组，旧逻辑只查
        // "[CQ:at," 字符串导致数组格式的 @我 永远检测不到、手环金色高亮失效。
        // 只在手机端做一次遍历，结果随协议下发，手环零计算。
        val messageElem = obj.get("message")
        val atMe = selfId != null && isAtMe(messageElem, selfId)
        // OneBot 标准 time 为 Unix 秒（10 位），而本地发送链路使用毫秒（Date.now() 13 位）。
        // 统一转为毫秒，避免同一会话内秒/毫秒混排导致消息顺序跳变。
        val rawTime = obj.get("time")?.asLong ?: 0L
        val messageId = obj.get("message_id")?.let {
            if (it.isJsonPrimitive) it.asString.takeIf { s -> s.isNotEmpty() } ?: it.asLong.toString() else ""
        } ?: ""
        return OneBotMessage(
            messageType = messageType,
            targetId = targetId,
            senderId = senderId,
            senderName = stripEmoji(sender?.get("nickname")?.asString ?: senderId),
            content = content,
            time = if (rawTime > 0 && rawTime < 100_000_000_000L) rawTime * 1000L else rawTime,
            isSelf = selfId != null && senderId == selfId,
            messageId = messageId,
            atMe = atMe
        )
    }

    /**
     * 解析消息撤回通知（post_type=notice, notice_type=friend_recall/group_recall）。
     * 只改手机端存储（内容替换为撤回标记），手环端下次拉取/签名 diff 自动反映，零手环改动。
     */
    fun parseRecallEvent(json: String): OneBotRecall? {
        val obj = try {
            JsonParser.parseString(json).asJsonObject
        } catch (e: Exception) {
            return null
        }
        if (obj.get("post_type")?.asString != "notice") return null
        when (obj.get("notice_type")?.asString) {
            "friend_recall" -> {
                val userId = obj.get("user_id")?.asLong?.toString() ?: return null
                val mid = obj.get("message_id")?.let {
                    if (it.isJsonPrimitive) it.asString.takeIf { s -> s.isNotEmpty() } ?: it.asLong.toString() else ""
                } ?: return null
                return OneBotRecall(targetId = userId, messageId = mid)
            }
            "group_recall" -> {
                val groupId = obj.get("group_id")?.asLong?.toString() ?: return null
                val mid = obj.get("message_id")?.let {
                    if (it.isJsonPrimitive) it.asString.takeIf { s -> s.isNotEmpty() } ?: it.asLong.toString() else ""
                } ?: return null
                return OneBotRecall(targetId = groupId, messageId = mid)
            }
            else -> return null
        }
    }

    /**
     * 解析拍一拍通知（v2.9.0）：notice_type=notify & sub_type=poke（兼容 group_poke/friend_poke）。
     * pokeMe 判定：target_id == self_id；无 target_id 的旧端形态默认拍的是我。
     * 会话归属：群聊=group_id，私聊=拍人者 QQ（拍一拍落在与拍人者的私聊会话里，与 QQ 行为一致）。
     */
    fun parsePokeEvent(json: String): OneBotPoke? {
        val obj = try {
            JsonParser.parseString(json).asJsonObject
        } catch (e: Exception) {
            return null
        }
        if (obj.get("post_type")?.asString != "notice") return null
        val noticeType = obj.get("notice_type")?.asString
        val subType = obj.get("sub_type")?.asString
        val isPoke = (noticeType == "notify" && subType == "poke") ||
            noticeType == "group_poke" || noticeType == "friend_poke"
        if (!isPoke) return null
        val selfId = obj.get("self_id")?.let { if (it.isJsonPrimitive) it.asString else it.toString() } ?: return null
        val senderId = obj.get("user_id")?.asLong?.toString() ?: return null
        val groupId = obj.get("group_id")?.let {
            if (it.isJsonPrimitive) (if (it.asJsonPrimitive.isString) it.asString else it.asLong.toString()) else null
        }
        val chatType = if (groupId != null) "group" else "private"
        val targetRaw = obj.get("target_id")?.let {
            if (it.isJsonPrimitive) (if (it.asJsonPrimitive.isString) it.asString else it.asLong.toString()) else null
        }
        // 无 target_id（部分协议端只报 user_id）：私聊形态默认拍的是我；群聊无法判定则不提醒
        val pokeMe = when {
            targetRaw != null -> targetRaw == selfId
            chatType == "private" -> true
            else -> false
        }
        val senderName = obj.getAsJsonObject("sender")?.get("nickname")?.asString ?: senderId
        val convId = if (chatType == "group") groupId!! else senderId
        return OneBotPoke(
            chatType = chatType,
            targetId = convId,
            senderId = senderId,
            senderName = stripEmoji(senderName),
            pokeMe = pokeMe
        )
    }

    /** @我/全体 检测：数组段格式与 CQ 字符串格式双兼容（手机端一次计算，手环零开销） */
    fun isAtMe(elem: com.google.gson.JsonElement?, selfId: String): Boolean {
        if (elem == null) return false
        if (elem.isJsonArray) {
            for (seg in elem.asJsonArray) {
                if (!seg.isJsonObject) continue
                val o = seg.asJsonObject
                if (o.get("type")?.asString == "at") {
                    val data = o.getAsJsonObject("data") ?: continue
                    val qq = data.get("qq")?.let { if (it.isJsonPrimitive) it.asString else it.toString() } ?: continue
                    if (qq == selfId || qq == "all") return true
                }
            }
            return false
        }
        val raw = if (elem.isJsonPrimitive) elem.asString else elem.toString()
        return raw.contains("[CQ:at,") &&
            (raw.contains("qq=$selfId") || raw.contains("qq=all") || raw.contains("qq=\"$selfId\""))
    }

    /**
     * CQ 字符串格式降级（v2.5.0 新增；v2.6.0 表情映射）：string 上报的协议端会把 CQ 码
     * 原样透传，face 段转为对应 emoji（Vela 原生表情渲染），其余段转为可读标记。
     */
    fun degradeCqString(s: String): String {
        var out = s
        // v2.14.0：卡片类 CQ 码先于通用剥离做智能渲染（data 值为 CQ 转义串，不含裸 ]）
        out = out.replace(Regex("\\[CQ:json,data=([^\\]]*)\\]")) { m ->
            runCatching { jsonCardSummary(cqUnescape(m.groupValues[1])) }.getOrDefault("[卡片消息]")
        }
        out = out.replace(Regex("\\[CQ:xml,data=([^\\]]*)\\]")) { m ->
            runCatching { xmlCardSummary(cqUnescape(m.groupValues[1])) }.getOrDefault("[卡片消息]")
        }
        out = out.replace(Regex("\\[CQ:at,qq=all[^\\]]*\\]"), "@全体成员")
        out = out.replace(Regex("\\[CQ:at,[^\\]]*name=([^,\\]]+)[^\\]]*\\]")) { m -> "@${m.groupValues[1]}" }
        out = out.replace(Regex("\\[CQ:at,qq=(\\d+)[^\\]]*\\]")) { m -> "@${m.groupValues[1]}" }
        // face 段先于通用 CQ 处理：映射为 emoji（未收录 id 回退「[表情]」）
        out = CQ_FACE.replace(out) { m ->
            faceEmoji(m.groupValues[1].toIntOrNull() ?: -1)
        }
        out = CQ_CODE.replace(out) { m ->
            when {
                m.value.startsWith("[CQ:image") -> if (m.value.contains(".gif")) "[GIF]" else "[图片]"
                m.value.startsWith("[CQ:record") || m.value.startsWith("[CQ:voice") -> "[语音]"
                m.value.startsWith("[CQ:video") -> "[视频]"
                m.value.startsWith("[CQ:file") ->
                    fileLabel(Regex("(?:name|filename)=([^,\\]]+)").find(m.value)?.groupValues?.get(1), "[文件]")
                m.value.startsWith("[CQ:reply") -> "[回复]"
                m.value.startsWith("[CQ:forward") -> "[合并转发]"
                m.value.startsWith("[CQ:mface") -> "[表情包]"
                else -> ""
            }
        }
        // v2.6.0：emoji 原生渲染可配置（Vela 部分固件字形缺失，默认透传，tofu 时用户可关）
        return capLen(if (ConfigHolder.config.emojiNative) out.trim() else markEmoji(out.trim()))
    }

    /**
     * v2.14.0 智能自动渲染器（数组段版）：除旧八类外，新增 json/xml 卡片摘要、
     * markdown 降纯文本、合并转发/表情包/GIF/文件名/位置/猜拳骰子等 20+ 段型，
     * AstrBot 机器人回复（markdown/json）首次在手环可读；末尾 capLen 长度护栏。
     */
    fun degradeContent(message: com.google.gson.JsonElement?): String {
        if (message == null) return ""
        if (message.isJsonPrimitive && message.asJsonPrimitive.isString) return degradeCqString(message.asString)
        if (!message.isJsonArray) return ""
        val arr: JsonArray = message.asJsonArray
        val sb = StringBuilder()
        for (elem in arr) {
            val seg = if (elem.isJsonObject) elem.asJsonObject else continue
            val data = seg.getAsJsonObject("data")
            when (seg.get("type")?.asString) {
                "text" -> {
                    val text = data?.get("text")?.asString ?: ""
                    // v2.6.0：emoji 原生渲染可配置（默认透传给手环，tofu 时降级「[表情]」）
                    sb.append(if (ConfigHolder.config.emojiNative) text else markEmoji(text))
                }
                "face" -> {
                    val id = data?.get("id")?.let {
                        if (it.isJsonPrimitive) it.asString.toIntOrNull() else null
                    }
                    sb.append(if (id != null) faceEmoji(id) else "[表情]")
                }
                "image" -> {
                    // v2.14.0：GIF 识别（url/file 后缀）
                    val urlish = (data.str("url") ?: "") + (data.str("file") ?: "")
                    sb.append(if (urlish.contains(".gif", true)) "[GIF]" else "[图片]")
                }
                "record", "voice" -> sb.append("[语音]")
                "video" -> sb.append("[视频]")
                "file" -> sb.append(fileLabel(data.str("name") ?: data.str("file"), "[文件]"))
                "reply" -> sb.append("[回复]")
                // v2.14.0 新增段型
                "json" -> sb.append(data.str("data")?.let { d ->
                    runCatching { jsonCardSummary(d) }.getOrDefault("[卡片消息]")
                } ?: "[卡片消息]")
                "xml" -> sb.append(data.str("data")?.let { d ->
                    runCatching { xmlCardSummary(d) }.getOrDefault("[卡片消息]")
                } ?: "[卡片消息]")
                "markdown" -> sb.append(markdownToPlain(data.str("content") ?: ""))
                "forward" -> sb.append("[合并转发]")
                "mface" -> sb.append("[表情包]")
                "poke" -> sb.append("[戳一戳]")
                "dice" -> sb.append("[骰子]")
                "rps" -> sb.append("[猜拳]")
                "share" -> sb.append(
                    fileLabel(
                        listOfNotNull(data.str("title"), data.str("content"))
                            .joinToString(" · ").takeIf { it.isNotBlank() },
                        "[链接]"
                    )
                )
                "location" -> sb.append(fileLabel(data.str("title"), "[位置]"))
                "tts" -> sb.append(data.str("text") ?: "[语音]")
                "contact" -> sb.append("[联系人]")
                "gift" -> sb.append("[礼物]")
                "at" -> {
                    // v2.5.0：数组段格式的 at 旧逻辑降成 [其他]，这里转为可读 @
                    val qq = data?.get("qq")?.let { if (it.isJsonPrimitive) it.asString else it.toString() }.orEmpty()
                    val name = data?.get("name")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
                    when {
                        qq == "all" -> sb.append("@全体成员")
                        name.isNotBlank() -> sb.append("@").append(name)
                        qq.isNotBlank() -> sb.append("@").append(qq)
                        else -> sb.append("@某人")
                    }
                }
                else -> sb.append("[其他]")
            }
        }
        return capLen(sb.toString())
    }

    /**
     * 拍一拍手环帧（v2.9.0）：复用 push_message 通道，poke=1（Gson 数字，手环端按
     * 1/true 双兼容判定）标记拍一拍消息。手环端渲染居中特效气泡 + 长震提醒，
     * 会话列表预览直接显示「XX 拍了拍你」。
     */
    fun buildPokeFrame(
        chatType: String,
        targetId: String,
        senderId: String,
        senderName: String,
        targetName: String,
        content: String,
        visible: Boolean
    ): String {
        val obj = JsonObject()
        obj.addProperty("type", "push_message")
        obj.addProperty("seq", 0)
        obj.addProperty("message_type", chatType)
        obj.addProperty("target_id", targetId)
        obj.addProperty("sender_id", senderId)
        obj.addProperty("sender_name", senderName)
        obj.addProperty("target_name", targetName)
        obj.addProperty("content", content)
        obj.addProperty("time", System.currentTimeMillis())
        obj.addProperty("is_self", false)
        obj.addProperty("visible", visible)
        obj.addProperty("poke", 1)
        return obj.toString()
    }

    fun toHandBandFrame(msg: OneBotMessage, visible: Boolean = true, targetName: String = msg.senderName): String {
        val obj = JsonObject()
        obj.addProperty("type", "push_message")
        obj.addProperty("seq", 0)
        obj.addProperty("message_type", msg.messageType)
        obj.addProperty("target_id", msg.targetId)
        obj.addProperty("sender_id", msg.senderId)
        obj.addProperty("sender_name", msg.senderName)
        obj.addProperty("target_name", targetName)
        obj.addProperty("content", msg.content)
        obj.addProperty("time", msg.time)
        obj.addProperty("is_self", msg.isSelf)
        obj.addProperty("visible", visible)
        // v2.13.0：message_id 透传（手环端撤回自己消息/表情回应定位用；为空省字节）
        if (msg.messageId.isNotEmpty()) obj.addProperty("message_id", msg.messageId)
        // at=1 表示该消息 @我/全体（手环端高亮；为真才下发省字节）
        if (msg.atMe) obj.addProperty("at", 1)
        return obj.toString()
    }

    fun buildSendRequest(messageType: String, targetId: String, content: String): String {
        val body = JsonObject()
        val params = JsonObject()
        val idAsLong = targetId.toLongOrNull()
        if (messageType == "group") {
            body.addProperty("action", "send_group_msg")
            if (idAsLong != null) params.addProperty("group_id", idAsLong) else params.addProperty("group_id", targetId)
        } else {
            body.addProperty("action", "send_private_msg")
            if (idAsLong != null) params.addProperty("user_id", idAsLong) else params.addProperty("user_id", targetId)
        }
        params.addProperty("message", content)
        body.add("params", params)
        return body.toString()
    }

    /** 与 buildSendRequest 对应的 OneBot 动作路径名。 */
    fun actionName(messageType: String): String =
        if (messageType == "group") "send_group_msg" else "send_private_msg"
}
