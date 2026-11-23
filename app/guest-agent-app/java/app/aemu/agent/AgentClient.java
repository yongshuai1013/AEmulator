package app.aemu.agent;

import android.net.LocalSocket;
import android.net.LocalSocketAddress;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * The Root Manager's line to the guest agent service (AgentStub in aemu-agent.jar). The agent serves
 * the abstract socket "aemu-agent-mgr" and admits this app by its uid; every answer the manager shows
 * comes over this line, from the layer itself — the manager never scans for root on its own, so what it
 * displays is what the layer knows, including when the answer is "not proven".
 *
 * Protocol: STATUS and SELFTEST answer with lines and a closing END; LIST answers pkg lines and END;
 * SETHIDE answers one OK/ERR line. A fresh connection per call keeps the failure modes simple: any
 * IOException here means the agent is not reachable, which the UI states as such.
 */
public final class AgentClient {
    private static final String SOCKET = "aemu-agent-mgr";
    private static final int TIMEOUT_MS = 20000;

    private AgentClient() { }

    /** one command, all answer lines (without the closing END) */
    public static List<String> call(String command) throws IOException {
        LocalSocket s = new LocalSocket();
        try {
            s.connect(new LocalSocketAddress(SOCKET, LocalSocketAddress.Namespace.ABSTRACT));
            s.setSoTimeout(TIMEOUT_MS);
            OutputStream out = s.getOutputStream();
            out.write((command + "\n").getBytes("UTF-8"));
            out.flush();
            BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), "UTF-8"));
            List<String> lines = new ArrayList<String>();
            String line;
            while ((line = in.readLine()) != null) {
                if (line.equals("END")) return lines;
                lines.add(line);
            }
            throw new IOException("the agent closed the connection early");
        } finally {
            try { s.close(); } catch (Throwable ignored) { }
        }
    }

    /** one command with a one-line answer (SETHIDE) */
    public static String callOne(String command) throws IOException {
        LocalSocket s = new LocalSocket();
        try {
            s.connect(new LocalSocketAddress(SOCKET, LocalSocketAddress.Namespace.ABSTRACT));
            s.setSoTimeout(TIMEOUT_MS);
            OutputStream out = s.getOutputStream();
            out.write((command + "\n").getBytes("UTF-8"));
            out.flush();
            BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), "UTF-8"));
            String line = in.readLine();
            if (line == null) throw new IOException("the agent closed the connection early");
            return line;
        } finally {
            try { s.close(); } catch (Throwable ignored) { }
        }
    }
}
