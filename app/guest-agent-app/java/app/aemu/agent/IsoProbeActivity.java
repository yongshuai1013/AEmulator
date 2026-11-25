package app.aemu.agent;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The isolation probe page (a measurement page, not a fix). One button: the agent is asked to
 * plant this run's markers through the host — a marker file OUTSIDE the guest root and a decoy at
 * the same path string inside it — and then to run the probe binary the app carries, which tries
 * to reach the outside marker cell by cell: an absolute symlink, a climbing relative symlink,
 * ".." from the working directory and through a dirfd, the /proc magic links, a proot-style
 * ptrace takeover, and io_uring.
 *
 * Every cell answers PASS (kept inside: it read the decoy, or was refused), LEAK (it read the
 * host marker itself) or NA (this environment cannot exercise the cell; the line says why).
 * The same lines are mirrored into the host log by the agent (iso-probe lines). Nothing on this
 * page changes any behaviour — it measures where the walls are, so the fix can be scoped.
 */
public final class IsoProbeActivity extends Activity {
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private TextView status;
    private Button runButton;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        setTitle("隔離探針");
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);

        TextView label = new TextView(this);
        label.setPadding(24, 16, 24, 8);
        label.setTextSize(14);
        label.setText("量測用，不是修復：這一頁請宿主在客體根外植入一個標記檔、在根內同路徑放一個誘餌，"
            + "再逐格試著用各種方法讀根外那個標記——符號連結、../.. 爬升、/proc 魔法連結、"
            + "dirfd 相對路徑、ptrace 接管、io_uring。\n"
            + "PASS＝被留在根內（讀到誘餌或被拒）；LEAK＝真的讀到根外標記；"
            + "NA＝這個環境沒辦法量這一格（原因寫在該行）。\n"
            + "結果會同步寫進宿主日誌（iso-probe 行）。這一頁不改變任何行為。");
        root.addView(label);

        runButton = new Button(this);
        runButton.setText("執行隔離探針");
        runButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { runProbes(); }
        });
        root.addView(runButton);

        status = new TextView(this);
        status.setPadding(24, 16, 24, 16);
        status.setTextSize(14);
        status.setText("尚未執行。");
        ScrollView scroll = new ScrollView(this);
        scroll.addView(status);
        root.addView(scroll, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(root);
    }

    private void runProbes() {
        runButton.setEnabled(false);
        status.setText("請代理植入標記…");
        io.execute(new Runnable() {
            @Override public void run() {
                try {
                    List<String> setup = AgentClient.call("ISOPROBE");
                    for (String l : setup) {
                        if (l.startsWith("ERR")) { show(l); return; }
                    }
                    post("標記已植入，探針執行中…");
                    List<String> lines = AgentClient.call("ISORUN", 120000);
                    show(render(lines));
                } catch (final Exception e) {
                    show("代理連不上：" + e.getMessage()
                        + "\n（這一頁透過客體代理服務運作；代理未運行時什麼都不量。）");
                }
            }
        });
    }

    private void post(final String text) {
        ui.post(new Runnable() {
            @Override public void run() { status.setText(text); }
        });
    }

    private void show(final String text) {
        ui.post(new Runnable() {
            @Override public void run() {
                status.setText(text);
                runButton.setEnabled(true);
            }
        });
    }

    /** the probe's ISO lines, one cell per block; anything else is shown as it came */
    private static String render(List<String> lines) {
        StringBuilder sb = new StringBuilder();
        for (String l : lines) {
            if (l.startsWith("ISO ")) {
                String[] p = l.split(" ", 4);
                if (p.length >= 3) {
                    sb.append(p[1]).append("：").append(p[2]);
                    if (p.length > 3) sb.append("\n  ").append(p[3]);
                    sb.append("\n\n");
                    continue;
                }
            }
            sb.append(l).append("\n\n");
        }
        String s = sb.toString().trim();
        return s.isEmpty() ? "探針沒有回傳任何一行。" : s;
    }
}
