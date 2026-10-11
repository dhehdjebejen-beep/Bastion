#!/usr/bin/env python3
"""Decode a BastionAC session file (logs/bastionac/sessions/<day>/*.btl.gz) to text.

    python btl_dump.py FILE.btl.gz              every record, one per line
    python btl_dump.py FILE.btl.gz --summary    counts and the session's shape only
    python btl_dump.py FILE.btl.gz --only move,flag,ac

The format is described in TelemetryFormat.java; this decoder mirrors
TelemetryReader.java. A file that is still being written (or whose server
died) simply ends early — everything before the cut is printed.
"""
import datetime
import gzip
import io
import json
import struct
import sys
import zlib
from collections import Counter

NAMES = {1: 'move', 2: 'tickend', 3: 'input', 4: 'tp_sent', 5: 'tp_confirm', 6: 'correction', 7: 'velocity',
         8: 'state', 9: 'env', 10: 'action', 11: 'broken', 12: 'place', 13: 'use', 14: 'attack', 15: 'swing',
         16: 'slot', 17: 'click', 18: 'flag', 19: 'ac', 20: 'pong', 21: 'keepalive', 22: 'damage', 23: 'life',
         24: 'target', 25: 'client', 26: 'note', 27: 'servertick', 28: 'speed', 29: 'vehicle', 30: 'ground',
         31: 'orectx', 32: 'close'}
ACTIONS = ['START', 'ABORT', 'STOP', 'DROP_ALL', 'DROP', 'RELEASE_USE', 'SWAP_HAND', 'STAB']
KEYS = [(1, 'W'), (2, 'S'), (4, 'A'), (8, 'D'), (16, 'jump'), (32, 'sneak'), (64, 'sprint')]
ACT = {1: 'setback', 2: 'cancel', 3: 'kick', 4: 'ban', 5: 'freeze', 6: 'resync', 7: 'dump', 8: 'argus'}
LIFE = {1: 'death', 2: 'respawn', 3: 'world', 4: 'gamemode', 5: 'authenticated'}
Q = 4096.0


class Cut(Exception):
    pass


class R:
    def __init__(self, data):
        self.b = data
        self.i = 0

    def u8(self):
        if self.i >= len(self.b):
            raise Cut()
        v = self.b[self.i]
        self.i += 1
        return v

    def take(self, n):
        if self.i + n > len(self.b):
            raise Cut()
        v = self.b[self.i:self.i + n]
        self.i += n
        return v

    def varint(self):
        v = shift = 0
        while True:
            b = self.u8()
            v |= (b & 0x7F) << shift
            if not b & 0x80:
                return v
            shift += 7

    def zigzag(self):
        v = self.varint()
        return (v >> 1) ^ -(v & 1)

    def f32(self):
        return struct.unpack('>f', self.take(4))[0]

    def f64(self):
        return struct.unpack('>d', self.take(8))[0]

    def i64(self):
        return struct.unpack('>q', self.take(8))[0]

    def str(self):
        return self.take(self.varint()).decode('utf-8', 'replace')


def read_gzip_tolerant(path):
    raw = open(path, 'rb').read()
    try:
        return gzip.decompress(raw)
    except (EOFError, OSError, zlib.error):
        d = zlib.decompressobj(16 + zlib.MAX_WBITS)
        return d.decompress(raw)


def pos(p):
    x = p >> 38
    y = (p << 52 & 0xFFFFFFFFFFFFFFFF)
    y = (y - (1 << 64) if y >= 1 << 63 else y) >> 52
    z = (p << 26 & 0xFFFFFFFFFFFFFFFF)
    z = (z - (1 << 64) if z >= 1 << 63 else z) >> 38
    return '%d %d %d' % (x, y, z)


def keys(bits):
    return '+'.join(n for b, n in KEYS if bits & b) or '-'


