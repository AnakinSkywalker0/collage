package com.abhishek.collage.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.abhishek.collage.ui.theme.Amber
import com.abhishek.collage.ui.theme.AmberSoft
import com.abhishek.collage.ui.theme.Card
import com.abhishek.collage.ui.theme.Cream
import com.abhishek.collage.ui.theme.Ink
import com.abhishek.collage.ui.theme.InkSoft
import com.abhishek.collage.ui.theme.Rose
import com.abhishek.collage.ui.theme.Teal
import com.abhishek.collage.ui.theme.TealSoft
import com.abhishek.collage.ui.theme.Violet
import com.abhishek.collage.ui.theme.VioletSoft

@Composable
fun HomeScreen(onVideoSelected: (Uri) -> Unit, modifier: Modifier = Modifier) {
    val pickVideo = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri -> uri?.let(onVideoSelected) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Cream)
            .padding(horizontal = 18.dp)
    ) {
        Spacer(Modifier.height(20.dp))

        // Saturated hero block. Everything below sits on cream, so this is the
        // one place the primary colour is allowed to fill an area.
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(32.dp))
                .background(Rose)
                .padding(26.dp)
        ) {
            Text("on your phone only", color = Card.copy(alpha = 0.85f), fontSize = 14.sp)
            Text(
                text = "Who's in\nthis video?",
                color = Card,
                fontSize = 40.sp,
                lineHeight = 44.sp,
                letterSpacing = (-1).sp,
                fontWeight = FontWeight.Black,
                modifier = Modifier.padding(top = 6.dp, bottom = 20.dp)
            )
            Row(horizontalArrangement = Arrangement.spacedBy((-12).dp)) {
                FaceChip(Amber, -7f)
                FaceChip(Teal, 5f)
                FaceChip(Violet, 11f)
            }
        }

        // Stat-chip row -- the reference's strongest device: three equal blocks,
        // each a soft tint carrying one saturated mark.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            StepChip("1", "detect", TealSoft, Teal, Modifier.weight(1f))
            StepChip("2", "match", AmberSoft, Amber, Modifier.weight(1f))
            StepChip("3", "rank", VioletSoft, Violet, Modifier.weight(1f))
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 14.dp)
                .clip(RoundedCornerShape(28.dp))
                .background(Card)
                .padding(22.dp)
        ) {
            Text(
                text = "How it works",
                color = Ink,
                fontSize = 19.sp,
                fontWeight = FontWeight.Black
            )
            Text(
                text = "Every face is found, matched to the person it belongs to, and counted. You get one collage with everyone in it.",
                color = InkSoft,
                fontSize = 14.sp,
                lineHeight = 21.sp,
                modifier = Modifier.padding(top = 8.dp)
            )
        }

        Spacer(Modifier.weight(1f))

        Button(
            onClick = { pickVideo.launch("video/*") },
            colors = ButtonDefaults.buttonColors(containerColor = Ink, contentColor = Card),
            shape = CircleShape,
            modifier = Modifier
                .fillMaxWidth()
                .height(62.dp)
        ) {
            Text("Pick a video", fontSize = 18.sp, fontWeight = FontWeight.Bold)
        }

        Spacer(Modifier.height(28.dp))
    }
}

/** Soft-tinted block with a saturated numbered dot -- the reference's stat chip. */
@Composable
private fun StepChip(
    number: String,
    label: String,
    tint: Color,
    mark: Color,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(22.dp))
            .background(tint)
            .padding(vertical = 16.dp, horizontal = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(30.dp)
                .clip(CircleShape)
                .background(mark),
            contentAlignment = Alignment.Center
        ) {
            Text(number, color = Card, fontSize = 14.sp, fontWeight = FontWeight.Black)
        }
        Text(
            text = label,
            color = Ink,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(top = 8.dp)
        )
    }
}

/** Rounded character chip: a blob with a face, drawn from shapes. */
@Composable
private fun FaceChip(color: Color, tilt: Float) {
    Box(
        modifier = Modifier
            .rotate(tilt)
            .size(58.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(color),
        contentAlignment = Alignment.Center
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            Eye()
            Eye()
        }
        Box(
            modifier = Modifier
                .offset(y = 11.dp)
                .size(width = 13.dp, height = 4.dp)
                .clip(CircleShape)
                .background(Ink.copy(alpha = 0.45f))
        )
    }
}

@Composable
private fun Eye() {
    Box(
        modifier = Modifier
            .size(7.dp)
            .clip(CircleShape)
            .background(Ink.copy(alpha = 0.75f))
    )
}
