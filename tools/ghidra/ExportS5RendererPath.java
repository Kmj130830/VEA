// Deep static trace of the S5API render hot path and model-context calls.
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
import ghidra.program.model.symbol.Symbol;
import ghidra.program.model.symbol.SymbolIterator;
import java.io.File;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.Map;

public class ExportS5RendererPath extends GhidraScript {
    private static final int MAX_FUNCTIONS = 320;
    private static final int MAX_DEPTH = 3;
    private static final class Work {
        final Function function;
        final int depth;
        Work(Function f, int d) { function = f; depth = d; }
    }

    @Override
    public void run() throws Exception {
        String[] args = getScriptArgs();
        if (args.length < 1) throw new IllegalArgumentException("Usage: ExportS5RendererPath.java <output.md>");
        File output = new File(args[0]);
        if (output.getParentFile() != null) output.getParentFile().mkdirs();

        LinkedHashMap<Address, Function> selected = new LinkedHashMap<>();
        ArrayDeque<Work> queue = new ArrayDeque<>();
        try (PrintWriter out = new PrintWriter(Files.newBufferedWriter(output.toPath(), StandardCharsets.UTF_8))) {
            out.println("# S5API render hot-path deep trace");
            out.println();
            out.println("> Static analysis only. No target code is executed.");
            out.println();
            out.println("- Program: " + currentProgram.getName());
            out.println("- Architecture: " + currentProgram.getLanguage().getLanguageID().getIdAsString());
            out.println("- Image base: " + currentProgram.getImageBase());
            out.println();

            String[] seedNames = {
                "Func_92864e29", "Func_417eec3e", "Func_6b47c126",
                "Func_47a12a73", "Func_133d38ab", "Func_2db29822"
            };
            for (String name : seedNames) {
                SymbolIterator symbols = currentProgram.getSymbolTable().getAllSymbols(true);
                while (symbols.hasNext()) {
                    monitor.checkCancelled();
                    Symbol s = symbols.next();
                    if (!s.getName().equals(name)) continue;
                    Function f = currentProgram.getFunctionManager().getFunctionAt(s.getAddress());
                    if (f == null) f = currentProgram.getFunctionManager().getFunctionContaining(s.getAddress());
                    if (f != null && !selected.containsKey(f.getEntryPoint())) {
                        selected.put(f.getEntryPoint(), f);
                        queue.addLast(new Work(f, 0));
                    }
                }
            }

            // Explicitly retain the upstream note/event feature path and
            // its frame/history helpers. These are easy to lose when the
            // generic call closure reaches its function cap.
            String[] internalSeeds = {
                "1801361f0", "180131fa0", "1801269f0", "1801270a0",
                "1801272b0", "180127120", "180127330", "180126c20",
                "180126e30", "180126d40", "1801271a0",
                "18006c720", "180074a50", "180076080", "180077520",
                "180028cf0", "180039ae0", "18003ab70", "18006fdb0",
                "18006b8b0", "18006eb60", "18003e540", "180040d50",
                "180074350", "180074400", "180074580", "180074a10",
                "180074a50", "180074580", "180074400", "180074350",
                "180077c40", "18007bf80", "18007ef90", "18007d000",
                "18006bfa0", "18006c2d0", "18006c6f0", "18006e670",
                "180007740", "180007a90", "180007ec0", "180007330",
                "180007cb0", "18006a860", "1800782e0", "180078590",
                "180001010", "1800066f0", "180010990", "180008a60",
                "180014230", "180006920", "1800148b0",
                // Trace construction of the per-render phone-category map.
                "180012b40", "1800591d0", "1800290f0", "18005b9a0",
                "18005c890", "18005cba0", "18004f9d0", "1800522f0",
                "18000adc0", "18000b100", "180026f60", "18007abf0",
                // The render-context phone-category tree is passed into
                // FUN_180074a50 separately from the global alias tree.
                // Force its initializer and adjacent subobject constructors
                // into the report instead of relying on the bounded closure.
                "180050aa0", "180013410", "180012030", "180011be0",
                // The phone-label -> category-index map builder walks the
                // global grouped phone inventory at DAT_1801c4160/4168.
                "18006c520", "1800103e0", "180197460",
                "18004be40", "18004cb90",
                // CRT/global constructor that populates DAT_1801c4160,
                // and the helper calls that materialize grouped phone strings.
                "180003a90", "180011120", "1800111c0", "180010500",
                "1800111c0", "18007b370",
                // Explicitly trace the output transform descriptor and its
                // construction path; do not infer its fields from matching
                // offsets in unrelated render-state objects.
                "18012c740", "180040630", "18012b150", "18012ad70",
                "18012b470", "18012b450", "18012c730", "1800c7ad0",
                "180128c40", "180129240", "1800bca40", "1800af170",
                "1800af230", "180126940", "180127220", "180127630",
                // The output overlap descriptor is built by the render-state
                // configuration helper chain; retain every constructor and
                // transfer helper explicitly in the static report.
                "1800d5a00", "1800b8d20", "1800b8e00",
                "180127d00", "180127dc0", "1800ad9d0", "1800ada20",
                // Explicit frame-window generator and its math helpers.
                "18012a9a0", "180082db0", "18007b870",
                // FFT plan creation used by the output transform descriptor.
                "18004e580", "18004e620", "18002ace0", "180010110",
                "180040630", "180082a30", "18007d700", "18007fca0",
                "18007a000", "18000fc20",
                "1800ccafd"
            };
            out.println("## Explicit internal call targets");
            out.println();
            out.println("| Address | Function | Body bytes |");
            out.println("|---:|---|---:|");
            for (String hex : internalSeeds) {
                monitor.checkCancelled();
                Address address = toAddr(hex);
                Function f = currentProgram.getFunctionManager().getFunctionAt(address);
                if (f == null) f = currentProgram.getFunctionManager().getFunctionContaining(address);
                if (f == null) {
                    out.printf("| %s | not recovered | - |%n", hex);
                    continue;
                }
                if (!selected.containsKey(f.getEntryPoint())) {
                    selected.put(f.getEntryPoint(), f);
                    queue.addLast(new Work(f, 0));
                }
                out.printf("| %s | %s | %d |%n", hex, f.getName(), f.getBody().getNumAddresses());
            }
            out.println();

            out.println("## Spectral output and transform-state construction cross-references");
            out.println();
            out.println("| Target address | Recovered target | Direct caller | Call site |");
            out.println("|---:|---|---|---:|");
            String[][] spectralTargets = {
                {"18012c740", "spectral output and overlap-add"},
                {"18012c730", "render-state frame-validity setter"},
                {"180040630", "real FFT wrapper"},
                {"1800c7ad0", "S5API render-state forward path"},
                {"18012b150", "excitation frame forward transform"},
                {"18012b470", "render-state transform feature copy A"},
                {"18012b450", "render-state transform feature copy B"},
                {"18004e580", "FFT plan builder"},
                {"18004e620", "FFT plan table setup"},
                {"18002ace0", "FFT plan state initialization"},
                {"180010110", "FFT execution-plan allocator"},
                {"18007d700", "inverse scalar FFT core"},
                {"18007fca0", "forward scalar FFT core"},
                {"180128c40", "transform-state constructor"},
                {"180129240", "transform-state allocator"},
                {"1800bca40", "render-state root initializer"},
                {"1800af170", "render-state model configuration loader"},
                {"1800af230", "render-state option setter"},
                {"180126940", "public render-state wrapper allocator"}
            };
            int spectralReferenceCount = 0;
            for (String[] target : spectralTargets) {
                Address targetAddress = toAddr(target[0]);
                Function targetFunction =
                    currentProgram.getFunctionManager().getFunctionAt(targetAddress);
                if (targetFunction == null) {
                    targetFunction = currentProgram.getFunctionManager()
                        .getFunctionContaining(targetAddress);
                }
                ReferenceIterator targetRefs =
                    currentProgram.getReferenceManager().getReferencesTo(targetAddress);
                while (targetRefs.hasNext()) {
                    Reference ref = targetRefs.next();
                    if (!ref.getReferenceType().isCall()) continue;
                    Function caller = currentProgram.getFunctionManager()
                        .getFunctionContaining(ref.getFromAddress());
                    if (caller == null) continue;
                    spectralReferenceCount++;
                    out.printf("| %s (%s) | %s | %s at %s | %s |%n",
                        targetAddress,
                        targetFunction == null ? "not recovered" : targetFunction.getName(),
                        target[1], caller.getName(), caller.getEntryPoint(), ref.getFromAddress());
                }
            }
            if (spectralReferenceCount == 0) {
                out.println("| - | - | no direct callers recovered | - |");
            }
            out.println();

            out.println("## Frame-window generator callers");
            out.println();
            Address frameWindowTarget = toAddr("18012a9a0");
            ReferenceIterator frameWindowRefs =
                currentProgram.getReferenceManager().getReferencesTo(frameWindowTarget);
            int frameWindowCallerCount = 0;
            while (frameWindowRefs.hasNext()) {
                Reference ref = frameWindowRefs.next();
                if (!ref.getReferenceType().isCall()) continue;
                Function caller = currentProgram.getFunctionManager()
                    .getFunctionContaining(ref.getFromAddress());
                if (caller == null) continue;
                frameWindowCallerCount++;
                out.printf("- %s at %s, call site %s%n",
                    caller.getName(), caller.getEntryPoint(), ref.getFromAddress());
            }
            if (frameWindowCallerCount == 0) {
                out.println("- No direct call reference recovered.");
            }
            out.println();

            out.println("## Native callsite instructions for transform-state factories");
            out.println();
            Function descriptorOwner =
                currentProgram.getFunctionManager().getFunctionAt(toAddr("1800d5a00"));
            if (descriptorOwner == null) {
                out.println("- FUN_1800d5a00 was not recovered.");
            } else {
                String[] factoryTargets = {"1800b8d20", "1800b8e00"};
                for (String targetHex : factoryTargets) {
                    Address targetAddress = toAddr(targetHex);
                    out.println("### Call to " + targetAddress);
                    out.println();
                    InstructionIterator ownerInstructions =
                        currentProgram.getListing().getInstructions(descriptorOwner.getBody(), true);
                    ArrayDeque<Instruction> preceding = new ArrayDeque<>();
                    int matchingCalls = 0;
                    while (ownerInstructions.hasNext()) {
                        monitor.checkCancelled();
                        Instruction ins = ownerInstructions.next();
                        boolean match = false;
                        for (Reference ref : ins.getReferencesFrom()) {
                            if (ref.getReferenceType().isCall() &&
                                ref.getToAddress().equals(targetAddress)) {
                                match = true;
                                break;
                            }
                        }
                        if (match) {
                            matchingCalls++;
                            out.println("Callsite: " + ins.getAddress());
                            out.println("~~~asm");
                            for (Instruction prior : preceding) {
                                out.println(prior.getAddress() + "  " + prior.toString());
                            }
                            out.println(ins.getAddress() + "  " + ins.toString());
                            out.println("~~~");
                            out.println();
                        }
                        preceding.addLast(ins);
                        while (preceding.size() > 48) preceding.removeFirst();
                    }
                    if (matchingCalls == 0) {
                        out.println("- No direct call instruction found in FUN_1800d5a00.");
                    }
                    out.println();
                }
            }

            out.println("## Output overlap descriptor construction callsites");
            out.println();
            out.println("| Target address | Function | Direct caller | Call site |");
            out.println("|---:|---|---|---:|");
            String[] outputDescriptorTargets = {
                "1800d5a00", "1800b8d20", "180127d00", "180127dc0",
                "1800ad9d0", "1800ada20"
            };
            int outputDescriptorReferenceCount = 0;
            for (String hex : outputDescriptorTargets) {
                Address targetAddress = toAddr(hex);
                Function targetFunction =
                    currentProgram.getFunctionManager().getFunctionAt(targetAddress);
                if (targetFunction == null) {
                    targetFunction = currentProgram.getFunctionManager()
                        .getFunctionContaining(targetAddress);
                }
                ReferenceIterator targetRefs =
                    currentProgram.getReferenceManager().getReferencesTo(targetAddress);
                while (targetRefs.hasNext()) {
                    Reference ref = targetRefs.next();
                    if (!ref.getReferenceType().isCall()) continue;
                    Function caller = currentProgram.getFunctionManager()
                        .getFunctionContaining(ref.getFromAddress());
                    if (caller == null) continue;
                    outputDescriptorReferenceCount++;
                    out.printf("| %s | %s | %s at %s | %s |%n",
                        targetAddress,
                        targetFunction == null ? "not recovered" : targetFunction.getName(),
                        caller.getName(), caller.getEntryPoint(), ref.getFromAddress());
                }
            }
            if (outputDescriptorReferenceCount == 0) {
                out.println("| - | - | no direct callers recovered | - |");
            }
            out.println();

            out.println("## Direct callers of model/context target");
            out.println();
            Address modelTarget = toAddr("1801361f0");
            ReferenceIterator refs = currentProgram.getReferenceManager().getReferencesTo(modelTarget);
            int callerCount = 0;
            while (refs.hasNext()) {
                Reference ref = refs.next();
                Function caller = currentProgram.getFunctionManager().getFunctionContaining(ref.getFromAddress());
                if (caller != null) {
                    callerCount++;
                    out.printf("- %s at %s, call site %s%n",
                        caller.getName(), caller.getEntryPoint(), ref.getFromAddress());
                }
            }
            if (callerCount == 0) out.println("- No direct references recovered at the exact entry address.");
            out.println();

            out.println("## Direct callers of note/event feature builder");
            out.println();
            Address inputTarget = toAddr("18006c720");
            ReferenceIterator inputRefs = currentProgram.getReferenceManager().getReferencesTo(inputTarget);
            int inputCallerCount = 0;
            while (inputRefs.hasNext()) {
                Reference ref = inputRefs.next();
                Function caller = currentProgram.getFunctionManager().getFunctionContaining(ref.getFromAddress());
                if (caller != null) {
                    inputCallerCount++;
                    out.printf("- %s at %s, call site %s%n",
                        caller.getName(), caller.getEntryPoint(), ref.getFromAddress());
                }
            }
            if (inputCallerCount == 0) out.println("- No direct references recovered at the exact entry address.");
            out.println();

            out.println("## Direct callers of phone-event adapter");
            out.println();
            Address phoneAdapterTarget = toAddr("18003ab70");
            ReferenceIterator phoneAdapterRefs =
                currentProgram.getReferenceManager().getReferencesTo(phoneAdapterTarget);
            int phoneAdapterCallerCount = 0;
            while (phoneAdapterRefs.hasNext()) {
                Reference ref = phoneAdapterRefs.next();
                Function caller = currentProgram.getFunctionManager()
                    .getFunctionContaining(ref.getFromAddress());
                if (caller == null) continue;
                phoneAdapterCallerCount++;
                out.printf("- %s at %s, call site %s%n",
                    caller.getName(), caller.getEntryPoint(), ref.getFromAddress());
            }
            if (phoneAdapterCallerCount == 0) {
                out.println("- No direct references recovered at the exact entry address.");
            }
            out.println();

            out.println("## Direct callers of phone-category context initializer");
            out.println();
            Address phoneMapTarget = toAddr("180050aa0");
            ReferenceIterator phoneMapRefs =
                currentProgram.getReferenceManager().getReferencesTo(phoneMapTarget);
            int phoneMapCallerCount = 0;
            while (phoneMapRefs.hasNext()) {
                Reference ref = phoneMapRefs.next();
                Function caller = currentProgram.getFunctionManager()
                    .getFunctionContaining(ref.getFromAddress());
                if (caller == null) continue;
                phoneMapCallerCount++;
                out.printf("- %s at %s, call site %s%n",
                    caller.getName(), caller.getEntryPoint(), ref.getFromAddress());
            }
            if (phoneMapCallerCount == 0) {
                out.println("- No direct references recovered at the exact entry address.");
            }
            out.println();

            out.println("## Direct callers of global phone-vocabulary constructor");
            out.println();
            Address vocabularyConstructor = toAddr("180003a90");
            ReferenceIterator constructorRefs =
                currentProgram.getReferenceManager().getReferencesTo(vocabularyConstructor);
            int constructorCallerCount = 0;
            while (constructorRefs.hasNext()) {
                Reference ref = constructorRefs.next();
                Function caller = currentProgram.getFunctionManager()
                    .getFunctionContaining(ref.getFromAddress());
                if (caller == null) continue;
                constructorCallerCount++;
                out.printf("- %s at %s, call site %s%n",
                    caller.getName(), caller.getEntryPoint(), ref.getFromAddress());
            }
            if (constructorCallerCount == 0) {
                out.println("- No direct caller recovered; constructor may be registered through CRT initialization metadata.");
            }
            out.println();

            out.println("## Direct callers of phone-label category map builder");
            out.println();
            Address categoryMapBuilder = toAddr("18006c520");
            ReferenceIterator categoryMapBuilderRefs =
                currentProgram.getReferenceManager().getReferencesTo(categoryMapBuilder);
            int categoryMapCallerCount = 0;
            while (categoryMapBuilderRefs.hasNext()) {
                Reference ref = categoryMapBuilderRefs.next();
                Function caller = currentProgram.getFunctionManager()
                    .getFunctionContaining(ref.getFromAddress());
                if (caller == null) continue;
                categoryMapCallerCount++;
                out.printf("- %s at %s, call site %s%n",
                    caller.getName(), caller.getEntryPoint(), ref.getFromAddress());
            }
            if (categoryMapCallerCount == 0) {
                out.println("- No direct call reference recovered; inspect vtable/initializer paths.");
            }
            out.println();

            out.println("## Grouped phone-vocabulary global references");
            out.println();
            out.println("| Global address | Referencing function | Reference site | Reference type |");
            out.println("|---:|---|---:|---|");
            String[] phoneVocabularyGlobals = {"1801c4160", "1801c4168"};
            int vocabularyReferenceCount = 0;
            for (String hex : phoneVocabularyGlobals) {
                Address globalAddress = toAddr(hex);
                ReferenceIterator vocabularyRefs =
                    currentProgram.getReferenceManager().getReferencesTo(globalAddress);
                while (vocabularyRefs.hasNext()) {
                    Reference ref = vocabularyRefs.next();
                    Function caller = currentProgram.getFunctionManager()
                        .getFunctionContaining(ref.getFromAddress());
                    if (caller == null) continue;
                    vocabularyReferenceCount++;
                    out.printf("| %s | %s at %s | %s | %s |%n",
                        globalAddress, caller.getName(), caller.getEntryPoint(),
                        ref.getFromAddress(), ref.getReferenceType());
                }
                byte[] raw = new byte[0x40];
                try {
                    currentProgram.getMemory().getBytes(globalAddress, raw);
                    StringBuilder hexBytes = new StringBuilder();
                    for (byte b : raw) hexBytes.append(String.format("%02x", b & 0xff));
                    out.printf("- Initial bytes at %s: %s%n", globalAddress, hexBytes);
                } catch (Exception e) {
                    out.printf("- Initial bytes at %s: unavailable (%s)%n",
                        globalAddress, e.getMessage());
                }
            }
            if (vocabularyReferenceCount == 0) {
                out.println("| - | no code references recovered | - | - |");
            }
            out.println();

            out.println("## Direct callers of S5API runtime-context allocator");
            out.println();
            Address allocatorTarget = toAddr("180012b40");
            ReferenceIterator allocatorRefs =
                currentProgram.getReferenceManager().getReferencesTo(allocatorTarget);
            int allocatorCallerCount = 0;
            while (allocatorRefs.hasNext()) {
                Reference ref = allocatorRefs.next();
                Function caller = currentProgram.getFunctionManager()
                    .getFunctionContaining(ref.getFromAddress());
                if (caller == null) continue;
                allocatorCallerCount++;
                out.printf("- %s at %s, call site %s%n",
                    caller.getName(), caller.getEntryPoint(), ref.getFromAddress());
            }
            if (allocatorCallerCount == 0) {
                out.println("- No direct references recovered at the exact entry address.");
            }
            out.println();

            // The phone-label classifier (FUN_180074a50) searches a global
            // string/index tree and two alias-table ranges. Trace references
            // to the tree root and every alias-table pointer slot so the
            // runtime initializer can be recovered rather than guessing IDs
            // from .rdata string order.
            out.println("## Global phoneme-map and alias-table cross-references");
            out.println();
            out.println("| Global address | Referencing function | Reference site | Reference type |");
            out.println("|---:|---|---:|---|");
            String[][] globalRanges = {
                {"1801c3080", "1801c3238"},
                {"1801c3240", "1801c3328"},
                {"1801c4140", "1801c4148"},
                {"1801c4160", "1801c4170"}
            };
            int globalReferenceCount = 0;
            for (String[] range : globalRanges) {
                Address begin = toAddr(range[0]);
                long endOffset = Long.parseLong(range[1], 16) -
                    Long.parseLong(range[0], 16);
                for (long offset = 0; offset < endOffset; offset += 8) {
                    monitor.checkCancelled();
                    Address globalAddress = begin.add(offset);
                    ReferenceIterator globalRefs =
                        currentProgram.getReferenceManager().getReferencesTo(globalAddress);
                    while (globalRefs.hasNext()) {
                        Reference ref = globalRefs.next();
                        Function caller = currentProgram.getFunctionManager()
                            .getFunctionContaining(ref.getFromAddress());
                        if (caller == null) continue;
                        globalReferenceCount++;
                        out.printf("| %s | %s at %s | %s | %s |%n",
                            globalAddress, caller.getName(), caller.getEntryPoint(),
                            ref.getFromAddress(), ref.getReferenceType());
                        if (!selected.containsKey(caller.getEntryPoint()) &&
                            selected.size() < MAX_FUNCTIONS) {
                            selected.put(caller.getEntryPoint(), caller);
                            queue.addLast(new Work(caller, 0));
                        }
                    }
                }
            }
            if (globalReferenceCount == 0) {
                out.println("| - | no code references recovered | - | - |");
            }
            out.println();

            while (!queue.isEmpty() && selected.size() < MAX_FUNCTIONS) {
                monitor.checkCancelled();
                Work work = queue.removeFirst();
                if (work.depth >= MAX_DEPTH) continue;
                InstructionIterator it = currentProgram.getListing().getInstructions(work.function.getBody(), true);
                while (it.hasNext() && selected.size() < MAX_FUNCTIONS) {
                    Instruction ins = it.next();
                    for (Reference ref : ins.getReferencesFrom()) {
                        if (!ref.getReferenceType().isCall()) continue;
                        Function callee = currentProgram.getFunctionManager().getFunctionAt(ref.getToAddress());
                        if (callee == null) callee = currentProgram.getFunctionManager().getFunctionContaining(ref.getToAddress());
                        if (callee != null && !selected.containsKey(callee.getEntryPoint())) {
                            selected.put(callee.getEntryPoint(), callee);
                            queue.addLast(new Work(callee, work.depth + 1));
                        }
                    }
                }
            }

            out.println("## Decompilation");
            out.println();
            out.println("- Selected functions: " + selected.size());
            out.println("- Maximum call depth: " + MAX_DEPTH);
            out.println();
            DecompInterface decompiler = new DecompInterface();
            decompiler.setOptions(new DecompileOptions());
            decompiler.openProgram(currentProgram);
            try {
                int index = 0;
                for (Map.Entry<Address, Function> entry : selected.entrySet()) {
                    monitor.checkCancelled();
                    Function f = entry.getValue();
                    index++;
                    out.println("### " + index + ". " + f.getName() + " at " + f.getEntryPoint());
                    out.println();
                    out.println("- Prototype: " + f.getReturnType().getDisplayName() + " " + f.getName() + "(" + parameters(f) + ")");
                    out.println("- Body bytes: " + f.getBody().getNumAddresses());
                    DecompileResults result = decompiler.decompileFunction(f, 25, monitor);
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
        println("S5API renderer deep report written: " + output.getAbsolutePath());
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
