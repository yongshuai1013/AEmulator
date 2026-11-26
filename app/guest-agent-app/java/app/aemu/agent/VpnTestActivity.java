package app.aemu.agent;

import android.app.Activity;
import android.content.Intent;
import android.net.VpnService;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * The VPN test page (milestone M3). One button walks the standard consent flow
 * (VpnService.prepare) and starts VpnTestService; the readout shows where establish() landed,
 * how many packets the tunnel has carried, the first packet's summary, and the per-flow
 * evidence table — the counts and the table are the milestone's evidence, and they should
 * agree with the hub's own flow lines in the host log.
 */
public final class VpnTestActivity extends Activity {
    private static final int REQ_CONSENT = 1;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private TextView status;

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            status.setText("狀態：" + VpnTestState.status
                    + "\ntun 讀到封包：" + VpnTestState.packets + " 個 / " + VpnTestState.bytes + " bytes"
                    + (VpnTestState.firstPacket.isEmpty() ? "" : "\n首顆封包：" + VpnTestState.firstPacket)
                    + "\n\nflow 證據表（與宿主日誌的 flow 行逐條對賬）：\n" + VpnTestState.flowsText()
                    + "\n\n接著請到 設定 → 更多 → VPN 查看：這一頁的測試 App 應出現在列管中，"
                    + "連線後狀態列會亮鑰匙圖示。");
            ui.postDelayed(this, 500);
        }
    };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        setTitle("VPN 測試（M3）");
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);

        TextView label = new TextView(this);
        label.setPadding(24, 16, 24, 8);
        label.setTextSize(14);
        label.setText("M3 測試：客體流量繞進隧道後，本服務會把封包翻譯成真連線送出、"
                + "回包再翻回隧道——被繞道的連線這次收得到回覆。\n"
                + "開始後開瀏覽器連 example.com：頁面應完整載入，下方 flow 表該條的 down 位元組應大於 0"
                + "（與宿主日誌的 flow established／closed 行對賬）。\n"
                + "UDP 不進隧道（QUIC 等走直通，是繞過、不是故障）；DNS 仍走宿主代理解析。");
        root.addView(label);

        Button start = new Button(this);
        start.setText("開始 VPN 測試（M3）");
        start.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { askConsent(); }
        });
        root.addView(start);

        Button stop = new Button(this);
        stop.setText("停止");
        stop.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                startService(new Intent(VpnTestActivity.this, VpnTestService.class)
                        .setAction(VpnTestService.ACTION_STOP));
            }
        });
        root.addView(stop);

        status = new TextView(this);
        status.setPadding(24, 16, 24, 16);
        status.setTextSize(14);
        root.addView(status);
        setContentView(root);
    }

    private void askConsent() {
        Intent prep = VpnService.prepare(this);
        if (prep != null) {
            VpnTestState.set("等待使用者同意 VPN 連線請求…");
            try {
                startActivityForResult(prep, REQ_CONSENT);
            } catch (Throwable t) {
                VpnTestState.set("同意對話框開不起來：" + t);
            }
        } else {
            startTest();
        }
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_CONSENT) return;
        if (resultCode == RESULT_OK) {
            startTest();
        } else {
            VpnTestState.set("使用者未同意 VPN 連線請求");
        }
    }

    private void startTest() {
        VpnTestState.set("已同意，啟動測試服務…");
        startService(new Intent(this, VpnTestService.class));
    }

    @Override protected void onResume() {
        super.onResume();
        ui.post(tick);
    }

    @Override protected void onPause() {
        ui.removeCallbacks(tick);
        super.onPause();
    }
}
