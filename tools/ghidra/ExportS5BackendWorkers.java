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
import java.util.LinkedHashMap;
import java.util.Map;

public class ExportS5BackendWorkers extends GhidraScript {
    private static final String[] TARGETS = {
        "180143460", // scalar
        "180177690", // explicit NEON selector
        "180163a60", // AVX2
        "180170d40", // SSE2
        "1801832e0", // generic optimized tier
        "180153270", // v0_naive implementation
        "180182c50"  // v0 secondary backend
    };

    @Override
    public void run() throws Exception {
        String[] args = getScriptArgs();
        if (args.length < 1) throw new IllegalArgumentException("Usage: ExportS5BackendWorkers.java <output.md>");
        File output = new File(args[0]);
        if (output.getParentFile() != null) output.getParentFile().mkdirs();

        LinkedHashMap<Address, Function> functions = new LinkedHashMap<>();
        try (PrintWriter out = new PrintWriter(Files.newBufferedWriter(output.toPath(), StandardCharsets.UTF_8))) {
            out.println("# S5API backend worker decompilation");
            out.println();
            out.println("> Static analysis only; no DLL execution or dynamic instrumentation.");
            out.println();
            out.println("- Program: " + currentProgram.getName());
            out.println("- Architecture: " + currentProgram.getLanguage().getLanguageID().getIdAsString());
            out.println();

            out.println("## Entry points, prototypes and xrefs");
            out.println();
            for (String hex : TARGETS) {
                monitor.checkCancelled();
                Address address = toAddr(hex);
                Function f = currentProgram.getFunctionManager().getFunctionAt(address);
                if (f == null) f = currentProgram.getFunctionManager().getFunctionContaining(address);
                out.println("### 0x" + hex + (f == null ? " (not recovered)" : " " + f.getName()));
                if (f == null) { out.println(); continue; }
                functions.put(f.getEntryPoint(), f);
                out.println("- Prototype: " + prototype(f));
                out.println("- Body bytes: " + f.getBody().getNumAddresses());
                ReferenceIterator refs = currentProgram.getReferenceManager().getReferencesTo(f.getEntryPoint());
                int n = 0;
                while (refs.hasNext() && n < 100) {
                    Reference ref = refs.next();
                    Function caller = currentProgram.getFunctionManager().getFunctionContaining(ref.getFromAddress());
                    out.println("- Xref from " + (caller == null ? "<unknown>" : caller.getName()) +
                        " at " + ref.getFromAddress() + " [" + ref.getReferenceType() + "]");
                    n++;
                }
                if (n == 0) out.println("- No direct xrefs recovered.");
                out.println();
            }

            out.println("## Decompiled workers");
            out.println();
            DecompInterface decompiler = new DecompInterface();
            decompiler.setOptions(new DecompileOptions());
            decompiler.openProgram(currentProgram);
            try {
                for (Map.Entry<Address, Function> entry : functions.entrySet()) {
                    monitor.checkCancelled();
                    Function f = entry.getValue();
                    out.println("### " + f.getName() + " at " + f.getEntryPoint());
                    out.println();
                    out.println("- RVA: 0x" + Long.toHexString(f.getEntryPoint().subtract(currentProgram.getImageBase())));
                    out.println("- Prototype: " + prototype(f));
                    out.println("- Body bytes: " + f.getBody().getNumAddresses());
                    DecompileResults result = decompiler.decompileFunction(f, 90, monitor);
                    if (result != null && result.decompileCompleted() && result.getDecompiledFunction() != null) {
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
        println("S5 backend worker report written: " + output.getAbsolutePath());
    }

    private String prototype(Function f) {
        StringBuilder b = new StringBuilder(f.getReturnType().getDisplayName()).append(" ").append(f.getName()).append("(");
        ghidra.program.model.listing.Parameter[] params = f.getParameters();
        for (int i = 0; i < params.length; i++) {
            if (i > 0) b.append(", ");
            b.append(params[i].getDataType().getDisplayName()).append(" ").append(params[i].getName());
        }
        if (f.hasVarArgs()) { if (params.length > 0) b.append(", "); b.append("..."); }
        return b.append(")").toString();
    }
}
