"""Run from the repo root using CPython 3.9.6; measure S4 conversions."""
import math
import struct
import sys
from pathlib import Path

assert sys.version_info[:3] == (3, 9, 6), sys.version
OPS = ('int_str', 'int_float', 'float_int', 'str_int', 'hex', 'oct',
       'bin', 'round1', 'round_int', 'abs', 'pow_float', 'zdiv')
rows = []


def token(x):
    if x is None:
        return '_'
    if isinstance(x, str):
        return 's:' + ','.join(format(ord(c), 'x') for c in x)
    if isinstance(x, float):
        return 'f:' + struct.pack('>d', x).hex()
    if isinstance(x, bool):
        return str(x)
    return str(x)


def measure(op, a, b=None):
    assert op in OPS
    try:
        result = {
            'int_str': lambda: int(a, b),
            'int_float': lambda: int(a),
            'float_int': lambda: float(a),
            'str_int': lambda: str(a),
            'hex': lambda: hex(a),
            'oct': lambda: oct(a),
            'bin': lambda: bin(a),
            'round1': lambda: round(a),
            'round_int': lambda: round(a, b),
            'abs': lambda: abs(a),
            'pow_float': lambda: pow(a, b),
            'zdiv': lambda: {
                0: lambda: a / 0.0,
                1: lambda: a // 0.0,
                2: lambda: a % 0.0,
                3: lambda: divmod(a, 0.0),
            }[b](),
        }[op]()
        tail = [token(result)]
    except Exception as e:
        message = str(e)
        assert message.isascii() and '\t' not in message, message
        tail = ['!' + type(e).__name__, message]
    rows.append('\t'.join([op, token(a), token(b)] + tail))


texts = ['', ' ', '\t\n', '0', '00', '010', '0_0', '000_1',
         '+1', '-1', '+', '-', '1_0', '_1', '1_', '1__0', '0x_f',
         '0XFF', '0o_7', '0b_1', '0x', '0x__f', '0x_f_',
         '0b2', '0o8', 'a', 'Z', '1.0', '1e2', " 1' ", '1"',
         '\\', '\x00', '  -12  ']
for s in texts:
    for base in (0, 2, 8, 10, 16, 36):
        measure('int_str', s, base)
for base in range(2, 37):
    for s in ('10', '-10', 'z', '1_0', '1__0'):
        measure('int_str', s, base)
for base in (-1, 1, 37, 2**80, -(2**80), True, False, 2.5, None):
    measure('int_str', '10', base)
# Independently measure int's whitespace, including the disputed 28..31.
spaces = list(range(9, 14)) + list(range(28, 33))
spaces += [133, 160, 5760] + list(range(8192, 8203))
spaces += [8232, 8233, 8239, 8287, 12288, 8203, 65279]
for c in spaces:
    for s in (chr(c) + '1' + chr(c), chr(c), '1' + chr(c)):
        measure('int_str', s, 10)
for n in (53, 63, 80, 256, 1023, 1024):
    for delta in (-1, 0, 1):
        for sign in (-1, 1):
            x = sign * (2**n + delta)
            for op in ('float_int', 'str_int', 'hex', 'oct', 'bin', 'abs'):
                measure(op, x)
for n in (0, 1, -1, True, False):
    for op in ('float_int', 'str_int', 'hex', 'oct', 'bin', 'abs'):
        measure(op, n)
bits = [0, 1, 0x000fffffffffffff, 0x0010000000000000,
        0x3fe0000000000000, 0x3ff0000000000000,
        0x4004000000000000, 0x400c000000000000,
        0x4340000000000000, 0x7fefffffffffffff,
        0x7ff0000000000000, 0x7ff8000000000000]
for raw in bits:
    for sign in (0, 1 << 63):
        x = struct.unpack('>d', (raw | sign).to_bytes(8, 'big'))[0]
        for op in ('int_float', 'round1', 'abs'):
            measure(op, x)
for x in (-351, -350, -250, -150, -50, -1, 0, 1, 50,
          150, 250, 350, 351, 2**80 + 1):
    for ndigits in (-4, -3, -2, -1, 0, 1, 80):
        measure('round_int', x, ndigits)
edge = 2**1024 - 2**970
for e in (edge - 1, edge, edge + 1, 2**1024):
    for sign in (-1, 1):
        for x in (0, 1, 2, 1.0, -1.0):
            # Positive integer powers of huge exponents need allocation;
            # those resource limits are profile pins, not CPython rows.
            if sign < 0 or isinstance(x, float):
                measure('pow_float', x, sign * e)
for x in (-2.0, -1.0, -0.0, 0.0, 0.5, 1.0, 2.0,
          math.inf, -math.inf, math.nan):
    for e in (-math.inf, math.inf, math.nan, -2.0, 0.0, 2.0, 2**53):
        measure('pow_float', x, e)
for x in (2, 2.0):
    for e in (-1075, -1074, -1073, 1023, 1024):
        measure('pow_float', x, e)
for x in (1.0, -1.0, math.inf, math.nan):
    for op in range(4):
        measure('zdiv', x, op)
Path('test/resources/yang/python/int-conv-v1.txt').write_text(
    'int-conv-v1\nCPython 3.9.6\n' + '\n'.join(dict.fromkeys(rows)) + '\n')
print(len(set(rows)), 'measured rows')
