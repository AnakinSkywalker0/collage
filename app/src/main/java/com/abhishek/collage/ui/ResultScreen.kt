package com.abhishek.collage.ui

import android.Manifest
import android.graphics.Bitmap
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.abhishek.collage.model.Person

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

    Column(modifier = modifier.fillMaxSize()) {
        // Collage + counts scroll together (a 5+ person collage is taller
        // than most screens); Save/Share stay pinned at the bottom.
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
        ) {
            Image(
                bitmap = collage.asImageBitmap(),
                contentDescription = "Generated collage",
                contentScale = ContentScale.FillWidth,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            )

            Text(
                text = "Appearance counts",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 16.dp)
            )

            Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                people.forEach { person ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Person ${person.displayIndex}")
                        Text("${person.appearanceCount} appearances")
                    }
                }
            }
        }

        saveMessage?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
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
                modifier = Modifier.weight(1f)
            ) {
                Text("Save")
            }
            OutlinedButton(onClick = onShare, modifier = Modifier.weight(1f)) {
                Text("Share")
            }
        }

        TextButton(onClick = onReset, modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
            Text("Process another video")
        }
    }
}

@Composable
fun ErrorScreen(message: String, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Text(text = "Something went wrong", style = MaterialTheme.typography.titleMedium)
        Text(text = message, modifier = Modifier.padding(top = 8.dp, bottom = 20.dp))
        Button(onClick = onRetry) { Text("Try again") }
    }
}
