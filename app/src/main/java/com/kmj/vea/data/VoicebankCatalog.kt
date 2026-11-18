package com.kmj.vea.data

import android.content.Context
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONObject

data class VoiceStyle(
    val id: String,
    val name: String
)

data class VoicebankInfo(
    val id: String,
    val displayName: String,
    val version: String,
    val languageCount: Int,
    val defaultStyleId: String,
    val styles: List<VoiceStyle>
)

object VoicebankCatalog {
    const val MEGPOID_ID = "ai_megpoid_v7"

    fun loadMegpoid(context: Context): VoicebankInfo {
        return runCatching {
            val json = context.assets.open("voicebanks/megpoid_v7_catalog.json")
                .bufferedReader(Charsets.UTF_8).use { it.readText() }
            val root = JSONObject(json)
            val stylesJson = root.getJSONArray("styles")
            val styles = buildList {
                for (index in 0 until stylesJson.length()) {
                    val style = stylesJson.getJSONObject(index)
                    add(VoiceStyle(style.getString("id"), style.getString("name")))
                }
            }
            VoicebankInfo(
                id = MEGPOID_ID,
                displayName = root.optString("singerName", "AI Megpoid"),
                version = root.optString("version", "7.0.0"),
                languageCount = root.optInt("languagesCount", 19),
                defaultStyleId = root.optString("defaultStyleId"),
                styles = styles
            )
        }.getOrElse {
            VoicebankInfo(
                id = MEGPOID_ID,
                displayName = "AI Megpoid",
                version = "7.0.0",
                languageCount = 19,
                defaultStyleId = "181b8942-ba8d-41a2-bbbb-4a30bd64e470",
                styles = listOf(
                    VoiceStyle("181b8942-ba8d-41a2-bbbb-4a30bd64e470", "MegpoidV7 Native"),
                    VoiceStyle("12afc179-c0ef-4394-a226-ac49b116e0b6", "MegpoidV7 NativeForCho"),
                    VoiceStyle("c83aec32-d408-4793-8ee3-24b018d37124", "MegpoidV7 Whisper"),
                    VoiceStyle("74e7be2f-649b-4d36-a0e2-5ee07ce4c9c7", "MegpoidV7 Cute"),
                    VoiceStyle("680003fe-701b-43d0-b29d-8c5bb5866191", "MegpoidV7 Downer"),
                    VoiceStyle("25f8e42e-f7bd-4906-8351-f7fc8959a121", "MegpoidV7 Power"),
                    VoiceStyle("ceb8ff3b-14e2-4fed-b417-d50c5512feed", "MegpoidV7 Soft"),
                    VoiceStyle("f417d92d-d2f2-4ad0-9bcb-1bd9e9960026", "MegpoidV7 Vivid"),
                    VoiceStyle("8326ca31-9d53-44f9-8c0e-76a5810adc86", "MegpoidV7 Ballade"),
                    VoiceStyle("5e0bb051-9105-473d-b761-d70e5311cade", "MegpoidV7 Chorus"),
                    VoiceStyle("c9958794-cbd5-4699-a925-64cf58ca80b0", "MegpoidV7 Delay"),
                    VoiceStyle("553897fa-8dff-46c1-87aa-80e5735110a1", "MegpoidV7 LoFi"),
                    VoiceStyle("a58bcab6-af68-4e4a-be16-3be8b3b9bf14", "MegpoidV7 Phase")
                )
            )
        }
    }
}

@Composable
fun MegpoidPortrait(
    size: Dp,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val bitmap = remember(context) {
        runCatching {
            context.assets.open("voicebanks/ai_megpoid_icon.png").use { stream ->
                BitmapFactory.decodeStream(stream)?.asImageBitmap()
            }
        }.getOrNull()
    }

    if (bitmap != null) {
        Image(
            bitmap = bitmap,
            contentDescription = "AI Megpoid",
            modifier = modifier.size(size).clip(CircleShape)
                .border(1.dp, Color(0xFF7D848B), CircleShape),
            contentScale = ContentScale.Crop
        )
    } else {
        Box(
            modifier = modifier.size(size).clip(CircleShape)
                .background(Color(0xFF424A53))
                .border(1.dp, Color(0xFF7D848B), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            androidx.compose.material3.Text(
                "G",
                color = Color.White,
                fontSize = (size.value * 0.48f).sp
            )
        }
    }
}
