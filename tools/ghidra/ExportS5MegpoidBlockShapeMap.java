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
        "180056f40", // per-layer runtime descriptor constructor
        "18005df50", // projection/normalization descriptor builder
        "180065510", // secondary feature operator descriptor builder
        "1800656e0", // shared descriptor setup helper
        "180060270", // optional feature-operator configuration path
        "180064cc0", // typed descriptor copy helper
        "180068190", // nine named-layer descriptor loader
        "1800114b0", // initializes the 0x40..0x230 vector-bearing subobject
        "180011900", // initializes a related vector-bearing subobject
        "180012030", // initializes the adjacent subobject and its fields
        "180013c00", // initializes the larger render/voice engine object
        "180014940", // destroys embedded vector subobjects
        "180015d10", // destroys vectors at +0x1f0, +0x208 and +0x220
        "180016dc0", // destroys the vector at +0x240 and nested +0x1f8 object
        "180016a80", // nested object cleanup invoked by FUN_180016dc0
        "18007af10", // nested object cleanup invoked at +0x1f8
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
        "1800c4730", // runtime model-state resolver before forward dispatch
        "1800ccce0", // caller that constructs/copies model runtime state
        "1800ced10", // builds per-block runtime records from parsed model configuration
        "1800d4bf0", // per-block 0x100-byte runtime record builder
        "1800bc5e0", // initializes dimension fields for the per-block constructor
        "1800c0a40", // indexes an array-valued model property by integer position
        "1800b6a20", // converts a model scalar value to an integer dimension
        "1800c0960", // resolves a nested model-object property by key
        "1800dc260", // tests whether a model object contains a generated property key
        "1800adca0", // copies a resolved model property value into a local descriptor
        "1800d5970", // resolves model child objects used by the block setup loop
        "1800b8a40", // initializes small runtime descriptors from scalar inputs
        "1800d4a90", // populate the first nested descriptor within each runtime block
        "1800d4610", // populate additional nested descriptors within each runtime block
        "1800c0320", // attach a shared vector descriptor into the runtime block
        "1800e63b0", // formats the per-block property-key string before lookup
        "1800f1110", // model property key factory used by per-block construction
        "180114660", // model property key factory used by per-block construction
        "18011b770", // model property key factory used by per-block construction
        "180106440", // model property key factory used by per-block construction
        "18010d550", // model property key factory used by per-block construction
        "1800f8220", // model property key factory used by per-block construction
        "1800ff330", // model property key factory used by per-block construction
        "1800ea000", // model property key factory used by per-block construction
        "1800b3130", // grow the 0x100-stride runtime record vector
        "1800b28a0", // append/construct a runtime block record
        "1800bc0c0", // copy a built runtime block record into an existing slot
        "1800b0b30", // adjust companion 0x88-byte state-record vector
        "1800b36d0", // grow companion 0x88-byte state-record vector
        "1800d3f00", // interior address retained for the constructor's long body
        "1800d3f6d", // constructor/frame-preparation call site
        "1800cdbb0", // runtime model-state copy constructor for DNN arrays
        "1800d5a00", // frame/workspace vector preparation
        "1800bbcc0", // 0x88-byte workspace record initializer
        "1800afab0", // banked matrix descriptor vector copy
        "1800aff50", // model weight matrix descriptor vector copy
        "1800b0110", // auxiliary descriptor vector copy
        "1800d9580", // 0x18-byte vector capacity helper A
        "1800d9830", // 0x18-byte vector capacity helper B
        "1800d9700", // 0x88-byte workspace vector capacity helper
        "1800b4410", // generic descriptor vector default-element append
        "1800c7ad0", // runtime block dispatcher/caller of residual depthwise-MLP
        "1800c72d0", // adjacent runtime block operator
        "1800c5260", // initial dense projection
        "1800c5440", // temporal stride-2 im2col projection
        "1800c7920", // transform immediately following the initial dense projection
        "1800c5d50", // residual depthwise-MLP runtime operator
        "1800c67e0", // depthwise history/ring-buffer producer
        "1800afc30", // 0x100-stride block-data vector copy helper
        "1800af720", // 0x88-stride block-state vector copy helper
        "1800b3bd0", // runtime buffer-vector capacity/growth helper
        "1800c3740", // linear projection runtime operator
        "1800aec40", // owner/caller that dispatches the neural forward method
        "180126c20", // higher-level render/voice processing caller
        "180126d40", // post-forward synthesis output buffer stage
        "1800aed70", // getter/copy helper called by post-forward feature buffer wrapper
        "1800aea60", // getter/copy helper called by post-forward auxiliary buffer wrapper
        "1800ae770", // processed frame/vector count query implementation
        "1800ae950", // output ring-buffer capacity query implementation
        "1800aeea0", // reset/advance runtime output cursor implementation
        "180126e30", // post-forward feature output buffer stage
        "180127120", // processed frame/vector count query
        "1801271a0", // output ring-buffer capacity query
        "180127330", // reset/advance runtime output cursor
        "180056d60", // model feature-flag dispatch
        "180056f40", // generic RNN constructor (known disabled for this bank)
        "180068190", // named model layer loader
        "18008d470", // named-layer model context builder / runtime entry
        "18008d470", // exported model-layer object constructor entry point
        "180085310", // adjacent named-model object vtable method
        "180085460", // adjacent named-model object vtable method
        "1800854e0", // adjacent named-model object vtable method
        "180085510", // adjacent named-model object vtable method
        "180085540", // adjacent named-model object vtable method
        "180085b40", // adjacent named-model object vtable method
        "180085bf0", // adjacent named-model object vtable method
        "180085c60", // adjacent named-model object vtable method
        "180085d10", // adjacent named-model object vtable method
        "18008d650", // adjacent named-model object vtable method
        "18008e340", // adjacent named-model object vtable method
        "18008e350", // adjacent named-model object vtable method
        "1800914b0", // adjacent named-model object vtable method
        "1800914f0", // adjacent named-model object vtable method
        "180091610", // adjacent named-model object vtable method
        "180092500", // adjacent named-model object vtable method
        "1800925b0", // adjacent named-model object vtable method
        "1800925f0", // adjacent named-model object vtable method
        "180097b60", // adjacent named-model object vtable method
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
            for (String hex : new String[] { "180010260", "1800812a0", "180081e20", "180010740", "180010540", "1800103e0", "18001acc0", "180083610", "180014f70", "180083c50", "180024a90", "18005ebe0", "18008d470", "18008d470", "180052f90", "1800537e0", "1800589d0", "18005e9b0", "180056f40", "18005df50", "180065510", "1800656e0", "180060270", "180064cc0", "180068190", "1800114b0", "180011900", "180012030", "180013c00", "180014940", "180015d10", "180016dc0", "180016a80", "18007af10", "180063ca0", "180064e90", "1800c4730", "1800ccce0", "1800ced10", "1800d4bf0", "1800bc5e0", "1800c0a40", "1800b6a20", "1800c0960", "1800dc260", "1800adca0", "1800d5970", "1800b8a40", "1800d4a90", "1800d4610", "1800c0320", "1800e63b0", "1800f1110", "180114660", "18011b770", "180106440", "18010d550", "1800f8220", "1800ff330", "1800ea000", "1800b3130", "1800b28a0", "1800bc0c0", "1800b0b30", "1800b36d0", "1800d3f00", "1800d3f6d", "1800cdbb0", "1800d5a00", "1800bbcc0", "1800afab0", "1800aff50", "1800b0110", "1800d9580", "1800d9830", "1800d9700", "1800b4410", "1800c7ad0", "1800c72d0", "1800c5260", "1800c5440", "1800c7920", "1800c5d50", "1800c67e0", "1800afc30", "1800af720", "1800b3bd0", "1800c3740", "1800aec40", "180126c20", "180126d40", "1800aed70", "1800aea60", "1800ae770", "1800ae950", "1800aeea0", "180126e30", "180127120", "1801271a0", "180127330" }) {
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

            out.println("## Global engine-object field-reference candidate scan");
            out.println();
            out.println("> Candidate non-stack references to engine-object fields observed near FUN_1800c7ad0 and related constructors/destructors.");
            out.println("> These are member-offset candidates, not direct tensor/weight descriptors. Constructor, destructor and callsite dataflow must be checked before assigning a semantic role.");
            out.println("> Confirmed vector triples include [begin,end,capacity] at +0x1f0/+0x1f8/+0x200 and +0x220/+0x228/+0x230 in FUN_180015d10, plus +0x240/+0x248/+0x250 in FUN_180016dc0.");
            out.println();
            String[] watchedOffsets = {
                    "0x1f8]", // confirmed vector end pointer for the vector beginning at +0x1f0 (capacity at +0x200)
                    "0x228]", // confirmed vector end pointer for the vector beginning at +0x220 (capacity at +0x230)
                    "0x250]", // confirmed vector end pointer for the vector beginning at +0x240 in FUN_180016dc0
                    "0x268]", // candidate member field; semantic role not established by offset scan
                    "0x280]", // candidate member field; semantic role not established by offset scan
                    "0x2b0]", // candidate member field; semantic role not established by offset scan
                    "0x2e0]", // residual block-data vector field identified at FUN_1800c7ad0
                    "0x2f8]"  // residual block-state vector field identified at FUN_1800c7ad0
            };
            LinkedHashMap<Address, Function> recordOffsetFunctions = new LinkedHashMap<>();
            LinkedHashMap<String, Integer> offsetHitCounts = new LinkedHashMap<>();
            InstructionIterator globalInstructions = currentProgram.getListing().getInstructions(true);
            int recordOffsetHits = 0;
            while (globalInstructions.hasNext()) {
                monitor.checkCancelled();
                Instruction instruction = globalInstructions.next();
                String rendered = format(instruction);
                boolean stackReference = rendered.contains("RBP") || rendered.contains("RSP");
                if (stackReference) continue;
                String matchedOffset = null;
                for (String candidateOffset : watchedOffsets) {
                    if (rendered.contains(candidateOffset)) {
                        matchedOffset = candidateOffset.substring(0, candidateOffset.length() - 1);
                        break;
                    }
                }
                if (matchedOffset == null) continue;

                Function owner = currentProgram.getFunctionManager()
                    .getFunctionContaining(instruction.getAddress());
                out.println("- field_offset=" + matchedOffset + " " + rendered + " [" +
                    (owner == null ? "<unknown>" : owner.getName()) + "]");
                offsetHitCounts.put(matchedOffset,
                    offsetHitCounts.getOrDefault(matchedOffset, 0) + 1);
                recordOffsetHits++;
                if (owner != null && recordOffsetFunctions.size() < 48) {
                    recordOffsetFunctions.putIfAbsent(owner.getEntryPoint(), owner);
                }
                if (recordOffsetHits >= 600) {
                    out.println("- Scan output capped at 600 matching instructions.");
                    break;
                }
            }
            out.println("- Matching non-stack instructions: " + recordOffsetHits);
            for (Map.Entry<String, Integer> entry : offsetHitCounts.entrySet()) {
                out.println("- " + entry.getKey() + " references: " + entry.getValue());
            }
            out.println("- Distinct candidate functions retained for decompilation: " +
                recordOffsetFunctions.size());
            out.println();

            out.println("## Model property-key factory table bytes");
            out.println();
            out.println("> Initial 16 entries (0x20-byte stride) from each static table base.");
            for (String tableHex : new String[] {
                    "1801d3c60", "1801d1c50", "1801cfc40", "1801cdc30",
                    "1801cbc20", "1801c9c10", "1801c7c00", "1801c5bf0" }) {
                monitor.checkCancelled();
                Address tableAddress = toAddr(tableHex);
                out.println("### Table at " + tableAddress);
                for (int offset = 0; offset < 0x200; offset += 16) {
                    byte[] raw = new byte[16];
                    int read = currentProgram.getMemory().getBytes(tableAddress.add(offset), raw);
                    StringBuilder hexBytes = new StringBuilder();
                    StringBuilder ascii = new StringBuilder();
                    for (int index = 0; index < read; index++) {
                        int value = raw[index] & 0xff;
                        if (index > 0) hexBytes.append(' ');
                        hexBytes.append(String.format("%02x", value));
                        ascii.append(value >= 0x20 && value <= 0x7e ? (char)value : '.');
                    }
                    out.println("- +" + Integer.toHexString(offset) + " " + hexBytes +
                        " |" + ascii + "|");
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
