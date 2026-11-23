import ghidra.app.decompiler.DecompInterface;
import ghidra.app.decompiler.DecompileOptions;
import ghidra.app.decompiler.DecompileResults;
import ghidra.app.script.GhidraScript;
import ghidra.program.model.address.Address;
import ghidra.program.model.listing.Function;
import ghidra.program.model.listing.Instruction;
import ghidra.program.model.listing.InstructionIterator;
import ghidra.program.model.symbol.Reference;
import ghidra.program.model.symbol.ReferenceIterator;
import java.io.File;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.Map;

public class ExportS5BlockRuntime extends GhidraScript {
    private static final int MAX_FUNCTIONS = 280;
    private static final String[] TARGETS = {
        // Render orchestration and the four implemented operator families.
        "1800c7ad0", "1800c5440", "1800c6390", "1800c72d0",
        "1800c5d50", "1800c67e0",

        // Megpoid stage/block tensor descriptor construction.
        "1800ced10", "1800d4bf0", "1800d4a90",
        "1800d4610", "1800c0320", "1800c3740",
        "1800c1a30", "1800ccdb0", "1801361f0",

        // Renderer-state construction, descriptor copying, and VTMG binding.
        "1800ccce0", "1800cdbb0", "1800c4730",
        "1800c47a0", "18005cba0", "180068190",
        "180064670",

        // Config/model binding and render-state construction callers.
        "1800aec40", "1800af170", "1800bca40",
        "1800aee20", "1800ae700", "1800ae770",
        "1800ae7e0", "1800ae860", "1800ae8e0",
        "1800ae950", "1800ae9e0", "1800c0670",
        "1800c06c0", "1800c0910", "1800d44b0",
        "1800d47c0",

        // Scalar math, dispatchers, and kernels already under investigation.
        "18012cb50", "18012cbb2", "18012ca50",
        "1800df720", "1800d84e0", "1800d8720",
        "18012d460", "18012e0d0", "18012f280",
        "180130b70", "180130ce0", "180130d90",

        // Phase-vocoder, oscillator, FFT/window and spectral synthesis helpers.
        "1801314f0", "1801334b0", "180133680",
        "180133ac0", "180133ba0", "180133fd0",
        "1801343c0", "180135490", "180138480",
        "180138e10", "1801397d0", "180139870",
        "18013aa00",

        // SPP FFT/IFFT, window, spectral-peak and interpolation helpers.
        "180040630", "180082a30", "18007a000",
        "18007d700", "18007fca0",
        // FFT plan/table initialization that calls FUN_18007a000.
        "180010110",
        // Leaf FFT permutations, radix stages, twiddle butterflies and packing.
        "18007e140", "18007db00", "18007cb90", "18007f170",
        "18007fff0", "1800800c0", "180081080", "18007c1c0", "18007b930",
        // Plan/context builders and additional real-FFT call sites.
        "18006d5d0", "180035b70", "180034cc0",
        "180030730", "1800356e0",
        "180135270", "1801347a0",
        "1801360c0", "180136150", "180135670", "180135b90",
        "18013a850", "1800789b0", "1800780b0",

        // Public model/resource initialization and render entry wrappers.
        "180126850", "180126940", "1801269f0",
        "180126c20", "180127220",
        "180127020", "180127120", "1801272b0",

        // VTB2 temporary loader and object registration/cleanup helpers.
        "1800b8f60", "1800dc4b0", "1800d5a00",
    };

    private static final String[] FORCE_FUNCTION_TARGETS = {
        // Direct callees observed inside FUN_180082a30.
        "18007d700", "18007fca0"
    };
    private static final String[] CONTEXT_CALL_SITES = {
        // Caller of the SPP phase/vocoder sample processor.
        "180096051",
        // Caller which binds the shared VTMG graph returned by FUN_180068190.
        "18008d5b2",
        // Caller which reaches the VTB2 Megpoid configuration loader FUN_1800af170.
        "18012729d",
        // Calls that create, expose, or invoke the loaded model objects.
        "1801268a1", "180126990", "180126a6c", "180126cde",
        "180127093", "180127193", "180127323"
    };

