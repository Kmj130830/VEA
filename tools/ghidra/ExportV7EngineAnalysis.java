// Ghidra headless triage for VOCALOID7's native rendering boundary.
// Static analysis only: this script never executes the target DLL.
import ghidra.app.decompiler.DecompInterface;
import ghidra.app.decompiler.DecompileOptions;
import ghidra.app.decompiler.DecompileResults;
import ghidra.app.script.GhidraScript;
import ghidra.program.model.address.Address;
import ghidra.program.model.listing.Data;
import ghidra.program.model.listing.DataIterator;
import ghidra.program.model.listing.Function;
import ghidra.program.model.listing.FunctionIterator;
import ghidra.program.model.listing.Instruction;
import ghidra.program.model.listing.InstructionIterator;
import ghidra.program.model.symbol.Reference;
import ghidra.program.model.symbol.ReferenceIterator;
import ghidra.program.model.symbol.SourceType;
import ghidra.program.model.symbol.Symbol;
import ghidra.program.model.symbol.SymbolIterator;
import ghidra.util.exception.CancelledException;

import java.io.File;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

public class ExportV7EngineAnalysis extends GhidraScript {
    private static final int MAX_STRINGS = 3000;
    private static final int MAX_SEEDS = 48;
    private static final int MAX_FUNCTIONS = 100;
    private static final int MAX_CALL_DEPTH = 2;
    private static final Pattern INTERESTING =
            Pattern.compile("(?i)(vocaloid|synth|render|voice|model|phoneme|note|wave|vtmg|vpit|vhnv|vocoder|pitch|engine|decoder|inference|offline)");

    private static class WorkItem {
        final Function function;
        final int depth;
        WorkItem(Function function, int depth) {
            this.function = function;
            this.depth = depth;
        }
    }

    @Override
    public void run() throws Exception {
        String[] args = getScriptArgs();
        if (args.length < 1) {
            printerr("Usage: ExportV7EngineAnalysis.java <output-markdown-path>");
            return;
        }

        File output = new File(args[0]);
        File parent = output.getParentFile();
        if (parent != null) parent.mkdirs();

        try (PrintWriter out = new PrintWriter(
                Files.newBufferedWriter(output.toPath(), StandardCharsets.UTF_8))) {
            writeHeader(out);
            writeMemoryMap(out);
            writeImportedAndNamedSymbols(out);
            LinkedHashMap<Address, Function> seeds = collectSeeds(out);
            LinkedHashMap<Address, Function> selected = expandCallClosure(seeds);
            writeDecompilation(out, selected, seeds.size());
        }

        println("V7 engine report written: " + output.getAbsolutePath());
    }

    private void writeHeader(PrintWriter out) {
        out.println("# VOCALOID7 DLL static-analysis report");
        out.println();
        out.println("> Static analysis only. The target PE was imported into Ghidra and was not executed.");
        out.println();
        out.println("- Program: `" + currentProgram.getName() + "`");
        out.println("- Language: `" + currentProgram.getLanguage().getLanguageID().getIdAsString() + "`");
        out.println("- Compiler spec: `" + currentProgram.getCompilerSpec().getCompilerSpecID().getIdAsString() + "`");
        out.println("- Image base: `" + currentProgram.getImageBase() + "`");
        out.println("- Pointer size: " + currentProgram.getDefaultPointerSize() + " bytes");
        out.println();
        out.println("The decompiler's prototypes are hypotheses. The absence of named exports does not prove that the DLL lacks an internal render path.");
        out.println();
    }

    private void writeMemoryMap(PrintWriter out) {
        out.println("## PE memory blocks");
        out.println();
        out.println("| Block | Start | End | Bytes | R | W | X |");
        out.println("|---|---:|---:|---:|:---:|:---:|:---:|");
        for (ghidra.program.model.mem.MemoryBlock block : currentProgram.getMemory().getBlocks()) {
            out.printf("| %s | `%s` | `%s` | %d | %s | %s | %s |%n",
                    escape(block.getName()), block.getStart(), block.getEnd(), block.getSize(),
                    block.isRead() ? "Y" : "", block.isWrite() ? "Y" : "", block.isExecute() ? "Y" : "");
        }
        out.println();
    }

    private void writeImportedAndNamedSymbols(PrintWriter out) {
        out.println("## Imported and named symbols");
        out.println();
        out.println("| Source | Type | Name | Address |");
        out.println("|---|---|---|---:|");
        SymbolIterator symbols = currentProgram.getSymbolTable().getAllSymbols(true);
        int count = 0;
        while (symbols.hasNext() && count < 12000) {
            Symbol symbol = symbols.next();
            String name = symbol.getName();
            boolean imported = symbol.getSource() == SourceType.IMPORTED;
            boolean interestingName = name != null && INTERESTING.matcher(name).find();
            if (!imported && !interestingName) continue;
            out.printf("| %s | %s | `%s` | `%s` |%n",
                    imported ? "IMPORTED" : symbol.getSource().toString(),
                    symbol.getSymbolType(), escape(name), symbol.getAddress());
            count++;
        }
        out.println();
        out.println("Rows emitted: " + count);
        out.println();
    }

