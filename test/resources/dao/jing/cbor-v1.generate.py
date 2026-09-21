#!/usr/bin/env python3
"""Candidate generator and independent reader for the DaoJing canonical-CBOR
fixture corpus `cbor-v1.json` (phase J0 of docs/design/dao.jing.cbor.md).

Python 3 standard library only.  Nothing here calls Jing, Boring, or any
Clojure code: the expected bytes come from the written contract alone.

Usage (run from anywhere; paths are relative to this script):

    python3 cbor-v1.generate.py            # print the candidate corpus JSON
    python3 cbor-v1.generate.py --check    # exit 1 unless it equals cbor-v1.json
    python3 cbor-v1.generate.py --inspect  # decode every fixture in cbor-v1.json
                                           # with the Jing-agnostic reader

The script never writes the frozen resource.  Producing a new corpus is an
explicit shell redirect performed under the review procedure in
cbor-v1.README.md; `--check` proves reproducibility without overwriting.

Before printing, the generator self-verifies every case:
  * canonical cases: encoding the input gives the bytes; the independent
    reader plus the profile checker decode those bytes and re-encode them
    to the identical bytes;
  * encode refusals: encoding the input raises exactly the declared class;
  * decode refusals: the profile checker raises exactly the declared class;
  * ids are unique; equivalence groups share bytes; retained-distinction
    groups differ pairwise; equal canonical bytes occur only inside one
    equivalence group (injectivity over the corpus).
"""

import hashlib
import json
import os
import struct
import sys
from fractions import Fraction
from math import gcd

HERE = os.path.dirname(os.path.abspath(__file__))
CORPUS_PATH = os.path.join(HERE, "cbor-v1.json")

# Provenance recorded in the corpus.  Constants, not git calls, so the output
# is a pure function of this file.
PLAN_PATH = "docs/design/dao.jing.cbor.md"
PLAN_COMMIT = "c96492f353354a661f6ad843f84d40d440193563"
TREE_COMMIT = "0dc06197"
PINS = {"boring": "org.replikativ/boring 0.1.30", "dart-cbor": "cbor 6.5.1"}

REFUSAL_CLASSES = [
    "malformed-cbor",
    "trailing-data",
    "invalid-utf8",
    "unpaired-surrogate",
    "non-canonical",
    "duplicate-key",
    "duplicate-element",
    "equality-collapse",
    "unknown-tag",
    "identifier-tag-39",
    "unknown-frame-name",
    "malformed-frame",
    "malformed-number",
    "native-float",
    "unsupported-simple",
    "unsupported-value",
]

REQUIRED_CATEGORIES = [
    "collections",
    "frames",
    "metadata",
    "unicode",
    "identifiers",
    "bytes",
    "numerics",
    "malformed",
    "boring-options",
    "injectivity",
    "unsupported-values",
]

LIST_NAME = "dao.jing/list"
KEYWORD_NAME = "dao.jing/keyword"
SYMBOL_NAME = "dao.jing/symbol"
FLOAT64_NAME = "dao.jing/float64"
WITH_META_NAME = "clojure/with-meta"
READER_POSITION_KEYS = ("line", "column", "end-line", "end-column")
CANONICAL_NAN = 0x7FF8000000000000
TAG_SET = 258


class Refusal(Exception):
    def __init__(self, cls, detail=""):
        assert cls in REFUSAL_CLASSES, cls
        super().__init__("%s: %s" % (cls, detail))
        self.cls = cls


# =============================================================================
# Deterministic CBOR primitives (RFC 8949 section 4.2.1 core rules)
# =============================================================================


def head(major, n):
    """Shortest definite head for major type `major` and argument `n`."""
    if n < 0:
        raise ValueError("negative argument")
    if n < 24:
        return bytes([major << 5 | n])
    if n < 0x100:
        return bytes([major << 5 | 24, n])
    if n < 0x10000:
        return bytes([major << 5 | 25]) + n.to_bytes(2, "big")
    if n < 0x100000000:
        return bytes([major << 5 | 26]) + n.to_bytes(4, "big")
    if n < 0x10000000000000000:
        return bytes([major << 5 | 27]) + n.to_bytes(8, "big")
    raise ValueError("argument exceeds 64 bits")


def raw_text(s):
    """A definite text string; no surrogate policy (primitive only)."""
    b = s.encode("utf-8")
    return head(3, len(b)) + b


