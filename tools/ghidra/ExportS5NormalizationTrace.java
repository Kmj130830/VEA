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

public class ExportS5NormalizationTrace extends GhidraScript {
    private static final String[] TARGETS = {
        "1800c5d50", "1800c67e0", "1800c3740", "18012cb50",
        "1800df720", "1800d8720", "1800d84e0",
        "180130b70", "180130ce0", "180130d90"
    };

    @Override
    public void run() throws Exception {
        String[] args = getScriptArgs();
        if (args.length < 1) throw new IllegalArgumentException("Usage: ExportS5NormalizationTrace.java <output.md>");
        File output = new File(args[0]);
        if (output.getParentFile() != null) output.getParentFile().mkdirs();
        try (PrintWriter out = new PrintWriter(Files.newBufferedWriter(output.toPath(), StandardCharsets.UTF_8))) {
            out.println("# S5API convolution, normalization and residual path");
            out.println();
            out.println("> Static Ghidra decompilation and instruction inspection only; no supplied DLL is executed.");
            out.println();
            out.println("- Program: " + currentProgram.getName());
            out.println("- Architecture: " + currentProgram.getLanguage().getLanguageID().getIdAsString());
            out.println("- Image base: " + currentProgram.getImageBase());
            out.println();
            out.println("## Function boundaries and direct callers");
            out.println();
            for (String hex : TARGETS) {
                monitor.checkCancelled();
                Address a = toAddr(hex);
                Function f = currentProgram.getFunctionManager().getFunctionAt(a);
                if (f == null) f = currentProgram.getFunctionManager().getFunctionContaining(a);
                out.println("### 0x" + hex + (f == null ? " (function not recovered)" : " " + f.getName()));
                if (f != null) {
                    long rva = f.getEntryPoint().subtract(currentProgram.getImageBase());
                    out.println("- RVA: 0x" + Long.toHexString(rva));
                    out.println("- Body bytes: " + f.getBody().getNumAddresses());
                    out.println("- Signature: " + prototype(f));
                }
                ReferenceIterator refs = currentProgram.getReferenceManager().getReferencesTo(a);
                int n = 0;
                while (refs.hasNext() && n < 40) {
                    Reference ref = refs.next();
                    Function caller = currentProgram.getFunctionManager().getFunctionContaining(ref.getFromAddress());
                    out.println("- Caller: " + (caller == null ? "<unknown>" : caller.getName()) +
                        " at " + ref.getFromAddress() + " (" + ref.getReferenceType() + ")");
                    n++;
                }
                if (n == 0) out.println("- No direct references recovered.");
                out.println();
            }

            DecompInterface decompiler = new DecompInterface();
            decompiler.setOptions(new DecompileOptions());
            decompiler.openProgram(currentProgram);
            try {
                for (String hex : TARGETS) {
                    monitor.checkCancelled();
                    Address a = toAddr(hex);
                    Function f = currentProgram.getFunctionManager().getFunctionAt(a);
                    if (f == null) f = currentProgram.getFunctionManager().getFunctionContaining(a);
                    if (f == null) continue;
                    out.println("## Decompilation " + f.getName() + " at " + f.getEntryPoint());
                    out.println();
                    out.println("- Signature: " + prototype(f));
                    DecompileResults result = decompiler.decompileFunction(f, 75, monitor);
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

            out.println("## Scalar affine kernel listing");
            out.println();
            dump(out, toAddr("18012cb50"), toAddr("18012cbd0"));
            out.println();
            out.println("## Layer-normalization caller listing");
            out.println();
            dump(out, toAddr("1800c5d50"), toAddr("1800c61d0"));
        }
        println("S5 normalization trace written: " + output.getAbsolutePath());
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

    private void dump(PrintWriter out, Address start, Address end) {
        InstructionIterator it = currentProgram.getListing().getInstructions(new AddressSet(start, end), true);
        while (it.hasNext()) {
            Instruction ins = it.next();
            StringBuilder b = new StringBuilder(ins.getAddress().toString()).append("  ").append(ins.getMnemonicString());
            for (int i = 0; i < ins.getNumOperands(); i++) b.append(i == 0 ? "  " : ", ").append(ins.getDefaultOperandRepresentation(i));
            out.println("- `" + b.toString().replace("`", "'") + "`");
        }
    }
}