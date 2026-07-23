# Software Requirements Specification (SRS)
## Project Name: PeerSync Intercom & Media Share (Android-Exclusive)
## Document Version: 1.0.0
## Date: July 2026

---

## 1. Introduction

### 1.1 Purpose
This document specifies the functional, non-functional, and architectural requirements for "PeerSync Intercom", a decentralized, offline, full-duplex (free talk) communication and audio-streaming application built exclusively for Android. 

### 1.2 Scope
The system enables a minimum of five (5) Android devices to communicate simultaneously in real-time over an ad-hoc local network without requiring cellular data, internet access, or external routers. Additionally, the system allows any single connected device to act as a media host, broadcasting high-fidelity music to all participants while maintaining an open voice intercom channel.

### 1.3 Key Concepts & Definitions
* **Full-Duplex (Free Talk):** Continuous, bidirectional audio streaming where all participants can speak and hear each other simultaneously without pressing buttons.
* **Group Owner (GO):** The central Android node in a Wi-Fi Direct network that acts as the virtual access point and DHCP server.
* **Client Spoke:** A peer Android device connected to the Group Owner.
* **Audio Ducking:** Automatically lowering the background music volume on a device when active human speech is detected on the network.
* **Jitter Buffer:** A localized memory buffer that reorders and aligns incoming network packets before playback to eliminate choppy audio.

---

## 2. System Architecture & Network Topology

### 2.1 Network Model: Local Star Topology
The system operates exclusively via **Wi-Fi Direct (Wi-Fi P2P)** arranged in a Hub-and-Spoke structure.
* **The Hub:** One dynamically elected or manually selected Android device instantiates the network as the Wi-Fi P2P Group Owner (GO).
* **The Spokes:** Up to 4 (or more) Android Client devices connect to the GO. All communication routes through the GO to minimize point-to-point wireless interference.
* **Bandwidth Overhead:** Pure Bluetooth (Classic or BLE) is strictly avoided for the primary network backbone due to its architectural throughput limits when handling 5 simultaneous full-duplex voice streams plus high-fidelity music.

### 2.2 Dual-Plane Network Architecture
To optimize latency and system stability, network traffic is segregated into two parallel logical planes:
* **The Control Plane (TCP):** Manages session state, connection handshakes, routing table updates, active member lists, and media controls (Play/Pause/Skip synchronization signals).
* **The Data Plane (UDP):** Dedicated to high-speed, stateless streaming of audio bytes. If packet loss occurs due to range limitations, the system discards the lost frames and plays the next chronological packet to prevent voice lag.

┌──────────────────────┐
│  Group Owner (Hub)   │
│  • Control (TCP)     │
│  • Data Router (UDP) │
└──────────┬───────────┘
           │
    ┌──────┴──────┐
    ▼             ▼
┌───────┐      ┌───────┐
│Client │      │Client │
│  1    │      │  2    │
└───────┘      └───────┘
    ▲             ▲
    └──────┬──────┘
           ▼
┌──────────────────────┐
│ Client 3 (Music + V) │
│  • Media Host Node   │
└──────────────────────┘

---

## 3. Functional Requirements

### 3.1 Peer Discovery & Session Management (F-01)
* **Autonomous Discovery:** The system must utilize Wi-Fi Direct Network Service Discovery (NSD) to broadcast a custom, encrypted cryptographic service token unique to this application.
* **Automatic Pairing:** Devices running the app must scan for this specific token in the background and display found sessions cleanly. No manual typing of IP or MAC addresses is permitted.
* **Network Resilience & Handover:** If a Client Spoke disconnects, the rest of the group conversation must remain uninterrupted. If the Group Owner drops unexpectedly, the remaining clients must execute a silent background negotiation protocol to elect a new Group Owner and re-establish the socket plane.

