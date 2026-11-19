// Trace the tensor parsing and shape-to-matrix conversion boundary in S5API.dll.
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
import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.Map;

public class ExportS5TensorParser extends GhidraScript {
    private static final int MAX_FUNCTIONS = 110;
    private static final int MAX_CALLERS_PER_SEED = 16;
    private static final int MAX_DEPTH = 2;

    private static final class Work {
        final Function function;
        final int depth;
        Work(Function f, int d) { function = f; depth = d; }
    }

    private static final String[] TARGETS = {
        "180006a80", "180006b10", "180006c80",
        "180010260", "1800103e0", "180010740",
        "18000b100", "18001acc0", "18001b1f0",
        "180020190", "1800214c0", "180024a90",
        "180026f60", "180047900", "180056f40",
        "180064670", "180068190", "18005ebe0",
        "18006fdb0", "180083610"
    };

    @Override
    public void run() throws Exception {
        String[] args = getScriptArgs();
        if (args.length < 1) {
            throw new IllegalArgumentException("Usage: ExportS5TensorParser.java <output.md>");
        }
        File output = new File(args[0]);
        if (output.getParentFile() != null) output.getParentFile().mkdirs();

        LinkedHashMap<Address, Function> selected = new LinkedHashMap<>();
        LinkedHashMap<Address, Integer> depthMap = new LinkedHashMap<>();
        ArrayDeque<Work> queue = new ArrayDeque<>();

        try (PrintWriter out = new PrintWriter(
                Files.newBufferedWriter(output.toPath(), StandardCharsets.UTF_8))) {
            out.println("# S5API tensor parser and matrix-layout trace");
            out.println();
            out.println("> Static reverse engineering only. No S5API code is executed.");
            out.println();
            out.println("- Program: " + currentProgram.getName());
            out.println("- Architecture: " + currentProgram.getLanguage().getLanguageID().getIdAsString());
            out.println("- Image base: " + currentProgram.getImageBase());
            out.println();

            out.println("## Target functions");
            out.println();
            out.println("| Address | Function | Prototype | Body bytes |");
            out.println("|---:|---|---|---:|");

            for (String hex : TARGETS) {
                monitor.checkCancelled();
                Address address = toAddr(hex);
                Function f = currentProgram.getFunctionManager().getFunctionAt(address);
                if (f == null) f = currentProgram.getFunctionManager().getFunctionContaining(address);
                if (f == null) {
                    out.printf("| %s | not recovered | - | - |%n", hex);
                    continue;
                }
                if (!selected.containsKey(f.getEntryPoint())) {
                    selected.put(f.getEntryPoint(), f);
                    depthMap.put(f.getEntryPoint(), 0);
                    queue.addLast(new Work(f, 0));
                }
                out.printf("| %s | %s | %s %s(%s) | %d |%n",
                    hex, f.getName(), f.getReturnType().getDisplayName(),
                    f.getName(), parameters(f), f.getBody().getNumAddresses());
            }
            out.println();

            out.println("## Direct callers and call sites");
            out.println();
            for (String hex : TARGETS) {
                monitor.checkCancelled();
                Address address = toAddr(hex);
                Function target = currentProgram.getFunctionManager().getFunctionAt(address);
                if (target == null) target = currentProgram.getFunctionManager().getFunctionContaining(address);
                if (target == null) continue;

                out.println("### " + target.getName() + " at " + target.getEntryPoint());
                ReferenceIterator refs = currentProgram.getReferenceManager().getReferencesTo(target.getEntryPoint());
                int printed = 0;
                while (refs.hasNext() && printed < MAX_CALLERS_PER_SEED) {
                    Reference ref = refs.next();
                    Function caller = currentProgram.getFunctionManager().getFunctionContaining(ref.getFromAddress());
                    if (caller == null) continue;
                    out.println("- " + caller.getName() + " at " + caller.getEntryPoint() +
                        "; call/reference site " + ref.getFromAddress());
                    printed++;
                    if (!selected.containsKey(caller.getEntryPoint()) && selected.size() < MAX_FUNCTIONS) {
                        selected.put(caller.getEntryPoint(), caller);
                        depthMap.put(caller.getEntryPoint(), 1);
                    }
                }
                if (printed == 0) out.println("- No direct code references recovered.");
                out.println();
            }

            // Add bounded direct callees from primary parsing functions.
            int initialCount = selected.size();
            for (int index = 0; index < initialCount && selected.size() < MAX_FUNCTIONS; index++) {
                Function f = (Function) selected.values().toArray()[index];
                monitor.checkCancelled();
                InstructionIterator instructions =
                    currentProgram.getListing().getInstructions(f.getBody(), true);
                while (instructions.hasNext() && selected.size() < MAX_FUNCTIONS) {
                    Instruction instruction = instructions.next();
                    for (Reference ref : instruction.getReferencesFrom()) {
                        if (!ref.getReferenceType().isCall()) continue;
                        Function callee = currentProgram.getFunctionManager().getFunctionAt(ref.getToAddress());
                        if (callee == null) {
                            callee = currentProgram.getFunctionManager().getFunctionContaining(ref.getToAddress());
                        }
                        if (callee != null && !selected.containsKey(callee.getEntryPoint())) {
                            selected.put(callee.getEntryPoint(), callee);
                            depthMap.put(callee.getEntryPoint(), 2);
                        }
                    }
                }
            }

            out.println("## Decompilation");
            out.println();
            out.println("- Functions selected: " + selected.size());
            out.println("- Maximum call expansion: 2 bounded levels");
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
                    long rva = f.getEntryPoint().subtract(currentProgram.getImageBase());
                    out.println("### " + index + ". " + f.getName() + " at " + f.getEntryPoint());
                    out.println();
                    out.println("- RVA: 0x" + Long.toHexString(rva));
                    out.println("- Body bytes: " + f.getBody().getNumAddresses());
                    out.println("- Expansion level: " + depthMap.get(entry.getKey()));
                    out.println("- Prototype: " + f.getReturnType().getDisplayName() +
                        " " + f.getName() + "(" + parameters(f) + ")");
                    DecompileResults result = decompiler.decompileFunction(f, 20, monitor);
                    if (result != null && result.decompileCompleted() &&
                            result.getDecompiledFunction() != null) {
                        out.println();
                        out.println("~~~c");
                        out.println(result.getDecompiledFunction().getC());
                        out.println("~~~");
                    } else {
                        out.println();
                        out.println("Decompilation unavailable: " +
                            (result == null ? "no result" : result.getErrorMessage()));
                    }
                    out.println();
                }
            } finally {
                decompiler.dispose();
            }
        }
        println("S5 tensor-parser report written: " + output.getAbsolutePath());
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
