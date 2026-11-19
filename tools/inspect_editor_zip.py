#!/usr/bin/env python3
"""Inventory and extract PE modules from the newly supplied Editor.zip.

The archive is inspected as data only; no Windows executable is launched.
"""
from __future__ import annotations
import hashlib, json, zipfile
from pathlib import Path

ARCHIVE=Path("Editor.zip")
OUT=Path("build/editor-package")
TARGET=OUT/"modules"
TARGET.mkdir(parents=True,exist_ok=True)

def inspect_pe(data:bytes):
    if len(data)<0x40 or data[:2]!=b"MZ": return None
    pe=int.from_bytes(data[0x3c:0x40],"little")
    if pe+24>len(data) or data[pe:pe+4]!=b"PE\0\0": return None
    machine=int.from_bytes(data[pe+4:pe+6],"little")
    nsec=int.from_bytes(data[pe+6:pe+8],"little")
    opt=pe+24
    magic=int.from_bytes(data[opt:opt+2],"little")
    dd=opt+(112 if magic==0x20b else 96 if magic==0x10b else 0)
    nd_off=opt+(108 if magic==0x20b else 92 if magic==0x10b else 0)
    com_rva=com_size=0
    if dd and nd_off+4<=len(data) and int.from_bytes(data[nd_off:nd_off+4],"little")>14:
        com_rva=int.from_bytes(data[dd+14*8:dd+14*8+4],"little")
        com_size=int.from_bytes(data[dd+14*8+4:dd+14*8+8],"little")
    return {"machine":hex(machine),"arch":{"0x8664":"x64","0xaa64":"ARM64","0x14c":"x86"}.get(hex(machine),"other"),
            "pe_kind":"PE32+" if magic==0x20b else "PE32" if magic==0x10b else hex(magic),
            "sections":nsec,"clr_directory_rva":hex(com_rva),"clr_directory_size":com_size,
            "managed_candidate":bool(com_rva and com_size),"size":len(data),
            "sha256":hashlib.sha256(data).hexdigest()}

def main():
    if not ARCHIVE.is_file(): raise SystemExit("Editor.zip not found in repository root")
    records=[]
    with zipfile.ZipFile(ARCHIVE) as z:
        bad=z.testzip()
        if bad: raise SystemExit("ZIP CRC failure: "+bad)
        for info in z.infolist():
            if info.is_dir(): continue
            data=z.read(info.filename)
            ext=Path(info.filename).suffix.lower()
            pe=inspect_pe(data)
            rec={"path":info.filename,"size":len(data),"compressed_size":info.compress_size,
                 "crc32":f"{info.CRC:08x}","pe":pe}
            records.append(rec)
            if pe and ext in {".dll",".exe",".ocx"}:
                safe=Path(info.filename)
                dest=TARGET/safe
                dest.parent.mkdir(parents=True,exist_ok=True)
                dest.write_bytes(data)
    (OUT/"inventory.json").write_text(json.dumps(records,ensure_ascii=False,indent=2),encoding="utf-8")
    lines=["# Editor.zip package inventory","",f"- Entries: {len(records)}",
           f"- PE modules: {sum(1 for r in records if r['pe'])}",
           f"- Managed candidates: {sum(1 for r in records if r['pe'] and r['pe']['managed_candidate'])}","",
           "| Path | Bytes | PE | Arch | CLR directory | SHA-256 |","|---|---:|:---:|---|---|---|"]
    for r in records:
        p=r["pe"]
        lines.append(f"| `{r['path']}` | {r['size']} | {'yes' if p else 'no'} | {p['arch'] if p else '-'} | {p['clr_directory_rva'] if p else '-'} | {p['sha256'] if p else '-'} |")
    (OUT/"INVENTORY.md").write_text("\n".join(lines)+"\n",encoding="utf-8")
    print((OUT/"INVENTORY.md").read_text(encoding="utf-8"))
if __name__=="__main__": main()
