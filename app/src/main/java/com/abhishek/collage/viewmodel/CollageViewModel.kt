package com.abhishek.collage.viewmodel

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.abhishek.collage.collage.CollageSaver
import com.abhishek.collage.collage.ShareUtil
import com.abhishek.collage.model.ProcessingState
import com.abhishek.collage.pipeline.PipelineOrchestrator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class CollageViewModel(application: Application) : AndroidViewModel(application) {

    private val orchestrator = PipelineOrchestrator(application)

    private val _state = MutableStateFlow<ProcessingState>(ProcessingState.Idle)
    val state: StateFlow<ProcessingState> = _state.asStateFlow()

    /**
     * The in-flight pipeline run, if any. Tracked so a second video selection
     * cannot run concurrently with the first: PipelineOrchestrator owns a single
     * ML Kit detector and a single TFLite interpreter, and neither is safe for
     * concurrent use (see FaceDetectorStage's class doc). Two overlapping runs
     * is not a hypothetical -- "Process another video" straight into the picker
     * while a run is still going is the obvious way to demo the app.
     */
    private var processingJob: Job? = null

    fun onVideoSelected(uri: Uri) {
        val previous = processingJob
        previous?.cancel()

        _state.value = ProcessingState.Running(ProcessingState.Stage.EXTRACTING, 0f)
        processingJob = viewModelScope.launch {
            // Cancellation is cooperative, so the previous run may still be
            // mid-frame. Wait for it to actually finish before touching the
            // shared detector/interpreter.
            previous?.join()
            try {
                val result = orchestrator.process(uri) { stage, fraction ->
                    _state.value = ProcessingState.Running(stage, fraction)
                }
                _state.value = ProcessingState.Done(result.people, result.collage)
            } catch (e: CancellationException) {
                // A superseded run, not a failure -- the newer run owns the
                // state now. Must be rethrown so the coroutine machinery sees
                // the cancellation rather than treating it as handled.
                throw e
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
        processingJob?.cancel()
        processingJob = null
        _state.value = ProcessingState.Idle
    }

    override fun onCleared() {
        super.onCleared()
        orchestrator.close()
    }
}
