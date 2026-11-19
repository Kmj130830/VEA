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

/**
 * Exports x64 call-site instructions and direct call edges for the S5API RNN
 * model-construction path. Static analysis only; the input DLL is not run.
 *
 * The key purpose is to preserve Windows x64 register and stack argument setup
 * around call instructions where Ghidra's decompiler has guessed an incomplete
 * callee prototype.
 */
public class ExportS5RnnCallSites extends GhidraScript {
    private static final int WINDOW_BEFORE = 0x50;
    private static final int WINDOW_AFTER = 0x28;
    private static final int MAX_XREFS = 64;
    private static final int MAX_CALLS_PER_FUNCTION = 160;

    private static final String[] SEEDS = {
        "180056d60", // enclosing model component setup
        "180056f40", // rnn model configuration and matrix descriptors
        "18005ebe0", // recurrent parameter loader
        "180024a90", // parameter-name builder
        "18005df50", // matrix transpose/pack
        "180065510", // matrix row-major pack
        "180068190", // layer/config loader
        "180060270", // generic parameter/config helper
        "1800c1a30", // stream/block processing path
        "1800c7ad0", // primary S5 processing path
        "18013a850", // backend context factory
        "18013aa00", // indirect backend invoke
        "1800c3740"  // generic tensor/GEMM wrapper
    };

    @Override
    public void run() throws Exception {
        String[] args = getScriptArgs();
        if (args.length < 1) {
            throw new IllegalArgumentException("Usage: ExportS5RnnCallSites.java <output.md>");
        }
        File output = new File(args[0]);
        if (output.getParentFile() != null) output.getParentFile().mkdirs();

        LinkedHashMap<Address, Function> seeds = new LinkedHashMap<>();
        try (PrintWriter out = new PrintWriter(
                Files.newBufferedWriter(output.toPath(), StandardCharsets.UTF_8))) {
            out.println("# S5API RNN call-site and argument-contract trace");
            out.println();
            out.println("> Static Ghidra disassembly/reference analysis only. No vendor DLL is executed.");
            out.println();
            out.println("- Program: " + currentProgram.getName());
            out.println("- Architecture: " + currentProgram.getLanguage().getLanguageID().getIdAsString());
            out.println("- Image base: " + currentProgram.getImageBase());
            out.println();
            out.println("## Seed functions");
            out.println();

            for (String hex : SEEDS) {
                monitor.checkCancelled();
                Address address = toAddr(hex);
                Function f = currentProgram.getFunctionManager().getFunctionAt(address);
                if (f == null) f = currentProgram.getFunctionManager().getFunctionContaining(address);
                out.println("### " + hex + (f == null ? " (function not recovered)" : " " + f.getName()));
                if (f == null) { out.println(); continue; }
                seeds.put(f.getEntryPoint(), f);
                out.println("- Entry: " + f.getEntryPoint());
                out.println("- RVA: 0x" + Long.toHexString(f.getEntryPoint().subtract(currentProgram.getImageBase())));
                out.println("- Function body bytes: " + f.getBody().getNumAddresses());
                out.println("- Signature as currently represented by Ghidra: " + prototype(f));
                out.println();
                dumpDirectCalls(out, f);
                dumpCallers(out, f);
            }

            out.println("## Exact caller instruction windows");
            out.println();
            out.println("Each window is centered on a reference to a seed function. These instructions preserve the actual call setup (register moves, stack stores, and call target) and must take precedence over guessed decompiler argument lists.");
            out.println();
            for (Map.Entry<Address, Function> entry : seeds.entrySet()) {
                monitor.checkCancelled();
                Function target = entry.getValue();
                out.println("### References to " + target.getName() + " at " + target.getEntryPoint());
                ReferenceIterator refs = currentProgram.getReferenceManager()
                    .getReferencesTo(target.getEntryPoint());
                int count = 0;
                while (refs.hasNext() && count < MAX_XREFS) {
                    monitor.checkCancelled();
                    Reference ref = refs.next();
                    Address from = ref.getFromAddress();
                    Function caller = currentProgram.getFunctionManager().getFunctionContaining(from);
                    out.println("#### Reference " + (count + 1) + " from " +
                        (caller == null ? "<unknown caller>" : caller.getName()) +
                        " at " + from + " [" + ref.getReferenceType() + "]");
                    dumpWindow(out, from, WINDOW_BEFORE, WINDOW_AFTER);
                    count++;
                }
                if (count == 0) out.println("- No references recovered by the static listing.");
                if (refs.hasNext()) out.println("- Xref output capped at " + MAX_XREFS + ".");
                out.println();
            }
        }
        println("S5API RNN call-site report written: " + output.getAbsolutePath());
    }

