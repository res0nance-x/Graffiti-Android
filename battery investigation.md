Ran list source files in Graffiti app
Analyzed AndroidManifest.xml:L1-117
Analyzed GraffitiApp.kt:L1-31
Analyzed GraffitiService.kt:L1-440
Analyzed PackMediaPlaybackService.kt:L1-152
Analyzed PackViewActivity.kt:L1-458
Analyzed WebViewActivity.kt:L1-606
Analyzed build.gradle.kts:L1-138
Ran list GraffitiCore source files
Analyzed GraffitiP2P.kt:L1-800
Analyzed GraffitiP2P.kt:L801-1600
Analyzed GraffitiP2P.kt:L1601-1966
Ran list R3 net source files
Analyzed MulticastDiscoverServer.kt:L1-95
Analyzed ScanDiscoverServer.kt:L1-68
Analyzed TCPNode.kt:L1-251
Analyzed GraffitiAPI.kt:L1-200
Ran list R3 http source files
Analyzed WebServer.kt:L1-251
Ran search web timers and WebSockets · Exited with code 1
Ran search web files for intervals and WebSockets
Analyzed storage.ts:L240-326
Analyzed network.ts:L510-569
Analyzed index.html:L850-920

The short answer: **It is unlikely that Graffiti’s own threads burned the CPU directly, because Android’s kernel-level CPU and wake-lock accounting would have attributed that usage to Graffiti’s app entry rather than "System apps" (70%). However, Graffiti *can* indirectly cause high "System apps" drain via the Wi-Fi and multicast locks it holds continuously.**

Here is how the symptoms line up against how Graffiti works under the hood:

---

