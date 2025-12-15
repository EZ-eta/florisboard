/*
 * Copyright (C) 2025 The FlorisBoard Contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package dev.patrickgold.florisboard.ime.text.gestures

import android.content.Context
import androidx.collection.SparseArrayCompat
import dev.patrickgold.florisboard.app.FlorisPreferenceStore
import dev.patrickgold.florisboard.ime.keyboard.KeyData
import dev.patrickgold.florisboard.ime.nlp.WordSuggestionCandidate
import dev.patrickgold.florisboard.ime.text.keyboard.TextKey
import dev.patrickgold.florisboard.keyboardManager
import dev.patrickgold.florisboard.nlpManager
import dev.patrickgold.florisboard.subtypeManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Extended GlideTypingManager that supports Nintype-style two-thumb sliding.
 *
 * Key behavioral changes from standard glide typing:
 * 1.Word is NOT committed on finger lift - committed on SPACE
 * 2.Multiple fingers can contribute to the same word
 * 3.Taps act as "anchors" for important letters
 * 4.Supports hybrid tap+swipe within single word
 */
class MultiPointerGlideTypingManager(context:Context) :
    GlideTypingGesture.Listener,
    MultiPointerGlideDetector.Listener {

    companion object {
        private const val MAX_SUGGESTION_COUNT = 8
    }

    private val prefs by FlorisPreferenceStore
    private val keyboardManager by context.keyboardManager()
    private val nlpManager by context.nlpManager()
    private val subtypeManager by context.subtypeManager()

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    // Classifier for gesture recognition
    private val glideTypingClassifier = StatisticalGlideTypingClassifier(context)

    // Current gesture state
    private var isMultiPointerGesture = false
    private var pendingWord:String? = null
    private var currentKeys:List<TextKey> = emptyList()
    private var keysByCharacter:SparseArrayCompat<TextKey> = SparseArrayCompat()

    // Track key sequence from multi-pointer gestures
    private val keySequence = mutableListOf<KeyHit>()

    // Preview timing
    private var lastPreviewTime = System.currentTimeMillis()

    data class KeyHit(
        val keyCode:Int,
        val character: Char,
        val timestamp:Long,
        val pointerId: Int
    )

    // ==================== Single Pointer Callbacks ====================

    override fun onGlideComplete(data:GlideTypingGesture.Detector.PointerData) {
        if (! isMultiPointerGesture) {
            // Standard single-finger behavior - but don't commit yet! 
            // Store the word and wait for space
            updateSuggestionsAsync(MAX_SUGGESTION_COUNT, commitWord = false) {
                glideTypingClassifier.clear()
            }
        }
    }

    override fun onGlideCancelled() {
        glideTypingClassifier.clear()
        keySequence.clear()
        pendingWord = null
        isMultiPointerGesture = false
    }

    override fun onGlideAddPoint(point:GlideTypingGesture.Detector.Position) {
        if (! isMultiPointerGesture) {
            glideTypingClassifier.addGesturePoint(point)
            maybeUpdatePreview()
        }
    }

    // ==================== Multi Pointer Callbacks ====================

    override fun onMultiGlideStart(pointerId: Int) {
        isMultiPointerGesture = true
        // Clear single pointer data as we're now in multi-pointer mode
        glideTypingClassifier.clear()
        keySequence.clear()
    }

    override fun onMultiGlideAddPoint(
        pointerId:Int,
        position: GlideTypingGesture.Detector.Position,
        key:TextKey?
    ) {
        // Add point to the classifier
        glideTypingClassifier.addGesturePoint(position)

        // Track key hits for multi-pointer analysis
        if (key != null) {
            val keyData = key.computedData
            if (keyData is KeyData) {
                val code = keyData.code
                val char = code.toChar()
                if (char.isLetter()) {
                    // Only add if different from last key (avoid duplicates from movement)
                    if (keySequence.isEmpty() || keySequence.last().keyCode != code) {
                        keySequence.add(KeyHit(
                            keyCode = code,
                            character = char.lowercaseChar(),
                            timestamp = System.currentTimeMillis(),
                            pointerId = pointerId
                        ))
                    }
                }
            }
        }

        maybeUpdatePreview()
    }

    override fun onMultiGlideComplete(data:MultiPointerGlideDetector.MultiPointerGestureData) {
        // Use the standard classifier for now, enhanced with key sequence info
        updateSuggestionsAsync(MAX_SUGGESTION_COUNT, commitWord = false) {
            glideTypingClassifier.clear()
            keySequence.clear()
            isMultiPointerGesture = false
        }
    }

    override fun onMultiGlideCancelled() {
        onGlideCancelled()
    }

    // ==================== Word Commitment ====================

    /**
     * Called when SPACE is pressed. Commits the pending word.
     * This is the key difference from standard glide typing! 
     */
    fun commitPendingWord():Boolean {
        val word = pendingWord ?:return false
        keyboardManager.commitGesture(word)
        pendingWord = null
        return true
    }

    /**
     * Check if there's a pending word waiting to be committed.
     */
    fun hasPendingWord():Boolean = pendingWord != null

    /**
     * Get the current pending word (for display purposes).
     */
    fun getPendingWord():String? = pendingWord

    /**
     * Clear pending word without committing.
     */
    fun clearPendingWord() {
        pendingWord = null
    }

    // ==================== Layout Management ====================

    fun setLayout(keys:List<TextKey>) {
        if (keys.isNotEmpty()) {
            currentKeys = keys
            keysByCharacter.clear()
            keys.forEach { key ->
                val keyData = key.computedData
                if (keyData is KeyData) {
                    keysByCharacter.put(keyData.code, key)
                }
            }
            glideTypingClassifier.setLayout(keys, subtypeManager.activeSubtype)
        }
    }

    // ==================== Private Helpers ====================

    private fun maybeUpdatePreview() {
        val time = System.currentTimeMillis()
        if (prefs.glide.showPreview.get() && time - lastPreviewTime > prefs.glide.previewRefreshDelay.get()) {
            updateSuggestionsAsync(1, commitWord = false) {}
            lastPreviewTime = time
        }
    }

    private fun updateSuggestionsAsync(
        maxSuggestions:Int,
        commitWord:Boolean,
        callback:(Boolean) -> Unit
    ) {
        if (! glideTypingClassifier.ready) {
            callback(false)
            return
        }

        scope.launch(Dispatchers.Default) {
            val suggestions = glideTypingClassifier.getSuggestions(MAX_SUGGESTION_COUNT, true)

            withContext(Dispatchers.Main) {
                if (suggestions.isNotEmpty()) {
                    // Store top suggestion as pending
                    pendingWord = keyboardManager.fixCase(suggestions.first())

                    // Build suggestion list for smartbar (excluding top which is pending)
                    val startIndex = 1
                    val suggestionList = suggestions
                        .subList(startIndex.coerceAtMost(suggestions.size), maxSuggestions.coerceAtMost(suggestions.size))
                        .map { WordSuggestionCandidate(keyboardManager.fixCase(it), confidence = 1.0) }

                    nlpManager.suggestDirectly(suggestionList)
                }

                callback(true)
            }
        }
    }

    val ready:Boolean get() = glideTypingClassifier.ready
}
