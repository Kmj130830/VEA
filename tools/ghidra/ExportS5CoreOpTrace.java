// Focused trace of the VOCALOID7 depthwise-convolution and layer-statistics call paths.
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

public class ExportS5CoreOpTrace extends GhidraScript {
    private static final String[] FUNCTIONS = {
        "1800c67e0", "1800c5d50", "18012e0d0", "18012f280",
        "18012e220", "180130110", "180130b70", "180130d90",
        "18012d350", "18012d460", "18012dc60", "18003be50"
    };

    @Override
    public void run() throws Exception {
        String[] args = getScriptArgs();
        if (args.length < 1) {
            throw new IllegalArgumentException("Usage: ExportS5CoreOpTrace.java <output.md>");
        }
        File output = new File(args[0]);
        if (output.getParentFile() != null) output.getParentFile().mkdirs();

        // LAB_180130ce0 is selected as a function pointer by FUN_180130b70,
        // but Ghidra had not assigned a function boundary to this indirect-call target.
        Address statsScalar = toAddr("180130ce0");
        Function scalarFunction = currentProgram.getFunctionManager().getFunctionAt(statsScalar);
        if (scalarFunction == null) {
            disassemble(statsScalar);
            scalarFunction = createFunction(statsScalar, "VEA_MRW_LNSTATS_SCALAR");
        }

        try (PrintWriter out = new PrintWriter(
                Files.newBufferedWriter(output.toPath(), StandardCharsets.UTF_8))) {
            out.println("# S5API core operator call-path trace");
            out.println();
            out.println("> Static decompilation and instruction inspection only; no supplied DLL is executed.");
            out.println();
            out.println("- Program: " + currentProgram.getName());
            out.println("- Architecture: " + currentProgram.getLanguage().getLanguageID().getIdAsString());
            out.println("- Image base: " + currentProgram.getImageBase());
            out.println();

            out.println("## Function boundaries");
            out.println();
            out.println("| RVA | Function | Body bytes | Prototype |");
            out.println("|---:|---|---:|---|");
            for (String hex : FUNCTIONS) {
                Address address = toAddr(hex);
                Function f = currentProgram.getFunctionManager().getFunctionAt(address);
                if (f == null) f = currentProgram.getFunctionManager().getFunctionContaining(address);
                if (f == null) {
                    out.println("| 0x" + hex + " | not recovered | - | - |");
                } else {
                    long rva = f.getEntryPoint().subtract(currentProgram.getImageBase());
                    out.println("| 0x" + Long.toHexString(rva) + " | " + f.getName() +
                        " | " + f.getBody().getNumAddresses() + " | " + prototype(f) + " |");
                }
            }
            if (scalarFunction != null) {
                long rva = scalarFunction.getEntryPoint().subtract(currentProgram.getImageBase());
                out.println("| 0x" + Long.toHexString(rva) + " | " + scalarFunction.getName() +
                    " | " + scalarFunction.getBody().getNumAddresses() + " | " +
                    prototype(scalarFunction) + " |");
            }
            out.println();

            out.println("## References to dispatchers and selected scalar kernels");
            out.println();
            String[] targets = { "18012e0d0", "18012f280", "180130b70", "180130d90", "180130ce0" };
            for (String hex : targets) {
                Address address = toAddr(hex);
                Function target = currentProgram.getFunctionManager().getFunctionAt(address);
                if (target == null) target = currentProgram.getFunctionManager().getFunctionContaining(address);
                out.println("### 0x" + hex + (target == null ? "" : " " + target.getName()));
                ReferenceIterator refs = currentProgram.getReferenceManager().getReferencesTo(address);
                int count = 0;
                while (refs.hasNext() && count < 40) {
                    Reference ref = refs.next();
                    Function caller = currentProgram.getFunctionManager().getFunctionContaining(ref.getFromAddress());
                    out.println("- " + (caller == null ? "<no containing function>" : caller.getName()) +
                        " at " + ref.getFromAddress() + " (" + ref.getReferenceType() + ")");
                    count++;
                }
                if (count == 0) out.println("- No static references recovered; target may be stored/selected indirectly.");
                out.println();
            }

            DecompInterface decompiler = new DecompInterface();
            decompiler.setOptions(new DecompileOptions());
            decompiler.openProgram(currentProgram);
            try {
                for (String hex : FUNCTIONS) {
                    monitor.checkCancelled();
                    Address address = toAddr(hex);
                    Function f = currentProgram.getFunctionManager().getFunctionAt(address);
                    if (f == null) f = currentProgram.getFunctionManager().getFunctionContaining(address);
                    if (f == null) continue;
                    out.println("## Decompilation: " + f.getName() + " at " + f.getEntryPoint());
                    out.println();
                    out.println("- Prototype: " + prototype(f));
                    out.println("- Body bytes: " + f.getBody().getNumAddresses());
                    DecompileResults result = decompiler.decompileFunction(f, 60, monitor);
                    if (result != null && result.decompileCompleted() && result.getDecompiledFunction() != null) {
                        out.println();
                        out.println("~~~c");
                        out.println(result.getDecompiledFunction().getC());
                        out.println("~~~");
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

            out.println("## Instruction listing: indirect DWCONV call site");
            out.println();
            dumpInstructions(out, toAddr("1800c68f0"), toAddr("1800c6960"));
            out.println();
            out.println("## Instruction listing: DWCONV scalar entry");
            out.println();
            dumpInstructions(out, toAddr("18012f280"), toAddr("18012f360"));
            out.println();
            out.println("## Instruction listing: LNSTATS scalar target and neighbouring code");
            out.println();
            dumpInstructions(out, toAddr("180130ce0"), toAddr("180130d90"));
            out.println();
            out.println("## Instruction listing: statistics dispatch indirect call site");
            out.println();
            dumpInstructions(out, toAddr("1800c5e70"), toAddr("1800c5f20"));
        }
        println("S5 core operator trace written: " + output.getAbsolutePath());
    }

    private String prototype(Function f) {
        StringBuilder b = new StringBuilder();
        b.append(f.getReturnType().getDisplayName()).append(" ").append(f.getName()).append("(");
        ghidra.program.model.listing.Parameter[] ps = f.getParameters();
        for (int i = 0; i < ps.length; i++) {
            if (i > 0) b.append(", ");
            b.append(ps[i].getDataType().getDisplayName()).append(" ").append(ps[i].getName());
        }
        if (f.hasVarArgs()) {
            if (ps.length > 0) b.append(", ");
            b.append("...");
        }
        return b.append(")").toString();
    }

    private void dumpInstructions(PrintWriter out, Address start, Address end) {
        InstructionIterator it =
            currentProgram.getListing().getInstructions(new AddressSet(start, end), true);
        while (it.hasNext()) {
            Instruction instruction = it.next();
            StringBuilder line = new StringBuilder();
            line.append(instruction.getAddress()).append("  ");
            line.append(instruction.getMnemonicString());
            int operands = instruction.getNumOperands();
            for (int i = 0; i < operands; i++) {
                line.append(i == 0 ? "  " : ", ");
                line.append(instruction.getDefaultOperandRepresentation(i));
            }
            out.println("- `" + line.toString().replace("`", "'") + "`");
        }
    }
}
