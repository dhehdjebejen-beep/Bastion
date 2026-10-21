"""Anti-cheat CPU benchmark driver: vanilla Fabric vs + BastionAC vs + Grim, at several bot counts.

python bench_run.py CONFIGS COUNTS [WARM MEASURE]
  e.g. python bench_run.py vanilla,bastion,grim 50,100 60 120

Paths come from the environment:
  ACBENCH_ROOT        working folder (default ./acbench); the server lives in ROOT/server
                      and needs its Fabric server libraries/ and versions/ copied in
  ACBENCH_JAVA        java executable (default: java on PATH)
  ACBENCH_FABRIC_API  fabric-api jar
  ACBENCH_BASTIONAC   bastionac jar
  ACBENCH_GRIM        grimac-fabric jar
  BENCH_SPACING       blocks between bots (default 96)
Results are appended to ROOT/results.jsonl and printed. Windows only (process
affinity and CPU time come from kernel32).
"""
import ctypes
import ctypes.wintypes as wt
import json
import os
import re
import shutil
import socket
import struct
import subprocess
import sys
import threading
import time

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.environ.get('ACBENCH_ROOT', os.path.abspath('acbench')).replace(os.sep, '/')
SRV = ROOT + '/server'
JAVA = os.environ.get('ACBENCH_JAVA', 'java')
FABRIC_API = os.environ.get('ACBENCH_FABRIC_API', ROOT + '/jars/fabric-api-0.141.4+1.21.11.jar')
MODS = {
    'vanilla': [],
    'bastion': [os.environ.get('ACBENCH_BASTIONAC', ROOT + '/jars/bastionac-3.5.0-beta+build.4.jar')],
    'grim': [os.environ.get('ACBENCH_GRIM', ROOT + '/jars/grimac-fabric-2.3.74-39d7eb1.jar')],
}
PORT, RCON_PORT, RCON_PASS = 25601, 25602, 'acbench-local'
SPACING = int(os.environ.get('BENCH_SPACING', '96'))

PROPS = '''online-mode=false
server-port=%d
enable-rcon=true
rcon.port=%d
rcon.password=%s
broadcast-rcon-to-ops=false
level-name=world
level-type=minecraft\\:flat
generate-structures=false
difficulty=peaceful
view-distance=4
simulation-distance=4
network-compression-threshold=-1
max-players=400
spawn-protection=0
rate-limit=0
sync-chunk-writes=false
enforce-secure-profile=false
motd=acbench
''' % (PORT, RCON_PORT, RCON_PASS)


# ------------------------------------------------------------------ rcon
class Rcon:
    def __init__(self):
        self.s = socket.create_connection(('127.0.0.1', RCON_PORT), timeout=60)
        self.n = 1
        self._send(3, RCON_PASS)
        self._read()

    def _send(self, kind, body):
        data = struct.pack('<ii', self.n, kind) + body.encode() + b'\x00\x00'
        self.s.sendall(struct.pack('<i', len(data)) + data)
        self.n += 1

    def _read(self):
        head = b''
        while len(head) < 4:
            head += self.s.recv(4 - len(head))
        n = struct.unpack('<i', head)[0]
        data = b''
        while len(data) < n:
            data += self.s.recv(n - len(data))
        return data[8:-2].decode('utf-8', 'replace')

    def cmd(self, c):
        self._send(2, c)
        return self._read()


# ------------------------------------------------------------------ process cpu time
k32 = ctypes.windll.kernel32


def cpu_seconds(pid):
    h = k32.OpenProcess(0x1000, False, pid)   # PROCESS_QUERY_LIMITED_INFORMATION
    if not h:
        return None
    c, e, kt, ut = wt.FILETIME(), wt.FILETIME(), wt.FILETIME(), wt.FILETIME()
    k32.GetProcessTimes(h, ctypes.byref(c), ctypes.byref(e), ctypes.byref(kt), ctypes.byref(ut))
    k32.CloseHandle(h)
    f = lambda t: (t.dwHighDateTime << 32 | t.dwLowDateTime) / 1e7
    return f(kt) + f(ut)


def set_affinity(pid, mask):
    h = k32.OpenProcess(0x0200 | 0x0400, False, pid)   # SET_INFORMATION | QUERY_INFORMATION
    ok = k32.SetProcessAffinityMask(h, mask)
    k32.CloseHandle(h)
    return ok


# ------------------------------------------------------------------ one run
def prepare(config):
    mods = SRV + '/mods'
    shutil.rmtree(mods, ignore_errors=True)
    os.makedirs(mods)
    shutil.copy(FABRIC_API, mods)
    for m in MODS[config]:
        shutil.copy(m, mods)
    with open(SRV + '/server.properties', 'w', encoding='utf-8') as f:
        f.write(PROPS)
    with open(SRV + '/eula.txt', 'w') as f:
        f.write('eula=true\n')


