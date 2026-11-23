/* Added for AEmulator Plus, 2026-11-15: Windows servicing cabinets (Microsoft MS_ProjectA mainos packages). GPL-3.0; see LICENSE and NOTICE.md. */
package app.aemu.importer.tools;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * The cabinet side of importing Microsoft's "MS_ProjectA" (Windows 10 Mobile Android subsystem, "Astoria") packages:
 * {@code microsoft_ms_projecta_mainos_cbs_<hash>.cab}, an LZX cabinet that holds {@code update.mum}, a few manifests,
 * the Android runtime DLLs of the subsystem and {@code windows/System32/aow/aow.wim}, the WIM with the Android root
 * file system ({@link WimReader}).
 *
 * <p>Only the facade lives here; the cabinet reader is {@link LgCab} (shared with the old LG KDZ import) and LZX is
 * {@link LzxDecoder}.
 */
public final class WindowsCab {
    private WindowsCab() {}

    /** Random-access bytes of a cabinet (the importer's file source, a file...). */
    public interface Source {
        long length();
        /** Reads exactly {@code len} bytes at {@code pos}. */
        void readFully(long pos, byte[] b, int off, int len) throws IOException;
    }

    private static LgCab.Src adapt(final Source s) {
        return new LgCab.Src() {
            @Override public long length() { return s.length(); }
            @Override public void readFully(long pos, byte[] b, int off, int len) throws IOException { s.readFully(pos, b, off, len); }
            @Override public void close() {}
        };
    }

    private static boolean isWim(LgCab.Member m) {
        return m.name.toLowerCase(Locale.ROOT).endsWith(".wim");
    }

    /** True for a cabinet that carries at least one .wim (and nothing in the way of reading its file table). */
    public static boolean hasWim(Source s) {
        if (s.length() < 64) return false;
        try (LgCab.Src src = adapt(s)) {
            byte[] h = new byte[8];                 // isCabHeader wants the signature and the reserved field
            src.readFully(0, h, 0, 8);
            if (!LgCab.isCabHeader(h)) return false;
            LgCab.Cab cab = LgCab.parse(src);
            for (LgCab.Member m : cab.members) if (isWim(m)) return true;
            return false;
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    /** Decodes every .wim member into {@code outDir} and returns the files, in cabinet order. */
    public static List<File> extractWims(Source s, File outDir, Consumer<String> log) throws IOException {
        try (LgCab.Src src = adapt(s)) {
            LgCab.Cab cab = LgCab.parse(src);
            List<String> names = new ArrayList<>();
            for (LgCab.Member m : cab.members) if (isWim(m)) names.add(m.name.replace('\\', '/'));
            if (names.isEmpty()) throw new IOException("CAB: no .wim file inside");
            log.accept("cab: " + cab.members.size() + " members, " + cab.folders.size() + " folder(s); unpacking " + names);
            return LgCab.extract(src, cab, outDir, WindowsCab::isWim, log);
        }
    }
}
