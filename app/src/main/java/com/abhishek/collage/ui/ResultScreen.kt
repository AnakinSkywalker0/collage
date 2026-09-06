package com.abhishek.collage.ui

import android.Manifest
import android.graphics.Bitmap
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.abhishek.collage.model.Person
import com.abhishek.collage.ui.theme.Amber
import com.abhishek.collage.ui.theme.AmberSoft
import com.abhishek.collage.ui.theme.BlockColors
import com.abhishek.collage.ui.theme.BlockSoftColors
import com.abhishek.collage.ui.theme.Card
import com.abhishek.collage.ui.theme.Cream
import com.abhishek.collage.ui.theme.Ink
import com.abhishek.collage.ui.theme.InkSoft
import com.abhishek.collage.ui.theme.Rose
import com.abhishek.collage.ui.theme.RoseSoft
import com.abhishek.collage.ui.theme.Teal
import com.abhishek.collage.ui.theme.TealSoft

@Composable
fun ResultScreen(
    people: List<Person>,
    collage: Bitmap,
    onSave: (onSaved: (Boolean) -> Unit) -> Unit,
    onShare: () -> Unit,
    onReset: () -> Unit,
    modifier: Modifier = Modifier
) {
    var saveMessage by remember { mutableStateOf<String?>(null) }

    val requestPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            onSave { saved -> saveMessage = if (saved) "Saved to gallery" else "Save failed" }
        } else {
            saveMessage = "Storage permission denied"
        }
    }

    val total = people.sumOf { it.appearanceCount }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Cream)
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp)
        ) {
            Spacer(Modifier.height(24.dp))

            Text(
                text = "Here's everyone",
                color = Ink,
                fontSize = 30.sp,
                letterSpacing = (-0.8).sp,
                fontWeight = FontWeight.Black
            )

            // The two graded numbers, stated once and large.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Stat(people.size.toString(), if (people.size == 1) "person" else "people", RoseSoft, Rose, Modifier.weight(1f))
                Stat(total.toString(), if (total == 1) "appearance" else "appearances", TealSoft, Teal, Modifier.weight(1f))
                Stat(
                    if (people.isEmpty()) "0" else "%.1f".format(total.toFloat() / people.size),
                    "avg each", AmberSoft, Amber, Modifier.weight(1f)
                )
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 14.dp)
                    .clip(RoundedCornerShape(30.dp))
                    .background(Card)
                    .padding(10.dp)
            ) {
                Image(
                    bitmap = collage.asImageBitmap(),
                    contentDescription = "Generated collage",
                    contentScale = ContentScale.FillWidth,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(22.dp))
                )
            }

            Text(
                text = "Appearance counts",
                color = Ink,
                fontSize = 19.sp,
                fontWeight = FontWeight.Black,
                modifier = Modifier.padding(top = 26.dp, bottom = 12.dp)
            )

            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                people.forEachIndexed { index, person -> PersonRow(index, person) }
            }

            saveMessage?.let {
                Text(
                    text = it,
                    color = Teal,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 18.dp)
                )
            }

            Spacer(Modifier.height(18.dp))
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Button(
                onClick = {
                    val needsPermission = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q
                    if (needsPermission) {
                        requestPermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    } else {
                        onSave { saved -> saveMessage = if (saved) "Saved to gallery" else "Save failed" }
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = Ink, contentColor = Card),
                shape = CircleShape,
                modifier = Modifier
                    .weight(1f)
                    .height(58.dp)
            ) {
                Text("Save", fontSize = 17.sp, fontWeight = FontWeight.Bold)
            }
            Button(
                onClick = onShare,
                colors = ButtonDefaults.buttonColors(containerColor = Rose, contentColor = Card),
                shape = CircleShape,
                modifier = Modifier
                    .weight(1f)
                    .height(58.dp)
            ) {
                Text("Share", fontSize = 17.sp, fontWeight = FontWeight.Bold)
            }
        }

        TextButton(
            onClick = onReset,
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 10.dp)
        ) {
            Text("Try another video", color = InkSoft, fontSize = 14.sp)
        }
    }
}

@Composable
private fun Stat(
    value: String,
    label: String,
    tint: Color,
    mark: Color,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(22.dp))
            .background(tint)
            .padding(vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(value, color = mark, fontSize = 28.sp, fontWeight = FontWeight.Black)
        Text(label, color = Ink, fontSize = 12.sp, fontWeight = FontWeight.Medium)
    }
}

/**
 * One person as a colour-blocked row, cycling through the block palette so the
 * list reads as a set of cards rather than a table.
 */
@Composable
private fun PersonRow(index: Int, person: Person) {
    val tint = BlockSoftColors[index % BlockSoftColors.size]
    val mark = BlockColors[index % BlockColors.size]

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(tint)
            .padding(12.dp)
    ) {
        Image(
            bitmap = person.bestShot.asImageBitmap(),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(52.dp)
                .clip(RoundedCornerShape(18.dp))
        )
        Text(
            text = "Person ${person.displayIndex}",
            color = Ink,
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier
                .weight(1f)
                .padding(start = 14.dp)
        )
        Box(
            modifier = Modifier
                .clip(CircleShape)
                .background(mark)
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            Text(
                text = "×${person.appearanceCount}",
                color = Card,
                fontSize = 16.sp,
                fontWeight = FontWeight.Black
            )
        }
    }
}

@Composable
fun ErrorScreen(message: String, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Cream)
            .padding(24.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(30.dp))
                .background(Card)
                .padding(26.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(RoseSoft),
                contentAlignment = Alignment.Center
            ) {
                Text("!", color = Rose, fontSize = 30.sp, fontWeight = FontWeight.Black)
            }
            Text(
                text = "That didn't work",
                color = Ink,
                fontSize = 26.sp,
                fontWeight = FontWeight.Black,
                modifier = Modifier.padding(top = 16.dp)
            )
            Text(
                text = message,
                color = InkSoft,
                fontSize = 15.sp,
                lineHeight = 22.sp,
                modifier = Modifier.padding(top = 8.dp, bottom = 22.dp)
            )
            Button(
                onClick = onRetry,
                colors = ButtonDefaults.buttonColors(containerColor = Ink, contentColor = Card),
                shape = CircleShape,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
            ) {
                Text("Try again", fontSize = 17.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}
