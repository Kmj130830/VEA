// Ghidra headless post-script: focused static decompilation of VOCALOID G2P DLLs.
// This script never loads the target DLL into the Android runtime or executes its code.
import ghidra.app.decompiler.DecompInterface;
import ghidra.app.decompiler.DecompileOptions;
import ghidra.app.decompiler.DecompileResults;
import ghidra.app.script.GhidraScript;
import ghidra.program.model.address.Address;
import ghidra.program.model.listing.Function;
import ghidra.program.model.listing.FunctionIterator;
import ghidra.program.model.listing.Instruction;
import ghidra.program.model.listing.InstructionIterator;
import ghidra.program.model.symbol.Reference;
import ghidra.program.model.symbol.Symbol;
import ghidra.program.model.symbol.SymbolIterator;
import ghidra.program.model.symbol.SymbolType;
import ghidra.util.exception.CancelledException;

import java.io.File;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;

public class ExportG2pAnalysis extends GhidraScript {
    private static final int MAX_SELECTED_FUNCTIONS = 100;
    private static final int MAX_CALL_DEPTH = 4;
    private static final int MAX_INDEXED_FUNCTIONS = 5000;
    private static final int MAX_DISASSEMBLY_LINES = 180;

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
        String[] scriptArgs = getScriptArgs();
        if (scriptArgs.length < 1) {
            printerr("Usage: ExportG2pAnalysis.java <output-markdown-path>");
            return;
        }

        File output = new File(scriptArgs[0]);
        File parent = output.getParentFile();
        if (parent != null) parent.mkdirs();

        try (PrintWriter out = new PrintWriter(
                Files.newBufferedWriter(output.toPath(), StandardCharsets.UTF_8))) {
            writeHeader(out);
            writeMemoryMap(out);
            List<Symbol> interestingSymbols = collectInterestingSymbols();
            writeSymbols(out, interestingSymbols);
            writeFunctionIndex(out);

            LinkedHashMap<Address, Function> selected = selectCallClosure(interestingSymbols);
            writeDecompilation(out, selected);
        }

