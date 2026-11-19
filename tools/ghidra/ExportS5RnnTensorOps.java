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

public class ExportS5RnnTensorOps extends GhidraScript {
    private static final int MAX_FUNCTIONS = 180;
    private static final String[] SEEDS = {
        "18013aa00", "1800c3740", "180056d60", "180056f40", "18005ebe0",
        "180024a90", "180064670", "180068190", "18005df50",
        "180065510", "1800656e0", "180057880", "1800873a0",
        "18008e350", "18008d470", "1800aec40", "1800c1a30",
        "1800c7ad0", "1800c5d50", "1800c67e0"
    };
    @Override
    public void run() throws Exception {
        String[] args = getScriptArgs();
        if (args.length < 1) throw new IllegalArgumentException("Usage: ExportS5RnnTensorOps.java <output.md>");
        File output = new File(args[0]);
        if (output.getParentFile() != null) output.getParentFile().mkdirs();
        LinkedHashMap<Address, Function> selected = new LinkedHashMap<>();
        LinkedHashMap<Address, Integer> depth = new LinkedHashMap<>();
        try (PrintWriter out = new PrintWriter(Files.newBufferedWriter(output.toPath(), StandardCharsets.UTF_8))) {
            out.println("# S5API recurrent-layer and tensor-operation trace");
            out.println();
            out.println("Static analysis only. Supplied Windows DLLs are not executed.");
            out.println("- Program: " + currentProgram.getName());
            out.println("- Image base: " + currentProgram.getImageBase());
            out.println();
            out.println("## Seed entry points and direct callers");
            out.println();
            for (String hex : SEEDS) {
                monitor.checkCancelled();
                Address address = toAddr(hex);
                Function f = currentProgram.getFunctionManager().getFunctionAt(address);
                if (f == null) f = currentProgram.getFunctionManager().getFunctionContaining(address);
                out.println("### 0x" + hex + (f == null ? " (not recovered)" : " " + f.getName()));
                if (f == null) continue;
                if (!selected.containsKey(f.getEntryPoint())) {
                    selected.put(f.getEntryPoint(), f);
                    depth.put(f.getEntryPoint(), 0);
                }
                out.println("- Signature: " + prototype(f));
                out.println("- Body bytes: " + f.getBody().getNumAddresses());
                ReferenceIterator refs = currentProgram.getReferenceManager().getReferencesTo(f.getEntryPoint());
                int n = 0;
                while (refs.hasNext() && n < 80) {
                    Reference ref = refs.next();
                    Function caller = currentProgram.getFunctionManager().getFunctionContaining(ref.getFromAddress());
                    out.println("- Caller: " + (caller == null ? "<unknown>" : caller.getName()) +
                        " at " + ref.getFromAddress() + " (" + ref.getReferenceType() + ")");
                    if (caller != null && selected.size() < MAX_FUNCTIONS && !selected.containsKey(caller.getEntryPoint())) {
                        selected.put(caller.getEntryPoint(), caller);
                        depth.put(caller.getEntryPoint(), 1);
                    }
                    n++;
                }
                if (n == 0) out.println("- No direct references recovered; inspect indirect call/vtable use.");
                out.println();
            }
            int seedCount = selected.size();
            for (int i = 0; i < seedCount && selected.size() < MAX_FUNCTIONS; i++) {
                Function f = (Function) selected.values().toArray()[i];
                InstructionIterator it = currentProgram.getListing().getInstructions(f.getBody(), true);
                while (it.hasNext() && selected.size() < MAX_FUNCTIONS) {
                    monitor.checkCancelled();
                    Instruction ins = it.next();
                    for (Reference ref : ins.getReferencesFrom()) {
                        if (!ref.getReferenceType().isCall()) continue;
                        Function callee = currentProgram.getFunctionManager().getFunctionAt(ref.getToAddress());
                        if (callee == null) callee = currentProgram.getFunctionManager().getFunctionContaining(ref.getToAddress());
                        if (callee != null && !selected.containsKey(callee.getEntryPoint())) {
                            selected.put(callee.getEntryPoint(), callee);
                            depth.put(callee.getEntryPoint(), 2);
                        }
                    }
                }
            }
            out.println("## Decompiled functions");
            out.println("- Total selected: " + selected.size());
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
                    long rva = f.getEntryPoint().subtract(currentProgram.getImageBase());
                    out.println("- RVA: 0x" + Long.toHexString(rva));
                    out.println("- Expansion depth: " + depth.get(item.getKey()));
                    out.println("- Prototype: " + prototype(f));
                    out.println("- Body bytes: " + f.getBody().getNumAddresses());
                    DecompileResults result = decompiler.decompileFunction(f, 45, monitor);
                    if (result != null && result.decompileCompleted() && result.getDecompiledFunction() != null) {
                        out.println();
                        out.println("~~~c");
                        out.println(result.getDecompiledFunction().getC());
                        out.println("~~~");
                    } else {
                        out.println("Decompilation unavailable: " + (result == null ? "no result" : result.getErrorMessage()));
                    }
                    out.println();
                }
            } finally { decompiler.dispose(); }
            out.println("## Instruction listings");
            dump(out, toAddr("1800c3740"), 0x100);
            dump(out, toAddr("180056f40"), 0x300);
            dump(out, toAddr("18013aa00"), 0x280);
        }
        println("S5 RNN and tensor operator report written: " + output.getAbsolutePath());
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
    private void dump(PrintWriter out, Address start, int byteCount) {
        Address end = start.add(byteCount);
        InstructionIterator it = currentProgram.getListing().getInstructions(
            new ghidra.program.model.address.AddressSet(start, end), true);
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