def main():
    args = sys.argv[1:]
    if not args:
        print(__doc__)
        return
    path = args[0]
    summary = '--summary' in args
    only = None
    if '--only' in args:
        only = set(args[args.index('--only') + 1].split(','))
    out = io.TextIOWrapper(sys.stdout.buffer, encoding='utf-8', errors='replace')
    r = R(read_gzip_tolerant(path))
    if r.take(4) != b'BTL2':
        sys.exit('not a BastionAC session file')
    r.u8()
    header = json.loads(r.str())
    out.write('# %s\n' % json.dumps(header, ensure_ascii=False))
    ts = header.get('start', 0)
    qx = qy = qz = 0
    have = False
    counts = Counter()
    cut = False
    while True:
        try:
            t = r.u8()
        except Cut:
            break
        try:
            ts += r.zigzag()
            name = NAMES.get(t, '?%d' % t)
            line = ''
            if t == 1:
                f = r.u8()
                if f & 1:
                    dx, dy, dz = r.zigzag(), r.zigzag(), r.zigzag()
                    if not have:
                        qx = qy = qz = 0
                        have = True
                    qx += dx
                    qy += dy
                    qz += dz
                    line += 'pos=%.4f,%.4f,%.4f ' % (qx / Q, qy / Q, qz / Q)
                if f & 2:
                    line += 'yaw=%.3f pitch=%.3f ' % (r.f32(), r.f32())
                line += 'g=%d%s' % (1 if f & 4 else 0, ' hcoll' if f & 8 else '')
            elif t == 3:
                line = keys(r.u8())
            elif t == 4:
                line = 'id=%d to=%.3f,%.3f,%.3f rel=%d' % (r.varint(), r.f64(), r.f64(), r.f64(), r.u8())
            elif t == 5:
                line = 'id=%d valid=%d' % (r.varint(), r.u8())
            elif t == 6:
                reason = r.u8()
                c = (r.f64(), r.f64(), r.f64())
                s = (r.f64(), r.f64(), r.f64())
                line = '%s claimed=%.3f,%.3f,%.3f server=%.3f,%.3f,%.3f block=%d' % (
                    'collide' if reason == 1 else 'other', *c, *s, r.varint())
            elif t == 7:
                line = 'v=%.3f,%.3f,%.3f explosion=%d' % (r.f32(), r.f32(), r.f32(), r.u8())
            elif t == 8:
                tick = r.varint(); mspt = r.f32(); last = r.f32(); ping = r.varint(); bal = r.f32(); worst = r.f32()
                x, y, z = r.f64(), r.f64(), r.f64(); yaw, pitch, hp = r.f32(), r.f32(), r.f32()
                food, gm, flags = r.u8(), r.u8(), r.varint()
                spd, grav, jump, step = r.f32(), r.f32(), r.f32(), r.f32()
                world, feet, veh = r.u8(), r.varint(), r.varint()
                eff = [(r.varint(), r.u8(), r.varint()) for _ in range(r.u8())]
                line = ('tick=%d mspt=%.1f ping=%d timer=%.0f(worst %.0f) pos=%.2f,%.2f,%.2f hp=%.1f food=%d gm=%d '
                        'flags=%x speed=%.4f world=%d effects=%s') % (tick, mspt, ping, bal, worst, x, y, z, hp, food,
                                                                      gm, flags, spd, world, eff)
            elif t == 9:
                line = 'env=%x' % r.varint()
            elif t == 10:
                a = r.u8(); p = r.i64(); face = r.u8(); r.varint(); st = r.varint(); bt = r.f32(); tool = r.varint()
                ex = r.u8()
                line = '%s %s face=%d state=%d breakTicks=%.1f tool=%d exposed=%s' % (
                    ACTIONS[a] if a < len(ACTIONS) else a, pos(p), face, st, bt, tool, format(ex, '06b'))
            elif t == 11:
                p = r.i64()
                line = '%s state=%d exposed=%s' % (pos(p), r.varint(), format(r.u8(), '06b'))
            elif t == 12:
                hand = r.u8(); p = r.i64(); face = r.u8(); h = (r.f32(), r.f32(), r.f32()); inside = r.u8()
                line = 'hand=%d %s face=%d hit=%.3f,%.3f,%.3f inside=%d item=%d' % (hand, pos(p), face, *h, inside, r.varint())
                r.varint()
            elif t == 13:
                line = 'hand=%d item=%d yaw=%.2f pitch=%.2f' % (r.u8(), r.varint(), r.f32(), r.f32())
            elif t == 14:
                tid, ttype, fl = r.varint(), r.varint(), r.u8()
                eye = (r.f64(), r.f64(), r.f64()); c = (r.f64(), r.f64(), r.f64())
                hw, h, yaw, pitch, dist, err, cd = (r.f32() for _ in range(7))
                tv = (r.f32(), r.f32(), r.f32()); ping = r.varint()
                line = 'target=%d type=%d flags=%x dist=%.2f aimErr=%.2f yaw=%.2f pitch=%.2f cooldown=%.2f ping=%d' % (
                    tid, ttype, fl, dist, err, yaw, pitch, cd, ping)
            elif t in (15, 16):
                line = str(r.u8())
            elif t == 17:
                line = 'sync=%d slot=%d button=%d action=%d screen=%d' % (r.varint(), r.zigzag(), r.u8(), r.u8(), r.u8())
            elif t == 18:
                line = '%s vl=%.1f %s' % (r.str(), r.f32(), r.str())
            elif t == 19:
                k = r.u8()
                line = '%s %s' % (ACT.get(k, k), r.str())
            elif t in (20, 21):
                line = 'rtt=%d' % r.varint()
            elif t == 22:
                line = 'amount=%.1f type=%s attacker=%d' % (r.f32(), r.str(), r.varint())
            elif t == 23:
                k, w = r.u8(), r.u8()
                line = '%s world=%d at=%.2f,%.2f,%.2f' % (LIFE.get(k, k), w, r.f64(), r.f64(), r.f64())
                if k in (2, 3):
                    have = False
            elif t == 24:
                line = 'id=%d d=%.2f,%.2f,%.2f' % (r.varint(), r.f32(), r.f32(), r.f32())
                r.f32(); r.f32()
            elif t == 25:
                brand = r.str(); proto = r.varint(); loc = r.str(); vd = r.u8(); r.u8(); r.u8(); r.u8()
                ch = [r.str() for _ in range(r.varint())]
                line = 'brand=%s protocol=%d locale=%s view=%d channels=%s' % (brand, proto, loc, vd, ch)
            elif t == 26:
                line = r.str()
            elif t == 28:
                line = '%.4f' % r.f32()
            elif t == 29:
                line = 'mounted=%d type=%d id=%d' % (r.u8(), r.varint(), r.varint())
            elif t == 30:
                line = 'state=%d' % r.varint()
            elif t == 31:
                p = r.i64()
                r.take(63)
                line = pos(p)
            elif t == 32:
                line = 'sync=%d' % r.varint()
            elif t in (2, 27):
                pass
            else:
                out.write('unknown record %d — stopping\n' % t)
                break
        except Cut:
            cut = True
            break
        counts[name] += 1
        if summary or (only and name not in only):
            continue
        stamp = datetime.datetime.fromtimestamp(ts / 1000).strftime('%H:%M:%S.%f')[:-3]
        out.write('%s %-10s %s\n' % (stamp, name, line))
    out.write('# records: %s%s\n' % (dict(counts.most_common()), ' (file ends mid-record: still open or cut)' if cut else ''))
    out.flush()


if __name__ == '__main__':
    main()
