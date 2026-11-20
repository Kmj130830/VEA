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
        "180063ca0", // primary typed matrix-array loader
        "180064e90", // secondary property loader/consumer
        "180064670", // matrix descriptor and numeric tensor packer
        "180006c80", // numeric JSON-array conversion
        "180020190", // .weight member reader
        "1800214c0", // .bias member reader
        "180010260", // salted model-parameter key transformation
        "180081e20", // numeric hex ostream manipulator used for key formatting
        "1800812a0", // MD5 block finalizer / digest output path
        "180010740", // model parameter string construction
        "180010540", // model parameter string concatenation
        "1800103e0", // model parameter string copy
        "18001acc0", // hash digest stream initialization
        "180083610", // hash digest stream formatting
        "180014f70", // hash digest stream finalization
        "180083c50", // integer-to-string component used by RNN name builder
        "1800c7ad0", // runtime block dispatcher/caller of residual depthwise-MLP
        "1800c72d0", // adjacent runtime block operator
        "1800c5260", // related linear/operator helper
        "1800c5440", // related linear/operator helper
        "1800c5d50", // residual depthwise-MLP runtime operator
        "1800c67e0", // depthwise history/ring-buffer producer
        "1800afc30", // 0x100-stride block-data vector copy helper
        "1800af720", // 0x88-stride block-state vector copy helper
        "1800b3bd0", // runtime buffer-vector capacity/growth helper
        "1800c3740", // linear projection runtime operator
        "1800aec40", // owner/caller that dispatches the neural forward method
        "180126c20", // higher-level render/voice processing caller
        "180056d60", // model feature-flag dispatch
        "180056f40", // generic RNN constructor (known disabled for this bank)
        "180068190", // named model layer loader
        "18008d470", // named-layer model context builder / runtime entry
        "18008d470", // exported model-layer object constructor entry point
        "180064670", // tensor-to-matrix descriptor loader
        "1800c5d50", // residual depthwise/MLP runtime block
        "1800c67e0", // depthwise history/ring-buffer producer
        "1800c3740", // linear projection operator
        "1800c6390"  // banked linear projection operator
    };
    private static final int MAX_CALLERS = 48;
    private static final int CONTEXT_INSTRUCTIONS = 18;

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

            out.println("## Relative function tables");
            out.println();
            dumpRelativeFunctionTable(
                out, "named-layer model object vtable", "180a5a348", -0x80, 0x80);
            dumpRelativeFunctionTable(
                out, "neural runtime operator vtable", "180a5bea8", -0x80, 0x40);
            out.println();
            out.println();

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

            out.println("## Raw instruction listing for tensor-loader ABI");
            out.println();
            for (String hex : new String[] { "180010260", "1800812a0", "180081e20", "180010740", "180010540", "1800103e0", "18001acc0", "180083610", "180014f70", "180083c50", "180024a90", "18005ebe0", "18008d470", "18008d470", "180052f90", "1800537e0", "1800589d0", "18005e9b0", "180063ca0", "180064e90", "1800c7ad0", "1800c72d0", "1800c5260", "1800c5440", "1800c5d50", "1800c67e0", "1800afc30", "1800af720", "1800b3bd0", "1800c3740", "1800aec40", "180126c20" }) {
                monitor.checkCancelled();
                Address address = toAddr(hex);
                Function f = currentProgram.getFunctionManager().getFunctionAt(address);
                if (f == null) f = currentProgram.getFunctionManager().getFunctionContaining(address);
                out.println("### " + hex + (f == null ? " (not recovered)" : " " + f.getName()));
                if (f != null) {
                    out.println("- Prototype: " + prototype(f));
                    out.println("- Body bytes: " + f.getBody().getNumAddresses());
                    InstructionIterator instructions =
                        currentProgram.getListing().getInstructions(f.getBody(), true);
                    while (instructions.hasNext()) {
                        monitor.checkCancelled();
                        out.println("- " + format(instructions.next()));
                    }
                }
                out.println();
            }

            out.println("## Global runtime-record pointer offset scan");
            out.println();
            out.println("> Scans non-stack memory operands for the runtime record-array pointer offsets observed by FUN_1800c7ad0.");
            out.println("> This is a candidate scan only; an offset match does not by itself prove the same object type.");
            out.println();
            LinkedHashMap<Address, Function> recordOffsetFunctions = new LinkedHashMap<>();
            InstructionIterator globalInstructions = currentProgram.getListing().getInstructions(true);
            int recordOffsetHits = 0;
            while (globalInstructions.hasNext()) {
                monitor.checkCancelled();
                Instruction instruction = globalInstructions.next();
                String rendered = format(instruction);
                boolean offsetMatch = rendered.contains("0x2e0]") || rendered.contains("0x2f8]");
                boolean stackReference = rendered.contains("RBP") || rendered.contains("RSP");
                if (!offsetMatch || stackReference) continue;

                Function owner = currentProgram.getFunctionManager()
                    .getFunctionContaining(instruction.getAddress());
                out.println("- " + rendered + " [" +
                    (owner == null ? "<unknown>" : owner.getName()) + "]");
                recordOffsetHits++;
                if (owner != null && recordOffsetFunctions.size() < 32) {
                    recordOffsetFunctions.putIfAbsent(owner.getEntryPoint(), owner);
                }
                if (recordOffsetHits >= 300) {
                    out.println("- Scan output capped at 300 matching instructions.");
                    break;
                }
            }
            out.println("- Matching non-stack instructions: " + recordOffsetHits);
            out.println("- Distinct candidate functions retained for decompilation: " +
                recordOffsetFunctions.size());
            out.println();

            out.println("## Target and caller decompilation");
            out.println();
            LinkedHashMap<Address, Function> all = new LinkedHashMap<>(targets);
            for (Map.Entry<Address, Function> item : callers.entrySet()) {
                if (all.size() >= MAX_CALLERS + targets.size()) break;
                all.putIfAbsent(item.getKey(), item.getValue());
            }
            for (Map.Entry<Address, Function> item : recordOffsetFunctions.entrySet()) {
                if (all.size() >= MAX_CALLERS + targets.size() + 16) break;
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

    private void dumpRelativeFunctionTable(
            PrintWriter out, String label, String baseHex,
            long startOffset, long endOffset) throws Exception {
        Address base = toAddr(baseHex);
        out.println("### " + label + " at " + base);
        for (long offset = startOffset; offset <= endOffset; offset += 4L) {
            monitor.checkCancelled();
            Address slot = offset < 0 ? base.subtract(-offset) : base.add(offset);
            byte[] raw = new byte[4];
            int read = currentProgram.getMemory().getBytes(slot, raw);
            if (read != 4) continue;
            long rva = Integer.toUnsignedLong(
                java.nio.ByteBuffer.wrap(raw).order(java.nio.ByteOrder.LITTLE_ENDIAN).getInt());
            Address candidate = currentProgram.getImageBase().add(rva);
            Function target = currentProgram.getFunctionManager().getFunctionAt(candidate);
            if (target == null) target = currentProgram.getFunctionManager().getFunctionContaining(candidate);
            if (target == null) continue;

            out.println("- Slot " + slot + " rva=0x" + Long.toHexString(rva) +
                " target=" + candidate + " function=" + target.getName() +
                " entry=" + target.getEntryPoint());

            ReferenceIterator refs = currentProgram.getReferenceManager().getReferencesTo(slot);
            while (refs.hasNext()) {
                Reference ref = refs.next();
                Function caller = currentProgram.getFunctionManager()
                    .getFunctionContaining(ref.getFromAddress());
                out.println("  - Slot reference from " + ref.getFromAddress() + " in " +
                    (caller == null ? "<unknown>" : caller.getName()) +
                    " [" + ref.getReferenceType() + "]");
            }
        }
        out.println();
    }

    private String formatBytes(byte[] bytes, int length) {
        if (length <= 0) return "<unreadable>";
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < length; i++) {
            if (i > 0) result.append(' ');
            result.append(String.format("%02x", bytes[i] & 0xff));
        }
        return result.toString();
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
