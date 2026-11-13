package com.mg4.streams

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/** Which connection the head unit is using. CAR = the car's own modem (Ethernet to the TBOX). */
enum class NetKind(val label: String, val key: String) {
    CAR("Car internet", "car"),
    WIFI("Wi-Fi", "wifi"),
    MOBILE("Mobile", "mobile"),
    VPN("VPN", "vpn"),
    OTHER("Online", "other"),
    NONE("Offline", "none");

    companion object {
        fun current(context: Context): NetKind {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val network = cm.activeNetwork ?: return NONE
            val caps = cm.getNetworkCapabilities(network) ?: return NONE
            if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return NONE
            return when {
                caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> VPN
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> WIFI
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> CAR
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> MOBILE
                else -> OTHER
            }
        }
    }
}
