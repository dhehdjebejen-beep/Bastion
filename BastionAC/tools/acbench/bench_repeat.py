"""Repeated, order-rotated benchmark: python bench_repeat.py COUNT REPEATS WARM MEASURE

Same environment as bench_run.py. A first unrecorded run generates the world.
"""
import json
import os
import sys

sys.argv = [sys.argv[0]] + sys.argv[1:]
import bench_run as B  # noqa: E402

count, repeats, warm, measure = (int(x) for x in sys.argv[1:5])
orders = [['vanilla', 'bastion', 'grim'], ['bastion', 'grim', 'vanilla'], ['grim', 'vanilla', 'bastion']]
print('== warm-up run (world generation), not recorded', flush=True)
B.run('vanilla', count, warm, 30)
for r in range(repeats):
    for c in orders[r % len(orders)]:
        print('== repeat %d: %s, %d bots' % (r + 1, c, count), flush=True)
        res = B.run(c, count, warm, measure)
        res['repeat'] = r + 1
        print('  ', json.dumps(res, ensure_ascii=False), flush=True)
        with open(B.ROOT + '/results-repeat.jsonl', 'a', encoding='utf-8') as f:
            f.write(json.dumps(res, ensure_ascii=False) + '\n')
