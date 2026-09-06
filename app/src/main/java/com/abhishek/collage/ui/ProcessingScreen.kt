package com.abhishek.collage.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.abhishek.collage.model.ProcessingState
import com.abhishek.collage.ui.theme.Amber
import com.abhishek.collage.ui.theme.AmberSoft
import com.abhishek.collage.ui.theme.Card
import com.abhishek.collage.ui.theme.Cream
import com.abhishek.collage.ui.theme.CreamDeep
import com.abhishek.collage.ui.theme.Ink
import com.abhishek.collage.ui.theme.InkSoft
import com.abhishek.collage.ui.theme.Rose
import com.abhishek.collage.ui.theme.RoseSoft
import com.abhishek.collage.ui.theme.Teal
import com.abhishek.collage.ui.theme.TealSoft

/**
 * Names every stage, not just the current one.
 *
 * The brief asks for "clear processing progress", and a bare percentage says
 * nothing about what the app is doing during the slow part of a run. Listing the
 * stages makes the wait legible, and makes the pipeline itself visible in the
 * submitted screen recording.
 */
@Composable
fun ProcessingScreen(
    stage: ProcessingState.Stage,
    fraction: Float,
    modifier: Modifier = Modifier
) {
    val clamped = fraction.coerceIn(0f, 1f)
    val stages = ProcessingState.Stage.entries
    val currentIndex = stages.indexOf(stage)
    val overall = ((currentIndex + clamped) / stages.size).coerceIn(0f, 1f)
    val animated by animateFloatAsState(targetValue = overall, label = "overall")

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Cream)
            .padding(horizontal = 18.dp)
    ) {
        Spacer(Modifier.height(24.dp))

        Text(
            text = "Working on it",
            color = Ink,
            fontSize = 30.sp,
            letterSpacing = (-0.8).sp,
            fontWeight = FontWeight.Black
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Stat("${currentIndex + 1}", "of ${stages.size}", TealSoft, Teal, Modifier.weight(1f))
            Stat("${(overall * 100).toInt()}", "percent", RoseSoft, Rose, Modifier.weight(1f))
            Stat(
                if (currentIndex >= stages.size - 1) "1" else "${stages.size - currentIndex - 1}",
                "to go", AmberSoft, Amber, Modifier.weight(1f)
            )
        }

        // The percentage as the largest thing on screen: it is the only number
        // that matters while waiting, and it stays readable in a recording.
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 14.dp)
                .clip(RoundedCornerShape(30.dp))
                .background(Card)
                .padding(26.dp)
        ) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = "${(overall * 100).toInt()}",
                    color = Ink,
                    fontSize = 76.sp,
                    lineHeight = 76.sp,
                    letterSpacing = (-3).sp,
                    fontWeight = FontWeight.Black
                )
                Text(
                    text = "%",
                    color = Rose,
                    fontSize = 30.sp,
                    fontWeight = FontWeight.Black,
                    modifier = Modifier.padding(bottom = 9.dp, start = 2.dp)
                )
            }
            Text(
                text = stage.label,
                color = InkSoft,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold
            )
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp)
                    .height(12.dp)
                    .clip(CircleShape)
                    .background(CreamDeep)
            ) {
                Box(
                    Modifier
                        .fillMaxWidth(animated)
                        .fillMaxHeight()
                        .clip(CircleShape)
                        .background(Rose)
                )
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 14.dp)
                .clip(RoundedCornerShape(28.dp))
                .background(Card)
                .padding(vertical = 18.dp, horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            stages.forEachIndexed { index, item ->
                StageRow(
                    label = item.label,
                    done = index < currentIndex,
                    active = index == currentIndex
                )
            }
        }

        Spacer(Modifier.weight(1f))
    }
}

@Composable
private fun Stat(
    value: String,
    label: String,
    tint: androidx.compose.ui.graphics.Color,
    mark: androidx.compose.ui.graphics.Color,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(22.dp))
            .background(tint)
            .padding(vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(value, color = mark, fontSize = 26.sp, fontWeight = FontWeight.Black)
        Text(label, color = Ink, fontSize = 12.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun StageRow(label: String, done: Boolean, active: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(22.dp)
                .clip(CircleShape)
                .background(
                    when {
                        done -> Teal
                        active -> Rose
                        else -> CreamDeep
                    }
                ),
            contentAlignment = Alignment.Center
        ) {
            if (done) Text("✓", fontSize = 12.sp, color = Card, fontWeight = FontWeight.Black)
        }
        Text(
            text = label,
            color = if (active || done) Ink else InkSoft.copy(alpha = 0.55f),
            fontSize = 15.sp,
            fontWeight = if (active) FontWeight.Black else FontWeight.Normal,
            modifier = Modifier.padding(start = 14.dp)
        )
    }
}