def enc_int(n):
    """CBOR integer, or bignum tag 2/3 with a minimal magnitude."""
    if 0 <= n < 1 << 64:
        return head(0, n)
    if -(1 << 64) <= n < 0:
        return head(1, -1 - n)
    if n > 0:
        tag, mag = 2, n
    else:
        tag, mag = 3, -1 - n
    b = mag.to_bytes((mag.bit_length() + 7) // 8, "big")
    return head(6, tag) + head(2, len(b)) + b


def named(name, payload):
    """Tag 27 over exactly [name-string payload]."""
    return head(6, 27) + head(4, 2) + raw_text(name) + payload


def sha256_hex(b):
    return hashlib.sha256(b).hexdigest()


# =============================================================================
# The Jing profile encoder over the fixture DSL
# =============================================================================


def text_of(t):
    """DSL text: a JSON string, or {"utf16": [hex code units]}."""
    if isinstance(t, str):
        s = t
    elif isinstance(t, dict) and set(t) == {"utf16"}:
        units = b"".join(int(u, 16).to_bytes(2, "big") for u in t["utf16"])
        s = units.decode("utf-16-be", "surrogatepass")
    else:
        raise ValueError("bad DSL text %r" % (t,))
    for ch in s:
        if 0xD800 <= ord(ch) <= 0xDFFF:
            raise Refusal("unpaired-surrogate", "U+%04X" % ord(ch))
    return s


def enc_text(t):
    return raw_text(text_of(t))


def canonical_f64(bits):
    if (bits >> 52) & 0x7FF == 0x7FF and bits & ((1 << 52) - 1):
        return CANONICAL_NAN
    return bits


def widen_f32(bits32):
    if (bits32 >> 23) & 0xFF == 0xFF and bits32 & 0x7FFFFF:
        return CANONICAL_NAN
    f = struct.unpack(">f", bits32.to_bytes(4, "big"))[0]
    return struct.unpack(">Q", struct.pack(">d", f))[0]


def float_bits(node):
    if node["t"] == "float64":
        return canonical_f64(int(node["bits"], 16))
    return widen_f32(int(node["bits"], 16))


def ratio_parts(node):
    n, d = int(node["num"]), int(node["den"])
    if d == 0:
        raise Refusal("malformed-number", "zero denominator")
    if d < 0:
        n, d = -n, -d
    g = gcd(n, d)
    return n // g, d // g


def is_reader_position_key(k):
    return k["t"] == "keyword" and k["ns"] is None and k["name"] in READER_POSITION_KEYS


def enc_entries(pairs, dup_cls):
    """Encode [(key-node, value-node-or-None)], sorted bytewise by encoded key.
    Duplicate canonical keys and portable-equality collapses are refused."""
    encoded = [(enc(k), None if v is None else enc(v), eq_key(k)) for k, v in pairs]
    keys = [e[0] for e in encoded]
    if len(set(keys)) != len(keys):
        raise Refusal(dup_cls)
    eqs = [e[2] for e in encoded]
    if len(set(eqs)) != len(eqs):
        raise Refusal("equality-collapse")
    encoded.sort(key=lambda e: e[0])
    out = b""
    for k, v, _ in encoded:
        out += k + (v if v is not None else b"")
    return out


def with_meta(node, body):
    m = node.get("meta")
    if m is None:
        return body
    assert m["t"] == "map", "metadata must be a map node"
    if "meta" in m:
        raise Refusal("malformed-frame", "metadata map itself carries metadata")
    # Validate before strip (A9): a stripped entry is still checked, so a lone
    # surrogate under :line is refused rather than silently discarded.
    for key, v in m["entries"]:
        if is_reader_position_key(key):
            enc(v)
    kept = [(k, v) for k, v in m["entries"] if not is_reader_position_key(k)]
    if not kept:
        return body
    meta = head(5, len(kept)) + enc_entries(kept, "duplicate-key")
    return named(WITH_META_NAME, head(4, 2) + meta + body)


META_TARGETS = {"vector", "list", "seq", "map", "sorted-map", "set", "sorted-set", "symbol"}


def enc(node):
    t = node["t"]
    if "meta" in node:
        assert t in META_TARGETS, "metadata on %s" % t
    if t == "nil":
        return b"\xf6"
    if t == "bool":
        return b"\xf5" if node["v"] else b"\xf4"
    if t == "int":
        return enc_int(int(node["v"]))
    if t in ("float64", "float32"):
        return named(FLOAT64_NAME, head(2, 8) + float_bits(node).to_bytes(8, "big"))
    if t == "decimal":
        e, m = int(node["exponent"]), int(node["mantissa"])
        if not -(1 << 64) <= e < 1 << 64:
            raise Refusal("malformed-number", "exponent outside major types 0/1")
        return head(6, 4) + head(4, 2) + enc_int(e) + enc_int(m)
    if t == "ratio":
        n, d = ratio_parts(node)
        return head(6, 30) + head(4, 2) + enc_int(n) + enc_int(d)
    if t == "str":
        return enc_text(node["v"])
    if t == "bytes":
        b = bytes.fromhex(node["hex"])
        return head(2, len(b)) + b
    if t in ("keyword", "symbol"):
        ns = b"\xf6" if node["ns"] is None else enc_text(node["ns"])
        name = KEYWORD_NAME if t == "keyword" else SYMBOL_NAME
        return with_meta(node, named(name, head(4, 2) + ns + enc_text(node["name"])))
    if t == "vector":
        items = node["items"]
        return with_meta(node, head(4, len(items)) + b"".join(enc(x) for x in items))
    if t in ("list", "seq"):
        items = node["items"]
        body = head(4, len(items)) + b"".join(enc(x) for x in items)
        return with_meta(node, named(LIST_NAME, body))
    if t in ("map", "sorted-map"):
        entries = [(k, v) for k, v in node["entries"]]
        return with_meta(node, head(5, len(entries)) + enc_entries(entries, "duplicate-key"))
    if t in ("set", "sorted-set"):
        items = [(x, None) for x in node["items"]]
        body = head(6, TAG_SET) + head(4, len(items)) + enc_entries(items, "duplicate-element")
        return with_meta(node, body)
    if t == "host":
        raise Refusal("unsupported-value", node["kind"])
    raise ValueError("unknown DSL node %r" % t)


def eq_key(node):
    """Portable decoded equality: numbers by exact value across kinds, scale and
    zero sign; canonical NaNs equal; sequentials equal across list/vector;
    metadata ignored."""
    t = node["t"]
    if t == "nil":
        return ("nil",)
    if t == "bool":
        return ("bool", bool(node["v"]))
    if t == "int":
        return ("num", Fraction(int(node["v"])))
    if t in ("float64", "float32"):
        bits = float_bits(node)
        if bits == CANONICAL_NAN:
            return ("nan",)
        if (bits >> 52) & 0x7FF == 0x7FF:
            return ("inf", bits >> 63)
        return ("num", Fraction(struct.unpack(">d", bits.to_bytes(8, "big"))[0]))
    if t == "decimal":
        return ("num", Fraction(int(node["mantissa"])) * Fraction(10) ** int(node["exponent"]))
    if t == "ratio":
        n, d = ratio_parts(node)
        return ("num", Fraction(n, d))
    if t == "str":
        return ("str", text_of(node["v"]))
    if t == "bytes":
        return ("bytes", bytes.fromhex(node["hex"]))
    if t in ("keyword", "symbol"):
        ns = None if node["ns"] is None else text_of(node["ns"])
        return (t, ns, text_of(node["name"]))
    if t in ("vector", "list", "seq"):
        return ("seq", tuple(eq_key(x) for x in node["items"]))
    if t in ("map", "sorted-map"):
        return ("map", frozenset((eq_key(k), eq_key(v)) for k, v in node["entries"]))
    if t in ("set", "sorted-set"):
        return ("set", frozenset(eq_key(x) for x in node["items"]))
    return ("host", node.get("kind"))


# =============================================================================
# Independent generic CBOR reader (no Jing knowledge)
# =============================================================================


class Item:
    """One generic CBOR data item.  kind is one of uint nint bytes text array
    map tag simple float.  `raw` is the item's exact encoded span."""

    def __init__(self, kind, value, raw, flags):
        self.kind, self.value, self.raw, self.flags = kind, value, raw, flags


def _need(buf, pos, n):
    if pos + n > len(buf):
        raise Refusal("malformed-cbor", "truncated at byte %d" % pos)


def _read_head(buf, pos):
    _need(buf, pos, 1)
    ib = buf[pos]
    major, ai = ib >> 5, ib & 31
    pos += 1
    flags = set()
    if ai < 24:
        return major, ai, ai, pos, flags
    if ai in (24, 25, 26, 27):
        size = 1 << (ai - 24)
        _need(buf, pos, size)
        arg = int.from_bytes(buf[pos:pos + size], "big")
        pos += size
        if major != 7 and arg < (24, 0x100, 0x10000, 0x100000000)[ai - 24]:
            flags.add("non-shortest")
        return major, ai, arg, pos, flags
    if ai == 31:
        return major, ai, None, pos, flags
    raise Refusal("malformed-cbor", "reserved additional information %d" % ai)


def _decode_utf8(b):
    try:
        return b.decode("utf-8", "strict")
    except UnicodeDecodeError as e:
        raise Refusal("invalid-utf8", str(e))


def read_item(buf, pos):
    start = pos
    major, ai, arg, pos, flags = _read_head(buf, pos)
    if major in (0, 1):
        if ai == 31:
            raise Refusal("malformed-cbor", "indefinite integer")
        return Item("uint" if major == 0 else "nint",
                    arg if major == 0 else -1 - arg, buf[start:pos], flags), pos
    if major in (2, 3):
        if ai == 31:
            flags.add("indefinite")
            chunks = []
            while True:
                _need(buf, pos, 1)
                if buf[pos] == 0xFF:
                    pos += 1
                    break
                cmaj, cai, clen, pos, cflags = _read_head(buf, pos)
                if cmaj != major or cai == 31:
                    raise Refusal("malformed-cbor", "bad indefinite-length chunk")
                flags |= cflags
                _need(buf, pos, clen)
                chunk = buf[pos:pos + clen]
                pos += clen
                if major == 3:
                    _decode_utf8(chunk)
                chunks.append(chunk)
            data = b"".join(chunks)
        else:
            _need(buf, pos, arg)
            data = buf[pos:pos + arg]
            pos += arg
        value = data if major == 2 else _decode_utf8(data)
        return Item("bytes" if major == 2 else "text", value, buf[start:pos], flags), pos
    if major in (4, 5):
        count = 0
        items = []
        while True:
            if ai == 31:
                _need(buf, pos, 1)
                if buf[pos] == 0xFF:
                    pos += 1
                    flags.add("indefinite")
                    break
            elif count == arg:
                break
            if major == 4:
                x, pos = read_item(buf, pos)
                items.append(x)
            else:
                k, pos = read_item(buf, pos)
                v, pos = read_item(buf, pos)
                items.append((k, v))
            count += 1
        return Item("array" if major == 4 else "map", items, buf[start:pos], flags), pos
    if major == 6:
        if ai == 31:
            raise Refusal("malformed-cbor", "indefinite tag")
        inner, pos = read_item(buf, pos)
        return Item("tag", (arg, inner), buf[start:pos], flags), pos
    # major 7
    if ai == 31:
        raise Refusal("malformed-cbor", "break outside an indefinite-length item")
    if ai < 24:
        return Item("simple", ai, buf[start:pos], flags), pos
    if ai == 24:
        if arg < 32:
            raise Refusal("malformed-cbor", "two-byte simple value below 32")
        return Item("simple", arg, buf[start:pos], flags), pos
    width = {25: 16, 26: 32, 27: 64}[ai]
    return Item("float", (width, arg), buf[start:pos], flags), pos


def read_one(buf):
    """Exactly one item and end of input."""
    item, pos = read_item(buf, 0)
    if pos != len(buf):
        raise Refusal("trailing-data", "%d bytes after the item" % (len(buf) - pos))
    return item


# =============================================================================
# Jing profile checker over generic items (decode side of the contract)
# =============================================================================


def _text_item(x):
    return x.kind == "text"


def _int_node(x, allow_bignum):
    if x.kind in ("uint", "nint"):
        return str(x.value)
    if allow_bignum and x.kind == "tag" and x.value[0] in (2, 3):
        return str(_bignum(x))
    return None


def _bignum(x):
    tag, inner = x.value
    if inner.kind != "bytes":
        raise Refusal("malformed-number", "bignum payload is not a byte string")
    mag = int.from_bytes(inner.value, "big")
    return mag if tag == 2 else -1 - mag


def _check_members(pairs, dup_cls):
    raws = [k.raw for k, _ in pairs]
    if len(set(raws)) != len(raws):
        raise Refusal(dup_cls)


def _frame(name, p):
    if name == LIST_NAME:
        if p.kind != "array":
            raise Refusal("malformed-frame", "list payload is not an array")
        return {"t": "list", "items": [to_node(x) for x in p.value]}
    if name in (KEYWORD_NAME, SYMBOL_NAME):
        if p.kind != "array" or len(p.value) != 2:
            raise Refusal("malformed-frame", "identifier payload is not [ns name]")
        ns, nm = p.value
        if not (ns.kind == "simple" and ns.value == 22) and not _text_item(ns):
            raise Refusal("malformed-frame", "namespace is neither nil nor text")
        if not _text_item(nm):
            raise Refusal("malformed-frame", "name is not text")
        return {"t": "keyword" if name == KEYWORD_NAME else "symbol",
                "ns": None if ns.kind == "simple" else ns.value, "name": nm.value}
    if name == FLOAT64_NAME:
        if p.kind != "bytes" or len(p.value) != 8:
            raise Refusal("malformed-frame", "float64 payload is not eight bytes")
        return {"t": "float64", "bits": p.value.hex()}
    if name == WITH_META_NAME:
        if p.kind != "array" or len(p.value) != 2:
            raise Refusal("malformed-frame", "with-meta payload is not [meta value]")
        m, v = to_node(p.value[0]), to_node(p.value[1])
        if m["t"] != "map":
            raise Refusal("malformed-frame", "metadata is not a map")
        if "meta" in m:
            raise Refusal("malformed-frame", "metadata map itself carries metadata")
        if v["t"] not in META_TARGETS or "meta" in v:
            raise Refusal("malformed-frame", "metadata target is not a bare collection or symbol")
        v = dict(v)
        v["meta"] = m
        return v
    raise Refusal("unknown-frame-name", name)


def to_node(x):
    k = x.kind
    if k in ("uint", "nint"):
        return {"t": "int", "v": str(x.value)}
    if k == "float":
        raise Refusal("native-float", "float%d" % x.value[0])
    if k == "simple":
        if x.value == 20:
            return {"t": "bool", "v": False}
        if x.value == 21:
            return {"t": "bool", "v": True}
        if x.value == 22:
            return {"t": "nil"}
        raise Refusal("unsupported-simple", "simple(%d)" % x.value)
    if k == "bytes":
        return {"t": "bytes", "hex": x.value.hex()}
    if k == "text":
        return {"t": "str", "v": x.value}
    if k == "array":
        return {"t": "vector", "items": [to_node(i) for i in x.value]}
    if k == "map":
        entries = [(to_node(a), to_node(b)) for a, b in x.value]
        _check_members(x.value, "duplicate-key")
        _check_collapse([e for e, _ in entries])
        return {"t": "map", "entries": entries}
    tag, p = x.value
    if tag in (2, 3):
        return {"t": "int", "v": str(_bignum(x))}
    if tag == 4:
        if p.kind != "array" or len(p.value) != 2:
            raise Refusal("malformed-number", "decimal payload is not [exponent mantissa]")
        e, m = _int_node(p.value[0], False), _int_node(p.value[1], True)
        if e is None or m is None:
            raise Refusal("malformed-number", "decimal component is not an integer")
        return {"t": "decimal", "exponent": e, "mantissa": m}
    if tag == 30:
        if p.kind != "array" or len(p.value) != 2:
            raise Refusal("malformed-number", "rational payload is not [numerator denominator]")
        n, d = _int_node(p.value[0], True), _int_node(p.value[1], True)
        if n is None or d is None or int(d) <= 0:
            raise Refusal("malformed-number", "rational components out of grammar")
        return {"t": "ratio", "num": n, "den": d}
    if tag == TAG_SET:
        if p.kind != "array":
            raise Refusal("malformed-frame", "set payload is not an array")
        items = [to_node(i) for i in p.value]
        _check_members([(i, None) for i in p.value], "duplicate-element")
        _check_collapse(items)
        return {"t": "set", "items": items}
    if tag == 39:
        raise Refusal("identifier-tag-39")
    if tag == 27:
        if p.kind != "array" or len(p.value) != 2 or not _text_item(p.value[0]):
            raise Refusal("malformed-frame", "tag 27 payload is not [name-string payload]")
        return _frame(p.value[0].value, p.value[1])
    raise Refusal("unknown-tag", "tag %d" % tag)


def _check_collapse(nodes):
    eqs = [eq_key(n) for n in nodes]
    if len(set(eqs)) != len(eqs):
        raise Refusal("equality-collapse")


def jing_decode(buf):
    """Profile acceptance: read one item, convert through the closed profile,
    then require canonical re-encoding to reproduce the input bytes."""
    if not buf:
        raise Refusal("malformed-cbor", "empty input")
    node = to_node(read_one(buf))
    if enc(node) != buf:
        raise Refusal("non-canonical")
    return node


# =============================================================================
# DSL constructors
# =============================================================================


def I(v, host=None):
    n = {"t": "int", "v": str(v)}
    if host:
        n["host"] = host
    return n


def F(bits):
    return {"t": "float64", "bits": bits}


def F32(bits):
    return {"t": "float32", "bits": bits}


def DEC(e, m):
    return {"t": "decimal", "exponent": str(e), "mantissa": str(m)}


def RAT(n, d):
    return {"t": "ratio", "num": str(n), "den": str(d)}


def S(v):
    return {"t": "str", "v": v}


def U16(*units):
    return {"utf16": list(units)}


def B(h):
    return {"t": "bytes", "hex": h}


def KW(ns, name):
    return {"t": "keyword", "ns": ns, "name": name}


def SYM(ns, name, meta=None):
    return _m({"t": "symbol", "ns": ns, "name": name}, meta)


def _m(node, meta):
    if meta is not None:
        node["meta"] = meta
    return node


def V(*items, meta=None):
    return _m({"t": "vector", "items": list(items)}, meta)


def L(*items, meta=None):
    return _m({"t": "list", "items": list(items)}, meta)


def SEQ(*items, meta=None):
    return _m({"t": "seq", "items": list(items)}, meta)


def M(*pairs, meta=None):
    return _m({"t": "map", "entries": [list(p) for p in pairs]}, meta)


def SM(*pairs, meta=None):
    return _m({"t": "sorted-map", "entries": [list(p) for p in pairs]}, meta)


def SET(*items, meta=None):
    return _m({"t": "set", "items": list(items)}, meta)


def SSET(*items, meta=None):
    return _m({"t": "sorted-set", "items": list(items)}, meta)


def HOST(kind, v):
    return {"t": "host", "kind": kind, "v": v}


NIL = {"t": "nil"}
TRUE = {"t": "bool", "v": True}
FALSE = {"t": "bool", "v": False}


def k(name):
    return KW(None, name)


READER_META = M((k("line"), I(12)), (k("column"), I(3)), (k("end-line"), I(12)), (k("end-column"), I(9)))
DOC_META = M((k("doc"), S("d")))


# Raw byte builders for hand-written decode-refusal inputs.  They use only the
# CBOR primitives above, never the profile encoder.
def R(*parts):
    out = b""
    for p in parts:
        out += p if isinstance(p, bytes) else bytes.fromhex(p.replace(" ", ""))
    return out


def T(s):
    return raw_text(s)


def NAMED(name, payload):
    return R("d81b82", T(name), payload)


def F64RAW(bits):
    return NAMED(FLOAT64_NAME, R("48", bits))


def KWRAW(ns, name):
    return NAMED(KEYWORD_NAME, R("82", "f6" if ns is None else T(ns), T(name)))


# =============================================================================
# Plan citations
# =============================================================================

P = "dao.jing.cbor.md "
P_SORT = P + "Encoding contract: sorted collections, bytewise key/element order, duplicate and equality-collapse rejection"
P_STR = P + "Encoding contract: UTF-8 without normalization; surrogate and invalid-UTF-8 rejection"
P_LIST = P + "Encoding contract: lists and sequences share tag 27 dao.jing/list; plain arrays are vectors"
P_META = P + "Encoding contract: clojure/with-meta retention, :line/:column/:end-line/:end-column stripping, empty-metadata omission"
P_ID = P + "Encoding contract: all keywords and symbols as tag 27 [namespace name]; tag 39 rejected"
P_BYTES = P + "Encoding contract: byte strings hashed by content; Layering and interfaces: immutable snapshots"
P_REJ = P + "Encoding contract: rejection bullet, TaggedValue/SimpleValue paragraph, shortest definite encodings"
P_FRAMES = P + "Encoding contract: Jing's four names use tag 27 followed by exactly [name-string, payload]"
P_SETS = P + "Encoding contract: frozen fixtures define the Boring mappings for sets and clojure/with-meta"
P_FLOAT = P + "Numeric identity: float64 carrier bullet (eight big-endian bytes, float32 widening, signed zero, canonical NaN)"
P_KIND = P + "Numeric identity: integer, floating-point, decimal and rational kinds are preserved"
P_INT = P + "Numeric identity: CBOR integers and bignum tags 2/3; host integer width is not identity"
P_DEC = P + "Numeric identity: decimal-fraction tag 4 [exponent mantissa], scale preserved"
P_RAT = P + "Numeric identity: rational tag 30, reduced, positive denominator, denominator 1 retained"
P_OPTS = P + "Community precedent and integration choice; Encoding contract (stringref, shapes, index disabled)"
P_DOMAIN = P + "Encoding contract: supported values; unsupported host objects rejected before storage"
P_ACC = P + "Implementation sequence and validation: acceptance requires injectivity"

# =============================================================================
# The corpus
# =============================================================================

CASES = []


def canon(cid, cats, plan, notes, inp, eq=None, distinct=(), expect=None):
    CASES.append({"id": cid, "kind": "canonical", "categories": list(cats), "input": inp,
                  "refusal": None, "equivalence": eq, "distinct": list(distinct),
                  "plan": plan, "notes": notes, "_expect": expect, "_raw": None})


def erefuse(cid, cats, plan, notes, inp, cls):
    CASES.append({"id": cid, "kind": "encode-refusal", "categories": list(cats), "input": inp,
                  "refusal": cls, "equivalence": None, "distinct": [],
                  "plan": plan, "notes": notes, "_expect": None, "_raw": None})


def drefuse(cid, cats, plan, notes, raw, cls):
    CASES.append({"id": cid, "kind": "decode-refusal", "categories": list(cats), "input": None,
                  "refusal": cls, "equivalence": None, "distinct": [],
                  "plan": plan, "notes": notes, "_expect": None, "_raw": raw})


PATH = "pathological-identifiers"

# --- collections --------------------------------------------------------------
C = ["collections"]
canon("coll/empty-vector", C, P_META, "Empty vector, no metadata.", V(),
      eq="metadata-stripped/empty-vector", distinct=["sequence-kind/empty"], expect="80")
canon("coll/empty-map", C, P_META, "Empty map, no metadata.", M(),
      eq="metadata-stripped/empty-map", expect="a0")
canon("coll/empty-set", C, P_SETS, "Empty set: tag 258 over an empty array.", SET(),
      eq="metadata-stripped/empty-set", expect="d9010280")
canon("coll/map-kw-insertion-ab", C, P_SORT, "Map built by inserting :a then :b.",
      M((k("a"), I(1)), (k("b"), I(2))), eq="sortedness/map-a-b")
canon("coll/map-kw-insertion-ba", C, P_SORT, "Same map built by inserting :b then :a; insertion order is not identity.",
      M((k("b"), I(2)), (k("a"), I(1))), eq="sortedness/map-a-b")
canon("coll/sorted-map-kw-a-b", C, P_SORT, "A sorted map normalizes to the ordinary map encoding.",
      SM((k("a"), I(1)), (k("b"), I(2))), eq="sortedness/map-a-b")
canon("coll/sorted-map-aa-b", C, P_SORT,
      "Host sort puts :aa before :b; canonical bytes put :b (name 6162) before :aa (name 626161) because the shorter text head sorts first bytewise.",
      SM((k("aa"), I(1)), (k("b"), I(2))), eq="sortedness/map-aa-b")
canon("coll/map-aa-b-insertion", C, P_SORT, "The unsorted map with the same entries.",
      M((k("b"), I(2)), (k("aa"), I(1))), eq="sortedness/map-aa-b")
canon("coll/set-ints-321", C, P_SORT, "Set built from 3 2 1.", SET(I(3), I(2), I(1)),
      eq="sortedness/set-1-2-3", expect="d90102830102 03".replace(" ", ""))
canon("coll/set-ints-123", C, P_SORT, "Set built from 1 2 3.", SET(I(1), I(2), I(3)), eq="sortedness/set-1-2-3")
canon("coll/sorted-set-ints-123", C, P_SORT, "Sorted set normalizes to the ordinary set encoding.",
      SSET(I(1), I(2), I(3)), eq="sortedness/set-1-2-3")
canon("coll/sorted-set-signed", C, P_SORT,
      "Host order -1 0 1 24 1000; canonical order 00 01 1818 1903e8 20: negative integers (major 1) sort after every non-negative one.",
      SSET(I(-1), I(0), I(1), I(24), I(1000)), eq="sortedness/set-signed",
      expect="d9010285000118181903e820")
canon("coll/set-signed-insertion", C, P_SORT, "The same elements in reverse insertion order.",
      SET(I(1000), I(24), I(1), I(0), I(-1)), eq="sortedness/set-signed")

MIXED_KEYS = [
    (I(1000), I(0)), (S("a"), I(1)), (NIL, I(2)), (TRUE, I(3)), (FALSE, I(4)), (I(-1), I(5)),
    (B("00"), I(6)), (k("k"), I(7)), (SYM(None, "s"), I(8)), (V(I(1)), I(9)),
    (F("3ff8000000000000"), I(10)), (DEC(-2, 314), I(11)), (RAT(1, 3), I(12)), (I(1 << 64), I(13)),
]
canon("coll/map-mixed-keys", C, P_SORT,
      "Keys of every scalar and collection kind, sorted bytewise by encoded key. 1000 (1903e8) precedes \"a\" (6161): bytewise, not length-first.",
      M(*MIXED_KEYS), eq="sortedness/mixed-keys")
canon("coll/map-mixed-keys-reversed", C, P_SORT, "The same map with reversed insertion order.",
      M(*reversed(MIXED_KEYS)), eq="sortedness/mixed-keys")
canon("coll/set-mixed-elements", C, P_SORT, "Set elements of mixed kinds, sorted bytewise by encoded element.",
      SET(S("a"), I(1000), NIL, F("bff0000000000000"), k("k"), B("ff"), V()))
canon("coll/nested-normalization", C, P_SORT, "Normalization applies at every depth: a map inside a vector inside a set.",
      SET(V(M((I(2), I(0)), (I(1), I(0))), SSET(I(9), I(8)))))
canon("coll/map-both-slash-keywords", C + ["identifiers"], P_SORT + "; " + P_ID,
      "(keyword nil \"a/b\") and (keyword \"a\" \"b\") are distinct keys of one map; no collapse.",
      M((KW(None, "a/b"), I(1)), (KW("a", "b"), I(2))))
erefuse("coll/map-int-float-key-collapse", C, P_SORT,
        "Integer 1 and float64 1.0 are portably equal: reject rather than lose an entry. Hosts whose map constructor already merges them cannot build this input; the test must build it with carriers or report N/A.",
        M((I(1), S("int")), (F("3ff0000000000000"), S("float"))), "equality-collapse")
erefuse("coll/set-signed-zero-collapse", C, P_SORT, "0.0 and -0.0 are portably equal (zero sign ignored).",
        SET(F("0000000000000000"), F("8000000000000000")), "equality-collapse")
erefuse("coll/set-int-ratio-collapse", C, P_SORT, "Integer 1 and ratio 1/1 are portably equal.",
        SET(I(1), RAT(1, 1)), "equality-collapse")
erefuse("coll/set-decimal-scale-collapse", C, P_SORT, "1M and 1.0M are portably equal (scale ignored).",
        SET(DEC(0, 1), DEC(-1, 10)), "equality-collapse")
erefuse("coll/set-vector-list-collapse", C, P_SORT, "[1] and (1) are equal under Clojure = on every host. See README AMBIGUITIES A6.",
        SET(V(I(1)), L(I(1))), "equality-collapse")
erefuse("coll/set-nan-payload-duplicate", C, P_SORT,
        "Two NaNs with different payloads normalize to the one canonical NaN: a duplicate introduced by normalization.",
        SET(F("7ff8000000000001"), F("7ff8000000000000")), "duplicate-element")
erefuse("coll/map-nan-payload-duplicate", C, P_SORT, "The map-key form of the NaN normalization duplicate.",
        M((F("7ff0000000000001"), I(1)), (F("fff8000000000000"), I(2))), "duplicate-key")
erefuse("coll/map-integer-width-duplicate", C, P_SORT + "; " + P_INT,
        "Integer 1 held small and held big: host width is not identity, so normalization makes the two keys one encoded key.",
        M((I(1), S("small")), (I(1, "big"), S("big"))), "duplicate-key")
erefuse("coll/set-float-decimal-collapse", C, P_SORT, "Float 1.0 and 1M are portably equal (kind ignored).",
        SET(F("3ff0000000000000"), DEC(0, 1)), "equality-collapse")

# --- list / vector and the named frames -----------------------------------------
FR = ["frames"]
canon("frame/vector-1-2", FR, P_LIST, "A plain CBOR array is a vector.", V(I(1), I(2)),
      distinct=["sequence-kind/1-2"], expect="820102")
canon("frame/list-1-2", FR, P_LIST, "tag 27 [\"dao.jing/list\" [1 2]].", L(I(1), I(2)),
      eq="sequence-realization/1-2", distinct=["sequence-kind/1-2"],
      expect="d81b826d64616f2e6a696e672f6c697374820102")
canon("frame/seq-1-2", FR, P_LIST, "A realized non-list sequence (lazy seq, cons, range) encodes as the list frame.",
      SEQ(I(1), I(2)), eq="sequence-realization/1-2")
canon("frame/empty-list", FR, P_LIST, "Empty list frame.", L(), eq="metadata-stripped/empty-list",
      distinct=["sequence-kind/empty"], expect="d81b826d64616f2e6a696e672f6c69737480")
canon("frame/empty-seq", FR, P_LIST, "An empty finite sequence is the empty list, not nil. See README AMBIGUITIES A7.",
      SEQ(), eq="metadata-stripped/empty-list")
canon("frame/vector-1", FR, P_LIST, "[1].", V(I(1)), eq="metadata-stripped/vector-1",
      distinct=["sequence-kind/1", "retained-metadata/vector-1", "retained-metadata/tag"], expect="8101")
canon("frame/list-1", FR, P_LIST, "(1).", L(I(1)), distinct=["sequence-kind/1", "retained-metadata/list-1"])
canon("frame/nested-sequences", FR, P_LIST, "Vector of an empty list and an empty vector; a list of a vector.",
      V(L(), V(), L(V(I(0)))))
canon("frame/float64-1.5", FR + ["numerics"], P_FRAMES, "tag 27 [\"dao.jing/float64\" h'3ff8000000000000'].",
      F("3ff8000000000000"), expect="d81b827064616f2e6a696e672f666c6f6174363448" + "3ff8000000000000")
canon("frame/keyword-a", FR + ["identifiers"], P_ID, "tag 27 [\"dao.jing/keyword\" [nil \"a\"]].", k("a"),
      distinct=[PATH], expect="d81b827064616f2e6a696e672f6b6579776f726482f66161")
canon("frame/symbol-a", FR + ["identifiers"], P_ID, "tag 27 [\"dao.jing/symbol\" [nil \"a\"]].", SYM(None, "a"),
      distinct=[PATH], expect="d81b826f64616f2e6a696e672f73796d626f6c82f66161")
canon("frame/keyword-a-b", FR + ["identifiers"], P_ID, "(keyword \"a\" \"b\").", KW("a", "b"),
      distinct=[PATH], expect="d81b827064616f2e6a696e672f6b6579776f7264826161" + "6162")
canon("frame/symbol-a-b", FR + ["identifiers"], P_ID, "(symbol \"a\" \"b\").", SYM("a", "b"), distinct=[PATH])

MF = ["frames", "malformed"]
drefuse("frame/list-payload-map", MF, P_FRAMES, "dao.jing/list payload must be an array.",
        NAMED(LIST_NAME, R("a0")), "malformed-frame")
drefuse("frame/list-payload-text", MF, P_FRAMES, "dao.jing/list payload must be an array.",
        NAMED(LIST_NAME, T("12")), "malformed-frame")
drefuse("frame/list-payload-nil", MF, P_FRAMES, "dao.jing/list payload must be an array.",
        NAMED(LIST_NAME, R("f6")), "malformed-frame")
drefuse("frame/list-missing-payload", MF, P_FRAMES, "tag 27 over a one-element array [name].",
        R("d81b81", T(LIST_NAME)), "malformed-frame")
drefuse("frame/list-extra-element", MF, P_FRAMES,
        "tag 27 over [name payload extra]: the IANA flattened constructor-argument form is not Jing's shape.",
        R("d81b83", T(LIST_NAME), "80", "80"), "malformed-frame")
drefuse("frame/tag27-not-array", MF, P_FRAMES, "tag 27 over a bare text string.",
        R("d81b", T(LIST_NAME)), "malformed-frame")
drefuse("frame/name-as-bytes", MF, P_FRAMES, "Frame name as a byte string, not text.",
        R("d81b824d", LIST_NAME.encode("ascii"), "80"), "malformed-frame")
drefuse("frame/keyword-one-component", MF, P_FRAMES, "Keyword payload [\"a\"] has one component.",
        NAMED(KEYWORD_NAME, R("81", T("a"))), "malformed-frame")
drefuse("frame/keyword-three-components", MF, P_FRAMES, "Keyword payload with three components.",
        NAMED(KEYWORD_NAME, R("83f6", T("a"), T("b"))), "malformed-frame")
drefuse("frame/keyword-ns-integer", MF, P_FRAMES, "Namespace must be nil or text.",
        NAMED(KEYWORD_NAME, R("8201", T("a"))), "malformed-frame")
drefuse("frame/keyword-ns-bytes", MF, P_FRAMES, "Namespace must be nil or text, not a byte string.",
        NAMED(KEYWORD_NAME, R("824161", T("a"))), "malformed-frame")
drefuse("frame/keyword-name-nil", MF, P_FRAMES, "Name must be text; nil is not a name.",
        NAMED(KEYWORD_NAME, R("82", T("a"), "f6")), "malformed-frame")
drefuse("frame/keyword-name-bytes", MF, P_FRAMES, "Name must be text, not a byte string.",
        NAMED(KEYWORD_NAME, R("82f64161")), "malformed-frame")
drefuse("frame/keyword-payload-slash-text", MF, P_FRAMES, "Slash-joined text is exactly what the frame replaces; never parse it.",
        NAMED(KEYWORD_NAME, T("a/b")), "malformed-frame")
drefuse("frame/keyword-payload-map", MF, P_FRAMES, "Keyword payload as a map.",
        NAMED(KEYWORD_NAME, R("a1", T("ns"), T("a"))), "malformed-frame")
drefuse("frame/symbol-one-component", MF, P_FRAMES, "Symbol payload [\"a\"] has one component.",
        NAMED(SYMBOL_NAME, R("81", T("a"))), "malformed-frame")
drefuse("frame/symbol-name-integer", MF, P_FRAMES, "Symbol name must be text.",
        NAMED(SYMBOL_NAME, R("82f6182a")), "malformed-frame")
drefuse("frame/symbol-payload-nil", MF, P_FRAMES, "Symbol payload nil.",
        NAMED(SYMBOL_NAME, R("f6")), "malformed-frame")
drefuse("frame/float64-seven-bytes", MF, P_FRAMES, "float64 payload must be exactly eight bytes.",
        NAMED(FLOAT64_NAME, R("47 3ff00000000000")), "malformed-frame")
drefuse("frame/float64-nine-bytes", MF, P_FRAMES, "float64 payload must be exactly eight bytes.",
        NAMED(FLOAT64_NAME, R("49 3ff000000000000000")), "malformed-frame")
drefuse("frame/float64-empty-bytes", MF, P_FRAMES, "float64 payload must be exactly eight bytes.",
        NAMED(FLOAT64_NAME, R("40")), "malformed-frame")
drefuse("frame/float64-four-bytes", MF, P_FRAMES, "A float32-width payload is not widened on the wire.",
        NAMED(FLOAT64_NAME, R("44 3f800000")), "malformed-frame")
drefuse("frame/float64-native-float-payload", MF, P_FRAMES, "Payload is a native CBOR float, not a byte string.",
        NAMED(FLOAT64_NAME, R("fb3ff0000000000000")), "malformed-frame")
drefuse("frame/float64-integer-payload", MF, P_FRAMES, "Payload is an integer.",
        NAMED(FLOAT64_NAME, R("01")), "malformed-frame")
drefuse("frame/float64-array-payload", MF, P_FRAMES, "Payload is an array of eight integers.",
        NAMED(FLOAT64_NAME, R("88 18 3f 18 f0 00 00 00 00 00 00")), "malformed-frame")
drefuse("frame/float64-noncanonical-nan", MF + ["numerics"], P_FLOAT,
        "A NaN payload other than 7ff8000000000000 re-encodes differently.",
        F64RAW("7ff8000000000001"), "non-canonical")
drefuse("frame/float64-negative-nan", MF + ["numerics"], P_FLOAT, "Sign-bit NaN fff8000000000000 is not the canonical NaN.",
        F64RAW("fff8000000000000"), "non-canonical")
drefuse("frame/name-non-shortest-length", MF, P_REJ, "Frame name text with a non-shortest length head (78 0d).",
        R("d81b82780d", LIST_NAME.encode("ascii"), "80"), "non-canonical")
drefuse("frame/unknown-name-char", MF, P_FRAMES, "A name outside Jing's closed vocabulary.",
        NAMED("dao.jing/char", T("a")), "unknown-frame-name")
drefuse("frame/unknown-name-case", MF, P_FRAMES, "Names match exactly; dao.jing/List is not dao.jing/list.",
        NAMED("dao.jing/List", R("80")), "unknown-frame-name")
drefuse("frame/unknown-name-stream-list", MF, P_FRAMES, "The stream profile's list frame is not a Jing frame.",
        NAMED("dao.stream/list", R("82f680")), "unknown-frame-name")
drefuse("frame/unknown-name-float32", MF, P_FRAMES, "There is no float32 frame: float32 widens to float64.",
        NAMED("dao.jing/float32", R("443f800000")), "unknown-frame-name")

# --- metadata -----------------------------------------------------------------
MD = ["metadata"]
canon("meta/empty-vector-empty-meta", MD, P_META, "Empty metadata is omitted.", V(meta=M()),
      eq="metadata-stripped/empty-vector")
canon("meta/empty-vector-reader-meta", MD, P_META, "Reader-position-only metadata is stripped, then omitted.",
      V(meta=READER_META), eq="metadata-stripped/empty-vector")
canon("meta/empty-map-empty-meta", MD, P_META, "Empty metadata is omitted.", M(meta=M()), eq="metadata-stripped/empty-map")
canon("meta/empty-map-reader-meta", MD, P_META, "Reader-position-only metadata is omitted.", M(meta=READER_META),
      eq="metadata-stripped/empty-map")
canon("meta/empty-set-empty-meta", MD, P_META, "Empty metadata is omitted.", SET(meta=M()), eq="metadata-stripped/empty-set")
canon("meta/empty-set-reader-meta", MD, P_META, "Reader-position-only metadata is omitted.", SET(meta=READER_META),
      eq="metadata-stripped/empty-set")
canon("meta/empty-list-empty-meta", MD, P_META, "Empty metadata is omitted.", L(meta=M()), eq="metadata-stripped/empty-list")
canon("meta/empty-list-reader-meta", MD, P_META, "Reader-position-only metadata is omitted.", L(meta=READER_META),
      eq="metadata-stripped/empty-list")
canon("meta/vector-1-empty-meta", MD, P_META, "Empty metadata is omitted.", V(I(1), meta=M()), eq="metadata-stripped/vector-1")
canon("meta/vector-1-reader-meta", MD, P_META, "All four reader-position keys are stripped.", V(I(1), meta=READER_META),
      eq="metadata-stripped/vector-1")
canon("meta/vector-1-line-only", MD, P_META, "A single :line key is stripped.", V(I(1), meta=M((k("line"), I(1)))),
      eq="metadata-stripped/vector-1")
canon("meta/vector-1-doc", MD, P_META,
      "tag 27 [\"clojure/with-meta\" [{:doc \"d\"} [1]]]: metadata wraps the value.",
      V(I(1), meta=DOC_META), eq="metadata-stripped/vector-1-doc", distinct=["retained-metadata/vector-1"],
      expect="d81b8271636c6f6a7572652f776974682d6d65746182a1d81b827064616f2e6a696e672f6b6579776f726482f663646f6361648101")
canon("meta/vector-1-doc-and-reader", MD, P_META, "Reader keys are stripped; :doc is retained.",
      V(I(1), meta=M((k("line"), I(3)), (k("doc"), S("d")), (k("column"), I(4)))), eq="metadata-stripped/vector-1-doc")
canon("meta/vector-1-tag", MD, P_META, "User :tag metadata is retained, never stripped.",
      V(I(1), meta=M((k("tag"), SYM(None, "String")))), distinct=["retained-metadata/tag"])
canon("meta/vector-1-qualified-line", MD, P_META, "Only unqualified :line is a reader key; :a/line is retained.",
      V(I(1), meta=M((KW("a", "line"), I(1)))), distinct=["retained-metadata/vector-1"])
canon("meta/vector-1-string-line-key", MD, P_META, "The string key \"line\" is not a reader key.",
      V(I(1), meta=M((S("line"), I(1)))), distinct=["retained-metadata/vector-1"])
canon("meta/vector-1-nested-line", MD, P_META, "Stripping is top-level only: {:m {:line 3}} keeps the inner :line.",
      V(I(1), meta=M((k("m"), M((k("line"), I(3)))))), distinct=["retained-metadata/vector-1"])
canon("meta/vector-1-meta-value-with-meta", MD, P_META,
      "A metadata value that itself carries metadata is encoded recursively; its reader keys are stripped too. See AMBIGUITIES A5.",
      V(I(1), meta=M((k("v"), V(I(2), meta=M((k("line"), I(1)), (k("k"), I(1))))))))
canon("meta/vector-1-list-in-meta", MD + ["frames"], P_META, "Lists inside metadata use the list frame.",
      V(I(1), meta=M((k("forms"), L(I(1), I(2))))))
canon("meta/list-1-doc", MD + ["frames"], P_META,
      "with-meta wraps the list frame: [meta (list frame)]. See AMBIGUITIES A4.",
      L(I(1), meta=DOC_META), distinct=["retained-metadata/list-1"])
canon("meta/map-plain", MD, P_META, "{:a 1}.", M((k("a"), I(1))), distinct=["retained-metadata/map"])
canon("meta/map-doc", MD, P_META, "{:a 1} with {:doc \"d\"}.", M((k("a"), I(1)), meta=DOC_META), distinct=["retained-metadata/map"])
canon("meta/set-plain", MD, P_META, "#{:a}.", SET(k("a")), distinct=["retained-metadata/set"])
canon("meta/set-doc", MD, P_META, "#{:a} with {:doc \"d\"}.", SET(k("a"), meta=DOC_META), distinct=["retained-metadata/set"])
canon("meta/symbol-x", MD + ["identifiers"], P_META, "Symbol x.", SYM(None, "x"),
      eq="metadata-stripped/symbol-x", distinct=["retained-metadata/symbol-x", PATH])
canon("meta/symbol-x-reader-meta", MD + ["identifiers"], P_META, "Symbol reader positions are stripped.",
      SYM(None, "x", meta=READER_META), eq="metadata-stripped/symbol-x")
canon("meta/symbol-x-doc", MD + ["identifiers"], P_META,
      "Symbol metadata is address-significant (retires the dao.jing.md residual).",
      SYM(None, "x", meta=DOC_META), distinct=["retained-metadata/symbol-x"])
canon("meta/map-a-b-doc", MD + ["collections"], P_META + "; " + P_SORT, "{:a 1 :b 2} with {:doc \"d\"}.",
      M((k("a"), I(1)), (k("b"), I(2)), meta=DOC_META), eq="sortedness/map-a-b-doc")
canon("meta/sorted-map-b-a-doc", MD + ["collections"], P_META + "; " + P_SORT,
      "A sorted map carrying metadata: the sorted-map and metadata normalizations compose (with-meta over the plain-map encoding).",
      SM((k("b"), I(2)), (k("a"), I(1)), meta=DOC_META), eq="sortedness/map-a-b-doc")
canon("meta/sorted-set-reader-meta", MD + ["collections"], P_META + "; " + P_SORT,
      "A sorted set with reader-position-only metadata: sorted-set normalization and stripping compose to the plain set.",
      SSET(I(3), I(1), I(2), meta=READER_META), eq="sortedness/set-1-2-3")
erefuse("meta/meta-with-own-meta", MD, P_META,
        "A metadata map that itself carries metadata: one value has one metadata map (A3/A10 ruling).",
        V(I(1), meta=M((k("a"), I(1)), meta=DOC_META)), "malformed-frame")
erefuse("meta/surrogate-under-stripped-line", MD + ["unicode"], P_STR + "; " + P_META,
        "Validate before strip (A9 ruling): a lone surrogate under :line is refused even though :line would be stripped.",
        V(I(1), meta=M((k("line"), S(U16("d800"))))), "unpaired-surrogate")
erefuse("meta/surrogate-in-meta-value", MD + ["unicode"], P_STR, "Unpaired surrogate inside a metadata value.",
        V(I(1), meta=M((k("doc"), S(U16("d800"))))), "unpaired-surrogate")
erefuse("meta/surrogate-in-meta-key", MD + ["unicode", "identifiers"], P_STR, "Unpaired surrogate inside a metadata keyword key.",
        V(I(1), meta=M((KW(None, U16("0061", "dc80")), I(1)))), "unpaired-surrogate")
erefuse("meta/surrogate-in-symbol-meta", MD + ["unicode"], P_STR, "Unpaired surrogate in symbol metadata.",
        SYM(None, "x", meta=M((k("doc"), S(U16("dfff"))))), "unpaired-surrogate")

WM = lambda payload: NAMED(WITH_META_NAME, payload)
DOC_META_RAW = R("a1", KWRAW(None, "doc"), T("d"))
MM = ["metadata", "malformed"]
drefuse("meta/decode-empty-meta", MM, P_META, "An encoder omits empty metadata, so a wire with-meta over {} is non-canonical.",
        WM(R("82a08101")), "non-canonical")
drefuse("meta/decode-reader-meta", MM, P_META, "An encoder strips :line, so a wire :line is non-canonical.",
        WM(R("82a1", KWRAW(None, "line"), "01", "8101")), "non-canonical")
drefuse("meta/decode-meta-not-map", MM, P_META, "Metadata must be a map.", WM(R("82 8101 8101")), "malformed-frame")
drefuse("meta/decode-meta-on-string", MM, P_META, "Strings carry no metadata.", WM(R("82", DOC_META_RAW, T("s"))), "malformed-frame")
drefuse("meta/decode-meta-on-keyword", MM, P_META, "Keywords carry no metadata.",
        WM(R("82", DOC_META_RAW, KWRAW(None, "k"))), "malformed-frame")
drefuse("meta/decode-meta-on-integer", MM, P_META, "Integers carry no metadata.", WM(R("82", DOC_META_RAW, "01")), "malformed-frame")
drefuse("meta/decode-nested-with-meta", MM, P_META, "with-meta directly around with-meta: one value has one metadata map.",
        WM(R("82", DOC_META_RAW, WM(R("82a1", KWRAW(None, "b"), "01", "8101")))), "malformed-frame")
drefuse("meta/decode-payload-three", MM, P_META, "with-meta payload must be exactly [meta value].",
        WM(R("83", DOC_META_RAW, "8101", "01")), "malformed-frame")
drefuse("meta/decode-flattened-form", MM, P_META, "tag 27 [\"clojure/with-meta\" meta value] (flattened) is not Boring's [name [meta value]] shape.",
        R("d81b83", T(WITH_META_NAME), DOC_META_RAW, "8101"), "malformed-frame")
drefuse("meta/decode-meta-with-own-meta", MM, P_META,
        "The metadata map is itself a with-meta frame over a map: metadata must be a bare map (A3/A10 ruling).",
        WM(R("82", WM(R("82", DOC_META_RAW, "a1", KWRAW(None, "a"), "01")), "8101")), "malformed-frame")
drefuse("meta/decode-invalid-utf8-under-line", MM + ["unicode"], P_STR + "; " + P_META,
        "Validate before strip (A9 ruling): an encoded surrogate under :line fails as invalid-utf8. Deliberately two defects (the :line key is also non-canonical) to pin that validation precedes the canonical check.",
        WM(R("82a1", KWRAW(None, "line"), "63eda080", "8101")), "invalid-utf8")

# --- unicode --------------------------------------------------------------------
UN = ["unicode"]
canon("uni/ascii", UN, P_STR, "Plain ASCII text.", S("hello"), expect="6568656c6c6f")
canon("uni/empty-string", UN, P_STR, "Empty text.", S(""), expect="60")
canon("uni/astral", UN, P_STR, "U+1F600 as four UTF-8 bytes.", S("\U0001F600"), eq="astral/u1f600", expect="64f09f9880")
canon("uni/astral-from-utf16-pair", UN, P_STR, "The same text given as a valid UTF-16 surrogate pair.",
      S(U16("d83d", "de00")), eq="astral/u1f600")
canon("uni/nonchar-fffe", UN, P_STR, "Noncharacter U+FFFE remains legal.", S("\ufffe"), expect="63efbfbe")
canon("uni/nonchar-ffff", UN, P_STR, "Noncharacter U+FFFF remains legal.", S("\uffff"))
canon("uni/nonchar-fdd0", UN, P_STR, "Noncharacter U+FDD0 remains legal.", S("\ufdd0"))
canon("uni/nonchar-1fffe", UN, P_STR, "Astral noncharacter U+1FFFE remains legal.", S("\U0001FFFE"))
canon("uni/nonchar-10ffff", UN, P_STR, "U+10FFFF, the last code point, remains legal.", S("\U0010FFFF"), expect="64f48fbfbf")
canon("uni/nul", UN, P_STR, "Embedded U+0000.", S("a\u0000b"), expect="63610062")
canon("uni/bom", UN, P_STR, "A leading U+FEFF is content, not a marker.", S("\ufeffa"))
canon("uni/composed-e-acute", UN, P_STR, "U+00E9.", S("\u00e9"), distinct=["no-normalization/e-acute"], expect="62c3a9")
canon("uni/decomposed-e-acute", UN, P_STR, "U+0065 U+0301: no NFC.", S("e\u0301"), distinct=["no-normalization/e-acute"],
      expect="6365cc81")
canon("uni/hangul-precomposed", UN, P_STR, "U+AC00.", S("\uac00"), distinct=["no-normalization/hangul"])
canon("uni/hangul-jamo", UN, P_STR, "U+1100 U+1161: no NFC.", S("\u1100\u1161"), distinct=["no-normalization/hangul"])
canon("uni/angstrom-sign", UN, P_STR, "U+212B ANGSTROM SIGN is not folded to U+00C5.", S("\u212b"),
      distinct=["no-normalization/angstrom"])
canon("uni/a-ring", UN, P_STR, "U+00C5.", S("\u00c5"), distinct=["no-normalization/angstrom"])
erefuse("uni/lone-low-mid", UN, P_STR, "\"a\\uDC80b\": the plan's named case.", S(U16("0061", "dc80", "0062")), "unpaired-surrogate")
erefuse("uni/lone-high-end", UN, P_STR, "Unpaired high surrogate at the end.", S(U16("0061", "d800")), "unpaired-surrogate")
erefuse("uni/lone-high-before-bmp", UN, P_STR, "High surrogate followed by a non-surrogate.", S(U16("d800", "0061")), "unpaired-surrogate")
erefuse("uni/reversed-pair", UN, P_STR, "Low then high is two unpaired surrogates.", S(U16("de00", "d83d")), "unpaired-surrogate")
erefuse("uni/surrogate-keyword-name", UN + ["identifiers"], P_STR, "In a keyword name.", KW(None, U16("d800")), "unpaired-surrogate")
erefuse("uni/surrogate-keyword-ns", UN + ["identifiers"], P_STR, "In a keyword namespace.", KW(U16("dc00"), "a"), "unpaired-surrogate")
erefuse("uni/surrogate-symbol-name", UN + ["identifiers"], P_STR, "In a symbol name.", SYM(None, U16("0078", "db80")), "unpaired-surrogate")
erefuse("uni/surrogate-map-key", UN, P_STR, "In a map key.", M((S(U16("dbff")), I(1))), "unpaired-surrogate")
erefuse("uni/surrogate-set-element", UN, P_STR, "In a set element.", SET(S(U16("dc00"))), "unpaired-surrogate")
UM = ["unicode", "malformed"]
drefuse("uni/invalid-utf8-bad-continuation", UM, P_STR, "C3 28 is not a valid sequence.", R("62c328"), "invalid-utf8")
drefuse("uni/invalid-utf8-overlong-nul", UM, P_STR, "C0 80 is an overlong NUL.", R("62c080"), "invalid-utf8")
drefuse("uni/invalid-utf8-encoded-surrogate", UM, P_STR, "ED A0 80 encodes U+D800 (CESU-8); not UTF-8.", R("63eda080"), "invalid-utf8")
drefuse("uni/invalid-utf8-truncated", UM, P_STR, "A lead byte with no continuation.", R("61c3"), "invalid-utf8")
drefuse("uni/invalid-utf8-above-max", UM, P_STR, "F4 90 80 80 is above U+10FFFF.", R("64f4908080"), "invalid-utf8")
drefuse("uni/invalid-utf8-keyword-name", UM + ["identifiers"], P_STR, "Invalid UTF-8 inside a keyword frame name component.",
        NAMED(KEYWORD_NAME, R("82f662c328")), "invalid-utf8")
drefuse("uni/invalid-utf8-map-key", UM, P_STR, "Invalid UTF-8 in a map key.", R("a162c32801"), "invalid-utf8")
drefuse("uni/invalid-utf8-metadata", UM + ["metadata"], P_STR, "Invalid UTF-8 in a metadata value.",
        WM(R("82a1", KWRAW(None, "doc"), "62c328", "8101")), "invalid-utf8")

# --- pathological identifiers ------------------------------------------------------
ID = ["identifiers"]
canon("id/symbol-42", ID, P_ID, "(symbol \"42\").", SYM(None, "42"), distinct=[PATH])
canon("id/int-42", ID + ["numerics"], P_ID, "Integer 42.", I(42), distinct=[PATH], expect="182a")
canon("id/string-42", ID, P_ID, "String \"42\".", S("42"), distinct=[PATH])
canon("id/keyword-42", ID, P_ID, "(keyword \"42\").", k("42"), distinct=[PATH])
canon("id/keyword-nil-ns-a-slash-b", ID, P_ID,
      "(keyword nil \"a/b\"): ns nil, name \"a/b\". Construct from components; a one-argument constructor that parses the slash gives id/... a-b instead.",
      KW(None, "a/b"), distinct=[PATH])
canon("id/symbol-nil-ns-a-slash-b", ID, P_ID, "(symbol nil \"a/b\").", SYM(None, "a/b"), distinct=[PATH])
canon("id/keyword-ns-a-slash-b-name-c", ID, P_ID, "ns \"a/b\", name \"c\".", KW("a/b", "c"), distinct=[PATH])
canon("id/keyword-ns-a-name-b-slash-c", ID, P_ID, "ns \"a\", name \"b/c\"; slash-joined text would collide with the previous case.",
      KW("a", "b/c"), distinct=[PATH])
canon("id/symbol-ns-a-slash-b-name-c", ID, P_ID, "Symbol ns \"a/b\", name \"c\".", SYM("a/b", "c"), distinct=[PATH])
canon("id/symbol-ns-a-name-b-slash-c", ID, P_ID, "Symbol ns \"a\", name \"b/c\"; slash-joined text would collide with the previous case.",
      SYM("a", "b/c"), distinct=[PATH])
canon("id/keyword-whitespace", ID, P_ID, "Name with an inner space.", k("a b"), distinct=[PATH])
canon("id/keyword-leading-space", ID, P_ID, "Name with a leading space.", k(" a"), distinct=[PATH])
canon("id/keyword-newline", ID, P_ID, "Name with a newline.", k("a\nb"), distinct=[PATH])
canon("id/symbol-whitespace", ID, P_ID, "Symbol name with an inner space.", SYM(None, "a b"), distinct=[PATH])
canon("id/keyword-leading-colon", ID, P_ID, "(keyword \":a\"): name \":a\", not ::a or :a.", k(":a"), distinct=[PATH])
canon("id/symbol-leading-colon", ID, P_ID, "(symbol \":a\"): a colon-leading symbol, which tag 39 text would confuse with :a.",
      SYM(None, ":a"), distinct=[PATH])
canon("id/keyword-empty-ns", ID, P_ID, "ns \"\" (empty), name \"a\"; distinct from ns nil.", KW("", "a"), distinct=[PATH])
canon("id/symbol-empty-ns", ID, P_ID, "ns \"\" (empty), name \"a\".", SYM("", "a"), distinct=[PATH])
canon("id/symbol-slash", ID, P_ID, "The symbol / (ns nil, name \"/\").", SYM(None, "/"), distinct=[PATH])
canon("id/symbol-ns-a-name-slash", ID, P_ID, "ns \"a\", name \"/\" (like clojure.core//).", SYM("a", "/"), distinct=[PATH])
canon("id/string-a-slash-b", ID, P_ID, "The string \"a/b\" is not an identifier.", S("a/b"), distinct=[PATH])
canon("id/string-colon-a", ID, P_ID, "The string \":a\".", S(":a"), distinct=[PATH])
canon("id/symbol-nil", ID, P_ID, "(symbol \"nil\") is not nil.", SYM(None, "nil"), distinct=[PATH])
canon("id/nil", ID, P_ID, "nil.", NIL, distinct=[PATH], expect="f6")
canon("id/symbol-true", ID, P_ID, "(symbol \"true\") is not true.", SYM(None, "true"), distinct=[PATH])
canon("id/true", ID, P_ID, "true.", TRUE, distinct=[PATH], expect="f5")
canon("id/false", ID, P_ID, "false.", FALSE, expect="f4")
canon("id/keyword-empty-name", ID, P_ID, "(keyword \"\"): empty name, accepted (A8 ruling).", k(""), distinct=[PATH])
canon("id/symbol-empty-name", ID, P_ID, "(symbol \"\"): empty name, accepted (A8 ruling).", SYM(None, ""), distinct=[PATH])
IM = ["identifiers", "malformed"]
drefuse("id/tag39-keyword-ordinary", IM, P_ID, "Boring's native :a (tag 39 \":a\"); rejected even for an ordinary name.",
        R("d827623a61"), "identifier-tag-39")
drefuse("id/tag39-keyword-qualified", IM, P_ID, "Boring's native :a/b.", R("d827643a612f62"), "identifier-tag-39")
drefuse("id/tag39-symbol", IM, P_ID, "Boring's native symbol a.", R("d8276161"), "identifier-tag-39")
drefuse("id/tag39-map-key", IM, P_ID, "Tag 39 nested as a map key.", R("a1d827623a6101"), "identifier-tag-39")
drefuse("id/tag39-in-vector", IM, P_ID, "Tag 39 nested in a vector.", R("81d8276161"), "identifier-tag-39")
drefuse("id/tag39-in-metadata", IM + ["metadata"], P_ID, "Tag 39 as a metadata key.",
        WM(R("82a1d827643a646f6361648101")), "identifier-tag-39")

# --- byte strings ----------------------------------------------------------------
BY = ["bytes"]
canon("bytes/empty", BY, P_BYTES, "Empty byte string.", B(""), expect="40")
canon("bytes/00ff", BY, P_BYTES, "Two bytes.", B("00ff"), expect="4200ff")
canon("bytes/content-a", BY, P_BYTES,
      "Content identity: a fresh host array with these bytes. J1/J2 must build it separately from bytes/content-b, mutate each host array after encoding and after decoding, and see no change in bytes or address.",
      B("0102030405"), eq="bytes-content/0102030405")
canon("bytes/content-b", BY, P_BYTES, "A second, separately allocated array with equal content: same address.",
      B("0102030405"), eq="bytes-content/0102030405")
canon("bytes/h61", BY, P_BYTES, "Byte string h'61'.", B("61"), distinct=["bytes-vs-text/a"])
canon("bytes/text-a", BY + ["unicode"], P_BYTES, "Text \"a\" differs from bytes h'61'.", S("a"), distinct=["bytes-vs-text/a"])
canon("bytes/vector-97", BY, P_BYTES, "The vector [97] differs from bytes h'61'.", V(I(97)), distinct=["bytes-vs-text/a"])
canon("bytes/in-set", BY + ["collections"], P_BYTES + "; " + P_SORT, "Byte-string set elements sort bytewise.", SET(B("01"), B("00")))
canon("bytes/length-24", BY, P_BYTES, "24 bytes need a one-byte length argument (58 18).", B("00" * 24))
drefuse("bytes/indefinite", BY + ["malformed"], P_REJ, "Indefinite-length byte string.", R("5f41014102ff"), "non-canonical")

# --- numerics --------------------------------------------------------------------
N = ["numerics"]
INTS = [
    (0, "00"), (1, "01"), (23, "17"), (24, "1818"), (255, "18ff"), (256, "190100"), (65535, "19ffff"),
    (65536, "1a00010000"), ((1 << 32) - 1, "1affffffff"), (1 << 32, "1b0000000100000000"),
    ((1 << 53) - 1, "1b001fffffffffffff"), (1 << 53, "1b0020000000000000"), ((1 << 53) + 1, "1b0020000000000001"),
    ((1 << 63) - 1, "1b7fffffffffffffff"), (1 << 63, "1b8000000000000000"), ((1 << 64) - 1, "1bffffffffffffffff"),
    (1 << 64, "c249010000000000000000"), ((1 << 64) + 1, "c249010000000000000001"), (10 ** 30, None),
    (-1, "20"), (-24, "37"), (-25, "3818"), (-256, "38ff"), (-257, "390100"), (-(1 << 53) + 1, "3b001ffffffffffffe"),
    (-(1 << 63), "3b7fffffffffffffff"), (-(1 << 63) - 1, "3b8000000000000000"), (-(1 << 64), "3bffffffffffffffff"),
    (-(1 << 64) - 1, "c349010000000000000000"), (-(10 ** 30), None),
]
INT_DISTINCT = {0: ["numeric-kind/zero"], 1: ["numeric-kind/one"], 1 << 53: ["numeric-kind/2^53"]}
INT_EQ = {1: "integer-width/1", (1 << 63) - 1: "integer-width/2^63-1", -(1 << 63): "integer-width/-2^63"}
for n, hx in INTS:
    canon("num/int/%d" % n, N, P_INT, "Integer %d: shortest head, or a minimal bignum beyond 64 bits." % n, I(n),
          eq=INT_EQ.get(n), distinct=INT_DISTINCT.get(n, []), expect=hx)
canon("num/int-big/1", N, P_INT, "1 held in a host big-integer type (BigInt, BigInteger, Dart BigInt).", I(1, "big"),
      eq="integer-width/1")
canon("num/int-big/2^63-1", N, P_INT, "2^63-1 held in a host big-integer type.", I((1 << 63) - 1, "big"),
      eq="integer-width/2^63-1")
canon("num/int-big/-2^63", N, P_INT, "-2^63 held in a host big-integer type.", I(-(1 << 63), "big"), eq="integer-width/-2^63")
canon("num/int/3", N, P_KIND, "Integer 3.", I(3), distinct=["numeric-kind/three"], expect="03")

canon("num/float64/1.0", N, P_KIND, "Float 1.0 differs from integer 1.", F("3ff0000000000000"), distinct=["numeric-kind/one"])
canon("num/float64/3.0", N, P_KIND, "Float 3.0.", F("4008000000000000"), distinct=["numeric-kind/three"])
canon("num/float64/0.5", N, P_KIND, "Float 0.5.", F("3fe0000000000000"), distinct=["numeric-kind/half"])
canon("num/float64/2^53", N, P_KIND, "Float 2^53 differs from integer 2^53.", F("4340000000000000"), distinct=["numeric-kind/2^53"])
canon("num/float64/0.0", N, P_FLOAT, "+0.0.", F("0000000000000000"), eq="float32-widening/zero",
      distinct=["signed-zero/float64", "numeric-kind/zero"])
canon("num/float64/-0.0", N, P_FLOAT, "-0.0 keeps its sign.", F("8000000000000000"), eq="float32-widening/neg-zero",
      distinct=["signed-zero/float64", "numeric-kind/zero"])
canon("num/float64/+inf", N, P_FLOAT, "+Infinity.", F("7ff0000000000000"), eq="float32-widening/pos-inf",
      distinct=["infinities"])
canon("num/float64/-inf", N, P_FLOAT, "-Infinity.", F("fff0000000000000"), eq="float32-widening/neg-inf",
      distinct=["infinities"])
canon("num/float64/nan-canonical", N, P_FLOAT, "The one canonical NaN 7ff8000000000000.", F("7ff8000000000000"),
      eq="canonical-nan", distinct=["infinities"])
canon("num/float64/nan-payload", N, P_FLOAT, "A quiet NaN with payload canonicalizes.", F("7ff8000000000001"), eq="canonical-nan")
canon("num/float64/nan-negative", N, P_FLOAT, "A sign-bit NaN canonicalizes.", F("fff8000000000000"), eq="canonical-nan")
canon("num/float64/nan-signaling", N, P_FLOAT, "A signaling NaN canonicalizes.", F("7ff0000000000001"), eq="canonical-nan")
canon("num/float32/nan-quiet", N, P_FLOAT, "A float32 quiet NaN canonicalizes.", F32("7fc00000"), eq="canonical-nan")
canon("num/float32/nan-negative-signaling", N, P_FLOAT, "A float32 negative signaling NaN canonicalizes.", F32("ff800001"),
      eq="canonical-nan")
canon("num/float64/0.1", N, P_FLOAT, "float64 0.1 = 3fb999999999999a.", F("3fb999999999999a"), distinct=["float32-vs-float64/0.1"])
canon("num/float32/0.1", N, P_FLOAT, "Host float32 0.1 (3dcccccd) widens exactly to 3fb99999a0000000.", F32("3dcccccd"),
      eq="float32-widening/0.1", distinct=["float32-vs-float64/0.1"],
      expect="d81b827064616f2e6a696e672f666c6f61743634483fb99999a0000000")
canon("num/float64/widened-0.1", N, P_FLOAT, "The float64 whose value is the widened float32 0.1.", F("3fb99999a0000000"),
      eq="float32-widening/0.1")
canon("num/float32/0.0", N, P_FLOAT, "float32 +0.0 widens to float64 +0.0.", F32("00000000"), eq="float32-widening/zero")
canon("num/float32/-0.0", N, P_FLOAT, "float32 -0.0 widens to float64 -0.0.", F32("80000000"), eq="float32-widening/neg-zero")
canon("num/float32/+inf", N, P_FLOAT, "float32 +Infinity widens.", F32("7f800000"), eq="float32-widening/pos-inf")
canon("num/float32/-inf", N, P_FLOAT, "float32 -Infinity widens.", F32("ff800000"), eq="float32-widening/neg-inf")
canon("num/float32/min-subnormal", N, P_FLOAT, "The smallest float32 subnormal widens to the normal float64 36a0000000000000.",
      F32("00000001"), eq="float32-widening/min-subnormal")
canon("num/float64/widened-min-subnormal", N, P_FLOAT, "The float64 equal to the widened float32 subnormal.",
      F("36a0000000000000"), eq="float32-widening/min-subnormal")
canon("num/float64/min-subnormal", N, P_FLOAT, "The smallest float64 subnormal.", F("0000000000000001"))
canon("num/float64/max-finite", N, P_FLOAT, "The largest finite float64.", F("7fefffffffffffff"))
canon("num/float64/-2.5", N, P_FLOAT, "-2.5.", F("c004000000000000"))
canon("num/float64/1e300", N, P_FLOAT, "1e300, which no narrower float could carry anyway.", F("7e37e43c8800759c"))

canon("num/decimal/1", N, P_DEC, "1M = tag 4 [0 1].", DEC(0, 1), distinct=["decimal-scale/one", "numeric-kind/one"], expect="c4820001")
canon("num/decimal/1.0", N, P_DEC, "1.0M = tag 4 [-1 10]; scale retained.", DEC(-1, 10), distinct=["decimal-scale/one"], expect="c482200a")
canon("num/decimal/1.00", N, P_DEC, "1.00M = tag 4 [-2 100].", DEC(-2, 100), distinct=["decimal-scale/one"], expect="c482211864")
canon("num/decimal/0", N, P_DEC, "0M.", DEC(0, 0), distinct=["decimal-scale/zero", "numeric-kind/zero"], expect="c4820000")
canon("num/decimal/0.0", N, P_DEC, "0.0M.", DEC(-1, 0), distinct=["decimal-scale/zero"])
canon("num/decimal/0.00", N, P_DEC, "0.00M.", DEC(-2, 0), distinct=["decimal-scale/zero"])
canon("num/decimal/1E+1", N, P_DEC, "1E+1 (positive exponent, negative scale).", DEC(1, 1), distinct=["decimal-scale/ten"])
canon("num/decimal/10", N, P_DEC, "10M.", DEC(0, 10), distinct=["decimal-scale/ten"])
canon("num/decimal/0.5", N, P_DEC, "0.5M.", DEC(-1, 5), distinct=["numeric-kind/half"])
canon("num/decimal/3.14", N, P_DEC, "3.14M.", DEC(-2, 314))
canon("num/decimal/-1.5", N, P_DEC, "-1.5M: negative mantissa.", DEC(-1, -15), expect="c482202e")
canon("num/decimal/bignum-mantissa", N, P_DEC, "Mantissa 10^40 needs tag 2 inside tag 4.", DEC(-30, 10 ** 40))
canon("num/decimal/negative-bignum-mantissa", N, P_DEC, "Mantissa -10^40 needs tag 3 inside tag 4.", DEC(-30, -(10 ** 40)))
canon("num/decimal/exponent-100", N, P_DEC, "Exponent 100.", DEC(100, 7))

canon("num/ratio/1-2", N, P_RAT, "1/2 = tag 30 [1 2].", RAT(1, 2), eq="reduced-ratio/1-2", distinct=["numeric-kind/half"],
      expect="d81e820102")
canon("num/ratio/2-4", N, P_RAT, "2/4 reduces to 1/2.", RAT(2, 4), eq="reduced-ratio/1-2")
canon("num/ratio/50-100", N, P_RAT, "50/100 reduces to 1/2.", RAT(50, 100), eq="reduced-ratio/1-2")
canon("num/ratio/-1-2", N, P_RAT, "-1/2: sign on the numerator.", RAT(-1, 2), eq="reduced-ratio/-1-2", expect="d81e822002")
canon("num/ratio/1-neg2", N, P_RAT, "1/-2 normalizes to -1/2 (positive denominator).", RAT(1, -2), eq="reduced-ratio/-1-2")
canon("num/ratio/-2-4", N, P_RAT, "-2/4 reduces to -1/2.", RAT(-2, 4), eq="reduced-ratio/-1-2")
canon("num/ratio/1-1", N, P_RAT, "1/1 keeps rational kind (denominator 1 retained).", RAT(1, 1), distinct=["numeric-kind/one"],
      expect="d81e820101")
canon("num/ratio/3-1", N, P_RAT, "3/1 keeps rational kind.", RAT(3, 1), eq="reduced-ratio/3-1", distinct=["numeric-kind/three"])
canon("num/ratio/6-2", N, P_RAT, "6/2 reduces to 3/1, still a ratio.", RAT(6, 2), eq="reduced-ratio/3-1")
canon("num/ratio/0-1", N, P_RAT, "0/1.", RAT(0, 1), eq="reduced-ratio/0-1", distinct=["numeric-kind/zero"], expect="d81e820001")
canon("num/ratio/0-5", N, P_RAT, "0/5 reduces to 0/1.", RAT(0, 5), eq="reduced-ratio/0-1")
canon("num/ratio/0-neg3", N, P_RAT, "0/-3 reduces to 0/1.", RAT(0, -3), eq="reduced-ratio/0-1")
canon("num/ratio/bignum-parts", N, P_RAT, "(2^64+1)/2^64: bignums inside tag 30.", RAT((1 << 64) + 1, 1 << 64))
canon("num/ratio/negative-bignum", N, P_RAT, "-(2^64+1)/3.", RAT(-((1 << 64) + 1), 3))
erefuse("num/ratio-zero-denominator", N, P_RAT, "Denominator 0 has no rational.", RAT(1, 0), "malformed-number")
erefuse("num/js-unsafe-integer", N, P_INT,
        "Node only: an integral JavaScript Number beyond 2^53 is refused; callers pass BigInt or the float64 carrier. N/A on JVM and Dart.",
        HOST("js-unsafe-integer", "9007199254740993"), "unsupported-value")
NM = ["numerics", "malformed"]
drefuse("num/native-half-float", NM, P_REJ, "Native float16 1.0.", R("f93c00"), "native-float")
drefuse("num/native-single-float", NM, P_REJ, "Native float32 1.0.", R("fa3f800000"), "native-float")
drefuse("num/native-double-float", NM, P_REJ, "Native float64 1.0.", R("fb3ff0000000000000"), "native-float")
drefuse("num/native-half-nan", NM, P_REJ, "Native float16 NaN (Boring's canonical NaN).", R("f97e00"), "native-float")
drefuse("num/native-half-zero", NM, P_REJ, "Native float16 0.0 (a decoder might turn it into an integer).", R("f90000"), "native-float")
drefuse("num/native-float-in-vector", NM, P_REJ, "Native float nested in a vector.", R("81fb3ff8000000000000"), "native-float")
drefuse("num/bignum-in-int-range", NM, P_INT, "tag 2 h'01' where integer 01 fits.", R("c24101"), "non-canonical")
drefuse("num/negative-bignum-in-int-range", NM, P_INT, "tag 3 h'00' is -1, which fits major type 1.", R("c34100"), "non-canonical")
drefuse("num/bignum-2^64-1", NM, P_INT, "2^64-1 as a bignum; it fits major type 0.", R("c248ffffffffffffffff"), "non-canonical")
drefuse("num/bignum-leading-zero", NM, P_INT, "2^64 with a leading zero byte in its magnitude.", R("c24a0001", "0000000000000000"), "non-canonical")
drefuse("num/bignum-empty-magnitude", NM, P_INT, "tag 2 h'' is zero, which fits major type 0.", R("c240"), "non-canonical")
drefuse("num/bignum-payload-integer", NM, P_INT, "Bignum payload must be a byte string.", R("c201"), "malformed-number")
drefuse("num/bignum-payload-text", NM, P_INT, "Bignum payload must be a byte string, not text.", R("c26131"), "malformed-number")
drefuse("num/decimal-payload-integer", NM, P_DEC, "tag 4 payload must be [exponent mantissa].", R("c401"), "malformed-number")
drefuse("num/decimal-three-elements", NM, P_DEC, "tag 4 payload with three elements.", R("c483000101"), "malformed-number")
drefuse("num/decimal-bignum-exponent", NM, P_DEC, "The exponent must be a major type 0/1 integer, never a bignum.",
        R("c482c249010000000000000000", "01"), "malformed-number")
drefuse("num/decimal-float-mantissa", NM, P_DEC, "The mantissa must be an integer.", R("c48200", F64RAW("3ff0000000000000")),
        "malformed-number")
drefuse("num/decimal-bignum-mantissa-in-range", NM, P_DEC, "Mantissa 1 as a bignum.", R("c48200c24101"), "non-canonical")
drefuse("num/ratio-zero-denominator-wire", NM, P_RAT, "tag 30 [1 0].", R("d81e820100"), "malformed-number")
drefuse("num/ratio-negative-denominator", NM, P_RAT, "tag 30 [1 -2]: the denominator must be positive.", R("d81e820121"), "malformed-number")
drefuse("num/ratio-unreduced", NM, P_RAT, "tag 30 [2 4] is not reduced.", R("d81e820204"), "non-canonical")
drefuse("num/ratio-float-denominator", NM, P_RAT, "Denominator as a float64 frame.", R("d81e8201", F64RAW("4000000000000000")),
        "malformed-number")
drefuse("num/ratio-one-element", NM, P_RAT, "tag 30 [1].", R("d81e8101"), "malformed-number")

# --- malformed and non-canonical encodings -------------------------------------------
MA = ["malformed"]
drefuse("mal/empty-input", MA, P_REJ, "Zero bytes are not a CBOR item.", R(""), "malformed-cbor")
drefuse("mal/truncated-uint", MA, P_REJ, "19 announces two argument bytes; one follows.", R("1901"), "malformed-cbor")
drefuse("mal/truncated-array", MA, P_REJ, "82 announces two elements; one follows.", R("8201"), "malformed-cbor")
drefuse("mal/truncated-text", MA, P_REJ, "63 announces three bytes; two follow.", R("636162"), "malformed-cbor")
drefuse("mal/reserved-additional-info", MA, P_REJ, "Additional information 28 is reserved.", R("1c"), "malformed-cbor")
drefuse("mal/lone-break", MA, P_REJ, "A break byte outside any indefinite item.", R("ff"), "malformed-cbor")
drefuse("mal/indefinite-integer", MA, P_REJ, "Major type 0 has no indefinite form.", R("1f"), "malformed-cbor")
drefuse("mal/two-byte-simple-below-32", MA, P_REJ, "f8 18: two-byte simple values below 32 are not well-formed.", R("f818"), "malformed-cbor")
drefuse("mal/trailing-integer", MA, P_REJ, "Two items: 1 then 1.", R("0101"), "trailing-data")
drefuse("mal/trailing-nil", MA, P_REJ, "nil followed by a zero byte.", R("f600"), "trailing-data")
drefuse("mal/trailing-array", MA, P_REJ, "Two empty arrays.", R("8080"), "trailing-data")
drefuse("mal/map-keys-descending", MA + ["collections"], P_SORT, "{\"b\" 1 \"a\" 2} with \"b\" first.", R("a2616201616102"), "non-canonical")
drefuse("mal/map-keys-length-first", MA + ["collections"], P_SORT,
        "{\"a\" 1, 1000 2} in RFC 7049 length-first order (\"a\" first): Jing requires bytewise order (1000 first).",
        R("a2616101", "1903e802"), "non-canonical")
drefuse("mal/set-unsorted", MA + ["collections"], P_SORT, "#{1 2} written 2 then 1.", R("d90102820201"), "non-canonical")
drefuse("mal/set-length-first", MA + ["collections"], P_SORT, "#{\"a\" 1000} in length-first order.", R("d90102826161", "1903e8"),
        "non-canonical")
drefuse("mal/indefinite-array", MA, P_REJ, "Indefinite-length array.", R("9f0102ff"), "non-canonical")
drefuse("mal/indefinite-map", MA, P_REJ, "Indefinite-length map.", R("bf616101ff"), "non-canonical")
drefuse("mal/indefinite-text", MA, P_REJ, "Indefinite-length text.", R("7f61616162ff"), "non-canonical")
drefuse("mal/non-shortest-uint-1", MA + ["numerics"], P_REJ, "1 as 18 01.", R("1801"), "non-canonical")
drefuse("mal/non-shortest-uint-23", MA + ["numerics"], P_REJ, "23 as 18 17.", R("1817"), "non-canonical")
drefuse("mal/non-shortest-uint-2", MA + ["numerics"], P_REJ, "1 as 19 0001.", R("190001"), "non-canonical")
drefuse("mal/non-shortest-uint-4", MA + ["numerics"], P_REJ, "1 as 1a 00000001.", R("1a00000001"), "non-canonical")
drefuse("mal/non-shortest-uint-8", MA + ["numerics"], P_REJ, "1 as 1b 0000000000000001.", R("1b0000000000000001"), "non-canonical")
drefuse("mal/non-shortest-nint", MA + ["numerics"], P_REJ, "-1 as 38 00.", R("3800"), "non-canonical")
drefuse("mal/non-shortest-text-length", MA, P_REJ, "\"a\" as 78 01 61.", R("780161"), "non-canonical")
drefuse("mal/non-shortest-bytes-length", MA, P_REJ, "h'01' as 58 01 01.", R("580101"), "non-canonical")
drefuse("mal/non-shortest-array-length", MA, P_REJ, "[1] as 98 01 01.", R("980101"), "non-canonical")
drefuse("mal/non-shortest-map-length", MA, P_REJ, "{1 1} as b8 01 01 01.", R("b8010101"), "non-canonical")
drefuse("mal/non-shortest-tag", MA, P_REJ, "tag 30 is d8 1e; here written d9 001e.", R("d9001e820102"), "non-canonical")
drefuse("mal/duplicate-map-key", MA + ["collections"], P_SORT, "{1 1, 1 2}.", R("a201010102"), "duplicate-key")
drefuse("mal/duplicate-keyword-key", MA + ["collections"], P_SORT, "{:a 1, :a 2}.",
        R("a2", KWRAW(None, "a"), "01", KWRAW(None, "a"), "02"), "duplicate-key")
drefuse("mal/duplicate-set-element", MA + ["collections"], P_SORT, "#{1 1}.", R("d90102820101"), "duplicate-element")
drefuse("mal/collapse-int-float-keys", MA + ["collections"], P_SORT, "{1 0, 1.0 0}: numerically equal keys of different kinds.",
        R("a20100", F64RAW("3ff0000000000000"), "00"), "equality-collapse")
drefuse("mal/collapse-vector-list-set", MA + ["collections"], P_SORT, "#{[1] (1)}: equal under decoded equality.",
        R("d90102828101", NAMED(LIST_NAME, R("8101"))), "equality-collapse")
drefuse("mal/collapse-signed-zero-set", MA + ["collections"], P_SORT, "#{0.0 -0.0}.",
        R("d9010282", F64RAW("0000000000000000"), F64RAW("8000000000000000")), "equality-collapse")
drefuse("mal/collapse-int-ratio-set", MA + ["collections"], P_SORT, "#{1 1/1}.", R("d901028201d81e820101"), "equality-collapse")
drefuse("mal/collapse-decimal-scale-set", MA + ["collections"], P_SORT, "#{1M 1.0M}.", R("d9010282c4820001c482200a"),
        "equality-collapse")
drefuse("mal/collapse-float-decimal-set", MA + ["collections"], P_SORT, "#{1.0 1M}.",
        R("d9010282c4820001", F64RAW("3ff0000000000000")), "equality-collapse")
drefuse("mal/unknown-tag-epoch", MA, P_REJ, "tag 1 epoch time.", R("c11a00000000"), "unknown-tag")
drefuse("mal/unknown-tag-date-string", MA, P_REJ, "tag 0 date/time text (#inst).", R("c0", T("2026-09-22T00:00:00Z")), "unknown-tag")
drefuse("mal/unknown-tag-uuid", MA, P_REJ, "tag 37 UUID (#uuid).", R("d82550", "00" * 16), "unknown-tag")
drefuse("mal/unknown-tag-1000", MA, P_REJ, "An unregistered numeric tag Boring would surface as TaggedValue.", R("d903e801"), "unknown-tag")
drefuse("mal/unknown-tag-self-describe", MA, P_REJ, "tag 55799 self-described CBOR prefix.", R("d9d9f701"), "unknown-tag")
drefuse("mal/unknown-tag-shared-ref", MA, P_REJ, "tag 28 shareable value.", R("d81c01"), "unknown-tag")
drefuse("mal/simple-undefined", MA, P_REJ, "undefined (simple 23).", R("f7"), "unsupported-simple")
drefuse("mal/simple-0", MA, P_REJ, "simple(0).", R("e0"), "unsupported-simple")
drefuse("mal/simple-19", MA, P_REJ, "simple(19).", R("f3"), "unsupported-simple")
drefuse("mal/simple-32", MA, P_REJ, "simple(32), two-byte form.", R("f820"), "unsupported-simple")

# --- effective Boring options ------------------------------------------------------------
BO = ["boring-options"]
canon("opt/repeated-strings", BO, P_OPTS,
      "Three equal strings stay three plain text items; stringref would emit tag 256 around the array and tag 25 references.",
      V(S("abc"), S("abc"), S("abc")), expect="83636162636361626363616263")
canon("opt/repeated-keywords", BO + ["identifiers"], P_OPTS,
      "The frame name \"dao.jing/keyword\" repeats verbatim in every frame; stringref would dedupe it.",
      V(k("k"), k("k"), k("k")))
canon("opt/repeated-map-shapes", BO, P_OPTS,
      "Maps sharing one key set stay independent maps; shapes would emit one shape and positional values.",
      V(M((k("a"), I(1)), (k("b"), I(2))), M((k("a"), I(3)), (k("b"), I(4))), M((k("a"), I(5)), (k("b"), I(6)))))
canon("opt/single-item", BO, P_OPTS,
      "A nested value is exactly one top-level item with no index frame after it: the bytes end where the item ends.",
      M((k("xs"), V(I(1), I(2), I(3))), (k("m"), M((S("k"), S("v"))))))
drefuse("opt/stringref-namespace", BO + ["malformed"], P_OPTS, "A stringref-encoded payload: tag 256 over [\"abc\" ref(0)].",
        R("d901008263616263d81900"), "unknown-tag")
drefuse("opt/stringref-reference", BO + ["malformed"], P_OPTS, "A bare tag 25 string reference.", R("d81900"), "unknown-tag")
drefuse("opt/trailing-index-like-item", BO + ["malformed"], P_OPTS,
        "A value followed by a second item (where an index frame would sit) is trailing data.",
        R("8101", "a1", T("index"), "00"), "trailing-data")

# --- unsupported host values ----------------------------------------------------------------
UV = ["unsupported-values"]
erefuse("host/character", UV, P_DOMAIN, "A host character (\\a): no slot in the supported domain.", HOST("char", "a"), "unsupported-value")
erefuse("host/instant", UV, P_DOMAIN, "#inst.", HOST("inst", "2026-09-22T00:00:00Z"), "unsupported-value")
erefuse("host/uuid", UV, P_DOMAIN, "#uuid.", HOST("uuid", "00000000-0000-0000-0000-000000000000"), "unsupported-value")
erefuse("host/function", UV, P_DOMAIN, "A function.", HOST("fn", "identity"), "unsupported-value")
erefuse("host/record", UV, P_DOMAIN, "An arbitrary record instance.", HOST("record", "{:a 1}"), "unsupported-value")
erefuse("host/nested-in-vector", UV, P_DOMAIN, "An unsupported value nested inside a supported collection.",
        V(I(1), HOST("char", "a")), "unsupported-value")


# =============================================================================
# Build, self-verify, emit
# =============================================================================


def build():
    ids = [c["id"] for c in CASES]
    dupes = sorted({i for i in ids if ids.count(i) > 1})
    assert not dupes, "duplicate case ids %s" % dupes
    out = []
    for c in CASES:
        cid = c["id"]
        hexs = sha = None
        if c["kind"] == "canonical":
            b = enc(c["input"])
            if c["_expect"] is not None:
                assert b.hex() == c["_expect"], "%s: %s != expected %s" % (cid, b.hex(), c["_expect"])
            again = jing_decode(b)
            assert enc(again) == b, "%s: round trip" % cid
            hexs, sha = b.hex(), sha256_hex(b)
        elif c["kind"] == "encode-refusal":
            try:
                enc(c["input"])
            except Refusal as r:
                assert r.cls == c["refusal"], "%s: refused %s, declared %s" % (cid, r.cls, c["refusal"])
            else:
                raise AssertionError("%s: encoded" % cid)
        else:
            b = c["_raw"]
            try:
                jing_decode(b)
            except Refusal as r:
                assert r.cls == c["refusal"], "%s: refused %s, declared %s" % (cid, r.cls, c["refusal"])
            else:
                raise AssertionError("%s: accepted" % cid)
            hexs, sha = b.hex(), sha256_hex(b)
        cats = list(c["categories"])
        if c["distinct"] and "injectivity" not in cats:
            cats.append("injectivity")
        out.append({"id": cid, "kind": c["kind"], "categories": cats, "input": c["input"],
                    "hex": hexs, "sha256": sha, "refusal": c["refusal"],
                    "equivalence": c["equivalence"], "distinct": c["distinct"],
                    "plan": c["plan"], "notes": c["notes"]})
    verify_groups(out)
    return out


def verify_groups(cases):
    canon_cases = [c for c in cases if c["kind"] == "canonical"]
    eq = {}
    for c in canon_cases:
        if c["equivalence"]:
            eq.setdefault(c["equivalence"], []).append(c)
    for g, members in eq.items():
        assert len(members) >= 2, "equivalence group %s has one member" % g
        assert len({m["hex"] for m in members}) == 1, "equivalence group %s differs" % g
    ds = {}
    for c in canon_cases:
        for g in c["distinct"]:
            ds.setdefault(g, []).append(c)
    for g, members in ds.items():
        assert len(members) >= 2, "distinct group %s has one member" % g
        hx = [m["hex"] for m in members]
        assert len(set(hx)) == len(hx), "distinct group %s collides" % g
    by_hex = {}
    for c in canon_cases:
        by_hex.setdefault(c["hex"], []).append(c)
    for hx, members in by_hex.items():
        groups = {m["equivalence"] for m in members}
        assert len(members) == 1 or (len(groups) == 1 and None not in groups), \
            "equal bytes outside one equivalence group: %s" % [m["id"] for m in members]
    for cat in REQUIRED_CATEGORIES:
        assert any(cat in c["categories"] for c in cases), "category %s uncovered" % cat


def render(cases):
    top = [
        ("format", "dao.jing.cbor-fixtures"),
        ("version", 1),
        ("contract", "DaoJing canonical CBOR v1"),
        ("plan", {"path": PLAN_PATH, "commit": PLAN_COMMIT, "tree": TREE_COMMIT}),
        ("pins", PINS),
        ("generator", "test/resources/dao/jing/cbor-v1.generate.py"),
        ("frame-names", [LIST_NAME, KEYWORD_NAME, SYMBOL_NAME, FLOAT64_NAME, WITH_META_NAME]),
        ("refusal-classes", REFUSAL_CLASSES),
        ("required-categories", REQUIRED_CATEGORIES),
    ]
    lines = ["{"]
    for key, value in top:
        lines.append(" %s: %s," % (json.dumps(key), json.dumps(value, ensure_ascii=True)))
    lines.append(' "cases": [')
    for i, c in enumerate(cases):
        sep = "," if i + 1 < len(cases) else ""
        lines.append("  " + json.dumps(c, ensure_ascii=True) + sep)
    lines.append(" ]")
    lines.append("}")
    return "\n".join(lines) + "\n"


# =============================================================================
# --inspect: the Jing-agnostic reader over the committed corpus
# =============================================================================

TAG_NAMES = {0: "date/time text", 1: "epoch time", 2: "positive bignum", 3: "negative bignum",
             4: "decimal fraction", 25: "stringref", 27: "named object", 28: "shareable",
             30: "rational", 37: "uuid", 39: "identifier", 256: "stringref namespace",
             258: "set", 55799: "self-described cbor"}
SIMPLE_NAMES = {20: "false", 21: "true", 22: "null", 23: "undefined"}


def describe(item, depth, lines):
    pad = "    " + "  " * depth
    fl = ""
    if item.flags:
        fl = "  [" + ", ".join(sorted(item.flags)) + "]"
    k = item.kind
    if k in ("uint", "nint"):
        lines.append("%s%s %d%s" % (pad, k, item.value, fl))
    elif k == "bytes":
        lines.append("%sbytes(%d) h'%s'%s" % (pad, len(item.value), item.value.hex(), fl))
    elif k == "text":
        lines.append("%stext(%d) %s%s" % (pad, len(item.value.encode("utf-8")), json.dumps(item.value), fl))
    elif k == "array":
        lines.append("%sarray(%d)%s" % (pad, len(item.value), fl))
        for x in item.value:
            describe(x, depth + 1, lines)
    elif k == "map":
        raws = [a.raw for a, _ in item.value]
        if len(set(raws)) != len(raws):
            order = "DUPLICATE KEYS"
        elif raws == sorted(raws):
            order = "keys in bytewise order"
        else:
            order = "keys NOT in bytewise order"
        lines.append("%smap(%d) %s%s" % (pad, len(item.value), order, fl))
        for a, b in item.value:
            lines.append(pad + "  key:")
            describe(a, depth + 2, lines)
            lines.append(pad + "  value:")
            describe(b, depth + 2, lines)
    elif k == "tag":
        tag, inner = item.value
        label = TAG_NAMES.get(tag, "unregistered here")
        extra = ""
        if tag == 27 and inner.kind == "array" and inner.value and inner.value[0].kind == "text":
            extra = " name=%s" % json.dumps(inner.value[0].value)
        if tag == 258 and inner.kind == "array":
            raws = [x.raw for x in inner.value]
            extra = " elements in bytewise order" if raws == sorted(raws) and len(set(raws)) == len(raws) \
                else " elements NOT strictly in bytewise order"
        lines.append("%stag %d (%s)%s%s" % (pad, tag, label, extra, fl))
        describe(inner, depth + 1, lines)
    elif k == "simple":
        lines.append("%ssimple %d (%s)" % (pad, item.value, SIMPLE_NAMES.get(item.value, "unassigned")))
    else:
        width, bits = item.value
        lines.append("%snative float%d 0x%0*x" % (pad, width, width // 4, bits))


def inspect(path):
    with open(path, "r", encoding="ascii") as f:
        doc = json.load(f)
    for c in doc["cases"]:
        header = "%s  [%s]" % (c["id"], c["kind"])
        if c["refusal"]:
            header += "  expect refusal: %s" % c["refusal"]
        print(header)
        if c["hex"] is None:
            print("    (encode-side case: no bytes)")
            continue
        buf = bytes.fromhex(c["hex"])
        print("    hex %s" % (c["hex"] or "(empty)"))
        print("    sha256 %s" % c["sha256"])
        lines = []
        try:
            if not buf:
                raise Refusal("malformed-cbor", "empty input")
            item, pos = read_item(buf, 0)
            describe(item, 0, lines)
            if pos != len(buf):
                lines.append("    TRAILING %d bytes after the first item" % (len(buf) - pos))
        except Refusal as r:
            lines.append("    READER ERROR %s" % r)
        print("\n".join(lines))


def main(argv):
    if "--inspect" in argv:
        inspect(CORPUS_PATH)
        return 0
    text = render(build())
    if "--check" in argv:
        with open(CORPUS_PATH, "r", encoding="ascii") as f:
            committed = f.read()
        if committed != text:
            sys.stderr.write("cbor-v1.json differs from the generator output\n")
            return 1
        sys.stderr.write("cbor-v1.json matches the generator output byte for byte\n")
        return 0
    sys.stdout.write(text)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
