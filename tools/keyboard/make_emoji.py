"""
Builds the keyboard's emoji list (app/src/main/assets/keyboard/emoji.txt) from two MIT-licensed
sources by Mu-An Chiou: unicode-emoji-json's data-by-group.json (order, groups, names, skin tones)
and emojilib's emoji-en-US.json (search keywords).

    python3 tools/keyboard/make_emoji.py data-by-group.json emoji-en-US.json

Output: a "=Group name" line starts each group, then one line per emoji:
    emoji<TAB>1 if it takes skin tones else 0<TAB>search words, space separated
"""
import json, os, sys


def write(groups_path, keywords_path, out_path):
    groups = json.load(open(groups_path, encoding="utf-8"))
    keywords = json.load(open(keywords_path, encoding="utf-8"))
    lines = []
    for g in groups:
        lines.append("=" + g["name"])
        for e in g["emojis"]:
            words = []
            for w in [e["name"]] + keywords.get(e["emoji"], []):
                for part in w.replace("_", " ").lower().split():
                    if part not in words and part.isascii():
                        words.append(part)
            lines.append(e["emoji"] + "\t" + ("1" if e.get("skin_tone_support") else "0") + "\t" + " ".join(words))
    with open(out_path, "w", encoding="utf-8") as f:
        f.write("\n".join(lines) + "\n")
    print(sum(len(g["emojis"]) for g in groups), "emoji in", len(groups), "groups")


if __name__ == "__main__":
    here = os.path.dirname(os.path.abspath(__file__))
    write(sys.argv[1], sys.argv[2], os.path.join(here, "..", "..", "app", "src", "main", "assets", "keyboard", "emoji.txt"))
