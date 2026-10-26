/*
 * constant-pool.js: a minimal, version-agnostic Java class-file constant-pool
 * editor. It parses only the header + constant pool, edits CONSTANT_Utf8 entries
 * (with length fixups), and re-serialises. Everything after the constant pool is
 * copied verbatim because it references the pool by index, and indices never
 * change: we only rewrite Utf8 *bytes*.
 *
 * Because it never inspects the class-file major version, it works on every
 * version including Java 25 / class v69 (mc26) jars. This is the same edit the
 * Java reference engine's ClassAccess/relocation performs; keeping the two in
 * lockstep is what makes the in-browser output trustworthy.
 *
 * Runs in the browser (assigns window.PPClass) and in Node (module.exports).
 */
(function (root) {
  'use strict';

  function ascii(str) {
    const out = new Uint8Array(str.length);
    for (let i = 0; i < str.length; i++) out[i] = str.charCodeAt(i) & 0xff;
    return out;
  }

  function concat(chunks) {
    let len = 0;
    for (const c of chunks) len += c.length;
    const out = new Uint8Array(len);
    let p = 0;
    for (const c of chunks) { out.set(c, p); p += c.length; }
    return out;
  }

  /** Replace every occurrence of needle with repl in a byte array (ASCII-safe). */
  function replaceBytes(hay, needle, repl) {
    if (needle.length === 0) return hay;
    const parts = [];
    let i = 0;
    while (i <= hay.length - needle.length) {
      let match = true;
      for (let j = 0; j < needle.length; j++) {
        if (hay[i + j] !== needle[j]) { match = false; break; }
      }
      if (match) {
        parts.push(repl);
        i += needle.length;
      } else {
        parts.push(hay.subarray(i, i + 1));
        i += 1;
      }
    }
    if (i < hay.length) parts.push(hay.subarray(i));
    return concat(parts);
  }

  const MAGIC = 0xcafebabe;

  /** Parse into { prefix, entries, tail }. entries preserve original order. */
  function parseClass(bytes) {
    const dv = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);
    let p = 0;
    if (dv.getUint32(p) !== MAGIC) throw new Error('Not a Java class file');
    p += 4; // magic
    p += 2; // minor
    p += 2; // major
    const cpCount = dv.getUint16(p);
    p += 2;
    const prefix = bytes.slice(0, p);
    const entries = [];
    for (let i = 1; i < cpCount; i++) {
      const tag = bytes[p];
      p += 1;
      if (tag === 1) { // Utf8
        const len = dv.getUint16(p);
        p += 2;
        entries.push({ tag: 1, data: bytes.slice(p, p + len) });
        p += len;
      } else {
        let size;
        switch (tag) {
          case 7: case 8: case 16: case 19: case 20: size = 2; break; // Class/String/MethodType/Module/Package
          case 15: size = 3; break;                                   // MethodHandle
          case 9: case 10: case 11: case 3: case 4:
          case 12: case 17: case 18: size = 4; break;                 // *ref/Integer/Float/NameAndType/Dynamic
          case 5: case 6: size = 8; break;                            // Long/Double
          default: throw new Error('Unknown constant pool tag ' + tag);
        }
        entries.push({ tag, raw: bytes.slice(p, p + size) });
        p += size;
        if (tag === 5 || tag === 6) i++; // Long/Double take two slots
      }
    }
    return { prefix, entries, tail: bytes.slice(p) };
  }

  function serializeClass(parsed) {
    const parts = [parsed.prefix];
    for (const e of parsed.entries) {
      if (e.tag === 1) {
        const len = e.data.length;
        parts.push(new Uint8Array([1, (len >> 8) & 0xff, len & 0xff]), e.data);
      } else {
        parts.push(new Uint8Array([e.tag]), e.raw);
      }
    }
    parts.push(parsed.tail);
    return concat(parts);
  }

  /** Apply [fromStr, toStr] substitutions to every Utf8 entry. */
  function editClassUtf8(bytes, replacements) {
    const parsed = parseClass(bytes);
    const rules = replacements.map(([f, t]) => [ascii(f), ascii(t)]);
    for (const e of parsed.entries) {
      if (e.tag !== 1) continue;
      let d = e.data;
      for (const [f, t] of rules) d = replaceBytes(d, f, t);
      e.data = d;
    }
    return serializeClass(parsed);
  }

  /** Read a class's access_flags without a bytecode library (version-agnostic). */
  function readAccessFlags(bytes) {
    const parsed = parseClass(bytes);
    // access_flags is the first u2 of the tail.
    return (parsed.tail[0] << 8) | parsed.tail[1];
  }

  function isFinal(bytes) {
    return (readAccessFlags(bytes) & 0x0010) !== 0;
  }

  /**
   * Return a copy of the class with ACC_FINAL (0x0010) cleared on the class
   * itself and on any method whose name is in methodNames. Kotlin compiles every
   * class as final by default, which blocks the wrapper from subclassing the
   * plugin main; clearing it only permits subclassing (safe for a plugin main
   * class), and clearing it on the lifecycle methods the wrapper overrides keeps
   * the rare method-final plugin loading. Version-agnostic: it only flips
   * access_flags u2 values and never touches the constant pool or any code.
   */
  function definalize(bytes, methodNames) {
    const FINAL = 0x0010;
    const parsed = parseClass(bytes);
    const tail = parsed.tail; // fresh buffer from bytes.slice, safe to mutate
    const dv = new DataView(tail.buffer, tail.byteOffset, tail.byteLength);

    // Class access_flags = first u2 of the tail.
    dv.setUint16(0, dv.getUint16(0) & ~FINAL);

    const names = new Set(methodNames || []);
    if (names.size === 0) return serializeClass(parsed);

    // Map constant-pool index -> entry, accounting for Long/Double double slots.
    const cpByIndex = {};
    let cpIndex = 1;
    for (const e of parsed.entries) {
      cpByIndex[cpIndex] = e;
      cpIndex += (e.tag === 5 || e.tag === 6) ? 2 : 1;
    }
    const dec = new TextDecoder('latin1');
    const utf8At = (idx) => {
      const e = cpByIndex[idx];
      return e && e.tag === 1 ? dec.decode(e.data) : null;
    };

    // tail layout: access_flags(2) this_class(2) super_class(2) interfaces then
    // fields then methods. Fields and methods share the member_info shape.
    let o = 6;
    o += 2 + dv.getUint16(o) * 2; // interfaces_count + interfaces
    const walkMembers = (mutate) => {
      const count = dv.getUint16(o); o += 2;
      for (let i = 0; i < count; i++) {
        const memberOffset = o;
        const accessFlags = dv.getUint16(o);
        const nameIndex = dv.getUint16(o + 2);
        const attrCount = dv.getUint16(o + 6);
        o += 8; // access(2) name(2) desc(2) attrCount(2)
        for (let a = 0; a < attrCount; a++) {
          o += 6 + dv.getUint32(o + 2); // attrName(2) attrLen(4) info(len)
        }
        if (mutate && (accessFlags & FINAL) && names.has(utf8At(nameIndex))) {
          dv.setUint16(memberOffset, accessFlags & ~FINAL);
        }
      }
    };
    walkMembers(false); // fields
    walkMembers(true);  // methods
    return serializeClass(parsed);
  }

  /**
   * Return a copy of a Velocity main class with two members appended, the same
   * ones the CLI adds:
   *
   *   @Inject private ProxyServer pluginpulse$proxy;
   *   @Subscribe public void pluginpulse$init(ProxyInitializeEvent e) {
   *       VelocityPulse.bootstrap(pluginpulse$proxy, this);
   *   }
   *
   * Guice fills the field and Velocity calls the handler, so the constructor
   * and velocity-plugin.json stay untouched. The method is branch-free, so it
   * needs no stack map frames. A class that already has the field is returned
   * unchanged (re-injecting an upgraded jar).
   */
  function addVelocityHook(bytes, relocInternal) {
    const FIELD = 'pluginpulse$proxy';
    const parsed = parseClass(bytes);
    const enc = new TextEncoder();
    const dec = new TextDecoder('utf-8');

    const cpByIndex = {};
    let next = 1;
    for (const e of parsed.entries) {
      cpByIndex[next] = e;
      next += (e.tag === 5 || e.tag === 6) ? 2 : 1;
    }
    const utf8At = (idx) => {
      const e = cpByIndex[idx];
      return e && e.tag === 1 ? dec.decode(e.data) : null;
    };
    const u2 = (v) => [(v >> 8) & 0xff, v & 0xff];
    const u4 = (v) => [(v >>> 24) & 0xff, (v >> 16) & 0xff, (v >> 8) & 0xff, v & 0xff];
    const add = (entry) => { parsed.entries.push(entry); return next++; };
    const utf8 = (s) => add({ tag: 1, data: enc.encode(s) });
    const ref = (tag, a, b) => add({ tag, raw: new Uint8Array(b === undefined ? u2(a) : [...u2(a), ...u2(b)]) });

    const tail = parsed.tail;
    const dv = new DataView(tail.buffer, tail.byteOffset, tail.byteLength);
    const thisClass = dv.getUint16(2);
    let o = 6;
    o += 2 + dv.getUint16(o) * 2; // interfaces
    const skipMembers = (onMember) => {
      const count = dv.getUint16(o); o += 2;
      for (let i = 0; i < count; i++) {
        if (onMember) onMember(dv.getUint16(o + 2));
        const attrCount = dv.getUint16(o + 6);
        o += 8;
        for (let a = 0; a < attrCount; a++) o += 6 + dv.getUint32(o + 2);
      }
    };
    const fieldsCountAt = o;
    let present = false;
    skipMembers((nameIdx) => { if (utf8At(nameIdx) === FIELD) present = true; });
    if (present) return bytes;
    const fieldsEnd = o;
    const methodsCountAt = o;
    skipMembers(null);
    const methodsEnd = o;

    const proxyDesc = 'Lcom/velocitypowered/api/proxy/ProxyServer;';
    const fieldName = utf8(FIELD);
    const fieldDesc = utf8(proxyDesc);
    const rva = utf8('RuntimeVisibleAnnotations');
    const injectType = utf8('Lcom/google/inject/Inject;');
    const subscribeType = utf8('Lcom/velocitypowered/api/event/Subscribe;');
    const codeName = utf8('Code');
    const methodName = utf8('pluginpulse$init');
    const methodDesc = utf8('(Lcom/velocitypowered/api/event/proxy/ProxyInitializeEvent;)V');
    const fieldRef = ref(9, thisClass, ref(12, fieldName, fieldDesc));
    const pulseClass = ref(7, utf8(relocInternal + '/velocity/VelocityPulse'));
    const bootstrap = ref(10, pulseClass, ref(12, utf8('bootstrap'),
      utf8('(' + proxyDesc + 'Ljava/lang/Object;)L' + relocInternal + '/velocity/VelocityUpdater;')));

    const annotation = (type) => [...u2(rva), ...u4(6), ...u2(1), ...u2(type), ...u2(0)];
    const field = [...u2(0x0002), ...u2(fieldName), ...u2(fieldDesc), ...u2(1), ...annotation(injectType)];
    const code = [
      0x2a,                          // aload_0
      0xb4, ...u2(fieldRef),         // getfield pluginpulse$proxy
      0x2a,                          // aload_0
      0xb8, ...u2(bootstrap),        // invokestatic VelocityPulse.bootstrap
      0x57,                          // pop
      0xb1,                          // return
    ];
    const method = [
      ...u2(0x0001), ...u2(methodName), ...u2(methodDesc), ...u2(2),
      ...u2(codeName), ...u4(12 + code.length), ...u2(2), ...u2(2), ...u4(code.length), ...code,
      ...u2(0), ...u2(0),
      ...annotation(subscribeType),
    ];

    const out = new Uint8Array(tail.length + field.length + method.length);
    out.set(tail.subarray(0, fieldsEnd), 0);
    out.set(field, fieldsEnd);
    out.set(tail.subarray(fieldsEnd, methodsEnd), fieldsEnd + field.length);
    out.set(method, methodsEnd + field.length);
    out.set(tail.subarray(methodsEnd), methodsEnd + field.length + method.length);
    const odv = new DataView(out.buffer);
    odv.setUint16(fieldsCountAt, dv.getUint16(fieldsCountAt) + 1);
    odv.setUint16(methodsCountAt + field.length, dv.getUint16(methodsCountAt) + 1);
    parsed.tail = out;

    const prefix = parsed.prefix.slice();
    new DataView(prefix.buffer).setUint16(8, next); // constant_pool_count
    parsed.prefix = prefix;
    return serializeClass(parsed);
  }

  const api = { parseClass, serializeClass, editClassUtf8, readAccessFlags, isFinal, definalize, replaceBytes,
    addVelocityHook };
  if (typeof module !== 'undefined' && module.exports) module.exports = api;
  root.PPClass = api;
})(typeof window !== 'undefined' ? window : globalThis);
