// Ghidra static report for the opaque S5API export surface.
import ghidra.app.decompiler.DecompInterface;
import ghidra.app.decompiler.DecompileOptions;
import ghidra.app.decompiler.DecompileResults;
import ghidra.app.script.GhidraScript;
import ghidra.program.model.address.Address;
import ghidra.program.model.listing.Function;
import ghidra.program.model.listing.Instruction;
import ghidra.program.model.listing.InstructionIterator;
import ghidra.program.model.symbol.Reference;
import ghidra.program.model.symbol.Symbol;
import ghidra.program.model.symbol.SymbolIterator;
import java.io.File;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.regex.Pattern;

public class ExportS5ApiAnalysis extends GhidraScript {
    private static final int MAX_FUNCTIONS = 140;
    private static final int MAX_DEPTH = 2;
    private static final Pattern TARGET = Pattern.compile("Func_[0-9a-fA-F]{8}");

    private static final class Work {
        final Function function;
        final int depth;
        Work(Function f, int d) { function = f; depth = d; }
    }

    @Override
    public void run() throws Exception {
        String[] args = getScriptArgs();
        if (args.length < 1) throw new IllegalArgumentException("Usage: ExportS5ApiAnalysis.java <output.md>");
        File output = new File(args[0]);
        if (output.getParentFile() != null) output.getParentFile().mkdirs();

        LinkedHashMap<Address, Function> selected = new LinkedHashMap<>();
        ArrayDeque<Work> queue = new ArrayDeque<>();
        try (PrintWriter out = new PrintWriter(Files.newBufferedWriter(output.toPath(), StandardCharsets.UTF_8))) {
            out.println("# S5API.dll export and call-closure analysis");
            out.println();
            out.println("> Static analysis only. No target binary was executed. Export names are opaque identifiers; function roles must be inferred from callers, constants, and data flow.");
            out.println();
            out.println("- Program: `" + currentProgram.getName() + "`");
            out.println("- Architecture: `" + currentProgram.getLanguage().getLanguageID().getIdAsString() + "`");
            out.println("- Compiler: `" + currentProgram.getCompilerSpec().getCompilerSpecID().getIdAsString() + "`");
            out.println("- Image base: `" + currentProgram.getImageBase() + "`");
            out.println();
            out.println("## Export functions");
            out.println();
            out.println("| Export | Address | Return / parameters |");
            out.println("|---|---:|---|");

            SymbolIterator symbols = currentProgram.getSymbolTable().getAllSymbols(true);
            int exportCount = 0;
            while (symbols.hasNext()) {
                monitor.checkCancelled();
                Symbol symbol = symbols.next();
                if (!TARGET.matcher(symbol.getName()).matches()) continue;
                exportCount++;
                Function f = currentProgram.getFunctionManager().getFunctionAt(symbol.getAddress());
                if (f == null) f = currentProgram.getFunctionManager().getFunctionContaining(symbol.getAddress());
                if (f == null) {
                    out.printf("| `%s` | `%s` | function not recovered |%n", symbol.getName(), symbol.getAddress());
                    continue;
                }
                selected.put(f.getEntryPoint(), f);
                queue.add(new Work(f, 0));
                out.printf("| `%s` | `%s` | `%s %s(%s)` |%n",
                    symbol.getName(), symbol.getAddress(), f.getReturnType().getDisplayName(),
                    f.getName(), parameters(f));
            }
            out.println();
            out.println("Export matches: " + exportCount);
            out.println("Recovered seed functions: " + selected.size());
            out.println();

            while (!queue.isEmpty() && selected.size() < MAX_FUNCTIONS) {
                monitor.checkCancelled();
                Work w = queue.removeFirst();
                if (w.depth >= MAX_DEPTH) continue;
                InstructionIterator it = currentProgram.getListing().getInstructions(w.function.getBody(), true);
                while (it.hasNext() && selected.size() < MAX_FUNCTIONS) {
                    Instruction ins = it.next();
                    for (Reference ref : ins.getReferencesFrom()) {
                        if (!ref.getReferenceType().isCall()) continue;
                        Function callee = currentProgram.getFunctionManager().getFunctionAt(ref.getToAddress());
                        if (callee == null) callee = currentProgram.getFunctionManager().getFunctionContaining(ref.getToAddress());
                        if (callee != null && !selected.containsKey(callee.getEntryPoint())) {
                            selected.put(callee.getEntryPoint(), callee);
                            queue.addLast(new Work(callee, w.depth + 1));
                        }
                    }
                }
            }

            out.println("## Export decompilation and bounded call closure");
            out.println();
            out.println("Selected functions: " + selected.size() + " (maximum call depth " + MAX_DEPTH + ").");
            out.println();
            DecompInterface decompiler = new DecompInterface();
            decompiler.setOptions(new DecompileOptions());
            decompiler.openProgram(currentProgram);
            try {
                int index = 0;
                for (Function f : selected.values()) {
                    monitor.checkCancelled();
                    index++;
                    out.println("### " + index + ". `" + f.getName() + "` at `" + f.getEntryPoint() + "`");
                    out.println();
                    out.println("- Prototype: `" + f.getReturnType().getDisplayName() + " " + f.getName() + "(" + parameters(f) + ")`");
                    out.println("- Body bytes: " + f.getBody().getNumAddresses());
                    DecompileResults result = decompiler.decompileFunction(f, 15, monitor);
                    if (result != null && result.decompileCompleted() && result.getDecompiledFunction() != null) {
                        out.println();
                        out.println("```c");
                        out.println(result.getDecompiledFunction().getC());
                        out.println("```");
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
        println("S5API report written: " + output.getAbsolutePath());
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
