package com.mg4.streams

/**
 * Decides whether video may play. Audio is always allowed.
 *
 * Implemented by SaicGearGate, which reads the gear from the SAIC vehicle hub.
 * If the gear can't be read, it returns false and video stays off.
 * Speed alone is not used: a car stopped at lights reads 0 km/h.
 */
interface ParkedGate {
    fun isParked(): Boolean
}
