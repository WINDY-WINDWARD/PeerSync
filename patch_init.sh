sed -i '/init {/a \
        // Clean up any zombie Wi-Fi Direct groups left over from previous app crashes\
        if (wifiP2pManager != null) {\
            val tempChannel = wifiP2pManager!!.initialize(context, android.os.Looper.getMainLooper(), null)\
            wifiP2pManager!!.removeGroup(tempChannel, null)\
        }' composeApp/src/androidMain/kotlin/com/peersync/app/network/WifiSocketController.kt
