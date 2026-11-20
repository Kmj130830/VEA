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
import java.util.LinkedHashMap;
import java.util.Map;

/** Static-only S5API typed-object, key lookup and tensor decode trace. */
public class ExportS5ModelObjectTree extends GhidraScript {
    private static final int WINDOW_BEFORE = 0x100;
    private static final int WINDOW_AFTER = 0x30;
    private static final int MAX_CALLERS = 80;
    private static final String[] SEEDS = {
        "180068190", // model layer configuration entry
        "18005ebe0", // recurrent parameter reader
        "180064670", // matrix tensor loader
        "180006c80", // typed numeric-array conversion
        "1800656e0", // tagged F32/F16 tensor string parser
        "18000fec0", // typed-object property lookup
        "18000b100", // object insertion/access path
        "180010260", // MD5 key hash
        "1800103e0", // key/string conversion helper
        "18001b1f0", // key serialization helper
        "180024a90", // recurrent parameter name builder
        "180026f60", // object/key lookup predicate
        "18000f5f0", // Base64/numeric text parser
        "180083610", // digest/string stream formatter
        "18001acc0", // digest/string stream initializer
        "180010890", // stream/object construction helper
        "1800279f0", // stream finalization helper
        "180081940", // typed-object tree lookup
        "18000fe00", // tensor/object iterator accessor
        "180082d20", // numeric output buffer setup
        "18000f6d0", // text-to-number decode loop
        "18002aa90", // model root-object setup
        "180056f40", // enclosing model constructor
        "180060270", // tensor/module consumer
        "18005df50", // adjacent matrix preparation path
        "180065510", // adjacent matrix preparation path
        "18013aa00", // backend dispatch thunk
        "18013a850", // backend object factory
        "1800c7ad0", // candidate recurrent hot path
        "1800c1a30", // candidate recurrent hot path
        "1800c3740", // backend/operator registry path
        "180081e20", // ostream numeric-base manipulator used by the digest formatter
        "1800812a0", // MD5 block/final digest output helper
        "18007b700", // streambuf-to-string copy used by digest formatting
        "180045130", // model-configured 16-bit scalar conversion callback provider
        "180054b50", // typed tensor destination allocation/setup
        "18001aa30", // assignment/copy helper for the per-model scalar converter
        "180088949", // model constructor callsite that installs the converter
        "180010740", // key concatenation helper
        "180010540", // key suffix/helper
        "180083c50", // recurrent layer-index to string formatter
        "18004e3b0", // model boolean flag used before RNN layer initialization
        "18004e1e0", // second model boolean flag used before RNN layer initialization
        "18004d170", // model subobject accessor for the first conditional path
        "18004d3b0", // model subobject accessor for the second conditional path
        "18004c460", // model subobject accessor for the third conditional path
        "18004c6a0", // model subobject accessor for the fourth conditional path
        "180065d50", // typed object child/value accessor
        "18001e360", // alternate typed tensor reader caller
        "18005e120", // recurrent matrix/tensor reader caller
        "180041590", // tensor input preparation
        "180047990", // typed tensor dimension/context setup
        "18004f2d0", // tensor context/member initializer
        "180056d60", // model decoder context setup
        "1800537e0", // writes a pointer at object offset +0x208
        "18005c340", // reads object offset +0x208
        "1800114b0", // writes object offset +0x208
        "180012030", // writes object offset +0x208
        "180012530", // writes object offset +0x208
        "180013c00", // writes object offset +0x208
        "180015d10", // reads and writes object offset +0x208
        "18017a760", // writes and reads object offset +0x208
        "18017b860", // reads object offset +0x208
        "1800bca40", // writes object offset +0x208
        "180029b80", // reads two object +0x208 fields
        "18002b080", // uses object +0x208 as a subobject base
        "180012240", // initializes the exact +0x288 model subobject passed to FUN_180056d60
        "180011900", // nested context/member initializer
        "180011680", // nested context/member initializer
        "180011a30", // nested context/member initializer
        "180013410", // writes parent field +0xef0
        "180013830", // writes parent field +0x878
        "1800181e0", // writes parent field +0x4c8
        "18009acc0", // writes parent field +0x4c8
        "180131fa0", // writes parent field +0x4c8
        "180019630", // writes parent field +0x878
        "180050aa0", // writes parent field +0xaa0
        "180018f00", // reads and updates parent field +0xef0
        "1800290f0", // reads parent field +0xef0
        "180031010", // reads parent field +0xef0
        "1800591d0", // takes address of parent field +0xef0
        "180016dc0", // writes field +0x240
        "180012030", // clears fields +0x208 and +0x240
        "1800114b0", // initializes a nested buffer object at +0x208
        "1800cdbb0", // copies the model processing contexts containing +0x208/+0x240
        "1800ccd23", // containing function creates a default context then copies from source
        "1800c4730", // source-context getter used by the copy caller
        "1800c0290", // field-copy helper used for the +0x208 member
        "1800c03a0", // adjacent context member-copy helper
        "18001a840", // shared field assignment helper
        "18004f2d0", // selects CPU-specific tensor operator function pointers
        "18003cb70", // scalar fallback installed in table slot +0x1e / context +0x208
        "1800a58b0", // AVX-512 converter installed in table slot +0x1e / context +0x208
        "1800a58e0", // adjacent AVX-512 table slot +0x1d
        "1800a7e60", // AVX-512 table slot +0x14
        "1800a8e40", // AVX-512 table slot +0x1b
        "1800a5370", // alternate CPU path table slot +0x14
        "180072b30", // baseline CPU path table slot +0x00
        "18000daa0", // underlying JSON/text parser called by FUN_18000fec0
        "18000b290", // parser error-context string append helper
        "18000f340", // parser error formatter
        "18001b020", // generic tree/node insertion helper
        "18007b520", // allocate ordered-tree node
        "18007abf0", // commit ordered-tree node insertion
        "18000e4e0", // JSON object parser
        "18000e070", // JSON array parser
        "18000eae0", // JSON string and escape parser
        "18000e3d0", // JSON number token parser
        "180083590", // typed double node writer
        "180083500", // typed boolean node writer
        "180083550"  // typed null node writer
    };

