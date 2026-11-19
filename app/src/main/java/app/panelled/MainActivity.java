package app.panelled;

import android.app.Activity;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

/** v0.1 probe screen: a hardware report plus buttons that fire the decoded ioctl sequence. */
public class MainActivity extends Activity {
    private TextView log;
    private ScrollView scroll;
    private int level = LedDevice.MAX_LEVEL;
    private final Handler ui = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(24, 24, 24, 24);

        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.addView(btn("Red", () -> color(level, 0, 0)));
        row.addView(btn("Green", () -> color(0, level, 0)));
        row.addView(btn("Blue", () -> color(0, 0, level)));
        row.addView(btn("White", () -> color(level, level, level)));
        row.addView(btn("Off", () -> log("off      " + LedDevice.off())));
        row.addView(btn("Cycle", this::cycle));
        row.addView(btn("Re-probe", () -> { log.setText(""); log(Probe.report()); }));
        root.addView(row);

        final TextView lvl = new TextView(this);
        lvl.setText("level " + level + " / " + LedDevice.MAX_LEVEL);
        SeekBar bar = new SeekBar(this);
        bar.setMax(LedDevice.MAX_LEVEL);
        bar.setProgress(level);
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean user) {
                level = p;
                lvl.setText("level " + level + " / " + LedDevice.MAX_LEVEL);
            }
            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) {}
        });
        root.addView(lvl);
        root.addView(bar);

        log = new TextView(this);
        log.setTypeface(Typeface.MONOSPACE);
        log.setTextSize(13);
        log.setTextIsSelectable(true);
        scroll = new ScrollView(this);
        scroll.addView(log);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1f));
        setContentView(root);

        log(Probe.report());
    }

    private Button btn(String text, Runnable r) {
        Button x = new Button(this);
        x.setText(text);
        x.setAllCaps(false);
        x.setOnClickListener((View v) -> r.run());
        return x;
    }

    private void color(int r, int g, int bl) {
        log(String.format("rgb %2d %2d %2d  %s", r, g, bl, LedDevice.setRgb(r, g, bl)));
    }

    /** Red, green, blue, white, off, one second each, so a person can watch the bars. */
    private void cycle() {
        int[][] steps = {{15, 0, 0}, {0, 15, 0}, {0, 0, 15}, {15, 15, 15}, {-1, -1, -1}};
        for (int i = 0; i < steps.length; i++) {
            final int[] s = steps[i];
            ui.postDelayed(() -> {
                if (s[0] < 0) log("off      " + LedDevice.off());
                else color(s[0], s[1], s[2]);
            }, i * 1000L);
        }
    }

    private void log(String line) {
        log.append(line + "\n");
        scroll.post(() -> scroll.fullScroll(View.FOCUS_DOWN));
    }
}
