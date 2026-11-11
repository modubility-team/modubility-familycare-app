package com.familycare.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val Ink = Color(0xFF173E38)
private val Paper = Color(0xFFF5F4EE)

@Composable
fun FamilyCareScreen(status: DoorStatus, ux: UxPolicy, onResize: () -> Unit = {}) {
    Surface(color = Paper, modifier = Modifier.fillMaxSize()) {
        if (!ux.showDetails) {
            // No departure prompt, checklist, scrolling, or layout controls while restricted.
            // Unknown connectivity uses the same conservative screen.
            Box(Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp)
                .testTag("restricted"), contentAlignment = Alignment.Center) {
                Text(ux.shortText(if (ux.available) "운전에 집중해 주세요" else "주행 상태를 확인하고 있어요"),
                    color = Ink, fontSize = 28.sp, fontWeight = FontWeight.Bold)
            }
        } else {
            ParkedScreen(status, onResize)
        }
    }
}

@Composable
private fun ParkedScreen(status: DoorStatus, onResize: () -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding().testTag("details")) {
        val wide = maxWidth >= 760.dp && maxWidth > maxHeight
        val padding = if (wide) 32.dp else 20.dp
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(padding),
            verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween) {
                Text("WithME", color = Ink, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                OutlinedButton(onClick = onResize, modifier = Modifier.heightIn(min = 64.dp).testTag("resize")) {
                    Text(if (wide) "화면 줄이기" else "화면 넓히기", fontSize = 18.sp)
                }
            }
            if (wide) {
                Row(Modifier.fillMaxWidth().testTag("wide"), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    Reminder(Modifier.weight(1.35f), status, true)
                    Items(Modifier.weight(1f))
                }
            } else {
                Column(Modifier.fillMaxWidth().testTag("narrow"), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                    Reminder(Modifier.fillMaxWidth(), status, false)
                    Items(Modifier.fillMaxWidth())
                }
            }
            Text("물건이 남아 있는지는 직접 확인해 주세요.", color = Ink.copy(alpha = .8f), fontSize = 16.sp)
        }
    }
}

@Composable
private fun Reminder(modifier: Modifier, status: DoorStatus, wide: Boolean) {
    val open = status == DoorStatus.OPEN
    val color = if (open) Ink else Paper
    val title = when (status) {
        DoorStatus.OPEN -> "잠깐, 챙기셨나요?"
        DoorStatus.CLOSED -> "한 번 더 챙겨요."
        DoorStatus.CONNECTING -> "차량 연결 확인 중"
        DoorStatus.UNAVAILABLE -> "문 상태 확인 필요"
        DoorStatus.ERROR -> "차량 연결 확인 필요"
    }
    val subtitle = when (status) {
        DoorStatus.OPEN -> "운전석 문이 열렸어요.\n내리기 전 소지품을 확인하세요."
        DoorStatus.CLOSED -> "운전석 문이 닫혀 있어요.\n문이 열리면 확인을 도와드릴게요."
        DoorStatus.CONNECTING -> "잠시만 기다려 주세요."
        else -> "앱을 다시 열어 연결해 주세요.\n소지품은 직접 확인해 주세요."
    }
    Column(modifier.background(if (open) Color(0xFFF2D5B2) else Ink, RoundedCornerShape(24.dp))
        .padding(if (wide) 32.dp else 24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Text(if (open) "내리기 전, 한 번 더" else "함께 챙기는 작은 습관", color = color.copy(alpha = .85f), fontSize = 17.sp)
        Text(title, color = color, fontSize = if (wide) 38.sp else 30.sp,
            lineHeight = if (wide) 48.sp else 40.sp, fontWeight = FontWeight.Bold)
        Text(subtitle, color = color, fontSize = 21.sp, lineHeight = 30.sp)
    }
}

@Composable
private fun Items(modifier: Modifier) {
    Column(modifier.background(Color.White, RoundedCornerShape(24.dp)).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("잊지 말고 챙겨요", color = Ink, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        listOf("휴대폰", "지갑", "가방").forEach { item ->
            Text(item, Modifier.fillMaxWidth().background(Paper, RoundedCornerShape(12.dp)).padding(14.dp),
                color = Ink, fontSize = 22.sp)
        }
    }
}