    private void dumpDirectCalls(PrintWriter out, Function function) throws ghidra.util.exception.CancelledException {
        out.println("- Direct calls from this function:");
        InstructionIterator it = currentProgram.getListing().getInstructions(function.getBody(), true);
        int count = 0;
        while (it.hasNext() && count < MAX_CALLS_PER_FUNCTION) {
            monitor.checkCancelled();
            Instruction instruction = it.next();
            for (Reference ref : instruction.getReferencesFrom()) {
                if (!ref.getReferenceType().isCall()) continue;
                Function callee = currentProgram.getFunctionManager().getFunctionAt(ref.getToAddress());
                if (callee == null) callee = currentProgram.getFunctionManager().getFunctionContaining(ref.getToAddress());
                out.println("  - " + instruction.getAddress() + " -> " +
                    (callee == null ? ref.getToAddress().toString() : callee.getName() + " (" + callee.getEntryPoint() + ")"));
                count++;
            }
        }
        if (count == 0) out.println("  - No direct call references recovered (indirect calls may remain).");
        if (count >= MAX_CALLS_PER_FUNCTION) out.println("  - Call output capped.");
    }

    private void dumpCallers(PrintWriter out, Function function) {
        out.println("- References to this function:");
        ReferenceIterator refs = currentProgram.getReferenceManager().getReferencesTo(function.getEntryPoint());
        int count = 0;
        while (refs.hasNext() && count < MAX_XREFS) {
            Reference ref = refs.next();
            Function caller = currentProgram.getFunctionManager().getFunctionContaining(ref.getFromAddress());
            out.println("  - " + (caller == null ? "<unknown>" : caller.getName()) +
                " at " + ref.getFromAddress() + " [" + ref.getReferenceType() + "]");
            count++;
        }
        if (count == 0) out.println("  - No references recovered.");
        if (count >= MAX_XREFS) out.println("  - Xref output capped.");
        out.println();
    }

    private void dumpWindow(PrintWriter out, Address center, int before, int after) {
        Address start;
        Address end;
        try {
            start = center.subtract(before);
            end = center.add(after);
        } catch (Exception e) {
            out.println("- Could not form instruction window: " + e.getMessage());
            return;
        }
        InstructionIterator it = currentProgram.getListing().getInstructions(
            new AddressSet(start, end), true);
        int count = 0;
        while (it.hasNext()) {
            Instruction ins = it.next();
            StringBuilder line = new StringBuilder("- ");
            line.append(ins.getAddress()).append("  ").append(ins.getMnemonicString());
            for (int i = 0; i < ins.getNumOperands(); i++) {
                line.append(i == 0 ? "  " : ", ").append(ins.getDefaultOperandRepresentation(i));
            }
            out.println(line.toString());
            count++;
        }
        if (count == 0) out.println("- No decoded instructions in this window.");
    }

    private String prototype(Function f) {
        StringBuilder b = new StringBuilder(f.getReturnType().getDisplayName())
            .append(" ").append(f.getName()).append("(");
        ghidra.program.model.listing.Parameter[] params = f.getParameters();
        for (int i = 0; i < params.length; i++) {
            if (i > 0) b.append(", ");
            b.append(params[i].getDataType().getDisplayName()).append(" ").append(params[i].getName());
        }
        if (f.hasVarArgs()) {
            if (params.length > 0) b.append(", ");
            b.append("...");
        }
        return b.append(")").toString();
    }
}
