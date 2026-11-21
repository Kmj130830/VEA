// Focused static trace for S5API's model byte transform, tensor parsing and key derivation.
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
import java.util.Map;

public class ExportS5ContainerAndHash extends GhidraScript {
    private static final int MAX_FUNCTIONS = 120;
    private static final String[] SEEDS = {
        "18002aa90", // VTx? byte transform / container decode
        "18000daa0", // JSON-like parser core
        "18000fec0", // parser/error cursor helper
        "18000f5f0", // Base64 decoder
        "180010260", // MD5/key digest
        "180010740", // string append helper
        "180010540", // string composition helper
        "180081a20", // input string length
        "1800812a0", // MD5 update/finalization core
        "180081e20", // hexadecimal stream manipulator
        "180081940", // typed-object key lookup
        "18001af30", // object member lookup
        "180083c50", // string/integer conversion helper
        "180083610", // stream-to-string helper
        "18001acc0", // four-word hexadecimal formatting
        "180006780", // integer/string formatting helper
        "180006c80", // numeric array conversion
        "1800656e0", // typed tensor conversion
        "180006a80", // aligned float allocation
        "180006b10", // descriptor allocation
        "180020190", // .weight hashed lookup
        "1800214c0", // .bias hashed lookup
        "180068190", // model layer construction entry
        "18004e3b0", // derives first S5 model feature flag
        "18004e1e0", // derives second S5 model feature flag
        "18004d170", // selects first nested S5 model object
        "18004d3b0", // selects second nested S5 model object
        "18004c460", // secondary path first nested object selector
        "18004c6a0", // secondary path second nested object selector
        "180026f60", // optional typed-object fallback
        "180010740", // string composition helper
        "180010540", // string composition helper
        "18001acc0", // digest-to-hex string conversion
        "180010260", // MD5 digest computation
        "180056d60", // caller/factory for model sub-layers, including the RNN block
        "180056f40", // model-config path that instantiates the RNN block
        "18005ebe0", // recurrent parameter loader
        "180064670", // shared model-layer tensor loader
        "18008d5b2", // callsite from Func_4cd84bb7 into FUN_180068190
        "18008ec74", // secondary model factory entry path
        "18008ec14",
        "18008ebc9",
        "180024a90", // indexed/reverse recurrent name builder
        "18001b1f0", // hashed JSON/object lookup helper
        "1800103e0", // string copy used before key generation
        "18000b100", // typed model object key lookup
        "18005704b", // callers constructing S5 layer subobjects
        "1800571c1",
        "180057337",
        "18005754b",
        "1800576d2",
        "18005fd34"  // additional recurrent parameter lookup caller
    };

