package app.aemu.agent;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.List;

/**
 * The import inbox inside the guest: everything the host streamed to this VM (AgentStub writes it into
 * AEmuImport/ on the guest's own external storage) is listed here; tapping an APK hands it to the guest's
 * package installer, a long press deletes it. Nothing here talks to the host — the folder is the whole
 * interface, so the list is also exactly what a file manager in the guest would show.
 */
public final class InboxActivity extends Activity {
    private File dir;
    private ListView list;
    private TextView empty;
    private List<File> files = new ArrayList<File>();

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        dir = new File(Environment.getExternalStorageDirectory(), "AEmuImport");
        list = new ListView(this);
        empty = new TextView(this);
        empty.setText("Nothing imported yet.\nFiles the host sends to this VM appear here.");
        empty.setPadding(32, 32, 32, 32);
        setContentView(list);
        list.setEmptyView(empty);
        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override public void onItemClick(AdapterView<?> parent, View view, int pos, long id) {
                File f = files.get(pos);
                if (!f.getName().endsWith(".apk")) {
                    Toast.makeText(InboxActivity.this, f.getName(), Toast.LENGTH_SHORT).show();
                    return;
                }
                Intent i = new Intent(Intent.ACTION_VIEW);
                i.setDataAndType(Uri.fromFile(f), "application/vnd.android.package-archive");
                i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                try {
                    startActivity(i);
                } catch (Throwable t) {
                    Toast.makeText(InboxActivity.this, "No installer answered: " + t.getMessage(), Toast.LENGTH_LONG).show();
                }
            }
        });
        list.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
            @Override public boolean onItemLongClick(AdapterView<?> parent, View view, int pos, long id) {
                final File f = files.get(pos);
                new AlertDialog.Builder(InboxActivity.this)
                    .setTitle(f.getName())
                    .setMessage("Delete this file from the inbox?")
                    .setPositiveButton("Delete", new DialogInterface.OnClickListener() {
                        @Override public void onClick(DialogInterface d, int which) {
                            if (f.delete()) refresh();
                            else Toast.makeText(InboxActivity.this, "Could not delete " + f.getName(), Toast.LENGTH_SHORT).show();
                        }
                    })
                    .setNegativeButton("Cancel", null)
                    .show();
                return true;
            }
        });
    }

    @Override protected void onResume() {
        super.onResume();
        refresh();
    }

    private void refresh() {
        files = new ArrayList<File>();
        File[] all = dir.isDirectory() ? dir.listFiles() : null;
        if (all != null) {
            Arrays.sort(all, new Comparator<File>() {
                @Override public int compare(File a, File b) { return Long.compare(b.lastModified(), a.lastModified()); }
            });
            for (File f : all) if (f.isFile()) files.add(f);
        }
        List<String> rows = new ArrayList<String>();
        DateFormat fmt = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT);
        for (File f : files) rows.add(f.getName() + "\n" + human(f.length()) + "  ·  " + fmt.format(new Date(f.lastModified())));
        list.setAdapter(new ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, rows));
        setTitle("AEmu Import (" + files.size() + ")");
    }

    private static String human(long n) {
        if (n < 1024) return n + " B";
        if (n < 1024 * 1024) return (n / 1024) + " KB";
        return String.format("%.1f MB", n / (1024.0 * 1024.0));
    }
}
