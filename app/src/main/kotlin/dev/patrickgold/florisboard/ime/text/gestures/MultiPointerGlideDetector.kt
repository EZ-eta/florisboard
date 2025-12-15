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
import android.view.MotionEvent
import dev.patrickgold.florisboard.R
import dev.patrickgold.florisboard.ime.text.key.KeyCode
import dev.patrickgold.florisboard.ime.text.keyboard.TextKey
import dev.patrickgold.florisboard.lib.util.ViewUtils
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Multi-pointer gesture detector for Nintype-style two-thumb sliding.
 * Tracks up to 2 simultaneous gesture paths and merges them into word candidates.
 */
class MultiPointerGlideDetector(context: Context) {

    companion object {
        private const val MAX_POINTERS = 2
        private const val MAX_DETECT_TIME = 500
        private const val VELOCITY_THRESHOLD = 0.10
        private val SWIPE_GESTURE_KEYS = arrayOf(KeyCode.DELETE, KeyCode.SHIFT, KeyCode.SPACE, KeyCode.CJK_SPACE)
    }

    private val keySize = ViewUtils.px2dp(context.resources.getDimension(R.dimen.key_width))
    private val listeners = arrayListOf<Listener>()

    private val pointerDataMap = mutableMapOf<Int, PointerData>()
    private val activePointerIds = mutableListOf<Int>()

    private var mergedGesturePoints = mutableListOf<TimestampedPoint>()
    private var isMultiPointerMode = false
    private var gestureActive = false

    data class TimestampedPoint(
        val x: Float,
        val y: Float,
        val timestamp:  Long,
        val pointerId: Int
    )

    data class PointerData(
        val positions: MutableList<TimestampedPoint> = mutableListOf(),
        var startTime: Long = 0,
        var isActuallyGesture: Boolean?  = null,
        var lastKey: TextKey? = null
    )

    fun onTouchEvent(event: MotionEvent, getKeyForPos: (Float, Float) -> TextKey?): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                resetState()
                handlePointerDown(event, 0, getKeyForPos)
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                val pointerIndex = event.actionIndex
                if (activePointerIds.size < MAX_POINTERS) {
                    handlePointerDown(event, pointerIndex, getKeyForPos)
                    isMultiPointerMode = true
                }
            }
            MotionEvent.ACTION_MOVE -> {
                handlePointerMove(event, getKeyForPos)
            }
            MotionEvent.ACTION_POINTER_UP -> {
                val pointerIndex = event.actionIndex
                val pointerId = event.getPointerId(pointerIndex)
                pointerDataMap[pointerId]?.let { data ->
                    if (data.isActuallyGesture == true) {
                        // Pointer completed its part of the gesture
                    }
                }
            }
            MotionEvent.ACTION_UP -> {
                return handleGestureComplete()
            }
            MotionEvent.ACTION_CANCEL -> {
                listeners.forEach { it.onMultiGlideCancelled() }
                resetState()
            }
        }
        return gestureActive
    }

    private fun handlePointerDown(event: MotionEvent, pointerIndex:  Int, getKeyForPos: (Float, Float) -> TextKey?) {
        val pointerId = event.getPointerId(pointerIndex)
        val x = event.getX(pointerIndex)
        val y = event.getY(pointerIndex)
        val timestamp = System.currentTimeMillis()

        val initialKey = getKeyForPos(x, y)

        if (initialKey != null && SWIPE_GESTURE_KEYS.contains(initialKey.computedData.code)) {
            return
        }

        val pointerData = PointerData(
            positions = mutableListOf(TimestampedPoint(x, y, timestamp, pointerId)),
            startTime = timestamp,
            lastKey = initialKey
        )

        pointerDataMap[pointerId] = pointerData
        activePointerIds.add(pointerId)

        mergedGesturePoints.add(TimestampedPoint(x, y, timestamp, pointerId))
    }

    private fun handlePointerMove(event: MotionEvent, getKeyForPos: (Float, Float) -> TextKey?) {
        for (i in 0 until event.pointerCount) {
            val pointerId = event.getPointerId(i)
            val pointerData = pointerDataMap[pointerId] ?: continue

            val x = event.getX(i)
            val y = event.getY(i)
            val timestamp = System.currentTimeMillis()

            val point = TimestampedPoint(x, y, timestamp, pointerId)
            pointerData.positions.add(point)
            mergedGesturePoints.add(point)

            if (pointerData.isActuallyGesture == null && pointerData.positions.size > 1) {
                val elapsed = timestamp - pointerData.startTime
                if (elapsed > 0 && elapsed < MAX_DETECT_TIME) {
                    val distance = calculateDistance(pointerData.positions)
                    val velocity = distance / elapsed
                    if (velocity > VELOCITY_THRESHOLD) {
                        pointerData.isActuallyGesture = true
                        gestureActive = true
                        listeners.forEach { it.onMultiGlideStart(pointerId) }
                    }
                } else if (elapsed >= MAX_DETECT_TIME) {
                    pointerData.isActuallyGesture = false
                }
            }

            if (pointerData.isActuallyGesture == true) {
                val currentKey = getKeyForPos(x, y)
                listeners.forEach {
                    it.onMultiGlideAddPoint(
                        pointerId = pointerId,
                        position = GlideTypingGesture.Detector.Position(x, y),
                        key = currentKey
                    )
                }
                pointerData.lastKey = currentKey
            }
        }
    }

    private fun handleGestureComplete(): Boolean {
        val hasValidGesture = pointerDataMap.values.any { it.isActuallyGesture == true }

        if (hasValidGesture) {
            val sortedPoints = mergedGesturePoints.sortedBy { it.timestamp }

            val multiPointerData = MultiPointerGestureData(
                pointerData = pointerDataMap.toMap(),
                mergedPoints = sortedPoints,
                isMultiPointer = isMultiPointerMode
            )

            listeners.forEach { it.onMultiGlideComplete(multiPointerData) }
        }

        val wasActive = gestureActive
        resetState()
        return wasActive || hasValidGesture
    }

    private fun calculateDistance(positions: List<TimestampedPoint>): Float {
        if (positions.size < 2) return 0f
        var totalDistance = 0f
        for (i in 1 until positions.size) {
            val dx = positions[i].x - positions[i - 1].x
            val dy = positions[i].y - positions[i - 1].y
            totalDistance += sqrt(dx.pow(2) + dy.pow(2))
        }
        return ViewUtils.px2dp(totalDistance)
    }

    private fun resetState() {
        pointerDataMap.clear()
        activePointerIds.clear()
        mergedGesturePoints.clear()
        isMultiPointerMode = false
        gestureActive = false
    }

    fun registerListener(listener:  Listener) {
        listeners.add(listener)
    }

    fun unregisterListener(listener:  Listener) {
        listeners.remove(listener)
    }

    data class MultiPointerGestureData(
        val pointerData: Map<Int, PointerData>,
        val mergedPoints: List<TimestampedPoint>,
        val isMultiPointer: Boolean
    )

    interface Listener {
        fun onMultiGlideStart(pointerId: Int)
        fun onMultiGlideAddPoint(pointerId:  Int, position: GlideTypingGesture.Detector.Position, key: TextKey?)
        fun onMultiGlideComplete(data: MultiPointerGestureData)
        fun onMultiGlideCancelled()
    }
}
