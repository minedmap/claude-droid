# Phone USB control (Claude Droid)

MCP server `usb` talks to the Claude Droid app (no root). The app must be running; the user must tap "allow" on the phone for each device.

Workflow:
1. `usb_list` -> note `id`, `vidpid`, `serialDriver`. If `hasPermission` is false, call `usb_permission` (user taps on the phone).
2. Unknown device: `usb_descriptors`, then `usb_control` / `usb_transfer` using interface ids and endpoint addresses from `usb_list`.
3. Serial devices: `serial_open` -> `serial_write` / `serial_read` (use `waitMs`) -> `serial_close`. DTR/RTS via `serial_lines`.
4. Arduino loop: `arduino-cli compile --fqbn arduino:avr:uno --output-dir /tmp/b sketch` -> `avr_flash` with `hexPath=/tmp/b/<sketch>.ino.hex` (boards: uno, nano, nano_old, mega2560; not leonardo) -> `serial_open` + `serial_read` to check output.
5. esptool/pyserial/avrdude need a socket: `serial_tcp` (mode rfc2217) then `esptool --port rfc2217://127.0.0.1:<tcpPort> ...`. Close the port with `serial_close` afterwards.

Limits: devices the Android kernel already claims (some HID/storage) may fail to claim; hubs and OTG power are on the user. A serial port opened by `serial_open` is exclusive: `avr_flash` closes it first.
