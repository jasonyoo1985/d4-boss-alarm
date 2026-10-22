"""Daily season check run by GitHub Actions (.github/workflows/season.yml).

Reads a few community season trackers and updates season.json only when at least
two of them agree. Anything unexpected leaves the file as it was, so the app keeps
showing the last good data (or hides the season line once its end date passes).

"schedule" in season.json is an emergency override for event cycles; it is edited
by hand only and this script never touches it.
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
    season = merge(cur, found)
    if season is None:
        print("no agreement; season.json unchanged")
        ok = sum(1 for f in found if f["no"]) >= 1
        return 0 if ok else 1   # fail (GitHub emails the owner) only when every site broke
    cur["season"] = season
    cur["checked"] = datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
    with open(PATH, "w", encoding="utf-8") as f:
        json.dump(cur, f, ensure_ascii=False, indent=2)
        f.write("\n")
    print("season.json:", season)
    return 0


if __name__ == "__main__":
    sys.exit(main())
