package com.mapbox.navigation.core.history

import com.mapbox.navigation.core.history.model.HistoryEvent
import com.mapbox.navigation.core.history.model.HistoryEventMapper
import com.mapbox.navigation.core.history.model.HistoryEventUpdateLocation
import com.mapbox.navigator.HistoryReader

/**
 * Allows you to read history files previously saved by [MapboxHistoryRecorder].
 * All files in the [MapboxHistoryRecorder.fileDirectory] can be read with this reader.
 *
 * @param filePath absolute path to a file containing the native history file.
 */
class MapboxHistoryReader(
    val filePath: String
) : Iterator<HistoryEvent> {

    private val nativeHistoryReader = HistoryReader(filePath)
    private val historyEventMapper = HistoryEventMapper()

    private var hasNext: Boolean = false
    private var next: HistoryEvent? = null

    init {
        hasNext = loadNext()
    }

    /**
     * Returns `true` if the iteration has more elements.
     */
    override fun hasNext(): Boolean = hasNext

    /**
     * Returns the next element in the iteration.
     *
     * @throws NullPointerException when [hasNext] is false
     */
    override fun next(): HistoryEvent = next!!.also {
        hasNext = loadNext()
    }

    /**
     * Loads the next [count] location events from the history file and returns all of the
     * [HistoryEvent] in a list. The size of the list returned will often be larger than [count]
     * because there are other types of events in the history file.
     *
     * @param count the maximum number of [HistoryEventUpdateLocation] to take
     */
    fun takeLocations(count: Int): List<HistoryEvent> {
        val historyEvents = mutableListOf<HistoryEvent>()
        var locationCount = 0
        while (locationCount < count && hasNext) {
            val event = next()
            if (event is HistoryEventUpdateLocation) {
                locationCount++
            }
            historyEvents.add(event)
        }
        return historyEvents
    }

    private fun loadNext(): Boolean {
        val historyRecord = nativeHistoryReader.next()
        next = if (historyRecord != null) {
            historyEventMapper.map(historyRecord)
        } else {
            null
        }
        return next != null
    }
}