    private LinkedHashMap<Address, Function> collectSeeds(PrintWriter out) throws CancelledException {
        LinkedHashMap<Address, Function> seeds = new LinkedHashMap<>();
        Map<Address, String> reasons = new LinkedHashMap<>();

        // Entry/initialization names, if the PE loader or analyzer recovered them.
        SymbolIterator symbols = currentProgram.getSymbolTable().getAllSymbols(true);
        while (symbols.hasNext() && seeds.size() < MAX_SEEDS) {
            monitor.checkCancelled();
            Symbol symbol = symbols.next();
            String name = symbol.getName();
            if (name == null) continue;
            String lower = name.toLowerCase(Locale.ROOT);
            if (lower.contains("dllmain") || lower.contains("dllentry") ||
                    lower.equals("entry") || lower.contains("startup")) {
                addFunctionSeed(symbol.getAddress(), "entry/init symbol " + name, seeds, reasons);
            }
        }

        // Strings are often the only semantic clues left in stripped PE libraries.
        out.println("## Defined string data");
        out.println();
        out.println("| Address | Length | String |");
        out.println("|---:|---:|---|");
        DataIterator dataIterator = currentProgram.getListing().getDefinedData(true);
        int stringCount = 0;
        while (dataIterator.hasNext() && stringCount < MAX_STRINGS) {
            monitor.checkCancelled();
            Data data = dataIterator.next();
            Object value;
            try {
                value = data.getValue();
            } catch (Exception ex) {
                continue;
            }
            if (!(value instanceof String)) continue;
            String text = ((String) value).replace("\0", "");
            if (text.length() < 2) continue;
            if (!isMostlyPrintable(text)) continue;
            out.printf("| `%s` | %d | %s |%n",
                    data.getMinAddress(), text.length(), escape(text));
            stringCount++;

            if (INTERESTING.matcher(text).find() && seeds.size() < MAX_SEEDS) {
                ReferenceIterator references =
                        currentProgram.getReferenceManager().getReferencesTo(data.getMinAddress());
                while (references.hasNext() && seeds.size() < MAX_SEEDS) {
                    Reference reference = references.next();
                    addFunctionSeed(reference.getFromAddress(),
                            "xref to string at " + data.getMinAddress() + ": " + text,
                            seeds, reasons);
                }
            }
        }
        out.println();
        out.println("Printable defined strings emitted: " + stringCount);
        out.println();

        // Calls into imported Windows/runtime APIs frequently reveal initialization,
        // file/model I/O, thread scheduling, and audio-render boundaries.
        symbols = currentProgram.getSymbolTable().getAllSymbols(true);
        while (symbols.hasNext() && seeds.size() < MAX_SEEDS) {
            monitor.checkCancelled();
            Symbol symbol = symbols.next();
            if (symbol.getSource() != SourceType.IMPORTED) continue;
            String name = symbol.getName();
            if (name == null || !INTERESTING.matcher(name).find()) continue;
            ReferenceIterator references =
                    currentProgram.getReferenceManager().getReferencesTo(symbol.getAddress());
            while (references.hasNext() && seeds.size() < MAX_SEEDS) {
                Reference reference = references.next();
                addFunctionSeed(reference.getFromAddress(),
                        "import xref " + name, seeds, reasons);
            }
        }

        // Retain named high-level functions even when they have no interesting string xref.
        FunctionIterator functions = currentProgram.getFunctionManager().getFunctions(true);
        while (functions.hasNext() && seeds.size() < MAX_SEEDS) {
            Function function = functions.next();
            String name = function.getName();
            if (name != null && !name.matches("(?i)FUN_[0-9a-f]+") &&
                    INTERESTING.matcher(name).find()) {
                addFunctionSeed(function.getEntryPoint(), "semantic function name " + name, seeds, reasons);
            }
        }

        out.println("## Candidate seed functions");
        out.println();
        out.println("| Function | Address | Bytes | Reason |");
        out.println("|---|---:|---:|---|");
        for (Function function : seeds.values()) {
            out.printf("| `%s` | `%s` | %d | %s |%n",
                    escape(function.getName()), function.getEntryPoint(),
                    function.getBody().getNumAddresses(), escape(reasons.get(function.getEntryPoint())));
        }
        out.println();
        out.println("Candidate seed count: " + seeds.size());
        out.println();
        if (seeds.isEmpty()) {
            out.println("No semantic seeds were recovered. No decompiler output is claimed for this file.");
            out.println();
        }
        return seeds;
    }

