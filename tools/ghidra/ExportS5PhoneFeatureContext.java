// Focused static trace of S5API phone-feature descriptor creation and inputs.
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
import java.util.ArrayDeque;

public class ExportS5PhoneFeatureContext extends GhidraScript {
    private static final String[] SEEDS = {
        "18005a980", "1800591d0", "180050aa0", "180032750",
        "18006c720", "18003ab70", "18006fdb0", "180074a50",
        "18006c520", "180003a90", "180021c50",
        "18005b9a0", "18005c890", "18005cba0",
        "18004be40", "18004cb90", "18004d660"
    };
    private static final String[] TARGETS = {
        "180050aa0", "1800591d0", "18005a980", "180032750",
        "18006c720", "18006fdb0", "180074a50", "18006c520"
    };

    @Override
    public void run() throws Exception {
        String[] args = getScriptArgs();
        if (args.length < 1) throw new IllegalArgumentException(
            "Usage: ExportS5PhoneFeatureContext.java <output.md>");
        File output = new File(args[0]);
        if (output.getParentFile() != null) output.getParentFile().mkdirs();

        LinkedHashMap<Address, Function> functions = new LinkedHashMap<>();
        for (String hex : SEEDS) {
            monitor.checkCancelled();
            Function f = findFunctionAtAddress(hex);
            if (f != null) functions.put(f.getEntryPoint(), f);
        }

        try (PrintWriter out = new PrintWriter(Files.newBufferedWriter(
                output.toPath(), StandardCharsets.UTF_8))) {
            out.println("# S5API phone-feature descriptor and render-context trace");
            out.println();
            out.println("> Static Ghidra analysis only. S5API.dll is not executed.");
            out.println();
            out.println("- Program: " + currentProgram.getName());
            out.println("- Architecture: " + currentProgram.getLanguage().getLanguageID().getIdAsString());
            out.println("- Image base: " + currentProgram.getImageBase());
            out.println();

            out.println("## Seed functions");
            out.println();
            out.println("| Address | Function | Body bytes |");
            out.println("|---:|---|---:|");
            for (String hex : SEEDS) {
                Address address = toAddr(hex);
                Function f = findFunctionAtAddress(hex);
                if (f == null) out.printf("| %s | not recovered | - |%n", address);
                else out.printf("| %s | %s | %d |%n",
                    address, f.getName(), f.getBody().getNumAddresses());
            }
            out.println();

            out.println("## Callsite cross-references and preceding instructions");
            out.println();
            for (String hex : TARGETS) {
                monitor.checkCancelled();
                Address target = toAddr(hex);
                Function targetFunction = findFunctionAtAddress(hex);
                out.println("### Target " + target + " (" +
                    (targetFunction == null ? "not recovered" : targetFunction.getName()) + ")");
                out.println();
                ReferenceIterator refs = currentProgram.getReferenceManager().getReferencesTo(target);
                int count = 0;
                while (refs.hasNext()) {
                    Reference ref = refs.next();
                    if (!ref.getReferenceType().isCall()) continue;
                    Function caller = currentProgram.getFunctionManager()
                        .getFunctionContaining(ref.getFromAddress());
                    if (caller == null) continue;
                    count++;
                    out.println("#### Caller " + caller.getName() + " at " +
                        caller.getEntryPoint() + ", call " + ref.getFromAddress());
                    out.println();
                    out.println("~~~asm");
                    printPrecedingInstructions(out, caller, ref.getFromAddress(), 72);
                    out.println("~~~");
                    out.println();
                }
                if (count == 0) {
                    out.println("- No direct call reference recovered.");
                    out.println();
                }
            }

            out.println("## Decompilation");
            out.println();
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
                    out.println();
                    out.println("- Body bytes: " + f.getBody().getNumAddresses());
                    DecompileResults result = decompiler.decompileFunction(f, 30, monitor);
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
        println("S5API phone-feature context report written: " + output.getAbsolutePath());
    }

    private Function findFunctionAtAddress(String hex) {
        Address address = toAddr(hex);
        Function f = currentProgram.getFunctionManager().getFunctionAt(address);
        if (f == null) f = currentProgram.getFunctionManager().getFunctionContaining(address);
        return f;
    }

    private void printPrecedingInstructions(
        PrintWriter out, Function function, Address callAddress, int window) {
        InstructionIterator iterator =
            currentProgram.getListing().getInstructions(function.getBody(), true);
        ArrayDeque<Instruction> prior = new ArrayDeque<>();
        Instruction callInstruction = null;
        while (iterator.hasNext()) {
            Instruction instruction = iterator.next();
            if (instruction.getAddress().equals(callAddress)) {
                callInstruction = instruction;
                break;
            }
            prior.addLast(instruction);
            while (prior.size() > window) prior.removeFirst();
        }
        for (Instruction instruction : prior) {
            out.println(instruction.getAddress() + "  " + instruction);
        }
        if (callInstruction != null) {
            out.println(callInstruction.getAddress() + "  " + callInstruction);
        } else {
            out.println("Callsite instruction not present in caller body.");
        }
    }
}