### 3.2 Full-Duplex Audio Engine (F-02)
* **Always-On Open Mic:** Sockets must remain continuously open for all connected users, enabling hands-free operation resembling a physical helmet intercom or a traditional phone call.
* **Voice Activation Detection (VAD):** To preserve battery life and prevent local Wi-Fi congestion, an internal VAD module must analyze input audio. If sound drops below a configurable ambient decibel threshold, the app must stop transmitting payload packets and only send lightweight "Keep-Alive" heartbeats.
* **Acoustic Management Integration:** The system must intercept audio at the hardware abstraction layer using communication-priority pipelines. This forces the device's Digital Signal Processor (DSP) to apply native Acoustic Echo Cancellation (AEC), Automatic Gain Control (AGC), and background Noise Suppression to completely eliminate audio feedback when speakers are used without headphones.

### 3.3 Dual-Stream Mixing & Media Sharing (F-03)
* **Media Broadcast Ownership:** Any single peer on the network can request and receive the "Media Host" token to share music. 
* **Audio Split Processing:** The Media Host must not merge microphone input and music files into a single network payload. Instead, it must compress and stream them as separate structural pipelines to preserve audio fidelity.
* **Packet Structure Specifications:** Every outgoing packet over the UDP Data Plane must contain a 4-byte header consisting of:
  1. *User Origin ID* (1 byte: identifies the speaking/sharing device)
  2. *Payload Flag* (`0x01` for Voice optimized at 16kHz Mono; `0x02` for Music optimized at 44.1kHz Stereo)
  3. *Sequence Index* (2 bytes: tracks chronological alignment)
* **Client-Side Summing & Ducking:** Receiving devices must run incoming packets through a digital mixer module. When the payload flag changes to `0x01` (active human speech), the mixer must automatically apply **Audio Ducking**—lowering the volume of the `0x02` music stream by a pre-set percentage—and restore music volume once speech halts.

---

## 4. Non-Functional Requirements

### 4.1 Performance & Latency
* **Round-Trip Delay:** Network latency across all 5 active nodes over the local UDP plane must not exceed 40 milliseconds (ms) to maintain realistic conversation tracking.
* **Audio Synchronization:** The shared music stream playback must not drift across devices by more than 50ms. The system will track internal Network Time Protocol (NTP) timestamps over the TCP Control Plane to synchronize the playback clocks.

### 4.2 System Reliability & Resource Constraints
* **Background Persistence:** The core networking and audio loops must reside inside an Android Foreground Service. This ensures the Android Operating System does not throttle or kill the connection antennas when the smartphone screen turns off or when the application is minimized.
* **Memory Management:** To avoid runtime memory leaks and high Garbage Collection (GC) pauses within the Java Virtual Machine, raw byte array operations and audio encoding must be offloaded down into the native system layer using efficient native ring buffers.

### 4.3 Security & Permissions
* **Local Security:** The network token used during discovery must change dynamically or incorporate a lightweight local pin validation system to prevent unauthorized local devices from hijacking the voice channel.
* **System Permissions:** The application must explicitly prompt the user for runtime permissions including `NEARBY_WIFI_DEVICES`, `ACCESS_FINE_LOCATION` (required by Android for older Wi-Fi hardware discovery), and `RECORD_AUDIO`.

---

## 5. Architectural Blueprints & Verification Matrix

### 5.1 Verification Scenarios

| Test Case ID | Feature Tested | Verification Condition | Expected Result |
| :--- | :--- | :--- | :--- |
| **TC-01** | Multi-Peer Capacity | Connect 5 Android devices via Wi-Fi P2P. | Group Owner maps 4 distinct client IPs. All 5 mics stream concurrently without dropped sockets. |
| **TC-02** | Audio Ducking | Play a local MP3 file from Device A while User B begins speaking. | Receiving devices decode the Voice header packet, immediately attenuating the Music output level by 60%. |
| **TC-03** | System Persistence | Lock the device screen on 3 out of 5 connected client phones. | Foreground service retains wake-locks; Wi-Fi Direct antennas remain fully active; voice feed continues. |

### 5.2 Recommended Structural References
Developers working on this implementation should model their system loops after the following conceptual models:
* **The Android Open-Source Project (AOSP) Native Communication Loop:** Study native communication routing setups to understand how to prioritize microphone frames over background media frames at the hardware kernel level.
* **Real-time Transport Protocol (RTP) Sequence Architectures:** Model the client-side jitter buffer on standard RTP specifications, implementing a sliding time-window array that drops late packets rather than delaying the real-time audio playback stream.