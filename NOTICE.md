# NOTICE

This project includes code adapted from **Shizuku** (https://github.com/RikkaApps/Shizuku),
Copyright RikkaApps, licensed under the Apache License 2.0
(https://www.apache.org/licenses/LICENSE-2.0).

Adapted files:
- `control/src/main/java/dev/minedmap/claudedroid/control/AdbMdns.kt`
- `control/src/main/java/dev/minedmap/claudedroid/control/PairingNotification.kt`
- `control/src/main/java/dev/minedmap/claudedroid/control/PairingReceiver.kt`

Uses **Shizuku-API** (`dev.rikka.shizuku:api`/`provider` 13.1.5, Apache-2.0).
Termux RUN_COMMAND interface per the Termux wiki. No LADB (GPL-3.0) code is included.

## USB host control (v0.6.0)

- **usb-serial-for-android** (https://github.com/mik3y/usb-serial-for-android, v3.9.0 via JitPack),
  Copyright (c) 2011-2013 Google Inc., 2013 Mike Wakerly and contributors, licensed under the MIT License.
  Used as a library dependency for CDC/FTDI/CP210x/CH34x/PL2303 serial drivers.
- **PhysicaloidLibrary** (https://github.com/ksksue/PhysicaloidLibrary), Copyright (C) 2013 Keisuke SUZUKI,
  licensed under the Apache License 2.0. The STK500v1/STK500v2 upload logic and the ATmega328P/2560 parameters in
  `app/src/main/java/dev/minedmap/claudedroid/usb/Stk500.kt` are adapted (ported to Kotlin) from its
  `Stk500.java`, `Stk500V2.java` and `AvrConf.java` (themselves derived from avrdude).
- The permission/attach flow follows the public Android USB host documentation; no code from termux-api (GPL-3.0) is included.
- RFC 2217 server behavior follows RFC 2217 / pyserial's documented client behavior (no code copied).