def start_server(log):
    cp = ';'.join(os.path.join(dp, fn).replace('\\', '/') for dp, _, fs in os.walk(SRV + '/libraries')
                  for fn in fs if fn.endswith('.jar')) + ';versions/1.21.11/server-1.21.11.jar'
    out = open(log, 'w', encoding='utf-8')
    p = subprocess.Popen([JAVA, '-Xms2G', '-Xmx2G', '-Djava.net.preferIPv4Stack=true', '-cp', cp,
                          'net.fabricmc.loader.impl.launch.knot.KnotServer', 'nogui'],
                         cwd=SRV, stdout=out, stderr=subprocess.STDOUT, stdin=subprocess.DEVNULL)
    set_affinity(p.pid, 0x3)    # two cores, like the production host
    return p, out


def wait_for(log, pattern, timeout):
    end = time.time() + timeout
    while time.time() < end:
        try:
            with open(log, encoding='utf-8', errors='replace') as f:
                if re.search(pattern, f.read()):
                    return True
        except OSError:
            pass
        time.sleep(1)
    return False


def mspt(r):
    out = r.cmd('tick query')
    m = re.search(r'([\d.,]+)\s*ms', out)
    return float(m.group(1).replace(',', '.')) if m else None, out


def run(config, count, warm, measure, fight=0.3):
    prepare(config)
    log = '%s/log-%s-%d-s%d.txt' % (ROOT, config, count, SPACING)
    p, out = start_server(log)
    try:
        if not wait_for(log, r'Done \(', 300):
            return {'config': config, 'bots': count, 'error': 'server did not start'}
        r = Rcon()
        for c in ('gamerule doMobSpawning false', 'gamerule doDaylightCycle false', 'gamerule doWeatherCycle false',
                  'time set noon', 'difficulty peaceful'):
            r.cmd(c)
        bot_log = open('%s/bots-%s-%d-s%d.txt' % (ROOT, config, count, SPACING), 'w', encoding='utf-8')
        total = warm + measure + count * 0.15 + 90
        bots = subprocess.Popen([sys.executable, '-u', os.path.join(HERE, 'bench_bots.py'), '127.0.0.1', str(PORT),
                                 str(count), str(fight), str(total)], stdout=bot_log, stderr=subprocess.STDOUT)
        time.sleep(count * 0.15 + 15)
        # Spread them out: a 96-block grid, out of each other's sight.
        side = max(1, int(count ** 0.5 + 0.999))
        for i in range(count):
            name = 'Bot%03d' % (i + 1)
            x, z = (i % side) * SPACING + 0.5, (i // side) * SPACING + 0.5
            r.cmd('tp %s %.1f -60 %.1f 0 0' % (name, x, z))
            if ((i + 1) % 10) < fight * 10:
                r.cmd('summon minecraft:pig %.1f -60 %.1f {NoAI:1b,Invulnerable:1b,Silent:1b,PersistenceRequired:1b}'
                      % (x, z + 2.5))
        time.sleep(warm)
        samples = []
        last_query = ''
        c0, t0 = cpu_seconds(p.pid), time.time()
        while time.time() - t0 < measure:
            time.sleep(10)
            v, last_query = mspt(r)
            if v is not None:
                samples.append(v)
        c1, t1 = cpu_seconds(p.pid), time.time()
        online = r.cmd('list')
        m = re.search(r'There are (\d+)', online)
        res = {'config': config, 'bots': count, 'spacing': SPACING, 'online': int(m.group(1)) if m else None,
               'cpu_pct_of_one_core': round(100 * (c1 - c0) / (t1 - t0), 1),
               'mspt_avg': round(sum(samples) / len(samples), 2) if samples else None,
               'mspt_max': round(max(samples), 2) if samples else None, 'window_s': round(t1 - t0),
               'tick_query': last_query[:160]}
        try:
            r.cmd('stop')
        except Exception:
            pass
        return res
    finally:
        try:
            p.wait(timeout=60)
        except Exception:
            p.kill()
        out.close()
        try:
            bots.kill()
        except Exception:
            pass
        with open(log, encoding='utf-8', errors='replace') as f:
            text = f.read()
        flags = len(re.findall(r'\[VL\]|flagged|failed .*x\d|\[Grim', text))
        kicks = len(re.findall(r'lost connection|was kicked|Kicked', text))
        print('   log: %d anti-cheat lines, %d disconnects' % (flags, kicks))


def main():
    configs = sys.argv[1].split(',')
    counts = [int(x) for x in sys.argv[2].split(',')]
    warm = int(sys.argv[3]) if len(sys.argv) > 3 else 60
    measure = int(sys.argv[4]) if len(sys.argv) > 4 else 120
    for n in counts:
        for c in configs:
            print('== %s, %d bots' % (c, n), flush=True)
            res = run(c, n, warm, measure)
            print('  ', json.dumps(res, ensure_ascii=False), flush=True)
            with open(ROOT + '/results.jsonl', 'a', encoding='utf-8') as f:
                f.write(json.dumps(res, ensure_ascii=False) + '\n')


if __name__ == '__main__':
    main()
