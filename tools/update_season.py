"""Daily season check run by GitHub Actions (.github/workflows/season.yml).

Reads a few community season trackers and updates season.json only when at least
two of them agree. Anything unexpected leaves the file as it was, so the app keeps
showing the last good data (or hides the season line once its end date passes).

"schedule" in season.json overrides event cycles in the app. This script only writes
the world boss entry (marked "auto") when helltides.com shows a cycle that no longer
matches the app's built-in one; hand-written entries are left alone.
"""
import json
import re
import sys
import urllib.request
from collections import Counter
from datetime import datetime, timezone

PATH = "season.json"
UA = "Mozilla/5.0 (compatible; d4-boss-alarm season check)"
MONTHS = {m: i + 1 for i, m in enumerate(
    ["jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec"])}
MDY = r"([A-Z][a-z]{2,8}\.? \d{1,2}, \d{4})"   # December 2, 2026 / Sep 15, 2026
DMY = r"(\d{1,2} [A-Z][a-z]{2,8} \d{4})"        # 15 Sep 2026

SOURCES = {
    "https://seasondex.com/d4": {
        "no": [r"current Diablo IV season is Season (\d+)"],
        "name": [r"current Diablo IV season is Season \d+\s*[-–—]\s*(Season of [^.<]+?)\s*\."],
        "start": [MDY + r"\s*[-–—]\s*[A-Z][a-z]{2,8}\.? \d{1,2}, \d{4}"],
        "end": [r"expected to end on " + MDY, r"ends? on " + MDY, MDY + r"\s*\(estimated\)"],
    },
    "https://ggseason.com/diablo4/": {
        "no": [r"No\.\s*(\d+)", r"Season (\d+)"],
        "name": [],  # its page text runs the name into the next sentence
        "start": [r"went live on " + MDY],
        "end": [r"end (?:around|on) " + MDY, r"ends? on " + MDY],
    },
    "https://www.arpg-timeline.com/game/diablo-iv": {
        "no": [r"Season (\d+)\s*[-–—]\s*Season of"],
        "name": [r"Season \d+\s*[-–—]\s*(Season of [^-–—]+?)\s*[-–—]"],
        "start": [r"has started \w{3}, " + DMY],
        "end": [r"will end (?:on )?\w{3}, " + DMY, r"(?:ends|will end) on " + MDY],
    },
}
ESTIMATE_WORDS = re.compile(r"estimat|expected|projected|not been announced|not confirmed", re.I)


def text_of(url):
    req = urllib.request.Request(url, headers={"User-Agent": UA})
    with urllib.request.urlopen(req, timeout=20) as r:
        html = r.read().decode("utf-8", "replace")
    html = re.sub(r"(?s)<script.*?</script>|<style.*?</style>", " ", html)
    html = re.sub(r"<[^>]+>", " ", html)
    for a, b in (("&amp;", "&"), ("&#x27;", "'"), ("&#39;", "'"), ("&rsquo;", "'"), ("&nbsp;", " ")):
        html = html.replace(a, b)
    return re.sub(r"\s+", " ", html)


def to_date(s):
    """'December 2, 2026', 'Sep 15, 2026' or '15 Sep 2026' -> 'YYYY-MM-DD'."""
    parts = s.replace(",", "").replace(".", "").split()
    if parts[0].isdigit():
        day, mon, year = parts
    else:
        mon, day, year = parts
    month = MONTHS.get(mon[:3].lower())
    if not month:
        return None
    return f"{int(year):04d}-{month:02d}-{int(day):02d}"


def first(patterns, text):
    for p in patterns:
        m = re.search(p, text)
        if m:
            return m.group(1).strip()
    return None


def read_source(url, rules):
    text = text_of(url)
    out = {"no": first(rules["no"], text), "name": first(rules["name"], text)}
    for key in ("start", "end"):
        raw = first(rules[key], text)
        out[key] = to_date(raw) if raw else None
    out["no"] = int(out["no"]) if out["no"] and out["no"].isdigit() else None
    out["estimate"] = bool(ESTIMATE_WORDS.search(text))
    return out


def agreed(values):
    """The value at least two sources gave, else None."""
    c = Counter(v for v in values if v)
    if not c:
        return None
    value, n = c.most_common(1)[0]
    return value if n >= 2 else None


def merge(cur, found):
    """Returns the new season dict, or None when nothing is agreed."""
    no = agreed(f["no"] for f in found)
    if not no:
        return None
    same = [f for f in found if f["no"] == no]
    old = cur.get("season") or {}
    new_season = no != old.get("no")
    if not new_season and no < (old.get("no") or 0):
        return None
    season = {} if new_season else dict(old)
    season["no"] = no
    name = agreed(f["name"] for f in same) or next((f["name"] for f in same if f["name"]), None)
    if name:
        season["name"] = name
    if new_season:
        season["nameKo"] = ""
    start = agreed(f["start"] for f in same)
    if start:
        season["start"] = start + "T17:00:00Z"
    end = agreed(f["end"] for f in same)
    if end:
        season["end"] = end + "T17:00:00Z"
        season["estimate"] = any(f["estimate"] for f in same if f["end"] == end)
    elif new_season:
        season["end"] = None
        season["estimate"] = True
    if season.get("end") and season.get("start") and season["end"] <= season["start"]:
        season["end"] = None
    # A new season needs a start date; without one keep the old data
    if new_season and not season.get("start"):
        return None
    return season


