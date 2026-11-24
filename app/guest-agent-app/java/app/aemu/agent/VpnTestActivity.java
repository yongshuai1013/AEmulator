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
 * The VPN test page (control plane, milestone M1). One button walks the standard consent flow
 * (VpnService.prepare) and starts VpnTestService; the readout shows where establish() landed and
 * how many packets the tunnel has carried — zero is the expected, honest number at M1, and the
 * page labels itself accordingly so a lit key icon is never read as a working VPN.
 */
public final class VpnTestActivity extends Activity {
    private static final int REQ_CONSENT = 1;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private TextView status;

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            status.setText("狀態：" + VpnTestState.status
                    + "\ntun 讀到封包：" + VpnTestState.packets + " 個 / " + VpnTestState.bytes
                    + " bytes（M1 預期 0）"
                    + "\n\n接著請到 設定 → 更多 → VPN 查看：這一頁的測試 App 應出現在列管中，"
                    + "連線後狀態列會亮鑰匙圖示。");
            ui.postDelayed(this, 500);
        }
    };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        setTitle("VPN 測試（控制面）");
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);

        TextView label = new TextView(this);
        label.setPadding(24, 16, 24, 8);
        label.setTextSize(14);
        label.setText("控制面測試：流量尚未進隧道。\n"
                + "這一頁證明的是 VpnService 的 establish() 能成功、設定頁會列管、狀態列會亮鑰匙圖示；"
                + "客體 App 的流量目前仍直通、不經過這個隧道。真的載流是下一里程碑（M2）。");
        root.addView(label);

        Button start = new Button(this);
        start.setText("開始 VPN 控制面測試");
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
