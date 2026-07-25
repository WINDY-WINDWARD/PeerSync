# Software Requirements Specification (SRS)
## Project Name: PeerSync Intercom & Media Share (Android-Exclusive)
## Document Version: 1.0.0
## Date: July 2026

---

## 1. Introduction

### 1.1 Purpose
This document specifies the functional, non-functional, and architectural requirements for "PeerSync Intercom", a decentralized, offline, full-duplex (free talk) communication and audio-streaming application built exclusively for Android. 

### 1.2 Scope
The system enables a minimum of five (5) Android devices to communicate simultaneously in real-time over an ad-hoc local network without requiring cellular data, internet access, or external routers. Additionally, the session creator (Group Owner) can broadcast high-fidelity music to all participants while maintaining an open voice intercom channel. Music playback and media controls are available to all peers but controlled exclusively by the Group Owner.

### 1.3 Key Concepts & Definitions
* **Full-Duplex (Free Talk):** Continuous, bidirectional audio streaming where all participants can speak and hear each other simultaneously without pressing buttons.
* **Group Owner (GO):** The Android device that creates a session. The GO manages session state, relays audio streams, and holds exclusive media playback control.
* **Client Spoke:** A peer Android device that joins an existing session hosted by the Group Owner.
* **Media Host:** Deprecated term. Media playback and control are now exclusively managed by the Group Owner (session creator).
* **Audio Ducking:** Automatically lowering the background music volume on a device when active human speech is detected on the network.
* **Jitter Buffer:** A localized memory buffer that reorders and aligns incoming network packets before playback to eliminate choppy audio.

---

## 2. System Architecture & Network Topology

### 2.1 Network Model: Local Star Topology via Google Nearby Connections API
The system operates via the **Google Nearby Connections API** using a Hub-and-Spoke structure with the P2P_CLUSTER strategy.
* **The Hub (GO):** The session creator becomes the Group Owner (GO) and manages the network group. The GO receives all audio streams from connected peers, relays them to all other participants, and controls media playback.
* **The Spokes:** Client devices discover and connect to the GO's advertised session. All audio and control traffic flows through the GO via the Nearby Connections API, minimizing point-to-point wireless interference.
* **Bandwidth Efficiency:** The Nearby Connections API intelligently selects the best available transport (Wi-Fi Direct, Bluetooth, or other local mechanisms) based on device capabilities and signal conditions. Pure Bluetooth is avoided where possible to handle 5+ simultaneous full-duplex voice streams plus high-fidelity music.

### 2.2 Dual-Plane Network Architecture
To optimize latency and system stability, network traffic is logically segregated into two concurrent planes over the Nearby Connections API:
* **The Control Plane:** Manages session state, connection handshakes, member list updates, media playback controls (Play/Pause/Skip), and heartbeat signals.
* **The Data Plane:** Dedicated to high-speed, stateless streaming of audio bytes. Audio frames are transmitted as individual packets with headers identifying source and payload type. If packet loss occurs due to range limitations, the system discards the lost frames and plays the next chronological packet to prevent voice lag.

Both planes utilize the underlying Nearby Connections API, which abstracts the transport layer (Wi-Fi Direct, Bluetooth, or other mechanisms) to ensure reliable, low-latency delivery suitable for real-time audio communication.

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
* **Autonomous Discovery:** The system utilizes the Google Nearby Connections API to discover and advertise PeerSync sessions. The session creator (GO) advertises a unique, encrypted service with a custom endpoint identifier. Discovering devices scan for active PeerSync advertisements and display found sessions without requiring manual IP entry.
* **Automatic Pairing:** Devices running the app must present a 6-digit numeric PIN to join a session. The PIN is validated by the GO before the device is added to the active member list.
* **Network Resilience & Handover:** If a Client Spoke disconnects, the rest of the group conversation must remain uninterrupted. If the Group Owner disconnects unexpectedly, the remaining clients must execute a silent background election protocol to promote the highest-ranked remaining client to Group Owner. The new GO re-advertises the session, and all clients reconnect with the same session PIN.

### 3.2 Full-Duplex Audio Engine (F-02)
* **Always-On Open Mic:** Sockets must remain continuously open for all connected users, enabling hands-free operation resembling a physical helmet intercom or a traditional phone call.
* **Voice Activation Detection (VAD):** To preserve battery life and prevent local Wi-Fi congestion, an internal VAD module must analyze input audio. If sound drops below a configurable ambient decibel threshold, the app must stop transmitting payload packets and only send lightweight "Keep-Alive" heartbeats.
* **Acoustic Management Integration:** The system must intercept audio at the hardware abstraction layer using communication-priority pipelines. This forces the device's Digital Signal Processor (DSP) to apply native Acoustic Echo Cancellation (AEC), Automatic Gain Control (AGC), and background Noise Suppression to completely eliminate audio feedback when speakers are used without headphones.

### 3.3 Dual-Stream Mixing & Media Sharing (F-03)
* **Media Broadcast Ownership:** Only the Group Owner (session creator) can broadcast music to all participants. Music is selected via the Android Storage Access Framework (SAF) file picker and streamed to all connected peers.
* **Audio Split Processing:** The Group Owner must not merge microphone input and music files into a single network payload. Instead, it must compress and stream them as separate structural pipelines to preserve audio fidelity. Voice stream remains at 16kHz Mono; music stream is at 44.1kHz Stereo.
* **Packet Structure Specifications:** Every outgoing packet over the Data Plane must contain a 4-byte header consisting of:
  1. *User Origin ID* (1 byte: identifies the speaking/sharing device)
  2. *Payload Flag* (`0x00` = Keep-Alive heartbeat, `0x01` for Voice optimized at 16kHz Mono; `0x02` for Music optimized at 44.1kHz Stereo)
  3. *Sequence Index* (2 bytes: tracks chronological alignment)
* **Client-Side Summing & Ducking:** Receiving devices run incoming packets through a digital mixer module. When the payload flag changes to `0x01` (active human speech), the mixer must automatically apply **Audio Ducking**—lowering the volume of the `0x02` music stream by a pre-set percentage—and restore music volume once speech halts.
* **Group Owner Playback Controls:** The GO (or any peer on behalf of the GO) can control music playback (Play/Pause/Skip) via control messages. These commands are relayed to all peers to keep playback synchronized.

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
| **TC-01** | Multi-Peer Capacity | Connect 5 Android devices via Nearby Connections API. | Group Owner maps 4 distinct client connections. All 5 mics stream concurrently without dropped connections. |
| **TC-02** | Audio Ducking | Group Owner plays a local MP3 file while User B begins speaking. | Receiving devices decode the Voice header packet, immediately attenuating the Music output level by 60%. |
| **TC-03** | System Persistence | Lock the device screen on 3 out of 5 connected client phones. | Foreground service retains wake-locks; Nearby Connections remain fully active; voice feed continues. |

### 5.2 Recommended Structural References
Developers working on this implementation should model their system loops after the following conceptual models:
* **The Android Open-Source Project (AOSP) Native Communication Loop:** Study native communication routing setups to understand how to prioritize microphone frames over background media frames at the hardware kernel level.
* **Real-time Transport Protocol (RTP) Sequence Architectures:** Model the client-side jitter buffer on standard RTP specifications, implementing a sliding time-window array that drops late packets rather than delaying the real-time audio playback stream.