# The app's built-in world boss cycle (Schedule.kt)
BOSS_ANCHOR_MS = 1792587600000
BOSS_PERIOD_MIN = 210
TIME_KEYS = ("startTime", "start_time", "start", "spawnTime", "spawn_time", "expected", "timestamp", "time", "date")


def devalue(flat):
    """Nuxt's flattened payload (__NUXT_DATA__) back into plain dicts and lists."""
    memo = {}

    def res(i, depth=0):
        if not isinstance(i, int) or i < 0 or i >= len(flat) or depth > 40:
            return None
        if i in memo:
            return memo[i]
        memo[i] = None  # breaks cycles
        v = flat[i]
        if isinstance(v, dict):
            out = {k: res(x, depth + 1) for k, x in v.items()}
        elif isinstance(v, list):
            tag = v[0] if v and isinstance(v[0], str) else None
            if tag is None:
                out = [res(x, depth + 1) for x in v]
            elif tag == "Date":
                out = v[1] if len(v) > 1 else None
            elif tag in ("Reactive", "ShallowReactive", "Ref", "ShallowRef"):
                out = res(v[1], depth + 1) if len(v) > 1 else None
            else:
                out = None
        else:
            out = v
        memo[i] = out
        return out

    return res(0)


def to_ms(v):
    if isinstance(v, bool):
        return None
    if isinstance(v, (int, float)):
        if 1.6e12 < v < 2.2e12:
            return int(v)
        if 1.6e9 < v < 2.2e9:
            return int(v * 1000)
        return None
    if isinstance(v, str) and re.match(r"^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}", v):
        try:
            return int(datetime.fromisoformat(v.replace("Z", "+00:00")).timestamp() * 1000)
        except ValueError:
            return None
    return None


def boss_times(html):
    """Spawn times (epoch ms) of named world bosses found in the page state."""
    times = set()
    for raw in re.findall(r"(?s)<script[^>]*__NUXT_DATA__[^>]*>(.*?)</script>", html):
        stack = [devalue(json.loads(raw))]
        while stack:
            o = stack.pop()
            if isinstance(o, dict):
                named = any(isinstance(o.get(k), str) and o.get(k) for k in ("name", "boss", "title"))
                if named:
                    for k in TIME_KEYS:
                        t = to_ms(o.get(k))
                        if t:
                            times.add(t - t % 60000)
                            break
                stack.extend(o.values())
            elif isinstance(o, list):
                stack.extend(o)
    return sorted(times)


def check_boss_cycle(cur):
    """Writes schedule.boss (auto) when helltides.com consistently shows a different cycle."""
    req = urllib.request.Request("https://helltides.com/worldboss", headers={"User-Agent": UA})
    with urllib.request.urlopen(req, timeout=20) as r:
        times = boss_times(r.read().decode("utf-8", "replace"))
    print("helltides boss times:", [datetime.fromtimestamp(t / 1000, timezone.utc).strftime("%m-%d %H:%M") for t in times])
    if len(times) < 3:
        return False
    gaps = {(b - a) // 60000 for a, b in zip(times, times[1:])}
    if len(gaps) != 1:
        print("gaps differ:", gaps)
        return False
    period = gaps.pop()
    if not 60 <= period <= 600:
        return False
    schedule = cur.setdefault("schedule", {})
    old = schedule.get("boss")
    if old and not old.get("auto"):
        return False  # hand-written; leave it
    off = (times[0] - BOSS_ANCHOR_MS) % (BOSS_PERIOD_MIN * 60000)
    matches_builtin = period == BOSS_PERIOD_MIN and min(off, BOSS_PERIOD_MIN * 60000 - off) <= 5 * 60000
    if matches_builtin:
        if old:
            del schedule["boss"]
            print("boss cycle back to built-in; override removed")
            return True
        return False
    new = {"anchor": datetime.fromtimestamp(times[0] / 1000, timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
           "periodMin": period, "durationMin": 15, "auto": True}
    if old and old.get("periodMin") == period and \
            (times[0] - to_ms(old["anchor"])) % (period * 60000) == 0:
        return False
    schedule["boss"] = new
    print("boss cycle changed:", new)
    return True


def main():
    with open(PATH, encoding="utf-8") as f:
        cur = json.load(f)
    found = []
    for url, rules in SOURCES.items():
        try:
            r = read_source(url, rules)
            print(url, r)
            found.append(r)
        except Exception as e:  # one dead site is fine; the others can still agree
            print(url, "failed:", e)
    try:
        cycle_changed = check_boss_cycle(cur)
    except Exception as e:
        print("boss cycle check failed:", e)
        cycle_changed = False
    season = merge(cur, found)
    if season is None:
        print("no agreement; season unchanged")
        if not cycle_changed:
            ok = sum(1 for f in found if f["no"]) >= 1
            return 0 if ok else 1   # fail (GitHub emails the owner) only when every site broke
    else:
        cur["season"] = season
    cur["checked"] = datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
    with open(PATH, "w", encoding="utf-8") as f:
        json.dump(cur, f, ensure_ascii=False, indent=2)
        f.write("\n")
    print("season.json:", cur)
    return 0


if __name__ == "__main__":
    sys.exit(main())
