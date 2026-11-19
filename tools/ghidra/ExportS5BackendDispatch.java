import ghidra.app.decompiler.DecompInterface;
import ghidra.app.decompiler.DecompileOptions;
import ghidra.app.decompiler.DecompileResults;
import ghidra.app.script.GhidraScript;
import ghidra.program.model.address.Address;
import ghidra.program.model.listing.Function;
import ghidra.program.model.symbol.Reference;
import ghidra.program.model.symbol.ReferenceIterator;
import java.io.File;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

public class ExportS5BackendDispatch extends GhidraScript {
    private static final String[] TARGETS = {
        "18013a110", "18013a540", "18013a600", "18013a850", "18013aa00",
        "1800c3740", "1800c5260", "1800c5440", "1800c5d50", "1800c6390",
        "1800c72d0", "1800c7ad0"
    };

    @Override
    public void run() throws Exception {
        String[] args = getScriptArgs();
        if (args.length < 1) {
            throw new IllegalArgumentException("Usage: ExportS5BackendDispatch.java <output.md>");
        }
        File output = new File(args[0]);
        if (output.getParentFile() != null) output.getParentFile().mkdirs();

        try (PrintWriter out = new PrintWriter(
                Files.newBufferedWriter(output.toPath(), StandardCharsets.UTF_8))) {
            out.println("# S5API backend selection and generic tensor dispatcher");
            out.println();
            out.println("> Static Ghidra decompilation only. No vendor binary is executed.");
            out.println();
            out.println("- Program: " + currentProgram.getName());
            out.println("- Architecture: " + currentProgram.getLanguage().getLanguageID().getIdAsString());
            out.println();

            out.println("## Backend name/function table at 0x1801a09e0");
            out.println();
            out.println("| Slot | Raw pointer | Decoded string/function |");
            out.println("|---:|---:|---|");
            Address table = toAddr("1801a09e0");
            for (int i = 0; i < 32; i++) {
                monitor.checkCancelled();
                Address slot = table.add((long)i * 8L);
                long value;
                try {
                    value = currentProgram.getMemory().getLong(slot);
                } catch (Exception e) {
                    out.println("| " + i + " | unreadable | " + e.getClass().getSimpleName() + " |");
                    break;
                }
                if (value == 0) {
                    out.println("| " + i + " | 0x0 | <null terminator> |");
                    if (i >= 1) break;
                    continue;
                }
                Address target;
                try { target = toAddr(value); }
                catch (Exception e) { target = null; }
                String label = target == null ? "<invalid address>" : readAscii(target, 160);
                Function f = target == null ? null : currentProgram.getFunctionAt(target);
                if (f == null && target != null) f = currentProgram.getFunctionManager().getFunctionContaining(target);
                if (f != null) label += " -> " + f.getName() + " (" + prototype(f) + ")";
                out.println("| " + i + " | 0x" + Long.toHexString(value) + " | " + escape(label) + " |");
            }
            out.println();

            out.println("## Target prototypes and callers");
            out.println();
            for (String hex : TARGETS) {
                monitor.checkCancelled();
                Address address = toAddr(hex);
                Function f = currentProgram.getFunctionManager().getFunctionAt(address);
                if (f == null) f = currentProgram.getFunctionManager().getFunctionContaining(address);
                out.println("### 0x" + hex + (f == null ? " (not recovered)" : " " + f.getName()));
                if (f == null) { out.println(); continue; }
                out.println("- Prototype: " + prototype(f));
                out.println("- Body bytes: " + f.getBody().getNumAddresses());
                ReferenceIterator refs = currentProgram.getReferenceManager().getReferencesTo(f.getEntryPoint());
                int n = 0;
                while (refs.hasNext() && n < 100) {
                    Reference ref = refs.next();
                    Function caller = currentProgram.getFunctionManager().getFunctionContaining(ref.getFromAddress());
                    out.println("- Xref: " + (caller == null ? "<unknown>" : caller.getName()) +
                        " at " + ref.getFromAddress() + " [" + ref.getReferenceType() + "]");
                    n++;
                }
                if (n == 0) out.println("- No direct code xrefs recovered (may be indirect or table-driven).");
                out.println();
            }

            out.println("## Decompiled backend/dispatcher functions");
            out.println();
            DecompInterface decompiler = new DecompInterface();
            decompiler.setOptions(new DecompileOptions());
            decompiler.openProgram(currentProgram);
            try {
                for (String hex : TARGETS) {
                    monitor.checkCancelled();
                    Address address = toAddr(hex);
                    Function f = currentProgram.getFunctionManager().getFunctionAt(address);
                    if (f == null) f = currentProgram.getFunctionManager().getFunctionContaining(address);
                    if (f == null) continue;
                    out.println("### " + f.getName() + " at " + f.getEntryPoint());
                    out.println();
                    out.println("- RVA: 0x" +
                        Long.toHexString(f.getEntryPoint().subtract(currentProgram.getImageBase())));
                    out.println("- Prototype: " + prototype(f));
                    out.println("- Body bytes: " + f.getBody().getNumAddresses());
                    DecompileResults result = decompiler.decompileFunction(f, 40, monitor);
                    if (result != null && result.decompileCompleted() &&
                            result.getDecompiledFunction() != null) {
                        out.println();
                        out.println("~~~c");
                        out.println(result.getDecompiledFunction().getC());
                        out.println("~~~");
                    } else {
                        out.println("- Decompilation unavailable: " +
                            (result == null ? "no result" : result.getErrorMessage()));
                    }
                    out.println();
                }
            } finally {
                decompiler.dispose();
            }
        }
        println("S5 backend-dispatch report written: " + output.getAbsolutePath());
    }

    private String prototype(Function f) {
        StringBuilder b = new StringBuilder(f.getReturnType().getDisplayName())
            .append(" ").append(f.getName()).append("(");
        ghidra.program.model.listing.Parameter[] ps = f.getParameters();
        for (int i = 0; i < ps.length; i++) {
            if (i > 0) b.append(", ");
            b.append(ps[i].getDataType().getDisplayName()).append(" ").append(ps[i].getName());
        }
        if (f.hasVarArgs()) {
            if (ps.length > 0) b.append(", ");
            b.append("...");
        }
        return b.append(")").toString();
    }

    private String readAscii(Address start, int max) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < max; i++) {
            byte value;
            try { value = currentProgram.getMemory().getByte(start.add(i)); }
            catch (Exception e) { break; }
            if (value == 0) break;
            int c = value & 0xff;
            b.append(c >= 0x20 && c <= 0x7e ? (char)c : '.');
        }
        return b.toString();
    }

    private String escape(String text) {
        return text.replace("|", "\\|").replace("\n", " ");
    }
}
