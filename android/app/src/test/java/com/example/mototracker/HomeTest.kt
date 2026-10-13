package com.example.mototracker

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Arriving home ends a trip (Jack, 2026-10-12) — and only a trip that has been away. */
class HomeTest {

    private val away = 2_000.0
    private val atDoor = 30.0

    @Test
    fun arrivingAfterARideEndsTheTrip() {
        val w = Home.Watch()
        assertFalse(w.offer(away, 5f, 60.0))
        assertFalse(w.offer(atDoor, 5f, 3.0), "one fix is not enough")
        assertTrue(w.offer(atDoor, 5f, 2.0), "two in a row is arriving")
    }

    @Test
    fun aTripOpenedAtHomeIsNotEndedUntilItHasLeft() {
        val w = Home.Watch()
        repeat(10) { assertFalse(w.offer(atDoor, 5f, 0.0)) }
        w.offer(away, 5f, 40.0)
        assertFalse(w.offer(atDoor, 5f, 0.0))
        assertTrue(w.offer(atDoor, 5f, 0.0))
    }

    @Test
    fun passingTheHouseAtSpeedIsNotArriving() {
        val w = Home.Watch()
        w.offer(away, 5f, 60.0)
        repeat(5) { assertFalse(w.offer(atDoor, 5f, 50.0)) }
    }

    @Test
    fun boundaryWobbleCannotEndATripTheGeofenceJustOpened() {
        val w = Home.Watch()
        w.offer(Home.RADIUS_M + 10.0, 5f, 20.0)
        repeat(5) { assertFalse(w.offer(Home.RADIUS_M - 20.0, 5f, 0.0)) }
    }

    @Test
    fun aVagueFixSaysNothing() {
        val w = Home.Watch()
        w.offer(away, 5f, 60.0)
        repeat(5) { assertFalse(w.offer(atDoor, 300f, 0.0)) }
    }

    @Test
    fun aStrayFixBetweenTwoDoesNotCount() {
        val w = Home.Watch()
        w.offer(away, 5f, 60.0)
        assertFalse(w.offer(atDoor, 5f, 0.0))
        assertFalse(w.offer(Home.RADIUS_M - 10.0, 5f, 0.0))
        assertFalse(w.offer(atDoor, 5f, 0.0))
    }

    @Test
    fun distanceIsRoughlyRight() {
        // 0.001 degrees of latitude is about 111 m.
        assertEquals(111.0, Home.distanceM(34.0, -84.0, 34.001, -84.0), 1.0)
    }
}
