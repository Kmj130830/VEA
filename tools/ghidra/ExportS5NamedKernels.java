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

public class ExportS5NamedKernels extends GhidraScript {
    private static final String TABLE = "1801a09e0";
    private static final int MAX_TABLE_PAIRS = 64;
    private static final int MAX_FUNCTIONS = 120;

    @Override
    public void run() throws Exception {
        String[] args = getScriptArgs();
        if (args.length < 1) throw new IllegalArgumentException("Usage: ExportS5NamedKernels.java <output.md>");
        File output = new File(args[0]);
        if (output.getParentFile() != null) output.getParentFile().mkdirs();

        LinkedHashMap<Address, Function> functions = new LinkedHashMap<>();
        try (PrintWriter out = new PrintWriter(Files.newBufferedWriter(output.toPath(), StandardCharsets.UTF_8))) {
            out.println("# S5API named BLAS kernel table and implementations");
            out.println();
            out.println("> Static reverse engineering only; no binary execution.");
            out.println();
            out.println("- Program: " + currentProgram.getName());
            out.println("- Image base: " + currentProgram.getImageBase());
            out.println();

            out.println("## Name-to-function mapping");
            out.println();
            out.println("| Index | Kernel label | Function pointer | Recovered function | Body bytes |");
            out.println("|---:|---|---:|---|---:|");
            Address table = toAddr(TABLE);
            int pairs = 0;
            for (int i = 0; i < MAX_TABLE_PAIRS; i++) {
                monitor.checkCancelled();
                Address nameSlot = table.add((long)i * 16L);
                long namePtr;
                long funcPtr;
                try {
                    namePtr = currentProgram.getMemory().getLong(nameSlot);
                    funcPtr = currentProgram.getMemory().getLong(nameSlot.add(8));
                } catch (Exception e) {
                    out.println("| " + i + " | <unreadable> | - | - | - |");
                    break;
                }
                if (namePtr == 0 || funcPtr == 0) {
                    out.println("| " + i + " | <null terminator> | 0x" + Long.toHexString(funcPtr) + " | - | - |");
                    break;
                }
                Address nameAddress = toAddr(namePtr);
                Address functionAddress = toAddr(funcPtr);
                String name = readAscii(nameAddress, 96);
                Function f = currentProgram.getFunctionManager().getFunctionAt(functionAddress);
                if (f == null) f = currentProgram.getFunctionManager().getFunctionContaining(functionAddress);
                if (f != null && functions.size() < MAX_FUNCTIONS) functions.put(f.getEntryPoint(), f);
                out.println("| " + i + " | " + escape(name) + " | 0x" + Long.toHexString(funcPtr) +
                    " | " + (f == null ? "<not recovered>" : f.getName()) +
                    " | " + (f == null ? "-" : Long.toString(f.getBody().getNumAddresses())) + " |");
                pairs++;
            }
            out.println();
            out.println("- Parsed non-null entries: " + pairs);
            out.println();

            out.println("## Direct callees of selected baseline and NEON functions");
            out.println();
            Address[] inspect = {
                toAddr("180139990"), // v0_naive
                toAddr("1801399f0"), // v1_tiling
                toAddr("180139a50"), // v2_neon_unroll
                toAddr("180139ab0"), // v5_neon_simd / v11_neon_prefetch
                toAddr("180139c30"), // v14_packed_neon
                toAddr("180139cf0"), // v16_adaptive
                toAddr("180139d50"), // v17_pdist4
                toAddr("180139db0"), // v18_pdist16
                toAddr("180139e10"), // v19_pdist32
                toAddr("180139e70"), // v20_adaptive_p32
                toAddr("180139ed0")  // v21_laneq_unroll2
            };
            for (Address seed : inspect) {
                Function f = currentProgram.getFunctionManager().getFunctionAt(seed);
                if (f == null) f = currentProgram.getFunctionManager().getFunctionContaining(seed);
                if (f == null) continue;
                out.println("### " + f.getName() + " at " + f.getEntryPoint());
                ReferenceIterator refs = currentProgram.getReferenceManager().getReferencesTo(f.getEntryPoint());
                int n = 0;
                while (refs.hasNext() && n < 40) {
                    Reference ref = refs.next();
                    Function caller = currentProgram.getFunctionManager().getFunctionContaining(ref.getFromAddress());
                    out.println("- Caller: " + (caller == null ? "<unknown>" : caller.getName()) +
                        " at " + ref.getFromAddress());
                    n++;
                }
                out.println();
            }

            // Include direct callees from the mapped table functions so small wrappers do not hide the work.
            int initialCount = functions.size();
            for (int i = 0; i < initialCount && functions.size() < MAX_FUNCTIONS; i++) {
                monitor.checkCancelled();
                Function f = (Function) functions.values().toArray()[i];
                ghidra.program.model.listing.InstructionIterator it =
                    currentProgram.getListing().getInstructions(f.getBody(), true);
                while (it.hasNext() && functions.size() < MAX_FUNCTIONS) {
                    ghidra.program.model.listing.Instruction ins = it.next();
                    for (Reference ref : ins.getReferencesFrom()) {
                        if (!ref.getReferenceType().isCall()) continue;
                        Function callee = currentProgram.getFunctionManager().getFunctionAt(ref.getToAddress());
                        if (callee == null) callee = currentProgram.getFunctionManager().getFunctionContaining(ref.getToAddress());
                        if (callee != null && !functions.containsKey(callee.getEntryPoint())) {
                            functions.put(callee.getEntryPoint(), callee);
                        }
                    }
                }
            }

            out.println("## Decompilation");
            out.println();
            out.println("- Total functions (table entries plus one direct-callee level): " + functions.size());
            out.println();
            DecompInterface decompiler = new DecompInterface();
            decompiler.setOptions(new DecompileOptions());
            decompiler.openProgram(currentProgram);
            try {
                int index = 0;
                for (Map.Entry<Address, Function> entry : functions.entrySet()) {
                    monitor.checkCancelled();
                    Function f = entry.getValue();
                    index++;
                    out.println("### " + index + ". " + f.getName() + " at " + f.getEntryPoint());
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
            } finally { decompiler.dispose(); }
        }
        println("S5 named-kernel report written: " + output.getAbsolutePath());
    }

    private String readAscii(Address addr, int limit) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < limit; i++) {
            byte v;
            try { v = currentProgram.getMemory().getByte(addr.add(i)); }
            catch (Exception e) { break; }
            if (v == 0) break;
            int c = v & 0xff;
            b.append(c >= 0x20 && c <= 0x7e ? (char)c : '.');
        }
        return b.toString();
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

    private String escape(String s) { return s.replace("|", "\\|").replace("\n", " "); }
}
