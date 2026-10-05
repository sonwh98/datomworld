"""Generator of test/resources/yang/python/int-contract-v1.txt.

Provenance, like dao/jing/cbor-v1.generate.py: run from the repository
root with CPython 3.9.6 to rewrite the fixture. Every column is
computed here by CPython and by the documented rules of yin.vm.integer
version 2 and dao.jing.cbor, independently of the Clojure code; the
Clojure renderer in yang.python.antlr.int-contract-fixtures must
reproduce this text byte for byte on every host.
"""
import re
import struct
import sys

assert sys.version.split()[0] == "3.9.6", sys.version

OUT = "test/resources/yang/python/int-contract-v1.txt"

PROFILES = [("wide", 4096, 4300), ("small", 60, 5)]

INTS = ["0", "1", "-1", "2^31-1", "2^31", "2^31+1", "2^32",
        "2^53-1", "2^53", "2^53+1", "-2^53+1", "-2^53", "-2^53-1",
        "2^62", "2^63-1", "2^63", "2^63+1", "-2^63+1", "-2^63", "-2^63-1",
        "2^64-1", "2^64", "2^64+1", "-2^64+1", "-2^64", "-2^64-1",
        "2^100", "10^30", "2^1074", "2^1075", "2^2000"]

FLOATS = ["f:2^53", "f:2^63"]

OPS = [
    ("wide", "shift-left", "1", "4095"),
    ("wide", "shift-left", "1", "4096"),
    ("wide", "shift-left", "-1", "4095"),
    ("wide", "shift-left", "-1", "4096"),
    ("wide", "mul", "2^2048-1", "2^2048-1"),
    ("wide", "mul", "2^2048", "2^2048-1"),
    ("wide", "mul", "2^2048", "2^2048"),
    ("wide", "mul", "2^4095", "-2"),
    ("wide", "add", "2^4095", "2^4095-1"),
    ("wide", "add", "2^4096-1", "1"),
    ("wide", "sub", "-2^4096+1", "1"),
    ("wide", "pow", "2", "4095"),
    ("wide", "pow", "2", "4096"),
    ("wide", "format", "2^4096-1"),
    ("wide", "format16", "2^4096-1"),
    ("wide", "parse", "10^1233"),
    ("wide", "parse", "10^1234"),
    ("wide", "parse", "10^4299"),
    ("wide", "parse", "10^4300"),
    ("wide", "parse16", "2^4096-1"),
    ("wide", "parse16", "2^4096"),
    ("small", "shift-left", "1", "59"),
    ("small", "shift-left", "1", "60"),
    ("small", "shift-left", "-1", "59"),
    ("small", "shift-left", "-1", "60"),
    ("small", "mul", "2^30-1", "2^30-1"),
    ("small", "mul", "2^30", "2^30-1"),
    ("small", "mul", "2^30", "2^30"),
    ("small", "add", "2^59", "2^59-1"),
    ("small", "add", "2^60-1", "1"),
    ("small", "sub", "-2^60+1", "1"),
    ("small", "pow", "2", "59"),
    ("small", "pow", "2", "60"),
    ("small", "format", "99999"),
    ("small", "format", "100000"),
    ("small", "format", "-99999"),
    ("small", "format", "-100000"),
    ("small", "format16", "2^60-1"),
    ("small", "parse", "99999"),
    ("small", "parse", "100000"),
    ("small", "parse16", "2^60-1"),
    ("small", "parse16", "2^60"),
]

DSL = re.compile(r"^(-?)(\d+)(?:\^(\d+))?(?:([+-])(\d+))?$")


def value(name):
    m = DSL.match(name)
    assert m, name
    sign, base, exp, op, d = m.groups()
    n = int(base) ** int(exp) if exp else int(base)
    n = -n if sign else n
    if op:
        n = n + int(d) if op == "+" else n - int(d)
    return n


def bits(n):
    return abs(n).bit_length()


def head(major, arg):
    if arg < 24:
        return bytes([major << 5 | arg])
    for ai, width in ((24, 1), (25, 2), (26, 4), (27, 8)):
        if arg < 1 << (8 * width):
            return bytes([major << 5 | ai]) + arg.to_bytes(width, "big")
    raise ValueError(arg)


