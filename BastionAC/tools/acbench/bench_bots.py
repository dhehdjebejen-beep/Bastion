"""Many honest vanilla-like bots for the anti-cheat CPU benchmark (protocol 774, 1.21.11).

python bench_bots.py HOST PORT COUNT FIGHT_SHARE SECONDS [PREFIX]

Walkers walk a circle with the vanilla ground physics bit for bit (Java float
rounding, Minecraft's sine table, grass friction) and turn in whole mouse
steps. Fighters stand next to their own invulnerable NoAI pig (summoned by the
driver) and hit it on the attack cooldown. Every bot answers keep-alives and
pings, confirms teleports and acknowledges chunk batches like a client does.
Prints one status line every 10 s: bots in play, kicked, teleports received.
"""
import ctypes
import math
import socket
import struct
import sys
import threading
import time

HOST, PORT, COUNT = sys.argv[1], int(sys.argv[2]), int(sys.argv[3])
FIGHT_SHARE, SECONDS = float(sys.argv[4]), float(sys.argv[5])
PREFIX = sys.argv[6] if len(sys.argv) > 6 else 'Bot'
PROTO = 774

# Run the bots on the two cores the server does not have.
try:
    ctypes.windll.kernel32.SetProcessAffinityMask(ctypes.windll.kernel32.GetCurrentProcess(), 0xC)
except Exception:
    pass


def f32(x):
    return struct.unpack('>f', struct.pack('>f', x))[0]


SINE = [f32(math.sin(i * math.pi * 2.0 / 65536.0)) for i in range(65536)]
K_SIN = f32(10430.378)
DEG = f32(math.pi / 180.0)


def mc_sin(v):
    return SINE[int(f32(f32(v) * K_SIN)) & 65535]


def mc_cos(v):
    return SINE[int(f32(f32(f32(v) * K_SIN) + 16384.0)) & 65535]


SLIP = f32(0.6)
GROUND_SPEED = f32(f32(0.1) * f32(f32(0.21600002) / f32(f32(SLIP * SLIP) * SLIP)))
FRICTION = f32(SLIP * f32(0.91))
FORWARD = f32(0.98)
AIR_SPEED = f32(0.02)                          # PlayerEntity.getOffGroundSpeed, not sprinting
AIR_FRICTION = f32(0.91)
STEP = 0.5 * 0.6 + 0.2
STEP = STEP * STEP * STEP * 8 * 0.15          # 50 % sensitivity: 0.15° per mouse count


def varint(n):
    out = b''
    n &= 0xFFFFFFFF
    while True:
        b = n & 0x7F
        n >>= 7
        if n:
            out += bytes([b | 0x80])
        else:
            return out + bytes([b])


def s(x):
    b = x.encode()
    return varint(len(b)) + b


def read_varint(buf, i):
    n = shift = 0
    while True:
        b = buf[i]
        i += 1
        n |= (b & 0x7F) << shift
        shift += 7
        if not b & 0x80:
            return n, i


STATS = {'play': 0, 'kicked': 0, 'tp': 0, 'pings': 0, 'reasons': {}}
SLOCK = threading.Lock()


