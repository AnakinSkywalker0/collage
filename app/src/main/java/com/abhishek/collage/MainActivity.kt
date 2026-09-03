package com.abhishek.collage

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import com.abhishek.collage.model.ProcessingState
import com.abhishek.collage.ui.ErrorScreen
import com.abhishek.collage.ui.HomeScreen
import com.abhishek.collage.ui.ProcessingScreen
import com.abhishek.collage.ui.ResultScreen
import com.abhishek.collage.ui.theme.CollageTheme
import com.abhishek.collage.viewmodel.CollageViewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            CollageTheme {
                val viewModel: CollageViewModel = viewModel()
                val state by viewModel.state.collectAsState()

                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    val contentModifier = Modifier.padding(innerPadding)
                    when (val current = state) {
                        is ProcessingState.Idle -> HomeScreen(
                            onVideoSelected = viewModel::onVideoSelected,
                            modifier = contentModifier
                        )

                        is ProcessingState.Running -> ProcessingScreen(
                            stage = current.stage,
                            fraction = current.fraction,
                            modifier = contentModifier
                        )

                        is ProcessingState.Done -> ResultScreen(
                            people = current.people,
                            collage = current.collage,
                            onSave = viewModel::saveCollage,
                            onShare = viewModel::shareCollage,
                            onReset = viewModel::reset,
                            modifier = contentModifier
                        )

                        is ProcessingState.Failed -> ErrorScreen(
                            message = current.message,
                            onRetry = viewModel::reset,
                            modifier = contentModifier
                        )
                    }
                }
            }
        }
    }
}
