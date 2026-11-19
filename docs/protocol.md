# How the panel's side light bars are driven

The 10.1" Android PoE wall panels (Rockchip, "ZX-SMT101x" family) have programmable RGB LED bars on the
left and right of the bezel. The seller ships a closed library (`libjnielc.so`, Java class
`com.example.elcapi.jnielc`) and a short PDF. This is what that library does, read from its arm64 build
by disassembly (the C source path baked into it is `.../Led_Seven_Memory/demo/app/src/main/jni/jnielc.c`).

| Java call | What the library executes |
|---|---|
| `seekstart()` | `fd = open("/dev/ledjni", O_RDONLY \| O_NOCTTY)` (flag value 0x100 on arm64) |
| `ledseek(0xa1, r)` | `ioctl(fd, 0xa1, r)` red, 0..15 |
| `ledseek(0xa2, g)` | `ioctl(fd, 0xa2, g)` green, 0..15 |
| `ledseek(0xa3, b)` | `ioctl(fd, 0xa3, b)` blue, 0..15 |
| `ledoff()` | `ioctl(fd, 0x99, 0)` |
| `seekstop()` | `close(fd)` |

- The request numbers are raw (not `_IO`-encoded) and the argument is the plain integer level.
- The seller's PDF says a single colour needs the other two channels set to 0 explicitly.
- `/dev/elcjni` (opened by `open()`/`openWatchdog()`) is a watchdog, not the light. The `*3` functions are
  copies of the same code.
- Relays and IO pins use a second library, `libgpio_control.so` (`ioctl_gpio(n, cmd, value)`); not
  decoded here.
- A different tablet (an Allnet clone) was reported to expose the same bars as sysfs files
  `/sys/devices/virtual/adw/adwdev/adw{red,green,blue}` taking `o` / `c` (on / off, no brightness). The
  probe checks for those too.

## Known unknown

Owners of RK3576 / Android 14 units report the vendor app and library work on some and not on others.
Whether `/dev/ledjni` exists and is openable by an app on *our* unit is what the v0.1 probe answers.

## Relay and digital IO (`/dev/gpio_control`)

The second library, `libgpio_control.so` (`com.sys.gpio.gpioJni`), controls 2 relays and 2 digital IO
pins through `/dev/gpio_control` (same SELinux label and class as `/dev/ledjni`: `u:object_r:device:s0`,
`chr_file`). Decoded by disassembly:

```
ioctl_gpio(gpio_num, cmd, value):
    fd = open("/dev/gpio_control", O_RDWR)
    if cmd == 0 (SET): request = 0x40000000 | (value == 1 ? 1 : 0)   # bit0: 1 = high, 0 = low
    else       (GET): request = 0x80000000                           # value is ignored
    arg = &gpio_num   # a POINTER, not a plain int -- unlike the LED calls
    return ioctl(fd, request, arg)   # GET returns the pin level (0/1) directly; SET returns 0/-1
    close(fd)
```

**Confirmed denied for an ordinary app, the same way as the LED**: `avc: denied { ioctl }` on
`/dev/gpio_control` for `untrusted_app`, tested with the GET call only (`request 0x80000000`, gpio 0-3).
The SET call was not tested on real hardware, deliberately -- it would flip a relay or IO pin whose
wiring is unknown, which is a real side effect, not a safe read. Given the identical label/class and the
same blanket denial on GET, SET should be assumed denied too, not verified.