    private void addFunctionSeed(
            Address address,
            String reason,
            LinkedHashMap<Address, Function> seeds,
            Map<Address, String> reasons) {
        Function function = currentProgram.getFunctionManager().getFunctionAt(address);
        if (function == null) {
            function = currentProgram.getFunctionManager().getFunctionContaining(address);
        }
        if (function == null || seeds.size() >= MAX_SEEDS) return;
        if (!seeds.containsKey(function.getEntryPoint())) {
            seeds.put(function.getEntryPoint(), function);
            reasons.put(function.getEntryPoint(), reason);
        }
    }

    private boolean isMostlyPrintable(String value) {
        int printable = 0;
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (!Character.isISOControl(ch) || ch == '\t' || ch == '\n') printable++;
        }
        return printable * 10 >= value.length() * 9;
    }

    private LinkedHashMap<Address, Function> expandCallClosure(
            LinkedHashMap<Address, Function> seeds) throws CancelledException {
        LinkedHashMap<Address, Function> selected = new LinkedHashMap<>(seeds);
        ArrayDeque<WorkItem> queue = new ArrayDeque<>();
        for (Function seed : seeds.values()) queue.addLast(new WorkItem(seed, 0));

        while (!queue.isEmpty() && selected.size() < MAX_FUNCTIONS) {
            monitor.checkCancelled();
            WorkItem item = queue.removeFirst();
            if (item.depth >= MAX_CALL_DEPTH) continue;

            InstructionIterator instructions =
                    currentProgram.getListing().getInstructions(item.function.getBody(), true);
            while (instructions.hasNext() && selected.size() < MAX_FUNCTIONS) {
                Instruction instruction = instructions.next();
                for (Reference reference : instruction.getReferencesFrom()) {
                    if (!reference.getReferenceType().isCall()) continue;
                    Function callee = currentProgram.getFunctionManager().getFunctionAt(reference.getToAddress());
                    if (callee == null) {
                        callee = currentProgram.getFunctionManager().getFunctionContaining(reference.getToAddress());
                    }
                    if (callee != null && !selected.containsKey(callee.getEntryPoint())) {
                        selected.put(callee.getEntryPoint(), callee);
                        queue.addLast(new WorkItem(callee, item.depth + 1));
                    }
                }
            }
        }
        return selected;
    }

    private void writeDecompilation(
            PrintWriter out,
            LinkedHashMap<Address, Function> selected,
            int seedCount) throws CancelledException {
        out.println("## Seed/call-closure decompilation");
        out.println();
        out.println("Seed count: " + seedCount + "; selected count: " + selected.size()
                + "; direct-call closure depth: " + MAX_CALL_DEPTH + ".");
        out.println();

        if (selected.isEmpty()) return;
        DecompInterface decompiler = new DecompInterface();
        decompiler.setOptions(new DecompileOptions());
        decompiler.openProgram(currentProgram);
        try {
            int index = 0;
            for (Function function : selected.values()) {
                monitor.checkCancelled();
                index++;
                out.println("### " + index + ". `" + escape(function.getName()) +
                        "` at `" + function.getEntryPoint() + "`");
                out.println();
                out.println("- Bytes: " + function.getBody().getNumAddresses());
                out.println("- Calling convention: `" + escape(function.getCallingConventionName()) + "`");
                out.println("- Prototype: `" + escape(function.getReturnType().getDisplayName()) +
                        " " + escape(function.getName()) + "(" + escape(parameterSummary(function)) + ")`");
                DecompileResults result = decompiler.decompileFunction(function, 25, monitor);
                if (result != null && result.decompileCompleted() && result.getDecompiledFunction() != null) {
                    out.println();
                    out.println("```c");
                    out.println(result.getDecompiledFunction().getC());
                    out.println("```");
                } else {
                    out.println();
                    out.println("Decompilation unavailable: " +
                            escape(result == null ? "no result" : result.getErrorMessage()));
                }
                out.println();
            }
        } finally {
            decompiler.dispose();
        }
    }

    private String parameterSummary(Function function) {
        StringBuilder text = new StringBuilder();
        ghidra.program.model.listing.Parameter[] parameters = function.getParameters();
        for (int i = 0; i < parameters.length; i++) {
            if (i > 0) text.append(", ");
            text.append(parameters[i].getDataType().getDisplayName())
                    .append(" ").append(parameters[i].getName());
        }
        return text.toString();
    }

    private String escape(String value) {
        if (value == null) return "";
        return value.replace("|", "\\|").replace("\r", " ").replace("\n", " ")
                .replace("`", "'");
    }
}
