package com.example.mototracker

/**
 * What this phone's own sensors are reading right now, for the riding screen
 * (Jack, 2026-10-13). Updated by the ride service every tick while riding; the
 * peaks are the hardest of the whole trip and start again with the next one.
 */
object Live {
    @Volatile var g = Double.NaN
        private set
    @Volatile var rot = Double.NaN
        private set
    @Volatile var peakG = 0.0
        private set
    @Volatile var peakRot = 0.0
        private set

    fun offer(s: Sensors.Summary) {
        if (s.n > 0) { g = s.peakG; if (s.peakG > peakG) peakG = s.peakG }
        if (s.gyroN > 0) { rot = s.peakRot; if (s.peakRot > peakRot) peakRot = s.peakRot }
    }

    fun reset() { g = Double.NaN; rot = Double.NaN; peakG = 0.0; peakRot = 0.0 }
}
