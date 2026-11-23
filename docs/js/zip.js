/* Bounded ZIP central-directory listing only. Bounded in-memory entry opening. No filesystem extraction. */
(function(root) {
"use strict";
function parse(bytes) {
  const b = bytes instanceof Uint8Array ? bytes : new Uint8Array(bytes);
  const v = new DataView(b.buffer, b.byteOffset, b.byteLength);
  const u16 = p => v.getUint16(p, true), u32 = p => v.getUint32(p, true);
  function require(ok, message) { if (!ok) throw new Error(message); }
  require(b.length <= 32 * 1024 * 1024, "ZIP listing is limited to 32 MB archives.");
  let end = -1;
  for (let p = b.length - 22; p >= Math.max(0, b.length - 65557); p--) {
    if (u32(p) === 0x06054b50 && p + 22 + u16(p + 20) === b.length) { end = p; break; }
  }
  require(end >= 0, "ZIP directory is missing or truncated.");
  require(u16(end + 4) === 0 && u16(end + 6) === 0 && u16(end + 8) === u16(end + 10), "Split ZIP archives are not supported.");
  const count = u16(end + 10), size = u32(end + 12), offset = u32(end + 16);
  require(count !== 65535 && size !== 0xffffffff && offset !== 0xffffffff, "ZIP64 archives are not supported.");
  require(count <= 2000, "ZIP listing is limited to 2,000 entries.");
  require(offset + size === end, "ZIP directory bounds are invalid or unsupported.");
  let p = offset;
  const entries = [];
  for (let i = 0; i < count; i++) {
    require(p + 46 <= end && u32(p) === 0x02014b50, "ZIP directory entry is truncated or invalid.");
    const flags = u16(p + 8), len = u16(p + 28);
    const next = p + 46 + len + u16(p + 30) + u16(p + 32);
    require(next <= end && len > 0, "ZIP entry name or metadata is truncated.");
    const packed = u32(p + 20), size = u32(p + 24), local = u32(p + 42);
    require(size !== 0xffffffff && packed !== 0xffffffff && local !== 0xffffffff, "ZIP64 entries are not supported.");
    require(u16(p + 34) === 0, "Split ZIP archives are not supported.");
    require(local + 30 <= offset && u32(local) === 0x04034b50, "ZIP local entry header is invalid.");
    const raw = b.slice(p + 46, p + 46 + len);
    // CP437 mapping for legacy ZIP filenames, UTF-8 when its flag is set.
    const cp437 = "ÇüéâäàåçêëèïîìÄÅÉæÆôöòûùÿÖÜ¢£¥₧ƒáíóúñÑªº¿⌐¬½¼¡«»░▒▓│┤╡╢╖╕╣║╗╝╜╛┐└┴┬├─┼╞╟╚╔╩╦╠═╬╧╨╤╥╙╘╒╓╫╪┘┌█▄▌▐▀αßΓπΣσµτΦΘΩδ∞φε∩≡±≥≤⌠⌡÷≈°∙·√ⁿ²■ ";
    let name = (flags & 2048) ? new TextDecoder().decode(raw)
        : Array.from(raw, c => c < 128 ? String.fromCharCode(c) : cp437[c - 128]).join("");
    name = name.replace(/[\p{Cc}\p{Cf}]/gu, "\uFFFD");
    entries.push({name, size, packed, method:u16(p+10), local, crc:u32(p+16), dataEnd:offset, directory: name.endsWith("/"), encrypted: !!(flags & 1)});
    p = next;
  }
  require(p === end, "ZIP directory size does not match its entries.");
  return entries;
}
function crc32(data) {
  let crc = 0xffffffff;
  for (const byte of data) {
    crc ^= byte;
    for (let n=0;n<8;n++) crc = (crc >>> 1) ^ (0xedb88320 & -(crc & 1));
  }
  return (crc ^ 0xffffffff) >>> 0;
}
async function open(archive, index) {
  const b = archive instanceof Uint8Array ? archive : new Uint8Array(archive);
  const entries = parse(b), e = entries[index];
  const v = new DataView(b.buffer,b.byteOffset,b.byteLength), u16=p=>v.getUint16(p,true);
  function require(ok,message) { if(!ok) throw new Error(message); }
  require(!!e, "ZIP entry is unavailable.");
  require(!e.directory, "This entry is a folder.");
  require(!e.encrypted, "Encrypted entries cannot be opened.");
  require(e.method===0 || e.method===8, "Only stored and deflated ZIP entries can be opened.");
  require(e.size <= 16*1024*1024, "ZIP entry opening is limited to 16 MB per file.");
  require(e.size <= Math.max(1,e.packed)*100, "ZIP compression ratio exceeds the 100:1 safety limit.");
  require(!(u16(e.local+6)&1) && u16(e.local+8)===e.method, "ZIP local and central entry metadata disagree.");
  const start=e.local+30+u16(e.local+26)+u16(e.local+28);
  require(start+e.packed<=e.dataEnd,"ZIP entry data is truncated.");
  let data;
  if(e.method===0) {
    require(e.packed===e.size,"Stored ZIP entry sizes disagree.");
    data=b.slice(start,start+e.packed);
  } else {
    if(typeof DecompressionStream === "undefined") throw new Error("This browser cannot open deflated ZIP entries. Use the Android app or a newer browser.");
    let ds;
    try { ds=new DecompressionStream("deflate-raw"); }
    catch(err) { throw new Error("This browser cannot open raw-deflate ZIP entries. Use the Android app or a newer browser."); }
    const reader=new Blob([b.slice(start,start+e.packed)]).stream().pipeThrough(ds).getReader();
    const chunks=[];let total=0;
    try {
      while(true) {
        const r=await reader.read();if(r.done)break;
        total+=r.value.length;
        if(total>e.size || total>16*1024*1024) { await reader.cancel(); throw new Error("ZIP entry expands beyond its declared size or safety limit."); }
        chunks.push(r.value);
      }
    } finally {reader.releaseLock();}
    data=new Uint8Array(total);let at=0;for(const c of chunks){data.set(c,at);at+=c.length;}
  }
  require(data.length===e.size,"ZIP entry size check failed.");
  require(crc32(data)===e.crc,"ZIP entry checksum check failed.");
  return data;
}
async function show(container, file, ctx) {
  const V = root.TengokViewers;
  const card = V.el("div", "card");
  container.appendChild(card);
  let cancelled = false, opening = false;
  ctx.onCleanup(() => { cancelled = true; });
  try {
    if (file.size > 32 * 1024 * 1024) throw new Error("ZIP listing is limited to 32 MB archives.");
    const archive = new Uint8Array(await file.arrayBuffer());
    const entries = parse(archive);
    if (cancelled) return;
    card.appendChild(V.el("div", "card-title", "ZIP contents · " + entries.length + (entries.length === 1 ? " entry" : " entries")));
    card.appendChild(V.el("p", "muted", "Tap a file to view it in memory. Nothing is written to storage or executed. Entry size and checksum are checked before opening."));
    if (!entries.length) card.appendChild(V.el("p", "muted", "This ZIP is empty."));
    for (const [index, entry] of entries.entries()) {
      const row = V.el("div", "zip-entry");
      const button = V.el(entry.directory ? "div" : "button", entry.directory ? "mono" : "btn mono", (entry.directory ? "" : "Open: ") + entry.name);
      if (!entry.directory) button.addEventListener("click", async () => {
        if (opening) return;
        opening=true; button.disabled=true;
        const status=V.el("div","muted small", "Opening in memory...");row.appendChild(status);
        try { const bytes=await open(archive,index); if(!cancelled) ctx.openArchiveFile(new File([bytes],entry.name)); }
        catch(e) { status.textContent=e.message; }
        finally {opening=false; button.disabled=false;}
      });
      row.appendChild(button);
      row.appendChild(V.el("div", "muted small", entry.directory ? "Folder" : V.humanSize(entry.size) + " · packed " + V.humanSize(entry.packed) + (entry.encrypted ? " · encrypted" : "")));
      card.appendChild(row);
    }
    card.appendChild(V.el("p", "muted zip-limits", "Limits: 32 MB ZIP, 2,000 entries. Open stored/deflated files up to 16 MB and 100:1 compression. No password opening, ZIP64, split archives or nested ZIP opening. Nothing is saved to storage."));
    const raw = V.el("button", "btn", "Show raw bytes");
    raw.addEventListener("click", ctx.onHex); card.appendChild(raw);
  } catch (e) {
    if (!cancelled) card.replaceChildren(V.errorCard(e.message, ctx.onHex));
  }
}
root.TengokZip = {parse, open, crc32, show};
})(typeof window !== "undefined" ? window : globalThis);
