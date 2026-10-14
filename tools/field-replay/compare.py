"""Field replay, step 3: per episode, when each mode opens vs. the field (local only).

Usage: python3 -I compare.py <episodes.jsonl> <result.csv> [-v]
"""
import json, csv, collections, sys
eps={e['id']:e for e in (json.loads(l) for l in open(sys.argv[1]))}
res=collections.defaultdict(dict)
for r in csv.DictReader(open(sys.argv[2])):
    res[int(r['episode'])][r['mode']]=(int(r['t']) if r['t'] else None, r['route'], r['rssi'])
modes=list(next(iter(res.values())).keys())
def dist_at(e,t):
    best=None
    for s in e['samples']:
        if s[0]<=t: best=s
    return best[4] if best else '?'
rows=[]
for i,m in sorted(res.items()):
    e=eps[i]; t0=e['samples'][0][0]; arm=e['arm']
    cells=[]
    for mode in modes:
        t,route,rssi=m[mode]
        cells.append(f"{(t-t0)/1000:5.1f}s {route[:8]:8s} {rssi:>4s} ~{dist_at(e,t)}m" if t else "      -")
    field=f"{(arm[0]-t0)/1000:5.1f}s {arm[1]} ~{arm[2]}m" if arm else "-"
    diff = any((m[x][0] or 0)!=(m[modes[0]][0] or 0) for x in modes)
    rows.append((i, e['src'][:30], f"n={len(e['samples'])}", f"max={max(s[1] for s in e['samples'])}", field, cells, diff))
print("episode | src | samples | max ema | field ARM | " + " | ".join(modes))
for r in rows:
    if r[6] or '-v' in sys.argv: print(r[0], r[1], r[2], r[3], "| field:", r[4], "|", " | ".join(r[5]))
# summary
cnt=collections.Counter()
for i,m in res.items():
    u=m[modes[0]][0]
    for mode in modes[1:]:
        t=m[mode][0]
        if t and not u: cnt[mode+' new-unlock']+=1
        elif t and u and t<u: cnt[mode+' earlier']+=1
        elif t and u and t==u: cnt[mode+' same']+=1
        elif u and not t: cnt[mode+' lost']+=1
print(dict(cnt))
