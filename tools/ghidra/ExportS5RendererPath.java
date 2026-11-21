// Deep static trace of the S5API render hot path and model-context calls.
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
import ghidra.program.model.symbol.Symbol;
import ghidra.program.model.symbol.SymbolIterator;
import java.io.File;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.Map;

public class ExportS5RendererPath extends GhidraScript {
    private static final int MAX_FUNCTIONS = 190;
    private static final int MAX_DEPTH = 3;
    private static final class Work {
        final Function function;
        final int depth;
        Work(Function f, int d) { function = f; depth = d; }
    }

    @Override
    public void run() throws Exception {
        String[] args = getScriptArgs();
        if (args.length < 1) throw new IllegalArgumentException("Usage: ExportS5RendererPath.java <output.md>");
        File output = new File(args[0]);
        if (output.getParentFile() != null) output.getParentFile().mkdirs();

        LinkedHashMap<Address, Function> selected = new LinkedHashMap<>();
        ArrayDeque<Work> queue = new ArrayDeque<>();
        try (PrintWriter out = new PrintWriter(Files.newBufferedWriter(output.toPath(), StandardCharsets.UTF_8))) {
            out.println("# S5API render hot-path deep trace");
            out.println();
            out.println("> Static analysis only. No target code is executed.");
            out.println();
            out.println("- Program: " + currentProgram.getName());
            out.println("- Architecture: " + currentProgram.getLanguage().getLanguageID().getIdAsString());
            out.println("- Image base: " + currentProgram.getImageBase());
            out.println();

            String[] seedNames = {
                "Func_92864e29", "Func_417eec3e", "Func_6b47c126",
                "Func_47a12a73", "Func_133d38ab", "Func_2db29822"
            };
            for (String name : seedNames) {
                SymbolIterator symbols = currentProgram.getSymbolTable().getAllSymbols(true);
                while (symbols.hasNext()) {
                    monitor.checkCancelled();
                    Symbol s = symbols.next();
                    if (!s.getName().equals(name)) continue;
                    Function f = currentProgram.getFunctionManager().getFunctionAt(s.getAddress());
                    if (f == null) f = currentProgram.getFunctionManager().getFunctionContaining(s.getAddress());
                    if (f != null && !selected.containsKey(f.getEntryPoint())) {
                        selected.put(f.getEntryPoint(), f);
                        queue.addLast(new Work(f, 0));
                    }
                }
            }

            // Explicitly retain the upstream note/event feature path and
            // its frame/history helpers. These are easy to lose when the
            // generic call closure reaches its function cap.
            String[] internalSeeds = {
                "1801361f0", "180131fa0", "1801269f0", "1801270a0",
                "1801272b0", "180127120", "180127330", "180126c20",
                "180126e30", "180126d40", "1801271a0",
                "18006c720", "180076080", "180077520", "180028cf0",
                "180039ae0", "18003ab70", "18006fdb0", "18006b8b0",
                "18006eb60", "18003e540", "180040d50",
                "180074a50", "180074580", "180074400", "180074350",
                "180077c40", "18007bf80", "18007ef90", "18007d000",
                "18006bfa0", "18006c2d0", "18006c6f0", "18006e670",
                "180007740", "180007a90", "180007ec0", "180007330",
                "180007cb0", "18006a860", "1800782e0", "180078590"
            };
            out.println("## Explicit internal call targets");
            out.println();
            out.println("| Address | Function | Body bytes |");
            out.println("|---:|---|---:|");
            for (String hex : internalSeeds) {
                monitor.checkCancelled();
                Address address = toAddr(hex);
                Function f = currentProgram.getFunctionManager().getFunctionAt(address);
                if (f == null) f = currentProgram.getFunctionManager().getFunctionContaining(address);
                if (f == null) {
                    out.printf("| %s | not recovered | - |%n", hex);
                    continue;
                }
                if (!selected.containsKey(f.getEntryPoint())) {
                    selected.put(f.getEntryPoint(), f);
                    queue.addLast(new Work(f, 0));
                }
                out.printf("| %s | %s | %d |%n", hex, f.getName(), f.getBody().getNumAddresses());
            }
            out.println();

            out.println("## Direct callers of model/context target");
            out.println();
            Address modelTarget = toAddr("1801361f0");
            ReferenceIterator refs = currentProgram.getReferenceManager().getReferencesTo(modelTarget);
            int callerCount = 0;
            while (refs.hasNext()) {
                Reference ref = refs.next();
                Function caller = currentProgram.getFunctionManager().getFunctionContaining(ref.getFromAddress());
                if (caller != null) {
                    callerCount++;
                    out.printf("- %s at %s, call site %s%n",
                        caller.getName(), caller.getEntryPoint(), ref.getFromAddress());
                }
            }
            if (callerCount == 0) out.println("- No direct references recovered at the exact entry address.");
            out.println();

            out.println("## Direct callers of note/event feature builder");
            out.println();
            Address inputTarget = toAddr("18006c720");
            ReferenceIterator inputRefs = currentProgram.getReferenceManager().getReferencesTo(inputTarget);
            int inputCallerCount = 0;
            while (inputRefs.hasNext()) {
                Reference ref = inputRefs.next();
                Function caller = currentProgram.getFunctionManager().getFunctionContaining(ref.getFromAddress());
                if (caller != null) {
                    inputCallerCount++;
                    out.printf("- %s at %s, call site %s%n",
                        caller.getName(), caller.getEntryPoint(), ref.getFromAddress());
                }
            }
            if (inputCallerCount == 0) out.println("- No direct references recovered at the exact entry address.");
            out.println();

            while (!queue.isEmpty() && selected.size() < MAX_FUNCTIONS) {
                monitor.checkCancelled();
                Work work = queue.removeFirst();
                if (work.depth >= MAX_DEPTH) continue;
                InstructionIterator it = currentProgram.getListing().getInstructions(work.function.getBody(), true);
                while (it.hasNext() && selected.size() < MAX_FUNCTIONS) {
                    Instruction ins = it.next();
                    for (Reference ref : ins.getReferencesFrom()) {
                        if (!ref.getReferenceType().isCall()) continue;
                        Function callee = currentProgram.getFunctionManager().getFunctionAt(ref.getToAddress());
                        if (callee == null) callee = currentProgram.getFunctionManager().getFunctionContaining(ref.getToAddress());
                        if (callee != null && !selected.containsKey(callee.getEntryPoint())) {
                            selected.put(callee.getEntryPoint(), callee);
                            queue.addLast(new Work(callee, work.depth + 1));
                        }
                    }
                }
            }

            out.println("## Decompilation");
            out.println();
            out.println("- Selected functions: " + selected.size());
            out.println("- Maximum call depth: " + MAX_DEPTH);
            out.println();
            DecompInterface decompiler = new DecompInterface();
            decompiler.setOptions(new DecompileOptions());
            decompiler.openProgram(currentProgram);
            try {
                int index = 0;
                for (Map.Entry<Address, Function> entry : selected.entrySet()) {
                    monitor.checkCancelled();
                    Function f = entry.getValue();
                    index++;
                    out.println("### " + index + ". " + f.getName() + " at " + f.getEntryPoint());
                    out.println();
                    out.println("- Prototype: " + f.getReturnType().getDisplayName() + " " + f.getName() + "(" + parameters(f) + ")");
                    out.println("- Body bytes: " + f.getBody().getNumAddresses());
                    DecompileResults result = decompiler.decompileFunction(f, 25, monitor);
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
        println("S5API renderer deep report written: " + output.getAbsolutePath());
    }

    private String parameters(Function f) {
        StringBuilder b = new StringBuilder();
        ghidra.program.model.listing.Parameter[] ps = f.getParameters();
        for (int i = 0; i < ps.length; i++) {
            if (i > 0) b.append(", ");
            b.append(ps[i].getDataType().getDisplayName()).append(" ").append(ps[i].getName());
        }
        if (f.hasVarArgs()) {
            if (b.length() > 0) b.append(", ");
            b.append("...");
        }
        return b.toString();
    }
}