### 1. Why the symptoms point *away* from Graffiti burning CPU directly
* **Per-UID CPU & Wake-Lock Attribution:** Android tracks CPU time per Linux UID (via kernel `uid_time_in_state` / eBPF) and wake-lock time per app in `BatteryStatsService`. If a thread inside Graffiti (e.g., `GraffitiP2P`, `TCPNode`, or `WebServer`) was spinning the CPU, or if Android blamed Graffiti's wake lock directly, **Graffiti itself would show up with the lion's share of the battery percentage**, not "System apps".
* **Effect of "Battery Optimization ON" (`Optimized`):**
  * Even though [`GraffitiService`](file:///d:/StudioProjects/Graffiti/app/src/main/java/r3/graffiti/GraffitiService.kt#L54-L183) runs as a `specialUse` Foreground Service and acquires a `PARTIAL_WAKE_LOCK` (`"Graffiti:TransferWakeLock"`), when battery optimization is left **ON**, Android’s Doze mode is still allowed to ignore/disable the app's `PARTIAL_WAKE_LOCK` once the device is stationary with the screen off.
  * When Doze suppresses an optimized app's wake lock, the app cannot keep the CPU awake on its own.

---

### 2. How Graffiti *could* indirectly cause "System apps" drain
If you look at [`GraffitiService.onStartCommand`](file:///d:/StudioProjects/Graffiti/app/src/main/java/r3/graffiti/GraffitiService.kt#L112-L155), as soon as the service starts, it acquires and holds **three locks unconditionally for the entire lifetime of the service**—even when no transfers are active and no peers are connected:

1. **`MulticastLock` (`"GraffitiMulticastLock"`)** — [`GraffitiService.kt:112-123`](file:///d:/StudioProjects/Graffiti/app/src/main/java/r3/graffiti/GraffitiService.kt#L112-L123)
   * Normally, the Wi-Fi chip’s hardware filter (APF) silently drops LAN broadcast/multicast chatter (mDNS, ARP, SSDP, router advertisements) without waking the main CPU.
   * Holding a `MulticastLock` 24/7 disables that hardware filter, forcing the Wi-Fi chip to wake the main CPU and pass **every multicast/broadcast packet on the network** up to the Linux kernel, `netd`/`network_stack`, and `system_server`.
   * That kernel wake-lock (`wlan_rx_wake` / `qcom_rx_wakelock`) and system packet processing are attributed to **"System apps"**, not to Graffiti.
2. **`WifiLock` in `WIFI_MODE_FULL_LOW_LATENCY` mode** — [`GraffitiService.kt:125-142`](file:///d:/StudioProjects/Graffiti/app/src/main/java/r3/graffiti/GraffitiService.kt#L125-L142)
   * `WIFI_MODE_FULL_LOW_LATENCY` disables Wi-Fi power-save (DTIM sleep) at the radio/firmware level to minimize packet latency. Note that Android docs specifically state `WIFI_MODE_FULL_LOW_LATENCY` is only active while the app is in the foreground or under specific conditions, and otherwise falls back, but combined with `PARTIAL_WAKE_LOCK` (during Light Doze or before Deep Doze kicks in), it keeps the Wi-Fi subsystem in a high-power state.
3. **`PARTIAL_WAKE_LOCK` (`"Graffiti:TransferWakeLock"`)** — [`GraffitiService.kt:144-155`](file:///d:/StudioProjects/Graffiti/app/src/main/java/r3/graffiti/GraffitiService.kt#L144-L155)
   * Despite being named `"Graffiti:TransferWakeLock"`, it is acquired on service startup and never released until the service is stopped. If the device didn't enter Deep Doze (e.g., it was moving slightly, or OEM policy kept foreground services out of Deep Doze), keeping the CPU awake all night allows background system processes and `WebView` / media services to run continuously without Doze throttling them.
4. **Unpaused `WebView` when backgrounded:**
   * Neither [`WebViewActivity`](file:///d:/StudioProjects/Graffiti/app/src/main/java/r3/graffiti/WebViewActivity.kt#L201-L209) nor [`PackViewActivity`](file:///d:/StudioProjects/Graffiti/app/src/main/java/r3/graffiti/PackViewActivity.kt#L448-L457) calls `webView.onPause()` / `webView.onResume()` in the Activity’s `onPause()` / `onResume()` lifecycle methods (`webView.onPause()` is only called in `onDestroy()`). If a pack viewer playlist timer (`requestAnimationFrame` in [`index.html:871-885`](file:///d:/IdeaProjects/GraffitiCore/src/main/resources/web/playlist/index.html#L871-L885)) or media element was left open in the background while the CPU was held awake, `audioserver` / `mediaserver` / `SurfaceFlinger` ("System apps") can stay active.

---

### 3. How to check the exact culprit on that test device
Before charging the device above 90% (which resets Android's battery stats history), you can run these `adb` commands to get the exact smoking gun:

1. **Check currently held wake locks (and whether Doze disabled Graffiti's lock):**
   ```powershell
   adb shell dumpsys power | Select-String -Pattern "Wake Locks|Graffiti|PARTIAL_WAKE_LOCK" -Context 2,10
   ```
   *(If Battery Optimization suppressed Graffiti's wake lock, Android prints `PARTIAL_WAKE_LOCK 'Graffiti:TransferWakeLock' ... (DISABLED)`).*

2. **See which exact process/UID and wake locks used the battery overnight:**
   ```powershell
   adb shell dumpsys batterystats | Select-String -Pattern "Estimated power use|All partial wake locks|All kernel wake locks" -Context 0,25
   ```
   * If you see **`wlan_rx_wake` / `qcom_rx_wakelock` / `netd` / `system`** at the top of kernel wake locks, Graffiti’s `MulticastLock` + noisy LAN traffic is a strong suspect.
   * If you see **`gms` (Google Play Services), `update_engine` (OTA update), `media.extractor`, or `system_server` sync loops**, it was an unrelated Android system task.

3. **If the high CPU usage is still happening right now:**
   ```powershell
   adb shell top -n 1 -m 15
   ```

---

### 4. Potential improvements in Graffiti (if you'd like to harden it)
Even if an OS process caused this specific spike, [`GraffitiService`](file:///d:/StudioProjects/Graffiti/app/src/main/java/r3/graffiti/GraffitiService.kt#L112-L155) currently holds all three locks (`PARTIAL_WAKE_LOCK`, `WIFI_MODE_FULL_LOW_LATENCY`, `MulticastLock`) 100% of the time. If you want to tighten battery usage, we could:
* Only hold `Graffiti:TransferWakeLock` and `WIFI_MODE_FULL_LOW_LATENCY` when `p2p.isAnyTransferActive()` is true (using `p2p.addOnTransferStateChangedListener`), or with a short timeout during sync/message receipt.
* Call `webView.onPause()` / `webView.onResume()` in [`WebViewActivity`](file:///d:/StudioProjects/Graffiti/app/src/main/java/r3/graffiti/WebViewActivity.kt) and [`PackViewActivity`](file:///d:/StudioProjects/Graffiti/app/src/main/java/r3/graffiti/PackViewActivity.kt) when the activities go into the background.