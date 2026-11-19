package app.panelled;

import android.os.Build;
import android.system.ErrnoException;
import android.system.Os;
import android.system.StructStat;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * Reads what this tablet exposes for its light bars, so we learn whether the vendor's /dev/ledjni path
 * exists here (RK3576 / Android 14 owners report it may not) and, if not, what does. Read-only: it
 * lists directories, stats nodes and runs ls/getprop; it writes nothing.
 */
final class Probe {
    private static final Pattern INTERESTING = Pattern.compile("(?i)led|elc|gpio|pwm|adw|relay|light|backlight|rgb");

    private Probe() {}

    static String report() {
        StringBuilder sb = new StringBuilder();
        sb.append("Panel LED probe ").append(BuildInfo.VERSION).append('\n');
        sb.append("model=").append(Build.MODEL).append(" device=").append(Build.DEVICE).append('\n');
        sb.append("build=").append(Build.DISPLAY).append('\n');
        sb.append("android=").append(Build.VERSION.RELEASE).append(" sdk=").append(Build.VERSION.SDK_INT)
                .append(" abis=").append(Arrays.toString(Build.SUPPORTED_ABIS)).append('\n');
        sb.append("app uid=").append(android.os.Process.myUid()).append(" selinux=").append(sh("cat /proc/self/attr/current").trim()).append("\n\n");

        sb.append("== device nodes the vendor library uses\n");
        for (String p : new String[] {LedDevice.PATH, "/dev/elcjni", "/dev/gpiojni"}) node(sb, p);

        sb.append("\n== /dev entries that look LED/GPIO related\n");
        File[] dev = new File("/dev").listFiles();
        if (dev == null) {
            sb.append("(cannot list /dev)\n");
        } else {
            Arrays.sort(dev);
            int n = 0;
            for (File f : dev) {
                if (INTERESTING.matcher(f.getName()).find()) {
                    node(sb, f.getPath());
                    n++;
                }
            }
            if (n == 0) sb.append("(none)\n");
        }

        sb.append("\n== /sys/class/leds\n").append(list("/sys/class/leds"));
        sb.append("\n== /sys/devices/virtual/adw (sysfs variant seen on other tablets)\n");
        sb.append(list("/sys/devices/virtual/adw")).append(list("/sys/devices/virtual/adw/adwdev"));
        for (String f : new String[] {"adwred", "adwgreen", "adwblue"}) {
            File x = new File("/sys/devices/virtual/adw/adwdev/" + f);
            sb.append(f).append(": exists=").append(x.exists()).append(" canWrite=").append(x.canWrite()).append('\n');
        }
        sb.append("\n== /sys/class/pwm\n").append(list("/sys/class/pwm"));

        sb.append("\n== /proc/devices (led/elc/gpio/pwm lines)\n");
        sb.append(sh("grep -iE 'led|elc|gpio|pwm|adw' /proc/devices"));

        sb.append("\n== getprop (led/rgb/lamp related)\n");
        sb.append(sh("getprop | grep -iE 'led|rgb|lamp|light|zx|smt'"));

        sb.append("\n== open test (read-only, no ioctl)\n");
        int fd = LedDevice.nativeOpen(LedDevice.PATH);
        if (fd < 0) {
            sb.append("open(").append(LedDevice.PATH).append(") -> -").append(LedDevice.errno(-fd)).append('\n');
        } else {
            sb.append("open(").append(LedDevice.PATH).append(") -> fd ").append(fd).append(" (opens)\n");
            LedDevice.nativeClose(fd);
        }
        return sb.toString();
    }

    private static void node(StringBuilder sb, String path) {
        File f = new File(path);
        sb.append(path).append(": exists=").append(f.exists());
        try {
            StructStat st = Os.stat(path);
            sb.append(String.format(" mode=%06o uid=%d gid=%d rdev=%d:%d", st.st_mode, st.st_uid, st.st_gid,
                    (st.st_rdev >> 8) & 0xfff, st.st_rdev & 0xff));
        } catch (ErrnoException e) {
            sb.append(" stat=").append(e.getMessage());
        }
        sb.append(" r=").append(f.canRead()).append(" w=").append(f.canWrite()).append('\n');
        String z = sh("ls -lZ " + path + " 2>&1").trim();
        if (!z.isEmpty()) sb.append("   ").append(z).append('\n');
    }

    private static String list(String dir) {
        String[] names = new File(dir).list();
        if (names == null) return dir + ": (missing or unreadable)\n";
        Arrays.sort(names);
        return dir + ": " + (names.length == 0 ? "(empty)" : String.join(" ", names)) + "\n";
    }

    /** Runs a shell one-liner as this app's uid; stdout+stderr, 5 s cap. */
    static String sh(String cmd) {
        try {
            Process p = new ProcessBuilder("sh", "-c", cmd).redirectErrorStream(true).start();
            StringBuilder out = new StringBuilder();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String line;
                while ((line = r.readLine()) != null) out.append(line).append('\n');
            }
            if (!p.waitFor(5, TimeUnit.SECONDS)) p.destroyForcibly();
            return out.toString();
        } catch (Exception e) {
            return "(" + e + ")\n";
        }
    }
}
