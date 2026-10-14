"""Field replay, step 1 (local only): logcat dumps / diagnose exports -> locked-car approach episodes.

Usage: python3 -I extract_episodes.py <log files or dirs ...> <out.jsonl>
Also writes <out>.csv for FieldReplayTest. Never commit or publish the input or the output.
"""
import os, re, sys, json, datetime

RAW_DIRS = sys.argv[1:-1]
OUT = sys.argv[-1]

LOGCAT = re.compile(r"^(\d\d)-(\d\d) (\d\d):(\d\d):(\d\d)\.(\d{3})\s+\d+\s+\d+\s+([VDIWE]) openzeekr\s*: \[(\w+)\] (.*)$")
MEM = re.compile(r"^(\d\d):(\d\d):(\d\d)\.(\d{3}) ([VDIWE])/(\w+)\s+(.*)$")
SAMPLE = re.compile(r"^rssi=(-?\d+) ema=(-?\d+) ~([\d,\.]+)m trend=\S+ zone=(\w+) armed=(true|false) "
                    r"thr\(u/l\)=(-?\d+)/(-?\d+) motion=(\w+)")
ARM = re.compile(r"approach-unlock ARM \(rssi=(-?\d+) ~([\d,\.]+)m[^)]*?(?:route=(\w+))?\)")
PRESENCE = re.compile(r"^arrival epoch: presence rssi=(-?\d+) motion=(\w+)")
FNDATE = re.compile(r"(20\d\d)(\d\d)(\d\d)-(\d\d)(\d\d)")

def file_year_date(path):
    m = FNDATE.search(os.path.basename(path)) or FNDATE.search(path)
    if not m: return None
    return int(m.group(1)), int(m.group(2)), int(m.group(3))

def events_of(path):
    ydate = file_year_date(path)
    year = ydate[0] if ydate else 2026
    prev_ms = None; day_shift = 0
    with open(path, "rb") as fb:
        bom = fb.read(2)
    enc = "utf-16" if bom in (b"\xff\xfe", b"\xfe\xff") else "utf-8"
    with open(path, encoding=enc, errors="replace") as f:
        for line in f:
            line = line.rstrip("\r\n")
            m = LOGCAT.match(line)
            if m:
                mo, d, hh, mi, ss, ms, lvl, area, msg = m.groups()
                dt = datetime.datetime(year, int(mo), int(d), int(hh), int(mi), int(ss), int(ms) * 1000)
            else:
                m = MEM.match(line)
                if not m or not ydate: continue
                hh, mi, ss, ms, lvl, area, msg = m.groups()
                # The in-memory log has no date: it ends at the export time in the file name.
                dt = datetime.datetime(ydate[0], ydate[1], ydate[2], int(hh), int(mi), int(ss), int(ms) * 1000)
            t = int(dt.timestamp() * 1000)
            if area not in ("prox", "ble", "svc", "motion"): continue
            yield t, area, msg

def main():
    files = []
    for d in RAW_DIRS:
        if os.path.isfile(d): files.append(d); continue
        for root, _, names in os.walk(d):
            for n in names:
                if n.endswith((".log", ".txt")): files.append(os.path.join(root, n))
    seen = set(); evs = []
    for p in sorted(files):
        for t, area, msg in events_of(p):
            key = (t, msg)
            if key in seen: continue
            seen.add(key); evs.append((t, area, msg, os.path.basename(p)))
    evs.sort(key=lambda e: e[0])
    episodes = []; cur = None; last_sample_t = None; armed_prev = None; pending_presence = []
    def close():
        nonlocal cur
        if cur and cur["samples"]: episodes.append(cur)
        cur = None
    for t, area, msg, src in evs:
        if area == "prox" and (msg.startswith("link down") or msg.startswith("link restored")):
            close(); armed_prev = None
            continue
        pm = PRESENCE.match(msg) if area == "prox" else None
        if pm:
            ev = (t, int(pm.group(1)), pm.group(2) == "MOVING")
            if cur: cur["presence"].append(ev)
            else: pending_presence.append(ev)
            continue
        am = ARM.search(msg) if area == "prox" else None
        if am and cur and cur["arm"] is None:
            cur["arm"] = (t, int(am.group(1)), am.group(2).replace(",", "."), am.group(3) or "?")
            continue
        sm = SAMPLE.match(msg) if area == "prox" else None
        if not sm: continue
        raw, ema, dist, zone, armed, thu, thl, motion = sm.groups()
        armed = armed == "true"
        if last_sample_t is not None and t - last_sample_t > 60_000: close(); armed_prev = None
        last_sample_t = t
        if armed:
            close(); armed_prev = True
            continue
        if cur is None:
            cur = {"src": src, "start": t, "after_lock_same_link": armed_prev is True,
                   "presence": [p for p in pending_presence if t - p[0] <= 40_000], "samples": [], "arm": None}
            pending_presence = []
        cur["samples"].append((t, int(ema), motion == "MOVING", int(thu), dist.replace(",", "."), int(raw)))
        armed_prev = False
    close()
    with open(OUT, "w") as o:
        for i, e in enumerate(episodes):
            e["id"] = i
            o.write(json.dumps(e) + "\n")
    # The replay input: fresh approaches only (after a Lock on the same link the walking route is latched off).
    with open(os.path.splitext(OUT)[0] + ".csv", "w") as o:
        for e in episodes:
            if e["after_lock_same_link"]: continue
            evs = [(p[0], "P", p[1], 1 if p[2] else 0, e["samples"][0][3]) for p in e["presence"]]
            evs += [(s[0], "S", s[1], 1 if s[2] else 0, s[3]) for s in e["samples"]]
            evs.sort(key=lambda x: (x[0], 0 if x[1] == "P" else 1))
            for t, k, r, m, th in evs: o.write(f"{e['id']},{t},{k},{r},{m},{th}\n")
    print("files", len(files), "events", len(evs), "episodes", len(episodes),
          "with field ARM", sum(1 for e in episodes if e["arm"]))

main()
