package app.panelled;

/**
 * Read-only probe for the panel's relay/IO node (/dev/gpio_control), decoded from the vendor's
 * libgpio_control.so (docs/protocol.md). Deliberately GET only: the SET command could flip a relay or
 * IO pin wired to something this app has no way to know about, so it is not implemented here.
 */
public final class GpioDevice {
    static native int nativeGet(int gpioNum);

    private GpioDevice() {}

    /** Pin level (0 or 1), or -errno on failure (e.g. SELinux denial -> -EACCES). */
    public static int get(int gpioNum) {
        return nativeGet(gpioNum);
    }
}
