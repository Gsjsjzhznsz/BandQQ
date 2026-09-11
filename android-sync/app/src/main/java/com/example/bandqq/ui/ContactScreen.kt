package com.example.bandqq.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.bandqq.config.ConfigHolder
import com.example.bandqq.onebot.OneBotClient
import com.example.bandqq.onebot.OneBotParser
import com.example.bandqq.sync.InterconnectBridge
import com.example.bandqq.sync.StoreHolder
import com.example.bandqq.sync.VisibleContact
import com.example.bandqq.ui.component.AvatarCircle
import com.example.bandqq.ui.component.PageScaffold
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Checkbox
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun ContactScreen(bottomInnerPadding: Dp, isActive: Boolean = true) {
    val context = LocalContext.current
    val store = StoreHolder.store
    val scope = rememberCoroutineScope()
    var activeType by remember { mutableStateOf("private") }
    var refreshKey by remember { mutableStateOf(0) }

    fun contactsFor(type: String): List<VisibleContact> {
        val all = store?.getCachedContacts() ?: emptyList()
        return all.filter { it.type == type }
    }
    val visibleIds = remember(refreshKey) { (store?.getVisibleContacts() ?: emptyList()).map { it.id }.toSet() }
    var selected by remember { mutableStateOf(visibleIds) }

    PageScaffold(title = "联系人", bottomInnerPadding = bottomInnerPadding) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Spacer(Modifier.height(innerPadding.calculateTopPadding() + 16.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(
                text = "私聊",
                onClick = { activeType = "private" },
                colors = if (activeType == "private") {
                    ButtonDefaults.textButtonColorsPrimary()
                } else {
                    ButtonDefaults.textButtonColors()
                },
            )
            TextButton(
                text = "群聊",
                onClick = { activeType = "group" },
                colors = if (activeType == "group") {
                    ButtonDefaults.textButtonColorsPrimary()
                } else {
                    ButtonDefaults.textButtonColors()
                },
            )
            Spacer(modifier = Modifier.weight(1f))
            TextButton(
                text = "刷新",
                onClick = {
                    val s = StoreHolder.store
                    if (s == null) {
                        toast(context, "同步服务尚未启动，请先启动同步")
                        return@TextButton
                    }
                    val client = OneBotClient(OneBotParser())
                    client.configure(ConfigHolder.config.endpoint)
                    val http = ConfigHolder.config.endpoint.httpUrl
                    client.requestApi("get_friend_list", http) { raw ->
                        scope.launch(Dispatchers.Main) {
                            applyContactList("private", raw, context)
                            refreshKey++
                        }
                    }
                    client.requestApi("get_group_list", http) { raw ->
                        scope.launch(Dispatchers.Main) {
                            applyContactList("group", raw, context)
                            refreshKey++
                        }
                    }
                },
            )
        }
        AnimatedContent(
            targetState = activeType,
            transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(180)) },
            label = "contactType",
        ) { type ->
            var entered by remember(type) { mutableStateOf(false) }
            // 仅当本页为当前页才播入场动画（预组合不触发，修复切页无动画）
            LaunchedEffect(type, isActive) { if (isActive) entered = true }
            Column {
                SmallTitle(text = if (type == "private") "私聊联系人" else "群聊联系人")
                contactsFor(type).forEachIndexed { index, c ->
                    Card(
                        modifier = Modifier.fillMaxWidth().listItemReveal(entered, index),
                        colors = CardDefaults.defaultColors(),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(start = 0.dp, top = 12.dp, bottom = 12.dp, end = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            AvatarCircle(
                                name = c.name,
                                id = c.id,
                                size = 40.dp,
                                fontSize = 17.sp,
                                modifier = Modifier.padding(start = 12.dp),
                            )
                            Column(modifier = Modifier.weight(1f).padding(start = 12.dp)) {
                                Text(text = c.name.ifBlank { c.id })
                                Text(
                                    text = if (type == "private") "私聊" else "群聊",
                                    color = MiuixTheme.colorScheme.onSurfaceSecondary,
                                )
                            }
                            Checkbox(
                                state = if (c.id in selected) ToggleableState.On else ToggleableState.Off,
                                onClick = {
                                    selected = if (c.id in selected) selected - c.id else selected + c.id
                                },
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.padding(top = 4.dp))
            }
        }
        TextButton(
            text = "保存选中联系人到手环",
            modifier = Modifier.fillMaxWidth(),
            onClick = {
                val s = StoreHolder.store ?: run {
                    toast(context, "同步服务尚未启动，请先启动同步")
                    return@TextButton
                }
                val checkedList = contactsFor(activeType).filter { it.id in selected }
                val merged = s.getVisibleContacts().filter { it.type != activeType } + checkedList
                s.setVisibleContacts(merged)
                if (InterconnectBridge.available && InterconnectBridge.isNodeReady()) {
                    InterconnectBridge.sendToBand(s.buildVisibleContactsFrame(0))
                    toast(context, "已保存并同步到手环")
                } else {
                    toast(context, "已保存，但手环未连接，将在连接后自动同步")
                }
            },
        )

        Spacer(modifier = Modifier.height(bottomInnerPadding + 12.dp))
    }
    }
}

private fun applyContactList(type: String, raw: String?, context: android.content.Context) {
    val store = StoreHolder.store ?: return
    if (raw == null) return
    val parsed = parseContactsRaw(type, raw)
    if (parsed.isNotEmpty()) {
        val others = store.getCachedContacts().filter { it.type != type }
        store.setCachedContacts(others + parsed)
    }
}

private fun parseContactsRaw(type: String, raw: String?): List<VisibleContact> {
    val out = mutableListOf<VisibleContact>()
    try {
        val data = JsonParser.parseString(raw ?: return out).asJsonObject.get("data") ?: return out
        if (!data.isJsonArray) return out
        for (e in data.asJsonArray) {
            val o = e.asJsonObject
            val id: String
            val name: String
            if (type == "private") {
                id = o.get("user_id")?.asLong?.toString() ?: continue
                name = o.get("nickname")?.asString ?: id
            } else {
                id = o.get("group_id")?.asLong?.toString() ?: continue
                name = o.get("group_name")?.asString ?: id
            }
            out.add(VisibleContact(id, type, name))
        }
    } catch (e: Exception) {
        return emptyList()
    }
    return out
}