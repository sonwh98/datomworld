"""Run from the repo root using CPython 3.9.6: the C3 S7 gate corpus.

Writes each program's source and its stdout. A `cpython` program is run
here; a `hand` program is never run and its stdout is pinned below:
CPython 3.9.6 has none of this support profile's limits (the bit, digit
and item limits of `limits`, the 60 bits and 5 digits of `small`), so
those outputs are the profile's, not CPython's.
"""
import contextlib
import io
import sys
from pathlib import Path

assert sys.version_info[:3] == (3, 9, 6), sys.version

# (name, origin, profile, source, pinned stdout of a hand program)
PROGRAMS = [
    ('promotion', 'cpython', 'wide', """\
a = 2 ** 53
b = a + 1
print(b, b - a, b - 1 == a)
x = 9007199254740991
print(x + 1, x + 2, x + 2 - 2)
y = 2 ** 63 - 1
print(y + 1, y + 1 - 1 == y, -y - 2)
n = 1
for i in range(70):
    n = n * 2
print(n, n // 2 ** 69, n - n)
m = n
while m > 1:
    m = m // 2
print(m, n * n // n == n)
print(3 * 2 ** 62, 2 ** 62 * 3 - 2 ** 63)
s = 0
for k in range(5):
    s = s + 2 ** 64
print(s, s - 5 * 2 ** 64)
print(-(2 ** 63) - 1 + 1, (2 ** 64 + 1) * (2 ** 64 - 1))
""",
     None),
    ('demotion', 'cpython', 'wide', """\
big = 2 ** 64
z = big - big
zero = 0
print(z, z == 0, z is zero, -z, z + 1)
d = {0: 'zero', 1: 'one'}
print(d[z], d[(big + 1) - big], d[big // big])
d[big - 1 - (big - 2)] = 'uno'
print(d)
s = {z, 0, 0.0, big - big}
print(len(s), s)
print(hash(z), hash(big), hash(big - big + 2 ** 61 - 1))
print(z in [0], [z] == [0], (z,) == (0,), {z: 1}[0])
t = big * 3
print(t // 3 - big, t % 3, (t // big) * 7)
""",
     None),
    ('keys', 'cpython', 'wide', """\
d = {}
d[1] = 'a'
d[1.0] = 'b'
d[True] = 'c'
print(d)
d[2 ** 64] = 'e'
d[18446744073709551616.0] = 'f'
print(d)
print(hash(-1), hash(-2), hash(-1.0), hash(2 ** 61 - 1), hash(2 ** 61))
print(hash(-(2 ** 61)), hash(2 ** 64), hash(-(2 ** 64)))
print(hash(0.5), hash(1.5), hash(-0.5), hash(1e100))
k = {0.5: 'half', 2 ** 53 + 1: 'odd', 9007199254740992.0: 'even'}
print(k[1 / 2], 2 ** 53 + 1 in k, 2 ** 53 in k, k[2 ** 53])
print(k)
e = {2 ** 53: 'a', 2 ** 53 + 1: 'b', 2 ** 64: 'c', 2 ** 64 + 1: 'd'}
print(len(e), e[2 ** 64 + 1], 18446744073709551617 in e)
s = {-1, -2, -1.0}
print(len(s), -1.0 in s, -2.0 in s, -3 in s)
""",
     None),
    ('divmod', 'cpython', 'wide', """\
for a in (7, -7):
    for b in (2, -2):
        print(a // b, a % b, divmod(a, b))
big = 2 ** 64
print(divmod(big + 1, 3), divmod(-big - 1, 3))
print(divmod(big, -7), divmod(-big, -7))
print(-2 ** 64 // 10 ** 10, (-2) ** 63 % 10 ** 9)
print(7.5 // 2, -7.5 // 2, 7.5 % -2, divmod(-7.5, 2))
print(-big // 3, -big % 3, big // -3, big % -3)
print((big + 1) // (big + 2), -(big + 1) // (big + 2), -1 // big)
""",
     None),
    ('shifts', 'cpython', 'wide', """\
print(1 >> 2 ** 70, -1 >> 2 ** 70, 2 ** 100 >> 2 ** 70)
print(-(2 ** 100) >> 2 ** 70, 3 << 0, 0 >> 2 ** 70)
print(1 << 64, -1 << 64, 2 ** 64 >> 1, -(2 ** 64) >> 63, 5 >> 1, -5 >> 1)
try:
    print(1 << -1)
except ValueError as e:
    print('ValueError', e.args)
try:
    print(1 >> -(2 ** 70))
except ValueError as e:
    print('ValueError', e.args)
print(1 << 100 >> 99, (2 ** 64 + 1) >> 64, -(2 ** 64 + 1) >> 64)
print(~(2 ** 64), ~-(2 ** 64), 2 ** 64 & -1, 2 ** 64 | 1, 2 ** 64 ^ 2 ** 64)
print(-(2 ** 64) >> 2 ** 64, 2 ** 64 >> 2 ** 64, 2 ** 64 >> 64)
""",
     None),
    ('power', 'cpython', 'wide', """\
print(2 ** 64, (-2) ** 63, 3 ** 40, (-3) ** 41)
print(2 ** -1, 2 ** -2, (-2) ** -3, 10 ** -2)
print(0 ** 0, 0.0 ** 0, 1 ** 2 ** 70, (-1) ** (2 ** 70 + 1), (-1) ** 2 ** 70)
print(2.0 ** 64, 2.5 ** 2, (-8) ** 2, 2 ** 2 ** 3, (-2.0) ** 3)
print(pow(2, 10), pow(2, 100), pow(-2, -1), pow(2.5, 2))
try:
    print(0 ** -1)
except ZeroDivisionError as e:
    print('ZeroDivisionError', e.args)
try:
    print(2.0 ** 1024)
except OverflowError as e:
    print('OverflowError', e.args)
try:
    print(2 ** -(2 ** 70))
except OverflowError as e:
    print('OverflowError', e.args)
print(2 ** -1074, 2 ** -1075, (2 ** 64) ** 2 == 2 ** 128)
print(1.0 ** (2 ** 70), (-1.0) ** (2 ** 64 + 1), 0.5 ** 1074)
""",
     None),
    ('conversions', 'cpython', 'wide', """\
print(int('  -0x_1f ', 0), int('101', 2), int(-2.9), int(True))
print(int(2 ** 64 + 0.0), int('9' * 30), int('-' + '7' * 20, 8))
print(float('1_0.5e1'), float(' -inf '), float(2 ** 64), float('1e-400'))
print(float(7), float(2 ** 53 + 1), float('-1.5e3'))
print(str(2 ** 64), repr(-2 ** 64), str(1e16), repr(1e-05), str(0.1))
print(repr(1 / 3), str(2.0 ** 70), repr(123456789.0), str(-1e-07))
print(hex(2 ** 64), oct(-8), bin(5), hex(-(2 ** 70)), bin(0), oct(2 ** 64))
print(round(2.5), round(3.5), round(-2.5), round(0.5), round(-0.4))
print(round(1234, -2), round(1250, -2), round(1350, -2), round(-1250, -2))
print(round(2 ** 64 + 2 ** 63, -19), round(2 ** 64, 5), round(1e20))
print(abs(-2 ** 64), abs(-0.0), abs(-2.5), abs(True))
print(bool(0), bool(2 ** 64), bool(0.0), bool(''), bool('0'))
try:
    int('0x1g', 16)
except ValueError as e:
    print(e.args)
try:
    int(1e400)
except OverflowError as e:
    print(e.args)
try:
    int(float('nan'))
except ValueError as e:
    print(e.args)
try:
    float('1e')
except ValueError as e:
    print(e.args)
try:
    round(1.5, 'x')
except TypeError as e:
    print(e.args)
try:
    round('a', 1.5)
except TypeError as e:
    print(e.args)
print(pow(base=2, exp=10), round(number=1250, ndigits=-2))
""",
     None),
    ('signed-zero', 'cpython', 'wide', """\
print(4.0 % -2.0, -4.0 % 2.0, 4.0 // -2.0, -4.0 // 2.0, 4.0 % 2.0)
print(-4.0 % -2.0, 6.0 // 3.0, -6.0 // -3.0)
print(4 % -2.0, 4.0 % -2, 0.0 % 5.0, 0.0 % -5.0, -0.0 % 5.0)
print(0.0 // 5.0, 0.0 // -5.0, -0.0 // 5.0)
inf = 1e400
print(5.0 % inf, -5.0 % inf, 5.0 % -inf, -5.0 % -inf, 0.0 % inf, 0.0 % -inf)
print(5.0 // inf, -5.0 // inf, 5.0 // -inf, -5.0 // -inf, 0.0 // inf)
print(0.0 // -inf)
print(-0.0, +(-0.0), -(0.0), -(-0.0), 0.0 * -1, -1e400, 1e400)
z = -0.0
print(z == 0.0, z == 0, hash(z), {0.0: 'a'}[z], str(z), repr(z), abs(z))
print(-0.0 + 0.0, -0.0 - 0.0, z * 0, 0 * -1.0, (-0.0) ** 3, (-0.0) ** 2)
print(int(z), round(z), float('-0'), -0.0 * 2 ** 64, z / 1, -(2 ** 64) * 0.0)
print(divmod(-0.0, 1.0), divmod(0.0, -1.0), z // 1, 0.0 % -1)
""",
     None),
    ('limits', 'hand', 'wide', """\
try:
    x = 2 ** 100000
except MemoryError as e:
    print('MemoryError', e.args)
print(2 ** 99999 > 0)
try:
    x = 1 << 2 ** 70
except MemoryError as e:
    print('MemoryError', e.args)
try:
    x = 1 << 100000
except MemoryError as e:
    print('MemoryError', e.args)
try:
    str(10 ** 4300)
except ValueError as e:
    print('ValueError', e.args)
try:
    int('9' * 4301)
except ValueError as e:
    print('ValueError', e.args)
print(len(str(10 ** 4299)), int('9' * 4300) % 7)
try:
    print('lost', 10 ** 4300)
except ValueError:
    print('nothing printed')
try:
    'a' * (2 ** 53 - 1)
except MemoryError as e:
    print('MemoryError', e.args)
try:
    [0] * 2 ** 62
except MemoryError as e:
    print('MemoryError', e.args)
print(repr('' * 2 ** 62), 'ab' * 3, len([1, 2] * 524288))
try:
    [1, 2] * 524289
except MemoryError as e:
    print('MemoryError', e.args)
try:
    'a' * 2 ** 63
except OverflowError as e:
    print('OverflowError', e.args)
x = [1]
try:
    x *= 1048577
except MemoryError:
    print('MemoryError', len(x))
""",
     """\
MemoryError ()
True
MemoryError ()
MemoryError ()
ValueError ('Exceeds the limit (4300 digits) for integer string conversion',)
ValueError ('Exceeds the limit (4300 digits) for integer string conversion',)
4300 3
nothing printed
MemoryError ()
MemoryError ()
'' ababab 1048576
MemoryError ()
OverflowError ("cannot fit 'int' into an index-sized integer",)
MemoryError 1
"""),
    ('small', 'hand', 'small', """\
print(hex(2 ** 59), hex(2 ** 59 - 1 + 2 ** 59), 99999, -99999)
try:
    x = 2 ** 60
except MemoryError as e:
    print('MemoryError', e.args)
try:
    print(99999 + 1)
except ValueError as e:
    print('ValueError', e.args)
d = {1: 'a'}
try:
    d[5e-324] = 'b'
except MemoryError:
    print('MemoryError', len(d))
try:
    hash(1)
except MemoryError as e:
    print('MemoryError', e.args)
try:
    'a' * 2 ** 59
except MemoryError as e:
    print('MemoryError', e.args)
try:
    [1, 2] * 2 ** 59
except MemoryError as e:
    print('MemoryError', e.args)
print('ab' * 3, len([0] * 1000), int('99999'), str(-99999))
try:
    int('100000')
except ValueError as e:
    print('ValueError', e.args)
""",
     """\
0x800000000000000 0xfffffffffffffff 99999 -99999
MemoryError ()
ValueError ('Exceeds the limit (5 digits) for integer string conversion',)
MemoryError 1
MemoryError ()
MemoryError ()
MemoryError ()
ababab 1000 99999 -99999
ValueError ('Exceeds the limit (5 digits) for integer string conversion',)
"""),
]

out = ['c3-corpus-v1', 'CPython 3.9.6']
for name, origin, profile, source, pinned in PROGRAMS:
    assert source.isascii() and source.endswith('\n'), name
    lines = source[:-1].split('\n')
    assert all(lines), name
    if origin == 'cpython':
        assert pinned is None, name
        buf = io.StringIO()
        with contextlib.redirect_stdout(buf):
            exec(compile(source, name, 'exec'), {'__name__': '__main__'})
        stdout = buf.getvalue()
    else:
        assert origin == 'hand' and pinned is not None, name
        stdout = pinned
    assert stdout.isascii() and stdout.endswith('\n'), name
    assert all(stdout[:-1].split('\n')), name
    out.append(' '.join(['program', name, origin, profile]))
    out += ['> ' + line for line in lines]
    out += ['| ' + line for line in stdout[:-1].split('\n')]
Path('test/resources/yang/python/c3-corpus-v1.txt').write_text(
    '\n'.join(out) + '\n')
print(len(PROGRAMS), 'programs')
