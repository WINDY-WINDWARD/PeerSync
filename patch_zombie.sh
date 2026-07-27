sed -i '/fun startHosting(sessionName: String, pin: String) {/a \
        // Attempt to clean up any zombie P2P groups from previous crashes\
        if (wifiP2pManager != null) {\
            if (p2pChannel == null) {\
                p2pChannel = wifiP2pManager!!.initialize(context, android.os.Looper.getMainLooper(), null)\
            }\
            wifiP2pManager!!.removeGroup(p2pChannel, null)\
        }' composeApp/src/androidMain/kotlin/com/peersync/app/network/WifiSocketController.kt
