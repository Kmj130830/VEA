// Find string constants associated with the proprietary neural model format and decompile their callers.
import ghidra.app.decompiler.DecompInterface;
import ghidra.app.decompiler.DecompileOptions;
import ghidra.app.decompiler.DecompileResults;
import ghidra.app.script.GhidraScript;
import ghidra.program.model.address.Address;
import ghidra.program.model.listing.Data;
import ghidra.program.model.listing.DataIterator;
import ghidra.program.model.listing.Function;
import ghidra.program.model.symbol.Reference;
import ghidra.program.model.symbol.ReferenceIterator;
import java.io.File;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

public class ExportS5ModelOperatorXrefs extends GhidraScript {
    private static final Pattern TOKEN = Pattern.compile(
        "(?i)(^|[^a-z0-9_])(S5_SIMD_TIER|conv[123]|cond[123]|linear|\\.weight(_ih_l|_hh_l)?|\\.bias(_ih_l|_hh_l)?|rnn|LongEnd|ShortEnd|Short|_reverse|pitch_range|S5_ENCP_AS_DECODER|MRW_DSU_SIMD|MRW_CARRIER_FUSE|MRW_ACT_SIMD|MRW_DWCONV_SIMD|MRW_LNSTATS_SIMD|MRW_MODROT_SIMD|MRW_GOLDEN_DUMP|prior1|prior2|excitation|reset_real|reset_imag|theta_ctr|vuv_gate|noise_prerot_real|noise_prerot_imag|Xcarrier_real|Xcarrier_imag|free_real|free_imag|mask_g_mod|Xmod[0-9]*_real|Xmod[0-9]*_imag|X_real|X_imag|ana_cosP|ana_sinP|waveform|delta|Xh_real|Xh_imag|A_n|dphi|Pn_r|Pn_i|Xn_real|Xn_imag)([^a-z0-9_]|$)"
    );
    private static final int MAX_FUNCTIONS = 180;

    @Override
    public void run() throws Exception {
        String[] args = getScriptArgs();
        if (args.length < 1) throw new IllegalArgumentException("Usage: ExportS5ModelOperatorXrefs.java <output.md>");
        File output = new File(args[0]);
        if (output.getParentFile() != null) output.getParentFile().mkdirs();

        LinkedHashMap<Address, Function> functions = new LinkedHashMap<>();
        try (PrintWriter out = new PrintWriter(Files.newBufferedWriter(output.toPath(), StandardCharsets.UTF_8))) {
            out.println("# S5API model/operator string cross references");
            out.println();
            out.println("> Static analysis only. String references are evidence of names used by the binary, not proof of file format semantics.");
            out.println();
            out.println("- Program: " + currentProgram.getName());
            out.println("- Architecture: " + currentProgram.getLanguage().getLanguageID().getIdAsString());
            out.println();
            out.println("## Matched strings and reference functions");
            out.println();
            out.println("| Address | String | Referencing function | Call/reference site |");
            out.println("|---:|---|---|---:|");

            DataIterator data = currentProgram.getListing().getDefinedData(true);
            int matches = 0;
            while (data.hasNext()) {
                monitor.checkCancelled();
                Data item = data.next();
                String value = item.getDefaultValueRepresentation();
                if (value == null || !TOKEN.matcher(value).find()) continue;
                matches++;
                String clean = value.replace("\\n", " ").replace("\\r", " ");
                if (clean.length() > 200) clean = clean.substring(0, 197) + "...";
                ReferenceIterator refs = currentProgram.getReferenceManager().getReferencesTo(item.getAddress());
                boolean hadRef = false;
                while (refs.hasNext()) {
                    Reference ref = refs.next();
                    Function caller = currentProgram.getFunctionManager().getFunctionContaining(ref.getFromAddress());
                    if (caller == null) continue;
                    hadRef = true;
                    if (functions.size() < MAX_FUNCTIONS) functions.put(caller.getEntryPoint(), caller);
                    out.printf("| %s | %s | %s | %s |%n",
                        item.getAddress(), clean.replace("|", "\\|"), caller.getName(), ref.getFromAddress());
                }
                if (!hadRef) {
                    out.printf("| %s | %s | (no direct code reference recovered) | - |%n",
                        item.getAddress(), clean.replace("|", "\\|"));
                }
            }
            out.println();
            out.println("- Matched data strings: " + matches);
            out.println("- Unique referencing functions collected: " + functions.size());
            out.println();

            out.println("## Caller decompilation");
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
                    out.println("- Body bytes: " + f.getBody().getNumAddresses());
                    DecompileResults result = decompiler.decompileFunction(f, 20, monitor);
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
        println("Model/operator xref report written: " + output.getAbsolutePath());
    }
}