    @Override
    public void run() throws Exception {
        String[] args = getScriptArgs();
        if (args.length < 1) throw new IllegalArgumentException(
            "Usage: ExportS5ContainerAndHash.java <output.md>");
        File output = new File(args[0]);
        if (output.getParentFile() != null) output.getParentFile().mkdirs();

        LinkedHashMap<Address, Function> selected = new LinkedHashMap<>();
        LinkedHashMap<Address, Integer> depth = new LinkedHashMap<>();
        try (PrintWriter out = new PrintWriter(
                Files.newBufferedWriter(output.toPath(), StandardCharsets.UTF_8))) {
            out.println("# S5API model container, typed tensor and key derivation trace");
            out.println();
            out.println("> Offline static analysis only. No supplied executable or voice model is run.");
            out.println();
            out.println("- Program: " + currentProgram.getName());
            out.println("- Architecture: " + currentProgram.getLanguage().getLanguageID().getIdAsString());
            out.println("- Image base: " + currentProgram.getImageBase());
            out.println();

            out.println("## Seed entry points");
            out.println();
            for (String hex : SEEDS) {
                monitor.checkCancelled();
                Address address = toAddr(hex);
                Function function = currentProgram.getFunctionManager().getFunctionAt(address);
                if (function == null) {
                    function = currentProgram.getFunctionManager().getFunctionContaining(address);
                }
                if (function == null) {
                    out.println("- `0x" + hex + "`: function not recovered");
                    continue;
                }
                if (!selected.containsKey(function.getEntryPoint())) {
                    selected.put(function.getEntryPoint(), function);
                    depth.put(function.getEntryPoint(), 0);
                }
                out.println("- `0x" + hex + "`: " + function.getName() +
                    " at " + function.getEntryPoint() + ", body " +
                    function.getBody().getNumAddresses() + " bytes");
            }
            out.println();

            out.println("## Direct callers of seed functions");
            out.println();
            for (Map.Entry<Address, Function> item : new LinkedHashMap<>(selected).entrySet()) {
                monitor.checkCancelled();
                out.println("### " + item.getValue().getName() + " at " + item.getKey());
                int count = 0;
                ReferenceIterator references =
                    currentProgram.getReferenceManager().getReferencesTo(item.getKey());
                while (references.hasNext()) {
                    Reference reference = references.next();
                    Function caller = currentProgram.getFunctionManager()
                        .getFunctionContaining(reference.getFromAddress());
                    String owner = caller == null ? "<data-or-unmapped>" : caller.getName();
                    out.println("- " + owner + " at " + reference.getFromAddress() +
                        " (" + reference.getReferenceType() + ")");
                    count++;
                    if (caller != null && !selected.containsKey(caller.getEntryPoint()) &&
                            selected.size() < MAX_FUNCTIONS) {
                        selected.put(caller.getEntryPoint(), caller);
                        depth.put(caller.getEntryPoint(), 1);
                    }
                }
                if (count == 0) out.println("- No direct caller reference recovered.");
                out.println();
            }

            // Expand one layer of direct callees from every original seed and caller.
            for (Map.Entry<Address, Function> item : new LinkedHashMap<>(selected).entrySet()) {
                monitor.checkCancelled();
                if (selected.size() >= MAX_FUNCTIONS) break;
                InstructionIterator instructions = currentProgram.getListing()
                    .getInstructions(item.getValue().getBody(), true);
                while (instructions.hasNext() && selected.size() < MAX_FUNCTIONS) {
                    Instruction instruction = instructions.next();
                    for (Reference reference : instruction.getReferencesFrom()) {
                        if (!reference.getReferenceType().isCall()) continue;
                        Function callee = currentProgram.getFunctionManager()
                            .getFunctionAt(reference.getToAddress());
                        if (callee == null) {
                            callee = currentProgram.getFunctionManager()
                                .getFunctionContaining(reference.getToAddress());
                        }
                        if (callee != null && !selected.containsKey(callee.getEntryPoint())) {
                            selected.put(callee.getEntryPoint(), callee);
                            depth.put(callee.getEntryPoint(), 2);
                        }
                    }
                }
            }

            out.println("## Call-site disassembly contexts");
            out.println();
            out.println("The following contexts preserve register/stack setup before calls that build recurrent names, hash parameter names, or look up typed model values.");
            out.println();
            String[] interestingTargets = {
                "18005ebe0", "180024a90", "18001b1f0", "18000b100",
                "180056d60", "180056f40", "180068190", "180064670"
            };
            for (Map.Entry<Address, Function> item : selected.entrySet()) {
                monitor.checkCancelled();
                Function function = item.getValue();
                ArrayList<Instruction> recent = new ArrayList<>();
                InstructionIterator instructions = currentProgram.getListing()
                    .getInstructions(function.getBody(), true);
                while (instructions.hasNext()) {
                    Instruction instruction = instructions.next();
                    boolean matched = false;
                    String matchedTarget = null;
                    for (Reference reference : instruction.getReferencesFrom()) {
                        if (!reference.getReferenceType().isCall()) continue;
                        String target = reference.getToAddress().toString().replaceFirst("^0+", "");
                        for (String interesting : interestingTargets) {
                            if (target.endsWith(interesting)) {
                                matched = true;
                                matchedTarget = interesting;
                                break;
                            }
                        }
                        if (matched) break;
                    }
                    if (matched) {
                        out.println("### " + function.getName() + " at " +
                            function.getEntryPoint() + " calls 0x" + matchedTarget +
                            " from " + instruction.getAddress());
                        out.println();
                        out.println("```asm");
                        for (Instruction prior : recent) {
                            out.println(prior.getAddress() + ": " + prior);
                        }
                        out.println(instruction.getAddress() + ": " + instruction);
                        out.println("```");
                        out.println();
                    }
                    recent.add(instruction);
                    if (recent.size() > 96) recent.remove(0);
                }
            }

            out.println("## Decompilation");
            out.println();
            out.println("- Selected functions: " + selected.size());
            out.println("- Includes seed functions, direct callers, and one layer of direct callees.");
            out.println("- Explicit callsite seeds include the active model-layer loader caller at 0x18008d5b2 and secondary factory path at 0x18008ec74.");
            out.println();

            DecompInterface decompiler = new DecompInterface();
            decompiler.setOptions(new DecompileOptions());
            decompiler.openProgram(currentProgram);
            try {
                int index = 0;
                for (Map.Entry<Address, Function> item : selected.entrySet()) {
                    monitor.checkCancelled();
                    Function function = item.getValue();
                    index++;
                    out.println("### " + index + ". " + function.getName() +
                        " at " + function.getEntryPoint());
                    out.println();
                    out.println("- RVA: 0x" +
                        Long.toHexString(function.getEntryPoint().subtract(currentProgram.getImageBase())));
                    out.println("- Body bytes: " + function.getBody().getNumAddresses());
                    out.println("- Expansion depth: " + depth.get(item.getKey()));
                    DecompileResults result = decompiler.decompileFunction(function, 20, monitor);
                    if (result != null && result.decompileCompleted() &&
                            result.getDecompiledFunction() != null) {
                        out.println();
                        out.println("```c");
                        out.println(result.getDecompiledFunction().getC());
                        out.println("```");
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
        println("S5API container/hash report written: " + output.getAbsolutePath());
    }
}