    @Override
    public void run() throws Exception {
        String[] args = getScriptArgs();
        if (args.length < 1) throw new IllegalArgumentException("Usage: ExportS5ModelObjectTree.java <output.md>");
        File output = new File(args[0]);
        if (output.getParentFile() != null) output.getParentFile().mkdirs();
        LinkedHashMap<Address, Function> selected = new LinkedHashMap<>();
        try (PrintWriter out = new PrintWriter(Files.newBufferedWriter(output.toPath(), StandardCharsets.UTF_8))) {
            out.println("# S5API typed model-object and tensor lookup trace");
            out.println();
            out.println("> Ghidra static analysis only. The supplied Windows DLL is not executed.");
            out.println("- Program: " + currentProgram.getName());
            out.println("- Architecture: " + currentProgram.getLanguage().getLanguageID().getIdAsString());
            out.println("- Image base: " + currentProgram.getImageBase());
            out.println();

            out.println("## Seed functions and callers");
            out.println();
            for (String hex : SEEDS) {
                monitor.checkCancelled();
                Address address = toAddr(hex);
                Function f = currentProgram.getFunctionManager().getFunctionAt(address);
                if (f == null) f = currentProgram.getFunctionManager().getFunctionContaining(address);
                out.println("### 0x" + hex + (f == null ? " (not recovered)" : " " + f.getName()));
                if (f == null) { out.println(); continue; }
                selected.put(f.getEntryPoint(), f);
                out.println("- Entry: " + f.getEntryPoint());
                out.println("- Prototype: " + prototype(f));
                out.println("- Body bytes: " + f.getBody().getNumAddresses());
                ReferenceIterator refs = currentProgram.getReferenceManager().getReferencesTo(f.getEntryPoint());
                int count = 0;
                while (refs.hasNext() && count < MAX_CALLERS) {
                    Reference ref = refs.next();
                    Function caller = currentProgram.getFunctionManager().getFunctionContaining(ref.getFromAddress());
                    out.println("- Caller: " + (caller == null ? "<unknown>" : caller.getName()) +
                        " at " + ref.getFromAddress() + " [" + ref.getReferenceType() + "]");
                    count++;
                }
                if (count == 0) out.println("- No direct callers recovered; indirect/data references may remain.");
                out.println();
            }

            out.println("## Decompiled seed functions");
            out.println();
            DecompInterface decompiler = new DecompInterface();
            decompiler.setOptions(new DecompileOptions());
            decompiler.openProgram(currentProgram);
            try {
                int index = 0;
                for (Map.Entry<Address, Function> item : selected.entrySet()) {
                    monitor.checkCancelled();
                    Function f = item.getValue();
                    index++;
                    out.println("### " + index + ". " + f.getName() + " at " + f.getEntryPoint());
                    out.println("- RVA: 0x" + Long.toHexString(f.getEntryPoint().subtract(currentProgram.getImageBase())));
                    out.println("- Prototype: " + prototype(f));
                    out.println("- Body bytes: " + f.getBody().getNumAddresses());
                    DecompileResults result = decompiler.decompileFunction(f, 60, monitor);
                    if (result != null && result.decompileCompleted() && result.getDecompiledFunction() != null) {
                        out.println();
                        out.println("~~~c");
                        out.println(result.getDecompiledFunction().getC());
                        out.println("~~~");
                    } else {
                        out.println("- Decompilation unavailable: " + (result == null ? "no result" : result.getErrorMessage()));
                    }
                    out.println();
                }
            } finally {
                decompiler.dispose();
            }

            out.println("## Raw marker constants");
            out.println();
            dumpBytes(out, "18019b770", 4);
            dumpBytes(out, "18019b778", 4);
            out.println();
            out.println("## Encoded text block decoder disassembly");
            out.println();
            dumpWindow(out, toAddr("18002aa90"), 0x10, 0x260);
            dumpWindow(out, toAddr("180068190"), 0x10, 0x180);
            out.println();
            out.println("## JSON parser entry disassembly");
            out.println();
            dumpWindow(out, toAddr("18000daa0"), 0x10, 0x220);
            out.println();
            out.println("## F16 callback target disassembly windows");
            out.println();
            dumpWindow(out, toAddr("18003cb70"), 0x20, 0xa0);
            dumpWindow(out, toAddr("1800a58b0"), 0x20, 0x50);
            dumpWindow(out, toAddr("1800a58e0"), 0x20, 0x50);
            out.println();
            out.println("## F16 callback candidate field accesses");
            out.println();
            for (String fieldOffset : new String[] {
                    "0x208", "0x240", "0x5f0",
                    "0x4c8", "0x878", "0xaa0", "0xcc8", "0xef0"
            }) {
                out.println("### Accesses to " + fieldOffset);
                out.println();
                dumpFieldOffsetAccesses(out, fieldOffset);
                out.println();
            }
            out.println("## Exact x64 call-site windows");
            out.println();
            for (Map.Entry<Address, Function> item : selected.entrySet()) {
                monitor.checkCancelled();
                Function target = item.getValue();
                out.println("### Callers of " + target.getName() + " at " + target.getEntryPoint());
                ReferenceIterator refs = currentProgram.getReferenceManager().getReferencesTo(target.getEntryPoint());
                int count = 0;
                while (refs.hasNext() && count < MAX_CALLERS) {
                    Reference ref = refs.next();
                    dumpWindow(out, ref.getFromAddress(), WINDOW_BEFORE, WINDOW_AFTER);
                    count++;
                }
                if (count == 0) out.println("- No direct call references.");
                out.println();
            }
        }
        println("S5API model-object tree report written: " + output.getAbsolutePath());
    }

