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
