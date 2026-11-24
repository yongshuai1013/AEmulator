package app.aemu.agent;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The Root Manager: the guest agent's own app inside the guest, the place root in this VM is looked at
 * and managed from. Three panels, all of them fed by the agent service over its socket (AgentClient) —
 * this app never probes for root itself, so it can only ever show what the layer knows:
 *
 *  - Status: what the layer reports about this guest's root (Magisk, the su paths, the modules, the
 *    overlay journal, /data/adb at a glance).
 *  - Hide list: the apps root is hidden from, toggled here. The list is the guest's own file; the agent
 *    rewrites the uid set the mount layer filters by and asks the host to follow in the daemon's
 *    denylist. The Magisk app and this manager always see the full guest and cannot be listed.
 *  - Self test: for one app, what a listed app would see, cell by cell, each cell labelled with where
 *    its answer comes from — including the cells that are only the layer's rule, or unproven.
 *
 * When the agent is not running (its host switch is off, or this guest cannot run it) there is nothing
 * to manage through: the app says exactly that and shows no data.
 */
public final class ManagerActivity extends Activity {
    private static final String MAGISK_PACKAGE = "com.topjohnwu.magisk";
    private static final String OWN_PACKAGE = "app.aemu.agent";

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());

    private TextView head;
    private LinearLayout panels;
    private TextView statusText;
    private ListView appList;
    private Spinner testSpinner;
    private TextView testText;
    private Button tabStatus, tabHide, tabTest;

    private List<AppRow> apps = new ArrayList<AppRow>();
    private final Set<String> hidden = new HashSet<String>();
    private boolean connected = false;

    private static final class AppRow {
        String pkg;
        String label;
        boolean locked;   // always sees the full guest: no toggle
    }

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        setTitle("AEmu Root Manager");
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        head = new TextView(this);
        head.setPadding(24, 16, 24, 8);
        head.setText("Connecting to the guest agent…");
        root.addView(head);

        LinearLayout tabs = new LinearLayout(this);
        tabs.setOrientation(LinearLayout.HORIZONTAL);
        tabStatus = tab(tabs, "Status");
        tabHide = tab(tabs, "Hide list");
        tabTest = tab(tabs, "Self test");
        root.addView(tabs);

        panels = new LinearLayout(this);
        panels.setOrientation(LinearLayout.VERTICAL);
        root.addView(panels, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        ScrollView statusScroll = new ScrollView(this);
        statusText = new TextView(this);
        statusText.setPadding(24, 16, 24, 16);
        statusText.setTextSize(14);
        statusScroll.addView(statusText);
        statusScroll.setTag("status");
        panels.addView(statusScroll, panelParams());

        appList = new ListView(this);
        appList.setTag("hide");
        panels.addView(appList, panelParams());

        LinearLayout testPanel = new LinearLayout(this);
        testPanel.setOrientation(LinearLayout.VERTICAL);
        testPanel.setTag("test");
        LinearLayout testRow = new LinearLayout(this);
        testRow.setOrientation(LinearLayout.HORIZONTAL);
        testSpinner = new Spinner(this);
        testRow.addView(testSpinner, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        Button run = new Button(this);
        run.setText("Run");
        run.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { runSelfTest(); }
        });
        testRow.addView(run);
        testPanel.addView(testRow);
        ScrollView testScroll = new ScrollView(this);
        testText = new TextView(this);
        testText.setPadding(24, 16, 24, 16);
        testText.setTextSize(14);
        testScroll.addView(testText);
        testPanel.addView(testScroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        panels.addView(testPanel, panelParams());

        Button inbox = new Button(this);
        inbox.setText("Import inbox");
        inbox.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                startActivity(new Intent(ManagerActivity.this, InboxActivity.class));
            }
        });
        root.addView(inbox);

        Button vpn = new Button(this);
        vpn.setText("VPN 測試（控制面）");
        vpn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                startActivity(new Intent(ManagerActivity.this, VpnTestActivity.class));
            }
        });
        root.addView(vpn);
        setContentView(root);
        showPanel("status");
        connect();
    }

    // The title bar's own overflow — the ⋮ the system draws at the right end of the bar, in the same row
    // as the icon and the title: the same two jumps the bottom buttons make. The bottom buttons stay;
    // these are the same doors hung on the bar, for screens where the page's bottom is out of reach.
    @Override public boolean onCreateOptionsMenu(Menu menu) {
        menu.add(Menu.NONE, 1, Menu.NONE, "VPN 測試（控制面）")
            .setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER);
        menu.add(Menu.NONE, 2, Menu.NONE, "Import inbox")
            .setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER);
        return true;
    }

    @Override public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == 1) {
            startActivity(new Intent(this, VpnTestActivity.class));
            return true;
        }
        if (item.getItemId() == 2) {
            startActivity(new Intent(this, InboxActivity.class));
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private Button tab(LinearLayout parent, String label) {
        final Button b = new Button(this);
        b.setText(label);
        final String tag = label.equals("Status") ? "status" : label.equals("Hide list") ? "hide" : "test";
        b.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { showPanel(tag); }
        });
        parent.addView(b, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        return b;
    }

    private static LinearLayout.LayoutParams panelParams() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
    }

    private void showPanel(String tag) {
        for (int i = 0; i < panels.getChildCount(); i++) {
            View child = panels.getChildAt(i);
            child.setVisibility(tag.equals(child.getTag()) ? View.VISIBLE : View.GONE);
        }
    }

    // ---- connection & status ---------------------------------------------------------------------------

    private void connect() {
        io.execute(new Runnable() {
            @Override public void run() {
                try {
                    final List<String> status = AgentClient.call("STATUS");
                    final List<String> list = AgentClient.call("LIST");
                    ui.post(new Runnable() {
                        @Override public void run() {
                            connected = true;
                            hidden.clear();
                            for (String line : list) if (line.startsWith("pkg ")) hidden.add(line.substring(4).trim());
                            head.setText("Connected to the guest agent. Everything below is the layer's own report.");
                            statusText.setText(renderStatus(status));
                            buildAppRows();
                        }
                    });
                } catch (final Exception e) {
                    ui.post(new Runnable() {
                        @Override public void run() {
                            connected = false;
                            head.setText("The guest agent is not running.");
                            statusText.setText("The Root Manager works through the guest agent service, and it " +
                                "could not be reached (" + e.getMessage() + ").\n\nTurn on “Guest agent” in " +
                                "this VM's settings on the host and reboot the VM. If it is already on, this " +
                                "guest may not support the agent. Nothing is managed — and nothing is shown — " +
                                "until the agent answers.");
                            Toast.makeText(ManagerActivity.this, "The guest agent is not running", Toast.LENGTH_LONG).show();
                        }
                    });
                }
            }
        });
    }

    /** The agent's STATUS lines, grouped readably. Unknown keys are shown as they came: no invention. */
    private static String renderStatus(List<String> lines) {
        Map<String, String> kv = new HashMap<String, String>();
        List<String[]> modules = new ArrayList<String[]>();
        Map<String, String[]> byId = new HashMap<String, String[]>();
        for (String line : lines) {
            int eq = line.indexOf('=');
            if (eq < 0) continue;
            String key = line.substring(0, eq), value = line.substring(eq + 1);
            if (key.startsWith("module.")) {
                String rest = key.substring(7);
                int dot = rest.lastIndexOf('.');
                if (dot < 0) continue;
                String id = rest.substring(0, dot), field = rest.substring(dot + 1);
                String[] row = byId.get(id);
                if (row == null) { row = new String[]{id, id, "?", "0", "0", "0"}; byId.put(id, row); modules.add(row); }
                if (field.equals("name")) row[1] = value;
                else if (field.equals("version")) row[2] = value;
                else if (field.equals("disabled")) row[3] = value;
                else if (field.equals("remove")) row[4] = value;
                else if (field.equals("update")) row[5] = value;
            } else {
                kv.put(key, value);
            }
        }
        StringBuilder sb = new StringBuilder();
        boolean magisk = "1".equals(kv.get("magisk.installed"));
        sb.append("Magisk: ");
        if (magisk) sb.append("installed, version ").append(kv.get("magisk.version")).append(" (").append(kv.get("magisk.code")).append(")");
        else sb.append("not installed");
        sb.append("\nMagisk app in the guest: ").append("1".equals(kv.get("magisk.manager")) ? "installed" : "not installed");
        sb.append("\n\nRoot paths (this manager always sees the full guest):");
        for (Map.Entry<String, String> e : kv.entrySet()) {
            if (!e.getKey().startsWith("path.") || e.getKey().endsWith(".ours")) continue;
            String path = e.getKey().substring(5);
            sb.append("\n  ").append(path).append(": ");
            if ("1".equals(e.getValue())) {
                sb.append("present");
                if ("1".equals(kv.get("path." + path + ".ours"))) sb.append(" (put there by the layer)");
            } else sb.append("absent");
        }
        sb.append("\n\nZygisk: ");
        if ("1".equals(kv.get("zygisk.on"))) sb.append("on (bridge: ").append(kv.get("zygisk.bridge")).append(")");
        else sb.append("off");
        sb.append("\n\nModules (").append(kv.get("modules.count")).append("):");
        if (modules.isEmpty()) sb.append("\n  none");
        for (String[] m : modules) {
            sb.append("\n  ").append(m[1]).append(" ").append(m[2]);
            if ("1".equals(m[3])) sb.append("  [disabled]");
            if ("1".equals(m[4])) sb.append("  [removal pending]");
            if ("1".equals(m[5])) sb.append("  [update staged]");
        }
        sb.append("\n\n/data/adb: ").append(kv.get("adb.entries")).append(" entries, ").append(human(kv.get("adb.bytes")));
        sb.append("\nOverlay journal: ");
        if ("1".equals(kv.get("journal.present"))) {
            sb.append(kv.get("journal.f")).append(" new files, ").append(kv.get("journal.d")).append(" new dirs, ")
                .append(kv.get("journal.r")).append(" replaced files, ").append(kv.get("journal.p")).append(" replaced dirs");
        } else sb.append("none (no module files are in the tree)");
        sb.append("\n\nHide list: ").append(kv.get("hideapps.count")).append(" app(s)");
        sb.append("\nUid set the mount layer filters by: ");
        if ("1".equals(kv.get("hideuids.present"))) sb.append(kv.get("hideuids.count")).append(" uid(s)");
        else sb.append("absent — no reader is hidden right now");
        return sb.toString();
    }

    private static String human(String bytes) {
        long n;
        try { n = Long.parseLong(bytes); } catch (Throwable t) { return "?"; }
        if (n < 1024) return n + " B";
        if (n < 1024 * 1024) return (n / 1024) + " KB";
        return String.format("%.1f MB", n / (1024.0 * 1024.0));
    }

    // ---- the hide list -----------------------------------------------------------------------------------

    private void buildAppRows() {
        apps = new ArrayList<AppRow>();
        PackageManager pm = getPackageManager();
        Set<String> seen = new HashSet<String>();
        for (ApplicationInfo ai : pm.getInstalledApplications(0)) {
            AppRow row = new AppRow();
            row.pkg = ai.packageName;
            row.label = String.valueOf(pm.getApplicationLabel(ai));
            row.locked = row.pkg.equals(MAGISK_PACKAGE) || row.pkg.equals(OWN_PACKAGE);
            apps.add(row);
            seen.add(row.pkg);
        }
        // listed but no longer installed: still shown, so the entry can be taken off again
        for (String pkg : hidden) {
            if (seen.contains(pkg)) continue;
            AppRow row = new AppRow();
            row.pkg = pkg;
            row.label = "(not installed)";
            apps.add(row);
        }
        Collections.sort(apps, new Comparator<AppRow>() {
            @Override public int compare(AppRow a, AppRow b) {
                int c = a.label.compareToIgnoreCase(b.label);
                return c != 0 ? c : a.pkg.compareTo(b.pkg);
            }
        });
        appList.setAdapter(new AppAdapter());
        List<String> names = new ArrayList<String>();
        for (AppRow row : apps) names.add(row.label + " — " + row.pkg);
        testSpinner.setAdapter(new ArrayAdapter<String>(this, android.R.layout.simple_spinner_dropdown_item, names));
    }

    private final class AppAdapter extends ArrayAdapter<AppRow> {
        AppAdapter() { super(ManagerActivity.this, 0, apps); }

        @Override public View getView(int pos, View reuse, ViewGroup parent) {
            final AppRow row = apps.get(pos);
            LinearLayout cell = reuse instanceof LinearLayout ? (LinearLayout) reuse : null;
            final CheckBox box;
            TextView text;
            if (cell == null) {
                cell = new LinearLayout(ManagerActivity.this);
                cell.setOrientation(LinearLayout.HORIZONTAL);
                cell.setPadding(16, 8, 16, 8);
                box = new CheckBox(ManagerActivity.this);
                box.setTag("box");
                cell.addView(box);
                text = new TextView(ManagerActivity.this);
                text.setTag("text");
                cell.addView(text);
            } else {
                box = (CheckBox) cell.findViewWithTag("box");
                text = (TextView) cell.findViewWithTag("text");
            }
            box.setOnCheckedChangeListener(null);
            box.setChecked(hidden.contains(row.pkg));
            box.setEnabled(!row.locked && connected);
            text.setText(row.label + "\n" + row.pkg + (row.locked ? "\nalways sees the full guest" : ""));
            box.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
                @Override public void onCheckedChanged(CompoundButton button, boolean on) { setHide(row, box, on); }
            });
            return cell;
        }
    }

    private void setHide(final AppRow row, final CheckBox box, final boolean on) {
        box.setEnabled(false);
        io.execute(new Runnable() {
            @Override public void run() {
                String answer;
                try {
                    answer = AgentClient.callOne("SETHIDE " + row.pkg + (on ? " 1" : " 0"));
                } catch (final Exception e) {
                    answer = "ERR " + e.getMessage();
                }
                final String result = answer;
                ui.post(new Runnable() {
                    @Override public void run() {
                        box.setEnabled(!row.locked && connected);
                        if (result.startsWith("OK")) {
                            if (on) hidden.add(row.pkg); else hidden.remove(row.pkg);
                            Toast.makeText(ManagerActivity.this,
                                (on ? "Root is now hidden from " : "Root is visible again to ") + row.pkg + " (" + result + ")",
                                Toast.LENGTH_LONG).show();
                        } else {
                            box.setOnCheckedChangeListener(null);
                            box.setChecked(!on);
                            box.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
                                @Override public void onCheckedChanged(CompoundButton b, boolean o) { setHide(row, box, o); }
                            });
                            Toast.makeText(ManagerActivity.this, result, Toast.LENGTH_LONG).show();
                        }
                    }
                });
            }
        });
    }

    // ---- the self test -------------------------------------------------------------------------------------

    private void runSelfTest() {
        int pos = testSpinner.getSelectedItemPosition();
        if (pos < 0 || pos >= apps.size() || !connected) return;
        final String pkg = apps.get(pos).pkg;
        testText.setText("Asking the agent…");
        io.execute(new Runnable() {
            @Override public void run() {
                try {
                    final List<String> lines = AgentClient.call("SELFTEST " + pkg);
                    ui.post(new Runnable() {
                        @Override public void run() { testText.setText(renderSelfTest(pkg, lines)); }
                    });
                } catch (final Exception e) {
                    ui.post(new Runnable() {
                        @Override public void run() { testText.setText("The agent could not be asked: " + e.getMessage()); }
                    });
                }
            }
        });
    }

    private static String renderSelfTest(String pkg, List<String> lines) {
        StringBuilder sb = new StringBuilder();
        sb.append(pkg).append("\n");
        for (String line : lines) {
            if (line.startsWith("cell ")) {
                String[] parts = line.substring(5).split("\\|", -1);
                sb.append("\n").append(parts.length > 0 ? parts[0] : "");
                if (parts.length > 1) sb.append("\n  now: ").append(parts[1]);
                if (parts.length > 2) sb.append("\n  listed view: ").append(parts[2]);
                if (parts.length > 3) sb.append("\n  source: ").append(parts[3]);
                sb.append("\n");
            } else if (line.startsWith("ERR")) {
                sb.append("\n").append(line).append("\n");
            } else {
                sb.append(line).append("\n");
            }
        }
        return sb.toString();
    }
}