def magnitude(n):
    return n.to_bytes(max(1, (n.bit_length() + 7) // 8), "big")


def cbor_int(n):
    if 0 <= n < 2 ** 64:
        return head(0, n)
    if -(2 ** 64) <= n < 0:
        return head(1, -1 - n)
    if n > 0:
        m = magnitude(n)
        return b"\xc2" + head(2, len(m)) + m
    m = magnitude(-1 - n)
    return b"\xc3" + head(2, len(m)) + m


def cbor_float(x):
    name = b"dao.jing/float64"
    return (b"\xd8\x1b\x82" + head(3, len(name)) + name
            + head(2, 8) + struct.pack(">d", x))


def hex_text(n):
    return format(n, "x")


def digits(n):
    return len(str(abs(n)))


def over(n, max_bits):
    return "bit-limit" if bits(n) > max_bits else "value"


def square(n, max_bits):
    if 2 * bits(n) - 1 > max_bits:
        return "bit-limit"
    return over(n * n, max_bits)


def shl_max(n, max_bits):
    if n == 0:
        return "any"
    if bits(n) > max_bits:
        return "none"
    return str(max_bits - bits(n))


def outcomes(n, max_bits, max_digits):
    fmt = "digit-limit" if digits(n) > max_digits else "value"
    return " ".join([
        "normalize=" + over(n, max_bits),
        "inc=" + over(n + 1, max_bits),
        "dec=" + over(n - 1, max_bits),
        "square=" + square(n, max_bits),
        "format=" + fmt,
        "format16=value",
        "shl=" + shl_max(n, max_bits)])


def op_outcome(op, args, max_bits, max_digits):
    a = value(args[0])
    if op == "shift-left":
        k = int(args[1])
        if a == 0:
            return "value"
        return "bit-limit" if bits(a) + k > max_bits else "value"
    if op == "mul":
        b = value(args[1])
        if bits(a) + bits(b) - 1 > max_bits:
            return "bit-limit"
        return over(a * b, max_bits)
    if op == "add":
        return over(a + value(args[1]), max_bits)
    if op == "sub":
        return over(a - value(args[1]), max_bits)
    if op == "pow":
        e = int(args[1])
        if e == 0 or a in (0, 1, -1):
            return "value"
        if e > max_bits or (bits(a) - 1) * e + 1 > max_bits:
            return "bit-limit"
        return over(a ** e, max_bits)
    if op == "format":
        return "digit-limit" if digits(a) > max_digits else "value"
    if op == "format16":
        return "value"
    if op == "parse":
        return "digit-limit" if digits(a) > max_digits else over(a, max_bits)
    if op == "parse16":
        return over(a, max_bits)
    raise ValueError(op)


def main():
    out = ["int-contract-v1",
           "generator CPython " + sys.version.split()[0]]
    for name, max_bits, max_digits in PROFILES:
        out.append("profile %s max-bits %d max-digits %d"
                   % (name, max_bits, max_digits))
    for name in INTS:
        n = value(name)
        out.append("%s bits %d" % (name, bits(n)))
        out.append("%s dec %s" % (name, str(n)))
        out.append("%s hex %s" % (name, hex_text(n)))
        out.append("%s hash %d" % (name, hash(n)))
        out.append("%s key %s 1" % (name, hex_text(n)))
        out.append("%s cbor %s" % (name, cbor_int(n).hex()))
        for profile, max_bits, max_digits in PROFILES:
            out.append("%s %s %s"
                       % (name, profile, outcomes(n, max_bits, max_digits)))
    for name in FLOATS:
        x = float(value(name[2:]))
        n = int(x)
        assert n == value(name[2:])
        out.append("%s dec %s" % (name, str(n)))
        out.append("%s hash %d" % (name, hash(x)))
        out.append("%s key %s 1" % (name, hex_text(n)))
        out.append("%s cbor %s" % (name, cbor_float(x).hex()))
        out.append("%s is-int %s" % (name, "true" if x is n else "false"))
        out.append("%s eq-int %s" % (name, "true" if x == n else "false"))
    limits = {p: (b, d) for p, b, d in PROFILES}
    for row in OPS:
        profile, op, args = row[0], row[1], row[2:]
        out.append("op %s %s %s %s"
                   % (profile, op, " ".join(args),
                      op_outcome(op, args, *limits[profile])))
    with open(OUT, "w") as f:
        f.write("\n".join(out) + "\n")


main()
