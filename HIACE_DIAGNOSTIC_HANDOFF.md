# HiAce wired CarPlay diagnostic handoff

Goal: stable wired Apple CarPlay on the existing HiAce headunit. A working alternative app is acceptable; current focus is this repository only, to avoid repeating tests. Do not modify MCU/CAN, flash firmware or bypass the CarPlay guard.

## Device
- A007, UMS512, Android 10, Vastking KingPad_SA10_EEA, build 202306172053, MUSB host.
- MFi I2C /dev/i2c-1; wireless off. Development package com.shilapi.xcertplay.hiace.
- ZLink already tested unsuccessfully; SpeedPlay sd_2.0.33 detects TS10 but sd_carplay socket/service not observed and init property empty; parked, no license purchase.

## Confirmed on 2026-11-28, V1.0.12 (163d265)
- iAP2 session and TCP acknowledgement working; NowPlayingUpdate, PowerUpdate, CallStateUpdate received.
- NCM control interface 3/0 and data interface 4/1 claimed; endpoint OUT 0x06, IN 0x88, status 0x87.
- GET_NTB_PARAMETERS 28 bytes: formats=3, inMax=32764, outMax=32764, outDivisor=4, outRemainder=0, outAlignment=4.
- SET_NTB_FORMAT NTB16=0, SET_NTB_INPUT_SIZE 32764=4, setInterface 4/1=true, packet filter=0.
- Gate opens after StartSession stream TX. IPv6 announcement fe80::2 -> ff02::1 ICMPv6 136 delivered to USB OUT (116/116 bytes). OUT periodically times out then recovers. IN always -1 in observed log; interrupt UsbRequest queue fails. No AirPlay UI.
- Native diagnostic experiment rejected by guard; reverted. Guard unchanged. Do not reintroduce native code to bypass it.

## V1.0.13 SmallNtb
- Test a smaller negotiated input limit of 8192 bytes (buffer 8196) only when SET_NTB_INPUT_SIZE accepts it; otherwise keep original supported limit. Keep output size, status handling, data gate, IPv6 announcement and authentication unchanged.
- Hypothesis only: receive transfer size may affect the host. A -1 alone does not prove controller failure. Test live before any further changes.
- Verify installed version, unplug/replug after app restart and clear logs. Capture early NCM negotiation, gate, announcement, bulk IN/OUT and status. No further alternative app tests unless explicitly requested.

## V1.0.13 test result
- 8192 input accepted (result=4), actual read buffer 8196. StartSession and IPv6 announcement 116/116 still work, interrupt queue fails, bulk IN remains -1. No AirPlay. Smaller buffers alone did not fix it.

## V1.0.14 AsyncRx
- Keep accepted 8 KiB input and existing output/IPv6/iAP2. Test Android UsbRequest for bulk IN 0x88 with one pending request across timeouts; pause interrupt reader so there is only one requestWait consumer on this NCM connection. Fall back to synchronous bulk IN if initialization or queue submission fails. No native changes or guard bypass.
- Look for async queue accepted/failed, wait timeout, and actual inbound bytes. A successful queue does not prove the phone has sent data. If the result is still negative, stop incremental guesswork and investigate USB transport with direct diagnostic evidence or compare supported hardware/USB ports.

## V1.0.14 test result — 2026-11-28 10:33 WIB
- Bulk IN 0x88 asynchronous initialization and queue accepted (8196 bytes); repeated wait timeouts while request remained queued; no inbound payload observed. Interrupt reader paused as intended. StartSession and IPv6 announcement 116/116 sent; iAP2 updates continue; OUT intermittently times out and recovers. Still stuck at stage 6.
- Stop speculative APK iteration. Neither smaller negotiated input nor asynchronous read alone fixed reception. USB controller/driver issue is plausible, not proven. iPhone-side NCM/session or networking issue also remains possible.
- Comparable public report: https://github.com/shihabal3amri/DiPlay/issues/1030 (UMS512 Android 10 SPRD MUSB; maintainer suspects host controller, no app fix in that comment; their USBMUX also fails, unlike our observed working iAP2).
- Next: inspect availability of read-only usbmon on existing firmware and/or test another physical USB data path if available. No root/flash/MCU/CAN changes. If no diagnostic access, acknowledge limits; do not promise another APK will solve it.