        println("G2P analysis report written: " + output.getAbsolutePath());
    }

    private void writeHeader(PrintWriter out) {
        out.println("# G2P DLL static decompilation report");
        out.println();
        out.println("> Static analysis only. The target binary was parsed by Ghidra; it was not executed.");
        out.println();
        out.println("- Program: `" + currentProgram.getName() + "`");
        out.println("- Language: `" + currentProgram.getLanguage().getLanguageID().getIdAsString() + "`");
        out.println("- Compiler spec: `" + currentProgram.getCompilerSpec().getCompilerSpecID().getIdAsString() + "`");
        out.println("- Image base: `" + currentProgram.getImageBase() + "`");
        out.println("- Default pointer size: " + currentProgram.getDefaultPointerSize() + " bytes");
        out.println();
        out.println("## Interpretation cautions");
        out.println();
        out.println("Ghidra's decompiler output is an analysis hypothesis, not a vendor header or ABI guarantee. "
                + "Inferred parameter types, C++ object layouts, ownership, calling conventions, and error semantics "
                + "must be checked against call sites and an authorized reference implementation before a JNI bridge is written.");
        out.println();
    }

    private void writeMemoryMap(PrintWriter out) {
        out.println("## PE memory blocks");
        out.println();
        out.println("| Block | Start | End | Bytes | R | W | X |");
        out.println("|---|---:|---:|---:|:---:|:---:|:---:|");
        for (ghidra.program.model.mem.MemoryBlock block : currentProgram.getMemory().getBlocks()) {
            out.printf("| %s | `%s` | `%s` | %d | %s | %s | %s |%n",
                    block.getName(), block.getStart(), block.getEnd(), block.getSize(),
                    block.isRead() ? "Y" : "", block.isWrite() ? "Y" : "", block.isExecute() ? "Y" : "");
        }
        out.println();
    }

    private List<Symbol> collectInterestingSymbols() {
        List<Symbol> result = new ArrayList<>();
        SymbolIterator symbols = currentProgram.getSymbolTable().getAllSymbols(true);
        while (symbols.hasNext()) {
            Symbol symbol = symbols.next();
            String name = symbol.getName();
            if (name != null && name.matches("(?i).*(g2pa|vis_g2pamanager).*")) {
                result.add(symbol);
            }
        }
        return result;
    }

    private void writeSymbols(PrintWriter out, List<Symbol> symbols) {
        out.println("## G2P-related symbols");
        out.println();
        if (symbols.isEmpty()) {
            out.println("No symbol whose name matches G2PA / VIS_G2PAManager was recovered.");
            out.println();
            return;
        }
        out.println("| Name | Address | Symbol type | Source | Function |");
        out.println("|---|---:|---|---|---|");
        for (Symbol symbol : symbols) {
            Function function = currentProgram.getFunctionManager().getFunctionAt(symbol.getAddress());
            String fn = function == null ? "" : function.getName();
            out.printf("| `%s` | `%s` | %s | %s | `%s` |%n",
                    escapeCell(symbol.getName()), symbol.getAddress(),
                    symbol.getSymbolType(), symbol.getSource(), escapeCell(fn));
        }
        out.println();
    }

    private void writeFunctionIndex(PrintWriter out) {
        out.println("## Function index");
        out.println();
        out.println("| Entry | Name | Bytes | Calling convention | Return type | Parameters |");
        out.println("|---:|---|---:|---|---|---|");
        FunctionIterator functions = currentProgram.getFunctionManager().getFunctions(true);
        int count = 0;
        while (functions.hasNext() && count < MAX_INDEXED_FUNCTIONS) {
            Function function = functions.next();
            out.printf("| `%s` | `%s` | %d | %s | `%s` | `%s` |%n",
                    function.getEntryPoint(), escapeCell(function.getName()),
                    function.getBody().getNumAddresses(),
                    escapeCell(function.getCallingConventionName()),
                    escapeCell(function.getReturnType().getDisplayName()),
                    escapeCell(parameterSummary(function)));
            count++;
        }
        out.println();
        if (functions.hasNext()) {
            out.println("Function index truncated at " + MAX_INDEXED_FUNCTIONS + " entries.");
            out.println();
        }
        out.println("Indexed function count: " + count);
        out.println();
    }

    private String parameterSummary(Function function) {
        StringBuilder text = new StringBuilder();
        ghidra.program.model.listing.Parameter[] parameters = function.getParameters();
        for (int i = 0; i < parameters.length; i++) {
            if (i > 0) text.append(", ");
            text.append(parameters[i].getDataType().getDisplayName())
                    .append(" ")
                    .append(parameters[i].getName());
        }
        if (function.hasVarArgs()) {
            if (text.length() > 0) text.append(", ");
            text.append("...");
        }
        return text.toString();
    }

    private LinkedHashMap<Address, Function> selectCallClosure(List<Symbol> symbols) throws CancelledException {
        LinkedHashMap<Address, Function> selected = new LinkedHashMap<>();
        ArrayDeque<WorkItem> queue = new ArrayDeque<>();
        FunctionIterator allFunctions = currentProgram.getFunctionManager().getFunctions(true);

        for (Symbol symbol : symbols) {
            if (symbol.getSymbolType() != SymbolType.FUNCTION) continue;
            Function function = currentProgram.getFunctionManager().getFunctionAt(symbol.getAddress());
            if (function == null) {
                function = currentProgram.getFunctionManager().getFunctionContaining(symbol.getAddress());
            }
            if (function != null && !selected.containsKey(function.getEntryPoint())) {
                selected.put(function.getEntryPoint(), function);
                queue.add(new WorkItem(function, 0));
            }
        }

        // If symbols are stripped or not recognized as functions, inspect a bounded prefix
        // so the report still contains useful machine-code/decompiler evidence.
        if (queue.isEmpty()) {
            int fallback = 0;
            while (allFunctions.hasNext() && fallback < 12) {
                Function function = allFunctions.next();
                selected.put(function.getEntryPoint(), function);
                queue.add(new WorkItem(function, 0));
                fallback++;
            }
        }

        while (!queue.isEmpty() && selected.size() < MAX_SELECTED_FUNCTIONS) {
            monitor.checkCancelled();
            WorkItem item = queue.removeFirst();
            if (item.depth >= MAX_CALL_DEPTH) continue;

            InstructionIterator instructions =
                    currentProgram.getListing().getInstructions(item.function.getBody(), true);
            while (instructions.hasNext() && selected.size() < MAX_SELECTED_FUNCTIONS) {
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

    private void writeDecompilation(PrintWriter out, LinkedHashMap<Address, Function> selected)
            throws CancelledException {
        out.println("## Entry-point call-closure decompilation");
        out.println();
        out.println("Selected " + selected.size() + " function(s). Seed symbols are G2PA-related exports; "
                + "called functions are followed up to depth " + MAX_CALL_DEPTH + ".");
        out.println();

        DecompInterface decompiler = new DecompInterface();
        decompiler.setOptions(new DecompileOptions());
        decompiler.openProgram(currentProgram);

        try {
            int index = 0;
            for (Function function : selected.values()) {
                monitor.checkCancelled();
                index++;
                out.println("### " + index + ". `" + function.getName() + "` at `" + function.getEntryPoint() + "`");
                out.println();
                out.println("- Bytes: " + function.getBody().getNumAddresses());
                out.println("- Calling convention: `" + function.getCallingConventionName() + "`");
                out.println("- Prototype: `" + function.getReturnType().getDisplayName() + " "
                        + function.getName() + "(" + parameterSummary(function) + ")`");
                DecompileResults result = decompiler.decompileFunction(function, 30, monitor);
                if (result != null && result.decompileCompleted() && result.getDecompiledFunction() != null) {
                    out.println();
                    out.println("```c");
                    out.println(result.getDecompiledFunction().getC());
                    out.println("```");
                } else {
                    out.println();
                    out.println("Decompilation unavailable: " +
                            (result == null ? "no result" : escapeCell(result.getErrorMessage())));
                    writeDisassembly(out, function);
                }
                out.println();
            }
        } finally {
            decompiler.dispose();
        }
    }

    private void writeDisassembly(PrintWriter out, Function function) {
        out.println("```asm");
        InstructionIterator instructions =
                currentProgram.getListing().getInstructions(function.getBody(), true);
        int count = 0;
        while (instructions.hasNext() && count < MAX_DISASSEMBLY_LINES) {
            Instruction instruction = instructions.next();
            out.println(instruction.getAddress() + ": " + instruction);
            count++;
        }
        if (instructions.hasNext()) out.println("; truncated");
        out.println("```");
    }

    private String escapeCell(String value) {
        if (value == null) return "";
        return value.replace("|", "\\|").replace("\r", " ").replace("\n", " ");
    }
}
