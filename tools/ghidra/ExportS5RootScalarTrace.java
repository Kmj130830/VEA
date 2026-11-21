// Focused static trace for the model-populated S5API render root.
// No S5API target code is executed by this script.
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

public class ExportS5RootScalarTrace extends GhidraScript {
    private Function functionAt(String hex) {
        Address address = toAddr(hex);
        Function f = currentProgram.getFunctionManager().getFunctionAt(address);
        return f != null ? f : currentProgram.getFunctionManager().getFunctionContaining(address);
    }

    @Override
    public void run() throws Exception {
        String[] args = getScriptArgs();
        if (args.length < 1) throw new IllegalArgumentException("Usage: ExportS5RootScalarTrace.java <output.md>");
        File output = new File(args[0]);
        if (output.getParentFile() != null) output.getParentFile().mkdirs();

        String[] seeds = {
            "1800ced10", "1800bc5e0", "1800d4bf0", "1800d5a00",
            "1800b8d20", "1800b8f60", "1800af170", "1800af230",
            "1800bca40", "1800cdbb0", "1800d4a90", "1800d4610"
        };
        LinkedHashMap<Address, Function> selected = new LinkedHashMap<>();
        for (String hex : seeds) {
            monitor.checkCancelled();
            Function f = functionAt(hex);
            if (f != null) selected.put(f.getEntryPoint(), f);
        }

        try (PrintWriter out = new PrintWriter(Files.newBufferedWriter(output.toPath(), StandardCharsets.UTF_8))) {
            out.println("# S5API render-root scalar population trace");
            out.println();
            out.println("> Static Ghidra decompilation of the supplied S5API.dll. No target function is executed.");
            out.println();
            out.println("- Program: " + currentProgram.getName());
            out.println("- Architecture: " + currentProgram.getLanguage().getLanguageID().getIdAsString());
            out.println("- Image base: " + currentProgram.getImageBase());
            out.println();

            out.println("## Direct callers of render-root constructor and transform setup");
            out.println();
            out.println("| Target | Caller | Call site |");
            out.println("|---:|---|---:|");
            String[] targets = {"1800bca40", "1800ced10", "1800d5a00", "1800af170"};
            for (String hex : targets) {
                Address target = toAddr(hex);
                ReferenceIterator refs = currentProgram.getReferenceManager().getReferencesTo(target);
                while (refs.hasNext()) {
                    Reference ref = refs.next();
                    if (!ref.getReferenceType().isCall()) continue;
                    Function caller = currentProgram.getFunctionManager().getFunctionContaining(ref.getFromAddress());
                    if (caller != null) out.printf("| %s | %s at %s | %s |%n", target, caller.getName(), caller.getEntryPoint(), ref.getFromAddress());
                }
            }
            out.println();

            Function rootBuilder = functionAt("1800ced10");
            if (rootBuilder != null) {
                out.println("## Instructions immediately before FUN_1800ced10 calls FUN_1800d5a00");
                out.println();
                Address target = toAddr("1800d5a00");
                InstructionIterator iterator = currentProgram.getListing().getInstructions(rootBuilder.getBody(), true);
                ArrayDeque<Instruction> prior = new ArrayDeque<>();
                int count = 0;
                while (iterator.hasNext()) {
                    monitor.checkCancelled();
                    Instruction ins = iterator.next();
                    boolean call = false;
                    for (Reference ref : ins.getReferencesFrom()) {
                        if (ref.getReferenceType().isCall() && ref.getToAddress().equals(target)) { call = true; break; }
                    }
                    if (call) {
                        count++;
                        out.println("Callsite: " + ins.getAddress());
                        out.println("~~~asm");
                        for (Instruction p : prior) out.println(p.getAddress() + "  " + p.toString());
                        out.println(ins.getAddress() + "  " + ins.toString());
                        out.println("~~~");
                        out.println();
                    }
                    prior.addLast(ins);
                    while (prior.size() > 96) prior.removeFirst();
                }
                if (count == 0) out.println("No direct call instruction found.");
                out.println();
            }

            DecompInterface decompiler = new DecompInterface();
            decompiler.setOptions(new DecompileOptions());
            decompiler.openProgram(currentProgram);
            try {
                out.println("## Selected function decompilation");
                out.println();
                int index = 0;
                for (Map.Entry<Address, Function> entry : selected.entrySet()) {
                    monitor.checkCancelled();
                    Function f = entry.getValue();
                    index++;
                    out.println("### " + index + ". " + f.getName() + " at " + f.getEntryPoint());
                    out.println();
                    out.println("- Body bytes: " + f.getBody().getNumAddresses());
                    DecompileResults result = decompiler.decompileFunction(f, 45, monitor);
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
        println("S5API root scalar report written: " + output.getAbsolutePath());
    }
}
