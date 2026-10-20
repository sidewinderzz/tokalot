"""
Builds the keyboard's English word list (app/src/main/assets/keyboard/en_US.txt.gz) from the
Android Open Source Project's English dictionary (LatinIME main_en_US.dict, Apache License 2.0;
the 2014 AOSP build, "version 741", as shipped in AOSP keyboard forks such as HeliBoard).

    python3 tools/keyboard/make_wordlist.py path/to/main_en-US.dict

Output, as UTF-8 text, gzipped:
    word<TAB>probability(0-255)[<TAB>x]   x: possibly offensive (accepted, never suggested)
    =bigrams
    word<TAB>next<TAB>next<TAB>next         the dictionary's likeliest next words, best first
    =shortcuts
    typed<TAB>replacement<TAB>strength(0-15)
"""
import struct, sys

def parse(path):
    b = open(path, 'rb').read()
    magic, ver, opts, hsize = struct.unpack('>IHHI', b[:12])
    assert magic == 0x9BC13AFE, hex(magic)
    words = {}   # node pos -> (word, prob, flags)
    bigram_raw = {}  # node pos -> [(target pos, p)]
    shortcuts = []  # (typed, target, p 0-15; 14+ means always replace)

    def u(pos, n):
        return int.from_bytes(b[pos:pos+n], 'big')

    def char(pos):
        c = b[pos]
        if c == 0x1F: return None, pos + 1
        if c < 0x20: return u(pos, 3), pos + 3
        return c, pos + 1

    stack = [(hsize, '')]
    while stack:
        pos, prefix = stack.pop()
        n = b[pos]; pos += 1
        if n >= 0x80: n = ((n & 0x7F) << 8) | b[pos]; pos += 1
        for _ in range(n):
            node = pos
            flags = b[pos]; pos += 1
            chars = []
            if flags & 0x20:
                while True:
                    c, pos = char(pos)
                    if c is None: break
                    chars.append(chr(c))
            else:
                c, pos = char(pos); chars.append(chr(c))
            word = prefix + ''.join(chars)
            prob = None
            if flags & 0x10: prob = b[pos]; pos += 1
            ct = flags & 0xC0
            child = None
            if ct:
                size = {0x40: 1, 0x80: 2, 0xC0: 3}[ct]
                child = pos + u(pos, size); pos += size
            if flags & 0x08:
                size = u(pos, 2); end = pos + size; p2 = pos + 2
                while p2 < end:
                    sf = b[p2]; p2 += 1
                    t = []
                    while True:
                        c, p2 = char(p2)
                        if c is None: break
                        t.append(chr(c))
                    shortcuts.append((prefix + ''.join(chars), ''.join(t), sf & 0x0F))
                    if not sf & 0x80: break
                pos = end
            bis = []
            if flags & 0x04:
                while True:
                    bf = b[pos]; pos += 1
                    size = {0x10: 1, 0x20: 2, 0x30: 3}[bf & 0x30]
                    origin = pos
                    off = u(pos, size); pos += size
                    bis.append((origin - off if bf & 0x40 else origin + off, bf & 0x0F))
                    if not bf & 0x80: break
            if prob is not None:
                words[node] = (word, prob, flags)
                if bis: bigram_raw[node] = bis
            if child is not None: stack.append((child, word))
    bigrams = []
    for src, lst in bigram_raw.items():
        for t, p in lst:
            if t in words: bigrams.append((words[src][0], words[t][0], p))
    return list(words.values()), bigrams, shortcuts



# Words the 2014 list lacks or ranks too low: texting shorthand, and farm, shop and machinery terms.
# Each is raised to at least this probability (0-255); nothing is ever lowered.
EXTRA = {
    "lol": 140, "lmao": 110, "omg": 120, "idk": 115, "thx": 110, "ty": 90, "btw": 115, "brb": 90, "imo": 95,
    "tbh": 105, "ikr": 85, "smh": 90, "np": 90, "pls": 100, "plz": 85, "ya": 100, "nah": 115, "ok": 150,
    "yep": 110, "nope": 110, "yup": 105, "hmm": 100, "haha": 125, "hahaha": 100, "lmk": 100, "cuz": 90,
    "swather": 70, "swathers": 60, "haybine": 60, "discbine": 55, "haylage": 60, "baleage": 55, "bushhog": 55,
    "hello": 150, "hey": 150, "hi": 130,
    "skidsteer": 60, "no-till": 60, "MIG": 70, "TIG": 70, "Fendt": 55, "Claas": 55, "Bobcat": 70, "windrower": 50,
}


def write(dict_path, out_path):
    words, bigrams, shortcuts = parse(dict_path)
    words = [w for w in words if not w[2] & 0x02]  # "not a word": entries that only carry a shortcut
    have = {w[0]: i for i, w in enumerate(words)}
    for w, p in EXTRA.items():
        if w in have:
            old = words[have[w]]
            words[have[w]] = (old[0], max(old[1], p), old[2])
        else:
            words.append((w, p, 0))
    words.sort(key=lambda w: (-w[1], w[0]))
    nexts = {}
    for a, c, _ in bigrams:
        nexts.setdefault(a, []).append(c)
    lines = ["# Tokalot keyboard word list. Source: AOSP LatinIME English dictionary, Apache License 2.0."]
    for w, p, flags in words:
        lines.append(w + "\t" + str(p) + ("\tx" if flags & 0x01 else ""))
    lines.append("=bigrams")
    for a in sorted(nexts):
        n = nexts[a]
        if n == ["the", "to", "of"]:
            continue  # the dictionary's filler for words it has no data on
        lines.append("\t".join([a] + n))
    lines.append("=shortcuts")
    for t, r, p in sorted(set(shortcuts)):
        lines.append(t + "\t" + r + "\t" + str(p))
    import gzip
    with gzip.GzipFile(out_path, "wb", mtime=0) as f:
        f.write(("\n".join(lines) + "\n").encode("utf-8"))
    print(len(words), "words,", len(nexts), "bigram lists,", len(shortcuts), "shortcuts")


if __name__ == "__main__":
    import os, sys
    here = os.path.dirname(os.path.abspath(__file__))
    write(sys.argv[1], os.path.join(here, "..", "..", "app", "src", "main", "assets", "keyboard", "en_US.txt.gz"))
