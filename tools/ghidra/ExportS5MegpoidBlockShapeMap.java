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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Static-only trace tying Megpoid's fixed-key model-block object construction
 * to its callers and the already identified runtime tensor operators.
 */
public class ExportS5MegpoidBlockShapeMap extends GhidraScript {
    private static final String[] TARGETS = {
        "1800589d0", // per-voice model block array constructor
        "1800537e0", // model-block object/parameter construction
        "180052f90", // related nested block setup
        "18005e9b0", // common typed object/tensor loader
        "180020190", // .weight member reader
        "1800214c0", // .bias member reader
        "180056d60", // model feature-flag dispatch
        "180056f40", // generic RNN constructor (known disabled for this bank)
        "180068190", // named model layer loader
        "180064670", // tensor-to-matrix descriptor loader
        "1800c5d50", // residual depthwise/MLP runtime block
        "1800c67e0", // depthwise history/ring-buffer producer
        "1800c3740", // linear projection operator
        "1800c6390"  // banked linear projection operator
    };
    private static final int MAX_CALLERS = 48;
    private static final int CONTEXT_INSTRUCTIONS = 7;

    @Override
    public void run() throws Exception {
        String[] args = getScriptArgs();
        if (args.length < 1) {
            throw new IllegalArgumentException(
                "Usage: ExportS5MegpoidBlockShapeMap.java <output.md>");
        }
        File output = new File(args[0]);
        if (output.getParentFile() != null) output.getParentFile().mkdirs();

        LinkedHashMap<Address, Function> targets = new LinkedHashMap<>();
        LinkedHashMap<Address, Function> callers = new LinkedHashMap<>();
        LinkedHashMap<Address, List<Reference>> refsByCaller = new LinkedHashMap<>();

        try (PrintWriter out = new PrintWriter(
                Files.newBufferedWriter(output.toPath(), StandardCharsets.UTF_8))) {
            out.println("# AI Megpoid V7 model-block shape and operator call-site trace");
            out.println();
            out.println("> Static Ghidra analysis only. The vendor DLL is not executed.");
            out.println("> Decompiled pseudocode and xrefs are observations about the inspected PE,");
            out.println("> not runtime proof that a code path executes for a particular project.");
            out.println();
            out.println("- Program: " + currentProgram.getName());
            out.println("- Architecture: " + currentProgram.getLanguage().getLanguageID().getIdAsString());
            out.println("- Image base: " + currentProgram.getImageBase());
            out.println();

            out.println("## Target functions and direct callers");
            out.println();
            for (String hex : TARGETS) {
                monitor.checkCancelled();
                Address address = toAddr(hex);
                Function f = currentProgram.getFunctionManager().getFunctionAt(address);
                if (f == null) f = currentProgram.getFunctionManager().getFunctionContaining(address);
                out.println("### 0x" + hex + (f == null ? " (not recovered)" : " " + f.getName()));
                if (f == null) {
                    out.println();
                    continue;
                }
                targets.put(f.getEntryPoint(), f);
                out.println("- Prototype: " + prototype(f));
                out.println("- Body bytes: " + f.getBody().getNumAddresses());

                ReferenceIterator refs = currentProgram.getReferenceManager()
                    .getReferencesTo(f.getEntryPoint());
                int count = 0;
                while (refs.hasNext() && count < 200) {
                    Reference ref = refs.next();
                    Function caller = currentProgram.getFunctionManager()
                        .getFunctionContaining(ref.getFromAddress());
                    out.println("- Xref from " + ref.getFromAddress() + " in " +
                        (caller == null ? "<unknown>" : caller.getName()) +
                        " [" + ref.getReferenceType() + "]");
                    if (caller != null && callers.size() < MAX_CALLERS) {
                        callers.put(caller.getEntryPoint(), caller);
                        refsByCaller.computeIfAbsent(caller.getEntryPoint(), k -> new ArrayList<>())
                            .add(ref);
                    }
                    count++;
                }
                if (count == 0) out.println("- No direct references recovered.");
                out.println();
            }

            out.println("## Direct caller instruction contexts");
            out.println();
            for (Map.Entry<Address, Function> item : callers.entrySet()) {
                monitor.checkCancelled();
                Function caller = item.getValue();
                out.println("### " + caller.getName() + " at " + item.getKey());
                out.println("- Prototype: " + prototype(caller));
                for (Reference ref : refsByCaller.get(item.getKey())) {
                    out.println("- Reference target: " + ref.getToAddress());
                    dumpCallContext(out, caller, ref.getFromAddress());
                }
                out.println();
            }

            out.println("## Target and caller decompilation");
            out.println();
            LinkedHashMap<Address, Function> all = new LinkedHashMap<>(targets);
            for (Map.Entry<Address, Function> item : callers.entrySet()) {
                if (all.size() >= MAX_CALLERS + targets.size()) break;
                all.putIfAbsent(item.getKey(), item.getValue());
            }

            DecompInterface decompiler = new DecompInterface();
            decompiler.setOptions(new DecompileOptions());
            decompiler.openProgram(currentProgram);
            try {
                int index = 0;
                for (Map.Entry<Address, Function> item : all.entrySet()) {
                    monitor.checkCancelled();
                    Function f = item.getValue();
                    index++;
                    out.println("### " + index + ". " + f.getName() + " at " + f.getEntryPoint());
                    out.println("- RVA: 0x" +
                        Long.toHexString(f.getEntryPoint().subtract(currentProgram.getImageBase())));
                    out.println("- Prototype: " + prototype(f));
                    out.println("- Body bytes: " + f.getBody().getNumAddresses());
                    DecompileResults result = decompiler.decompileFunction(f, 45, monitor);
                    if (result != null && result.decompileCompleted() &&
                            result.getDecompiledFunction() != null) {
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
        }
        println("Megpoid block shape report written: " + output.getAbsolutePath());
    }

    private void dumpCallContext(PrintWriter out, Function caller, Address referenceAddress) {
        InstructionIterator it = currentProgram.getListing().getInstructions(caller.getBody(), true);
        List<Instruction> before = new ArrayList<>();
        boolean found = false;
        int after = 0;
        while (it.hasNext()) {
            Instruction instruction = it.next();
            if (!found) {
                if (instruction.getAddress().equals(referenceAddress)) {
                    found = true;
                    out.println("- Instruction context around " + referenceAddress + ":");
                    for (Instruction prior : before) {
                        out.println("  - " + format(prior));
                    }
                    out.println("  - " + format(instruction));
                    after = CONTEXT_INSTRUCTIONS;
                } else {
                    before.add(instruction);
                    if (before.size() > CONTEXT_INSTRUCTIONS) before.remove(0);
                }
            } else if (after > 0) {
                out.println("  - " + format(instruction));
                after--;
            } else {
                break;
            }
        }
        if (!found) {
            out.println("- No instruction object at reference address (possibly an operand/xref site).");
        }
    }

    private String format(Instruction instruction) {
        StringBuilder b = new StringBuilder(instruction.getAddress().toString())
            .append("  ").append(instruction.getMnemonicString());
        for (int i = 0; i < instruction.getNumOperands(); i++) {
            b.append(i == 0 ? "  " : ", ")
                .append(instruction.getDefaultOperandRepresentation(i));
        }
        return b.toString();
    }

    private String prototype(Function f) {
        StringBuilder b = new StringBuilder(f.getReturnType().getDisplayName())
            .append(" ").append(f.getName()).append("(");
        ghidra.program.model.listing.Parameter[] params = f.getParameters();
        for (int i = 0; i < params.length; i++) {
            if (i > 0) b.append(", ");
            b.append(params[i].getDataType().getDisplayName()).append(" ")
                .append(params[i].getName());
        }
        if (f.hasVarArgs()) {
            if (params.length > 0) b.append(", ");
            b.append("...");
        }
        return b.append(")").toString();
    }
}
