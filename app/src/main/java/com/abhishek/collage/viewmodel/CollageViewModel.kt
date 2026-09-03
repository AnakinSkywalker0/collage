package com.abhishek.collage.viewmodel

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.abhishek.collage.collage.CollageSaver
import com.abhishek.collage.collage.ShareUtil
import com.abhishek.collage.model.ProcessingState
import com.abhishek.collage.pipeline.PipelineOrchestrator
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class CollageViewModel(application: Application) : AndroidViewModel(application) {

    private val orchestrator = PipelineOrchestrator(application)

    private val _state = MutableStateFlow<ProcessingState>(ProcessingState.Idle)
    val state: StateFlow<ProcessingState> = _state.asStateFlow()

    fun onVideoSelected(uri: Uri) {
        _state.value = ProcessingState.Running(ProcessingState.Stage.EXTRACTING, 0f)
        viewModelScope.launch {
            try {
                val result = orchestrator.process(uri) { stage, fraction ->
                    _state.value = ProcessingState.Running(stage, fraction)
                }
                _state.value = ProcessingState.Done(result.people, result.collage)
            } catch (e: Exception) {
                _state.value = ProcessingState.Failed(e.message ?: "Something went wrong while processing the video.")
            }
        }
    }

    fun saveCollage(onSaved: (Boolean) -> Unit) {
        val current = _state.value
        if (current !is ProcessingState.Done) return
        viewModelScope.launch {
            val uri = CollageSaver.saveToGallery(getApplication(), current.collage)
            onSaved(uri != null)
        }
    }

    fun shareCollage() {
        val current = _state.value
        if (current !is ProcessingState.Done) return
        viewModelScope.launch {
            ShareUtil.share(getApplication(), current.collage)
        }
    }

    fun reset() {
        _state.value = ProcessingState.Idle
    }

    override fun onCleared() {
        super.onCleared()
        orchestrator.close()
    }
}