class Bot:
    def __init__(self, idx):
        self.name = '%s%03d' % (PREFIX, idx)
        self.fighter = (idx % 10) < FIGHT_SHARE * 10
        self.sock = None
        self.buf = bytearray()
        self.lock = threading.Lock()
        self.threshold = -1
        self.ready = False
        self.alive = True
        self.x = self.y = self.z = 0.0
        self.vx = self.vz = 0.0
        self.yaw = 0.0
        self.pitch = 0.0
        self.eid = None
        self.pig = None
        self.ticks = 0
        self.next_hit = 12
        self.sent_input = False

    # ---------------------------------------------------------------- wire
    def send(self, pid, body=b''):
        data = varint(pid) + body
        if self.threshold >= 0:
            data = varint(0) + data   # never above the threshold here; uncompressed frame
        try:
            with self.lock:
                self.sock.sendall(varint(len(data)) + data)
        except OSError:
            self.alive = False

    def recv_frames(self):
        while self.alive:
            try:
                chunk = self.sock.recv(1 << 16)
            except OSError:
                break
            if not chunk:
                break
            self.buf += chunk
            while True:
                if not self.buf:
                    break
                try:
                    ln, i = read_varint(self.buf, 0)
                except IndexError:
                    break
                if len(self.buf) < i + ln:
                    break
                frame = bytes(self.buf[i:i + ln])
                del self.buf[:i + ln]
                if self.threshold >= 0:
                    dl, j = read_varint(frame, 0)
                    if dl > 0:
                        import zlib
                        frame = zlib.decompress(frame[j:])
                    else:
                        frame = frame[j:]
                pid, j = read_varint(frame, 0)
                yield pid, frame[j:]
        self.alive = False

    # ---------------------------------------------------------------- session
    def run(self):
        try:
            self.sock = socket.create_connection((HOST, PORT), timeout=30)
            self.sock.settimeout(None)
            self.send(0x00, varint(PROTO) + s('localhost') + struct.pack('>H', PORT) + varint(2))
            import hashlib
            import uuid as _uuid
            u = _uuid.UUID(bytes=hashlib.md5(b'bench:' + self.name.encode()).digest())
            self.send(0x00, s(self.name) + u.bytes)
            state = 'login'
            for pid, body in self.recv_frames():
                if state == 'login':
                    if pid == 0x03:
                        self.threshold = read_varint(body, 0)[0]
                    elif pid == 0x02:
                        self.send(0x03)
                        state = 'config'
                        self.send(0x00, s('en_us') + bytes([4]) + varint(0) + bytes([1]) + bytes([0x7F])
                                  + varint(1) + bytes([0]) + bytes([1]) + varint(0))
                        self.send(0x02, s('minecraft:brand') + s('vanilla'))
                    elif pid == 0x00:
                        self.kicked(body)
                        return
                elif state == 'config':
                    if pid == 0x0E:
                        self.send(0x07, varint(0))
                    elif pid == 0x04:
                        self.send(0x04, body[:8])
                    elif pid == 0x05:
                        self.send(0x05, body[:4])
                    elif pid == 0x09:
                        self.send(0x06, body[:16] + varint(3))
                    elif pid == 0x03:
                        self.send(0x03)
                        state = 'play'
                        with SLOCK:
                            STATS['play'] += 1
                    elif pid == 0x02:
                        self.kicked(body)
                        return
                else:
                    self.play_packet(pid, body)
        except Exception as e:
            with SLOCK:
                STATS['reasons'][type(e).__name__] = STATS['reasons'].get(type(e).__name__, 0) + 1
        finally:
            if self.ready or self.alive:
                with SLOCK:
                    STATS['play'] -= 1 if self.ready else 0
            self.alive = False

    def kicked(self, body):
        text = body[:200].decode('utf-8', 'replace')
        with SLOCK:
            STATS['kicked'] += 1
            key = ''.join(ch for ch in text if ch.isprintable())[:80]
            STATS['reasons'][key] = STATS['reasons'].get(key, 0) + 1
        self.alive = False

    def play_packet(self, pid, body):
        if pid == 0x2B:                      # keep-alive
            self.send(0x1B, body[:8])
        elif pid == 0x3B and len(body) == 4:  # ping (anti-cheat transactions)
            self.send(0x2C, body)
            STATS['pings'] += 1
        elif pid == 0x0B:                     # chunk batch finished
            self.send(0x0A, struct.pack('>f', 20.0))
        elif pid == 0x30 and self.eid is None:
            self.eid = struct.unpack('>i', body[:4])[0]
            self.send(0x2B)                   # player loaded
        elif pid == 0x50:
            self.send(0x2B)
        elif pid == 0x46:                     # teleport
            tid, i = read_varint(body, 0)
            x, y, z = struct.unpack('>ddd', body[i:i + 24])
            self._px, self._pz = self.x, self.z
            i += 24 + 24
            yaw, pitch = struct.unpack('>ff', body[i:i + 8])
            flags = struct.unpack('>i', body[i + 8:i + 12])[0] if len(body) >= i + 12 else 0
            if flags:                         # relative parts: add to what we have
                if flags & 1: x += self.x
                if flags & 2: y += self.y
                if flags & 4: z += self.z
                if flags & 8: yaw += self.yaw
                if flags & 16: pitch += self.pitch
            with self.lock:
                self.x, self.y, self.z, self.yaw, self.pitch = x, y, z, f32(yaw), f32(pitch)
                self.vx = self.vz = 0.0
                # The first tick after a teleport starts in the air: the client
                # only finds the ground by colliding with it during that move.
                self.airborne = True
            self.send(0x00, varint(tid))
            # ClientPlayNetworkHandler answers a teleport with a full move, on ground false.
            self.send(0x1E, struct.pack('>dddff', x, y, z, self.yaw, self.pitch) + bytes([0]))
            self.ready = True
            self.tps = getattr(self, 'tps', 0) + 1
            if self.tps > 2 and getattr(self, 'tp_logged', 0) < 40:
                self.tp_logged = getattr(self, 'tp_logged', 0) + 1
                print('[tp] %s #%d at tick %d %s: %.4f %.4f %.4f flags=%d (we had %.4f %.4f) v=%.4f %.4f'
                      % (self.name, self.tps, self.ticks, 'fighter' if self.fighter else 'walker',
                         x, y, z, flags, self._px, self._pz, self.vx, self.vz), flush=True)
            with SLOCK:
                STATS['tp'] += 1
        elif pid == 0x01 and self.fighter and self.pig is None and self.ready:
            eid, i = read_varint(body, 0)
            i += 16
            _, i = read_varint(body, i)
            x, y, z = struct.unpack('>ddd', body[i:i + 24])
            if eid != self.eid and abs(x - self.x) < 4 and abs(z - self.z) < 4:
                self.pig = (eid, x, y, z)
        elif pid == 0x20:                     # disconnect (play)
            self.kicked(body)

    # ---------------------------------------------------------------- one client tick
    def tick(self):
        if not (self.alive and self.ready):
            return
        self.ticks += 1
        if self.fighter:
            self.tick_fighter()
        else:
            self.tick_walker()
        self.send(0x0C)                       # client tick end

    def tick_walker(self):
        if not self.sent_input:
            self.send(0x2A, bytes([1]))       # W held
            self.sent_input = True
        with self.lock:
            self.yaw = f32(self.yaw + f32(3 * STEP))
            # LivingEntity.tickMovement, players: a horizontal velocity under
            # 0.003 in length (squared 9e-6) is zeroed as a whole.
            if self.vx * self.vx + self.vz * self.vz < 9.0e-6:
                self.vx = self.vz = 0.0
            # updateVelocity(GROUND_SPEED, (0, 0, 0.98))
            rad = f32(self.yaw * DEG)
            fs, gc = mc_sin(rad), mc_cos(rad)
            air = getattr(self, 'airborne', False)
            self.airborne = False
            iz = FORWARD * (AIR_SPEED if air else GROUND_SPEED)
            ax = 0.0 * gc - iz * fs
            az = iz * gc + 0.0 * fs
            self.vx += ax
            self.vz += az
            self.x += self.vx
            self.z += self.vz
            # The friction is taken after the move, which found the ground.
            self.vx *= FRICTION
            self.vz *= FRICTION
            body = struct.pack('>dddff', self.x, self.y, self.z, self.yaw, self.pitch) + bytes([1])
        self.send(0x1E, body)

    def tick_fighter(self):
        if self.pig is None:
            return
        eid, px, py, pz = self.pig
        if not getattr(self, 'aimed', False):
            # Turn to the pig once, in whole mouse steps; it never moves again.
            dx, dz, dy = px - self.x, pz - self.z, (py + 0.45) - (self.y + 1.62)
            want = math.degrees(math.atan2(-dx, dz))
            wpitch = math.degrees(-math.atan2(dy, math.hypot(dx, dz)))
            self.yaw = f32(self.yaw + round((want - self.yaw) / STEP) * STEP)
            self.pitch = f32(self.pitch + round((wpitch - self.pitch) / STEP) * STEP)
            self.send(0x1F, struct.pack('>ff', self.yaw, self.pitch) + bytes([1]))
            self.aimed = True
            return
        if self.ticks >= self.next_hit:
            # A hand: 11-16 ticks between hits, never a metronome. The attack
            # comes from the key press at the start of the client tick, before
            # anything the tick sends about movement.
            self.next_hit = self.ticks + 11 + (hash((self.name, self.ticks)) % 6)
            self.send(0x19, varint(eid) + varint(1) + bytes([0]))   # attack, not sneaking
            self.send(0x3C, varint(0))                               # swing main hand
        if self.ticks % 20 == 0:
            self.send(0x1D, struct.pack('>ddd', self.x, self.y, self.z) + bytes([1]))


def main():
    bots = [Bot(i + 1) for i in range(COUNT)]
    for b in bots:
        threading.Thread(target=b.run, daemon=True).start()
        time.sleep(0.15)
    start = time.time()
    next_tick = time.perf_counter()
    last_report = 0
    while time.time() - start < SECONDS:
        for b in bots:
            b.tick()
        next_tick += 0.05
        delay = next_tick - time.perf_counter()
        if delay > 0:
            time.sleep(delay)
        else:
            next_tick = time.perf_counter()
        if time.time() - last_report >= 10:
            last_report = time.time()
            ready = sum(1 for b in bots if b.ready and b.alive)
            pigs = sum(1 for b in bots if b.fighter and b.pig)
            with SLOCK:
                print('[bots] ready %d/%d, fighters with a target %d, kicked %d, teleports %d, pings %d %s'
                      % (ready, COUNT, pigs, STATS['kicked'], STATS['tp'], STATS['pings'],
                         dict(list(STATS['reasons'].items())[:4])), flush=True)


if __name__ == '__main__':
    main()