    @Override
    public void run() throws Exception {
        String[] args = getScriptArgs();
        if (args.length < 1) throw new IllegalArgumentException("Usage: ExportS5BlockRuntime.java <output.md>");
        File output = new File(args[0]);
        if (output.getParentFile() != null) output.getParentFile().mkdirs();

        LinkedHashMap<Address, Function> selected = new LinkedHashMap<>();
        try (PrintWriter out = new PrintWriter(Files.newBufferedWriter(output.toPath(), StandardCharsets.UTF_8))) {
            out.println("# S5API Megpoid renderer-state and residual-block binding analysis");
            out.println();
            out.println("> Static analysis only. Vendor PE code is never executed.");
            out.println();
            out.println("- Program: " + currentProgram.getName());
            out.println("- Image base: " + currentProgram.getImageBase());
            out.println();

            out.println("## Additional caller context");
            out.println();
            for (String hex : CONTEXT_CALL_SITES) {
                monitor.checkCancelled();
                Address callSite = toAddr(hex);
                Function caller = currentProgram.getFunctionManager().getFunctionContaining(callSite);
                out.println("- Call site 0x" + hex + ": " +
                    (caller == null ? "<no containing function>" : caller.getName() + " at " + caller.getEntryPoint()));
                if (caller != null) selected.put(caller.getEntryPoint(), caller);
            }
            out.println();
            out.println("## Renderer-state, stage-binding, and block-descriptor targets");
            out.println();
            for (String hex : TARGETS) {
                monitor.checkCancelled();
                Address address = toAddr(hex);
                Function f = currentProgram.getFunctionManager().getFunctionAt(address);
                if (f == null) f = currentProgram.getFunctionManager().getFunctionContaining(address);
                if (f == null) {
                    for (String forceHex : FORCE_FUNCTION_TARGETS) {
                        if (!forceHex.equalsIgnoreCase(hex)) continue;
                        disassemble(address);
                        f = currentProgram.getFunctionManager().getFunctionAt(address);
                        if (f == null) {
                            f = createFunction(address, null);
                        }
                        break;
                    }
                }
                out.println("### 0x" + hex + (f == null ? " (not recovered)" : " " + f.getName()));
                if (f == null) { out.println(); continue; }
                selected.put(f.getEntryPoint(), f);
                out.println("- Prototype: " + prototype(f));
                out.println("- Body bytes: " + f.getBody().getNumAddresses());
                ReferenceIterator refs = currentProgram.getReferenceManager().getReferencesTo(f.getEntryPoint());
                int n = 0;
                while (refs.hasNext() && n < 160) {
                    Reference ref = refs.next();
                    Function caller = currentProgram.getFunctionManager().getFunctionContaining(ref.getFromAddress());
                    out.println("- Xref: " + (caller == null ? "<unknown>" : caller.getName()) +
                        " at " + ref.getFromAddress() + " [" + ref.getReferenceType() + "]");
                    n++;
                }
                if (n == 0) out.println("- No direct references recovered.");
                out.println();
            }

            int initial = selected.size();
            for (int i = 0; i < initial && selected.size() < MAX_FUNCTIONS; i++) {
                Function f = (Function)selected.values().toArray()[i];
                monitor.checkCancelled();
                InstructionIterator it = currentProgram.getListing().getInstructions(f.getBody(), true);
                while (it.hasNext() && selected.size() < MAX_FUNCTIONS) {
                    Instruction ins = it.next();
                    for (Reference ref : ins.getReferencesFrom()) {
                        if (!ref.getReferenceType().isCall()) continue;
                        Function callee = currentProgram.getFunctionManager().getFunctionAt(ref.getToAddress());
                        if (callee == null) callee = currentProgram.getFunctionManager().getFunctionContaining(ref.getToAddress());
                        if (callee != null && !selected.containsKey(callee.getEntryPoint())) {
                            selected.put(callee.getEntryPoint(), callee);
                        }
                    }
                }
            }

            out.println("## Decompilation");
            out.println();
            out.println("- Functions selected (primary targets plus one direct callee level): " + selected.size());
            out.println();
            DecompInterface decompiler = new DecompInterface();
            decompiler.setOptions(new DecompileOptions());
            decompiler.openProgram(currentProgram);
            try {
                int index = 0;
                for (Map.Entry<Address, Function> item : selected.entrySet()) {
                    monitor.checkCancelled();
                    Function f = item.getValue();
                    index++;
                    out.println("### " + index + ". " + f.getName() + " at " + f.getEntryPoint());
                    out.println();
                    out.println("- RVA: 0x" + Long.toHexString(f.getEntryPoint().subtract(currentProgram.getImageBase())));
                    out.println("- Prototype: " + prototype(f));
                    out.println("- Body bytes: " + f.getBody().getNumAddresses());
                    DecompileResults result = decompiler.decompileFunction(f, 60, monitor);
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
            } finally { decompiler.dispose(); }
        }
        println("S5 block runtime report written: " + output.getAbsolutePath());
    }

    private String prototype(Function f) {
        StringBuilder b = new StringBuilder(f.getReturnType().getDisplayName()).append(" ").append(f.getName()).append("(");
        ghidra.program.model.listing.Parameter[] ps = f.getParameters();
        for (int i = 0; i < ps.length; i++) {
            if (i > 0) b.append(", ");
            b.append(ps[i].getDataType().getDisplayName()).append(" ").append(ps[i].getName());
        }
        if (f.hasVarArgs()) { if (ps.length > 0) b.append(", "); b.append("..."); }
        return b.append(")").toString();
    }
}