    private void dumpBytes(PrintWriter out, String hexAddress, int length) {
        Address address = toAddr(hexAddress);
        byte[] bytes = new byte[length];
        out.println("- " + address + ":");
        try {
            currentProgram.getMemory().getBytes(address, bytes);
            StringBuilder hex = new StringBuilder();
            StringBuilder ascii = new StringBuilder();
            for (byte value : bytes) {
                int b = value & 0xff;
                if (hex.length() > 0) hex.append(' ');
                hex.append(String.format("%02x", b));
                ascii.append(b >= 0x20 && b <= 0x7e ? (char)b : '.');
            }
            out.println("  - bytes: " + hex);
            out.println("  - ASCII: " + ascii);
            ghidra.program.model.listing.Data data = currentProgram.getListing().getDataAt(address);
            if (data != null) {
                out.println("  - data type: " + data.getDataType().getDisplayName());
                out.println("  - defined value: " + data.getDefaultValueRepresentation());
            }
        } catch (Exception e) {
            out.println("  - read failed: " + e.getMessage());
        }
    }

    private String prototype(Function f) {
        StringBuilder b = new StringBuilder(f.getReturnType().getDisplayName()).append(" ").append(f.getName()).append("(");
        ghidra.program.model.listing.Parameter[] params = f.getParameters();
        for (int i = 0; i < params.length; i++) {
            if (i > 0) b.append(", ");
            b.append(params[i].getDataType().getDisplayName()).append(" ").append(params[i].getName());
        }
        if (f.hasVarArgs()) { if (params.length > 0) b.append(", "); b.append("..."); }
        return b.append(")").toString();
    }

    private void dumpFieldOffsetAccesses(PrintWriter out, String offset) {
        InstructionIterator it = currentProgram.getListing().getInstructions(true);
        int count = 0;
        while (it.hasNext()) {
            Instruction ins = it.next();
            String rendered = ins.toString().toLowerCase();
            if (!rendered.contains("+ " + offset + "]") &&
                !rendered.contains("+ " + offset + ",")) continue;
            if (rendered.contains("[rbp +") || rendered.contains("[rsp +")) continue;
            Function owner = currentProgram.getFunctionManager().getFunctionContaining(ins.getAddress());
            out.println("- " + (owner == null ? "<unknown>" : owner.getName()) +
                " @ " + ins.getAddress() + ": " + rendered);
            count++;
        }
        if (count == 0) out.println("- No non-stack references to +" + offset + " were recovered.");
        out.println("- Total matching instructions: " + count);
    }

    private void dumpWindow(PrintWriter out, Address center, int before, int after) {
        Address start;
        Address end;
        try { start = center.subtract(before); end = center.add(after); }
        catch (Exception e) { out.println("- Window unavailable: " + e.getMessage()); return; }
        InstructionIterator it = currentProgram.getListing().getInstructions(new AddressSet(start, end), true);
        out.println("#### Call-site near " + center);
        int count = 0;
        while (it.hasNext()) {
            Instruction ins = it.next();
            StringBuilder line = new StringBuilder("- ").append(ins.getAddress()).append("  ").append(ins.getMnemonicString());
            for (int i = 0; i < ins.getNumOperands(); i++) {
                line.append(i == 0 ? "  " : ", ").append(ins.getDefaultOperandRepresentation(i));
            }
            out.println(line.toString());
            count++;
        }
        if (count == 0) out.println("- No decoded instructions in this window.");
    }
}
