# RideCommS - Frequently Asked Questions (FAQ) & Troubleshooting

## 1. Wi-Fi & Session Discovery

### Q: Why is a session name or `DIRECT-PS-...` network still showing up after it was destroyed?
**A:** This is known as a **zombie network** or **ghost scan result**. 

* **Cause**: Android OS (`WifiManager` and `wpa_supplicant`) caches Wi-Fi access point scan results at the driver level. When a Group Owner (Host) ends a session or destroys a Wi-Fi Direct group, nearby client devices may retain the old beacon in their OS scan cache for a few scan cycles, or Android may keep a persistent P2P network profile in its system server.
* **Quick Fix (User)**: Toggle Wi-Fi **OFF** and **ON** on your device (Settings &rarr; Wi-Fi &rarr; Off &rarr; On). Toggling Wi-Fi immediately flushes the Android Wi-Fi driver cache and clears lingering SoftAP/P2P instances.
* **Built-in App Protections**:
  - **Timestamp Filtering**: RideCommS automatically discards any Wi-Fi scan result older than 15 seconds.
  - **Persistent Group Purging**: When ending a session, RideCommS issues persistent group teardown requests to remove saved P2P network profiles.
  - **Single Specifier Request**: RideCommS issues connection requests once without spamming background network specifiers.

### Q: Why can't my device find any nearby sessions?
**A:** Ensure the following settings are enabled on all participating devices:
1. **Wi-Fi**: Wi-Fi must be turned ON (you do not need to be connected to an active router or internet network).
2. **Location Services**: On Android 12 and below, Android requires system **Location (GPS)** to be ON for Wi-Fi scanning.
3. **Permissions**: Ensure **Nearby Devices** (Android 13+) or **Fine Location** permissions are granted in RideCommS Settings.

---

## 2. Connections & PIN Authentication

### Q: Where do I find the PIN to join a session?
**A:** The **Group Owner (Host)** can view the 8-digit PIN by tapping the **QR Code / PIN** icon in the top left banner of the Active Session screen. Guests can enter the PIN manually or scan the QR code to join automatically.

### Q: What happens if a device temporarily loses connection?
**A:** RideCommS automatically enters a **5-minute fallback reconnection mode**. It will attempt to seamlessly re-establish the Wi-Fi Direct link to the host without destroying your session state.

---

## 3. Audio & Bluetooth Intercom

### Q: How do I switch audio output to a Bluetooth helmet headset?
**A:** 
1. Pair your Bluetooth headset with your Android phone.
2. In the RideCommS **Audio Controls** section, select **Bluetooth**.
3. If multiple Bluetooth devices are connected, tap your headset from the Bluetooth selection dialog.

### Q: How do I mute my microphone?
**A:** Tap the **Mic Mute** toggle button inside the Audio Controls card or in the bottom bar during an active session.

---

## 4. Music Sharing & Host Controls

### Q: Who can control music playback during a session?
**A:** By default, the **Group Owner** controls music playback. Any peer can request media hosting permissions by tapping **Request Music Host** in the Media Controls card.