## Hardware diagnostic incident — 2026-11-28 10:38 WIB
- usbmon absent; debugfs USB devices readable. Active config 6: interface 2 ipheth, NCM 3/4 usbfs (0x88 IN), second NCM 5/6 inactive (0x89 IN at alt1). eth0 NO-CARRIER, RX/TX zero; tun0 RX zero, TX 10 packets. MUSB host behind 1a40:0101 USB2 hub.
- Headunit rebooted after attempted read of /sys/kernel/debug/musb-hdrc.0.auto/regdump. uptime confirmed 1 minute, sys.boot.reason=reboot. Causal mechanism unconfirmed. DO NOT repeat regdump or access hardware registers, softconnect/testmode, unbind drivers, root or flash. Prioritize normal headunit operation. No new APK or intrusive tests; consider simple cable/other port or known-good phone only when parked and stable.

## Physical port comparison — 2026-11-28
- Headunit returned to normal after reboot. V1.0.14 re-enabled for physical USB port test.
- USB 0 and USB 1 both stuck at UI stage 6; USB 2 stuck at stage 3. No full logs for ports 1/2; shared-controller fault is not proven. Use USB 0 as baseline; no need to repeat port comparison.
- Next low-risk control: another known-good data cable or iPhone if available; otherwise review NCM interface selection and iAP2 declaration offline. Do not read hardware registers or modify kernel/firmware.

## Stage 3 regression and V1.0.15 UsbMuxFirst
- USB 0 also regressed to stage 3 after port/cable switching. Headunit restart and iPhone restart did not restore stage 6. Logs show config 6 selected, setConfiguration reported false, claim and NCM negotiation successful, then USBMUX version reply timeout after 60 s. No pairing. Cause of absent reply still unknown.
- Change order: verify active USB configuration with standard GET_CONFIGURATION, skip redundant SET_CONFIGURATION if already active; do not claim when selection is known wrong or failed without confirmation. Perform USBMUX handshake before opening NCM. Keep V1.0.14 NCM transport, MFi and guard unchanged. No hardware register reads, kernel changes or firmware modifications.
- Target: USBMUX version accepted, host opened before NCM, then normal pairing and stage 6. This addresses the stage 3 regression only; stage 6 still unresolved. Test parked, USB 0, stop on unexpected reboot.

## V1.0.15 live test — 2026-11-28 11:03 WIB
- Stage 3 recovered: USBMUX TCP RX, iAP2 CarPlayAvailability wiredAvailable=true, 0x4301 sent and TCP ACK observed. NCM gate opened, async RX queue accepted at 0x88 but repeated wait timeouts, announcement 116/116 sent; OUT intermittently fails. Stage 6 remains unresolved. One successful stage 3 recovery does not prove repeatability; initial GET_CONFIGURATION log not supplied.
- Source audit: CarPlaySessionSettings.kt advertises carPlayUsbInterfaceNumber=3; NcmFunctionDiscovery picks first control/data pair 3/4 on config 6. No demonstrated mismatch. Redline hardware documentation https://dashboard-docs.redline-labs.com/design/carplay-port.html identifies first NCM pair as AV link and notes undrained interrupt can cause bulk OUT timeouts; second pair not the AV link on their test. No blind switch to 0x89. Earlier interrupt queue failed; V1.0.14/15 paused interrupt reader for exclusive asynchronous RX. Investigate whether stock cdc_ncm driver is present via directory listing only; also use a known-good different iPhone if available. Do not alter kernel, bind/unbind drivers or read registers. No further APK until evidence supports a change.

## V1.0.16 iOS27NcmStatus — proposed diagnostic
- User confirms only iPhone available runs iOS 27.2.1. cdc_ncm present but no bound interfaces, even after force-stop; no new network interface, tun0 disappears. Do not bind/unbind or change firmware.
- Verified Redline CarPlay port notes: https://dashboard-docs.redline-labs.com/design/carplay-port.html#carrier — iOS 27 notification 0x2b, 10-byte payload, split 16+2; undrained status can stall OUT. Their kernel patch does not directly apply to our userspace bridge.
- Keep USBMUX-first configuration from 1.0.15. Queue status interrupt at endpoint max packet size during VPN receive startup before start gate; legacy queue fallback if modern queue rejected; single requestWait dispatcher handles status and bulk IN; reassemble and log CDC notifications; do not gate on guessed carrier. Close both requests safely. No native/guard/MFi changes. Test success requires actual status queue/read and NCM inbound or AirPlay; no promise of working CarPlay.
