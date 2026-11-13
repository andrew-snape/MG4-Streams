package com.mg4.streams

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.Parcel
import android.util.Log

/**
 * Reports whether the car is in P, read from the SAIC vehicle hub.
 *
 * Read-only: it only calls the hub's getService and the vehiclecondition gear
 * transaction. Any failure (no hub, no binder, unknown code) counts as not parked.
 * Gear codes confirmed on the car: 1 = P, 2 = R, 3 = N, 4 = D.
 *
 * isParked() makes Binder calls, so call it off the main thread.
 */
class SaicGearGate : ParkedGate {

    @Volatile private var hub: IBinder? = null
    @Volatile private var condition: IBinder? = null
    @Volatile private var bindRequested = false

    /** Starts the asynchronous bind to the hub if it is not already bound or pending. */
    fun connect(context: Context) {
        if (bindRequested) return
        boundContext = context.applicationContext
        bindRequested = runCatching {
            context.applicationContext.bindService(
                Intent(HUB_ACTION).setPackage(HUB_PACKAGE),
                connection,
                Context.BIND_AUTO_CREATE,
            )
        }.getOrDefault(false)
    }

    /** Stops the bind. Call from the activity's onDestroy. */
    fun disconnect(context: Context) {
        if (!bindRequested) return
        runCatching { context.applicationContext.unbindService(connection) }
        resetBinding()
    }

    private fun resetBinding() {
        bindRequested = false
        hub = null
        condition = null
    }

    private var boundContext: Context? = null

    private fun unbindAndReset() {
        boundContext?.let { runCatching { it.unbindService(connection) } }
        resetBinding()
    }

    override fun isParked(): Boolean = readGear() == GEAR_PARK

    private fun readGear(): Int? {
        val hubBinder = hub?.takeIf { it.isBinderAlive } ?: return null
        val conditionBinder = condition?.takeIf { it.isBinderAlive }
            ?: callBinder(hubBinder, HUB_DESCRIPTOR, TX_GET_SERVICE, "vehiclecondition")?.also { condition = it }
            ?: return null
        return callInt(conditionBinder, CONDITION_DESCRIPTOR, TX_CONDITION_GEAR)
            ?.takeIf { it > 0 }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            hub = service
            condition = null
            Log.i(TAG, "vehicle hub connected")
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            hub = null
            condition = null
            Log.w(TAG, "vehicle hub disconnected")
        }

        // The hub died or returned no binder: unbind, drop the state, and let the next connect() rebind.
        override fun onBindingDied(name: ComponentName?) {
            Log.w(TAG, "vehicle hub binding died")
            unbindAndReset()
        }

        override fun onNullBinding(name: ComponentName?) {
            Log.w(TAG, "vehicle hub returned a null binding")
            unbindAndReset()
        }
    }

    /** AIDL call convention: interface token, arguments, transact, readException, then the result. */
    private fun callInt(binder: IBinder, descriptor: String, code: Int): Int? =
        transact(binder, descriptor, code, null) { it.readInt() }

    private fun callBinder(binder: IBinder, descriptor: String, code: Int, arg: String): IBinder? =
        transact(binder, descriptor, code, arg) { it.readStrongBinder() }

    private fun <T> transact(
        binder: IBinder,
        descriptor: String,
        code: Int,
        arg: String?,
        readReply: (Parcel) -> T,
    ): T? {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(descriptor)
            if (arg != null) data.writeString(arg)
            if (!binder.transact(code, data, reply, 0)) return null
            reply.readException()
            readReply(reply)
        } catch (error: Exception) {
            Log.d(TAG, "$descriptor#$code failed: ${error.message}")
            null
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    private companion object {
        const val TAG = "MG4_STREAMS_GATE"
        const val HUB_PACKAGE = "com.saicmotor.service.vehicle"
        const val HUB_ACTION = "com.saicmotor.service.vehicle.VehicleService"
        const val HUB_DESCRIPTOR = "com.saicmotor.sdk.vehiclesettings.IHubService"
        const val CONDITION_DESCRIPTOR = "com.saicmotor.sdk.vehiclesettings.IVehicleConditionService"
        const val TX_GET_SERVICE = 1
        const val TX_CONDITION_GEAR = 5
        const val GEAR_PARK = 1
    }
}
