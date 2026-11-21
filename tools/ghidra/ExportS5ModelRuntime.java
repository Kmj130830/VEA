import ghidra.app.decompiler.DecompInterface;
import ghidra.app.decompiler.DecompileOptions;
import ghidra.app.decompiler.DecompileResults;
import ghidra.app.script.GhidraScript;
import ghidra.program.model.address.Address;
import ghidra.program.model.listing.Function;
import ghidra.program.model.listing.Instruction;
import ghidra.program.model.listing.InstructionIterator;
import ghidra.program.model.symbol.Reference;
import java.io.File;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.Map;

public class ExportS5ModelRuntime extends GhidraScript {
    private static final int MAX_FUNCTIONS = 140;
    private static final int MAX_DEPTH = 1;

    private static final class Work {
        final Function function;
        final int depth;
        Work(Function f, int d) { function = f; depth = d; }
    }

    @Override
    public void run() throws Exception {
        String[] args = getScriptArgs();
        if (args.length < 1) throw new IllegalArgumentException("Usage: ExportS5ModelRuntime.java <output.md>");
        File output = new File(args[0]);
        if (output.getParentFile() != null) output.getParentFile().mkdirs();

        String[] seeds = {
            "180068190", "180020190", "1800214c0", "18005ebe0",
            "18005fd62874", "18006fdb0", "180024a90", "180064670",
            "180047900", "1800c7ad0", "1800c1a30", "1800ccdb0",
            "1800c5260", "1800c3740", "1800c1060", "1800c1210",
            "1800df720", "1800c0ee0", "18012d350", "18012e0d0",
            "180130b70", "18012d460", "18012f280", "180130ce0",
            // Caller of the shared VTMG graph loader (FUN_180068190).
            "18008d5b2",
            // Adjacent 0x2e8-byte model-context builder with a separate typed-JSON path.
            "18008d650"
        };

        LinkedHashMap<Address, Function> selected = new LinkedHashMap<>();
        LinkedHashMap<Address, Integer> depths = new LinkedHashMap<>();
        try (PrintWriter out = new PrintWriter(Files.newBufferedWriter(output.toPath(), StandardCharsets.UTF_8))) {
            out.println("# S5API model runtime and DSP operator trace");
            out.println();
            out.println("> Static reverse engineering only; all native code is analyzed offline and never executed.");
            out.println();
            out.println("- Program: " + currentProgram.getName());
            out.println("- Architecture: " + currentProgram.getLanguage().getLanguageID().getIdAsString());
            out.println("- Image base: " + currentProgram.getImageBase());
            out.println();
            out.println("## Seed functions and direct callers");
            out.println();

            for (String hex : seeds) {
                monitor.checkCancelled();
                Address address = toAddr(hex);
                Function f = currentProgram.getFunctionManager().getFunctionAt(address);
                if (f == null) f = currentProgram.getFunctionManager().getFunctionContaining(address);
                if (f == null) {
                    out.println("- " + hex + ": function not recovered");
                    continue;
                }
                if (!selected.containsKey(f.getEntryPoint())) {
                    selected.put(f.getEntryPoint(), f);
                    depths.put(f.getEntryPoint(), 0);
                }
                out.println("- " + hex + " -> " + f.getName() + " at " + f.getEntryPoint() +
                    " (body bytes " + f.getBody().getNumAddresses() + ")");
            }
            out.println();

            out.println("## References to selected entry points");
            out.println();
            for (Map.Entry<Address, Function> item : selected.entrySet()) {
                monitor.checkCancelled();
                out.println("### " + item.getValue().getName() + " at " + item.getKey());
                int count = 0;
                for (Reference ref : currentProgram.getReferenceManager().getReferencesTo(item.getKey())) {
                    Function caller = currentProgram.getFunctionManager().getFunctionContaining(ref.getFromAddress());
                    if (caller != null) {
                        out.println("- " + caller.getName() + " call/reference at " + ref.getFromAddress());
                        count++;
                    }
                }
                if (count == 0) out.println("- No direct code references recovered.");
                out.println();
            }

            // Include one layer of direct callees for tensor loaders and DSP operators.
            for (Map.Entry<Address, Function> item : new LinkedHashMap<>(selected).entrySet()) {
                monitor.checkCancelled();
                if (selected.size() >= MAX_FUNCTIONS) break;
                InstructionIterator it = currentProgram.getListing().getInstructions(item.getValue().getBody(), true);
                while (it.hasNext() && selected.size() < MAX_FUNCTIONS) {
                    Instruction ins = it.next();
                    for (Reference ref : ins.getReferencesFrom()) {
                        if (!ref.getReferenceType().isCall()) continue;
                        Function callee = currentProgram.getFunctionManager().getFunctionAt(ref.getToAddress());
                        if (callee == null) callee = currentProgram.getFunctionManager().getFunctionContaining(ref.getToAddress());
                        if (callee != null && !selected.containsKey(callee.getEntryPoint())) {
                            selected.put(callee.getEntryPoint(), callee);
                            depths.put(callee.getEntryPoint(), 1);
                        }
                    }
                }
            }

            out.println("## Function decompilation");
            out.println();
            out.println("- Selected functions: " + selected.size());
            out.println("- Direct callee expansion depth: " + MAX_DEPTH);
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
                    long entryRva = f.getEntryPoint().subtract(currentProgram.getImageBase());
                    out.println("- Entry RVA: 0x" + Long.toHexString(entryRva));
                    out.println("- Body bytes: " + f.getBody().getNumAddresses());
                    out.println("- Call depth: " + depths.get(item.getKey()));
                    DecompileResults result = decompiler.decompileFunction(f, 15, monitor);
                    if (result != null && result.decompileCompleted() && result.getDecompiledFunction() != null) {
                        out.println();
                        out.println("~~~c");
                        out.println(result.getDecompiledFunction().getC());
                        out.println("~~~");
                    } else {
                        out.println();
                        out.println("Decompilation unavailable: " + (result == null ? "no result" : result.getErrorMessage()));
                    }
                    out.println();
                }
            } finally {
                decompiler.dispose();
            }
        }
        println("S5API model runtime report written: " + output.getAbsolutePath());
    }
}
