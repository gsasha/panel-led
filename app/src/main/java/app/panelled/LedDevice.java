package app.panelled;

/**
 * The panel's RGB side light bars, driven the way the vendor library does it (docs/protocol.md):
 * open /dev/ledjni, ioctl(0xa1, red), ioctl(0xa2, green), ioctl(0xa3, blue) with levels 0..15, close.
 * ioctl(0x99, 0) switches the bars off. Every call opens and closes the device, as the vendor does.
 */
public final class LedDevice {
    public static final String PATH = "/dev/ledjni";
    public static final int REQ_RED = 0xa1;
    public static final int REQ_GREEN = 0xa2;
    public static final int REQ_BLUE = 0xa3;
    public static final int REQ_OFF = 0x99;
    public static final int MAX_LEVEL = 15;

    static {
        System.loadLibrary("panelled");
    }

    static native int nativeOpen(String path);
    static native int nativeIoctl(int fd, int request, int arg);
    static native int nativeClose(int fd);

    private LedDevice() {}

    /** Result of one open + ioctl sequence + close; a negative value is -errno. */
    public static final class Result {
        public final int open;
        public final int[] ioctls;
        public final int close;

        Result(int open, int[] ioctls, int close) {
            this.open = open;
            this.ioctls = ioctls;
            this.close = close;
        }

        public boolean ok() {
            if (open < 0 || close < 0) return false;
            for (int r : ioctls) if (r < 0) return false;
            return true;
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder("open=").append(open < 0 ? "-" + errno(-open) : "fd");
            for (int i = 0; i < ioctls.length; i++) {
                sb.append(" ioctl").append(i).append('=').append(ioctls[i] < 0 ? "-" + errno(-ioctls[i]) : ioctls[i]);
            }
            return sb.append(" close=").append(close).append(ok() ? "  OK" : "  FAILED").toString();
        }
    }

    /** Levels are 0..15 per channel; anything outside is clamped. */
    public static Result setRgb(int red, int green, int blue) {
        return run(new int[] {REQ_RED, REQ_GREEN, REQ_BLUE},
                new int[] {clamp(red), clamp(green), clamp(blue)});
    }

    public static Result off() {
        return run(new int[] {REQ_OFF}, new int[] {0});
    }

    /** 0xRRGGBB -> levels, the same /16 mapping the community app uses. */
    public static Result setColor(int rgb) {
        return setRgb(((rgb >> 16) & 0xFF) / 16, ((rgb >> 8) & 0xFF) / 16, (rgb & 0xFF) / 16);
    }

    private static Result run(int[] requests, int[] args) {
        int fd = nativeOpen(PATH);
        if (fd < 0) return new Result(fd, new int[0], 0);
        int[] rc = new int[requests.length];
        for (int i = 0; i < requests.length; i++) rc[i] = nativeIoctl(fd, requests[i], args[i]);
        return new Result(fd, rc, nativeClose(fd));
    }

    private static int clamp(int v) {
        return Math.max(0, Math.min(MAX_LEVEL, v));
    }

    static String errno(int e) {
        switch (e) {
            case 1: return "EPERM(1)";
            case 2: return "ENOENT(2)";
            case 5: return "EIO(5)";
            case 13: return "EACCES(13)";
            case 16: return "EBUSY(16)";
            case 19: return "ENODEV(19)";
            case 22: return "EINVAL(22)";
            case 25: return "ENOTTY(25)";
            default: return "errno " + e;
        }
    }
}
