import ghidra.app.decompiler.DecompInterface;
import ghidra.app.decompiler.DecompileOptions;
import ghidra.app.decompiler.DecompileResults;
import ghidra.app.script.GhidraScript;
import ghidra.program.model.address.Address;
import ghidra.program.model.address.AddressSet;
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

public class ExportS5BackendContext extends GhidraScript {
    private static final int MAX_FUNCTIONS = 100;
    private static final String[] SEEDS = {
        "18013a850", "18013aa00", "1800c3740",
        "1800c3840", "1800c6390", "1800c5260"
    };

    @Override
    public void run() throws Exception {
        String[] args = getScriptArgs();
        if (args.length < 1) throw new IllegalArgumentException("Usage: ExportS5BackendContext.java <output.md>");
        File output = new File(args[0]);
        if (output.getParentFile() != null) output.getParentFile().mkdirs();

        LinkedHashMap<Address, Function> functions = new LinkedHashMap<>();
        LinkedHashMap<Address, Integer> depths = new LinkedHashMap<>();
        try (PrintWriter out = new PrintWriter(
                Files.newBufferedWriter(output.toPath(), StandardCharsets.UTF_8))) {
            out.println("# S5API generic tensor-backend context and vtable trace");
            out.println();
            out.println("Static reverse engineering only. No PE DLL code is executed.");
            out.println("- Program: " + currentProgram.getName());
            out.println("- Image base: " + currentProgram.getImageBase());
            out.println();

            for (String hex : SEEDS) {
                monitor.checkCancelled();
                Address address = toAddr(hex);
                Function f = currentProgram.getFunctionManager().getFunctionAt(address);
                if (f == null) f = currentProgram.getFunctionManager().getFunctionContaining(address);
                out.println("## Seed " + hex + (f == null ? " (not recovered)" : " " + f.getName()));
                if (f == null) { out.println(); continue; }
                functions.put(f.getEntryPoint(), f);
                depths.put(f.getEntryPoint(), 0);
                out.println("- Prototype: " + prototype(f));
                out.println("- Body bytes: " + f.getBody().getNumAddresses());
                ReferenceIterator refs = currentProgram.getReferenceManager().getReferencesTo(f.getEntryPoint());
                int n = 0;
                while (refs.hasNext() && n < 100) {
                    Reference ref = refs.next();
                    Function caller = currentProgram.getFunctionManager().getFunctionContaining(ref.getFromAddress());
                    out.println("- Reference from " + ref.getFromAddress() + " in " +
                        (caller == null ? "<unknown>" : caller.getName()) + " (" + ref.getReferenceType() + ")");
                    if (caller != null && functions.size() < MAX_FUNCTIONS && !functions.containsKey(caller.getEntryPoint())) {
                        functions.put(caller.getEntryPoint(), caller);
                        depths.put(caller.getEntryPoint(), 1);
                    }
                    n++;
                }
                if (n == 0) out.println("- No direct references recovered.");
                out.println();
            }

            int startCount = functions.size();
            for (int i = 0; i < startCount && functions.size() < MAX_FUNCTIONS; i++) {
                Function f = (Function) functions.values().toArray()[i];
                InstructionIterator it = currentProgram.getListing().getInstructions(f.getBody(), true);
                while (it.hasNext() && functions.size() < MAX_FUNCTIONS) {
                    monitor.checkCancelled();
                    Instruction ins = it.next();
                    for (Reference ref : ins.getReferencesFrom()) {
                        if (!ref.getReferenceType().isCall()) continue;
                        Function callee = currentProgram.getFunctionManager().getFunctionAt(ref.getToAddress());
                        if (callee == null) callee = currentProgram.getFunctionManager().getFunctionContaining(ref.getToAddress());
                        if (callee != null && !functions.containsKey(callee.getEntryPoint())) {
                            functions.put(callee.getEntryPoint(), callee);
                            depths.put(callee.getEntryPoint(), 2);
                        }
                    }
                }
            }

            out.println("## Function decompilation");
            out.println("- Total: " + functions.size());
            DecompInterface decompiler = new DecompInterface();
            decompiler.setOptions(new DecompileOptions());
            decompiler.openProgram(currentProgram);
            try {
                int index = 0;
                for (Map.Entry<Address, Function> item : functions.entrySet()) {
                    monitor.checkCancelled();
                    Function f = item.getValue();
                    index++;
                    out.println("### " + index + ". " + f.getName() + " at " + f.getEntryPoint());
                    out.println("- RVA: 0x" + Long.toHexString(f.getEntryPoint().subtract(currentProgram.getImageBase())));
                    out.println("- Depth: " + depths.get(item.getKey()));
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
            } finally {
                decompiler.dispose();
            }

            out.println("## Instruction listing: backend context factory");
            dump(out, toAddr("18013a850"), 0x300);
            out.println();
            out.println("## Instruction listing: operation dispatcher");
            dump(out, toAddr("18013aa00"), 0x100);
        }
        println("S5 backend context report written: " + output.getAbsolutePath());
    }

    private String prototype(Function f) {
        StringBuilder b = new StringBuilder();
        b.append(f.getReturnType().getDisplayName()).append(" ").append(f.getName()).append("(");
        ghidra.program.model.listing.Parameter[] ps = f.getParameters();
        for (int i = 0; i < ps.length; i++) {
            if (i > 0) b.append(", ");
            b.append(ps[i].getDataType().getDisplayName()).append(" ").append(ps[i].getName());
        }
        if (f.hasVarArgs()) { if (ps.length > 0) b.append(", "); b.append("..."); }
        return b.append(")").toString();
    }

    private void dump(PrintWriter out, Address start, int length) {
        InstructionIterator it = currentProgram.getListing().getInstructions(
            new AddressSet(start, start.add(length)), true);
        while (it.hasNext()) {
            Instruction ins = it.next();
            StringBuilder b = new StringBuilder(ins.getAddress().toString()).append("  ").append(ins.getMnemonicString());
            for (int i = 0; i < ins.getNumOperands(); i++) {
                b.append(i == 0 ? "  " : ", ").append(ins.getDefaultOperandRepresentation(i));
            }
            out.println("- " + b.toString());
        }
    }
}
