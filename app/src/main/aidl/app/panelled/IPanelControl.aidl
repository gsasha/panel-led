// IPanelControl -- the panel-led control API, Binder/AIDL only. This app runs no network server of
// any kind (no MQTT, no HTTP): an external app (e.g. a future MQTT<->Home Assistant bridge) binds to
// PanelControlService and calls these methods directly, process-to-process on the same device. See
// docs/api.md for the binding contract, the permission model and an example client.
//
// Versioning, while this is 0.x (alpha): the interface may still change between releases, and a
// caller should build against the exact .aidl file shipped with the panel-led version it targets.
// From 1.0 on this file is append-only: existing methods, their order and the CAP_* bit values never
// change; a new capability gets a new method at the end, gated behind its own CAP_* bit.
package app.panelled;

interface IPanelControl {
    // Capability bits for getCapabilities(). A caller checks the bit before calling anything beyond
    // getCapabilities() itself, instead of guessing from a failure. Bits with no method implementing
    // them yet are reserved, not assigned -- CAP_LED is the only one this version of panel-led can
    // ever return.
    const int CAP_LED = 0x1;

    // What this build of panel-led actually supports (a bitmask of CAP_* above). Always callable,
    // never fails.
    int getCapabilities();

    // The side LED bars. color is 0xRRGGBB, 0..255 per channel (standard Android Color packing);
    // scaled internally to the hardware's 0..15 levels per channel. Returns 0 on success, or a
    // negative errno (e.g. -13 == EACCES, expected until the panel vendor signs this app or an
    // equivalent bridge is in place -- see docs/protocol.md).
    int setLedColor(int color);

    // Turns the LED bars off. Same return convention as setLedColor.
    int setLedOff();
}
