/* Bounded ZIP central-directory listing only. No decompression or extraction. */
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
    entries.push({name, size, packed, directory: name.endsWith("/"), encrypted: !!(flags & 1)});
    p = next;
  }
  require(p === end, "ZIP directory size does not match its entries.");
  return entries;
}
async function show(container, file, ctx) {
  const V = root.TengokViewers;
  const card = V.el("div", "card");
  container.appendChild(card);
  let cancelled = false;
  ctx.onCleanup(() => { cancelled = true; });
  try {
    if (file.size > 32 * 1024 * 1024) throw new Error("ZIP listing is limited to 32 MB archives.");
    const entries = parse(await file.arrayBuffer());
    if (cancelled) return;
    card.appendChild(V.el("div", "card-title", "ZIP contents · " + entries.length + " entries"));
    card.appendChild(V.el("p", "muted", "Read-only listing. Paths and sizes are declared by the archive, not verified file contents. Nothing is extracted or executed."));
    if (!entries.length) card.appendChild(V.el("p", "muted", "This ZIP is empty."));
    for (const entry of entries) {
      const row = V.el("div", "zip-entry");
      row.appendChild(V.el("div", "mono", entry.name));
      row.appendChild(V.el("div", "muted small", entry.directory ? "Folder" : V.humanSize(entry.size) + " · packed " + V.humanSize(entry.packed) + (entry.encrypted ? " · encrypted" : "")));
      card.appendChild(row);
    }
    card.appendChild(V.el("p", "muted small", "Limits: 32 MB ZIP, 2,000 entries. No extraction, password opening, ZIP64 or split archives. Encrypted entries can be listed only when their directory is readable."));
    const raw = V.el("button", "btn", "Show raw bytes");
    raw.addEventListener("click", ctx.onHex); card.appendChild(raw);
  } catch (e) {
    if (!cancelled) card.replaceChildren(V.errorCard(e.message, ctx.onHex));
  }
}
root.TengokZip = {parse, show};
})(typeof window !== "undefined" ? window : globalThis);
