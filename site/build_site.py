#!/usr/bin/env python3
"""Build the published site into _site/: copy the static pages and render the canonical /legal
markdown docs to matching HTML, so the website always mirrors the in-app legal documents (single
source of truth in /legal). Uses a tiny built-in markdown subset renderer — no pip dependency —
matching the same subset the in-app renderer supports (headings, paragraphs, bullets, blockquote,
**bold**, [text](url) and <autolinks>).
"""
import html
import os
import re
import shutil

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SITE = os.path.join(ROOT, "site")
LEGAL = os.path.join(ROOT, "legal")
OUT = os.path.join(ROOT, "_site")

# Canonical legal markdown -> published html filename.
LEGAL_PAGES = {
    "privacy-policy.md": "privacy.html",
    "terms-of-service.md": "terms.html",
    "health-data-notice.md": "health-data.html",
    "support.md": "support.html",
    "open-source-licenses.md": "open-source-licenses.html",
}

TEMPLATE = """<!DOCTYPE html>
<html lang="en"><head><meta charset="utf-8" />
<meta name="viewport" content="width=device-width, initial-scale=1" />
<title>{title} — Wild Odds Gym Tracker</title>
<style>
:root {{ color-scheme: light dark; }}
body {{ font-family: system-ui, -apple-system, Segoe UI, Roboto, sans-serif;
  max-width: 720px; margin: 0 auto; padding: 40px 20px; line-height: 1.6; }}
a {{ color: #2f6fb0; }} h1 {{ font-size: 1.7rem; }} h2 {{ font-size: 1.2rem; margin-top: 28px; }}
blockquote {{ border-left: 3px solid #8886; margin: 16px 0; padding: 4px 14px; color: #8a8a8a; }}
</style></head><body>
{body}
<p style="margin-top:36px"><a href="index.html">← Home</a></p>
</body></html>
"""


def inline(text):
    text = html.escape(text)
    text = re.sub(r"\*\*(.+?)\*\*", r"<strong>\1</strong>", text)
    text = re.sub(r"\[(.+?)\]\((.+?)\)", r'<a href="\2">\1</a>', text)
    text = re.sub(r"&lt;(https?://[^&]+?)&gt;", r'<a href="\1">\1</a>', text)
    return text


def md_to_html(md):
    out, para, bullets = [], [], []

    def flush_para():
        if para:
            out.append("<p>" + " ".join(para) + "</p>")
            para.clear()

    def flush_bullets():
        if bullets:
            out.append("<ul>" + "".join(f"<li>{b}</li>" for b in bullets) + "</ul>")
            bullets.clear()

    for raw in md.replace("\r\n", "\n").split("\n"):
        line = raw.rstrip()
        if not line.strip():
            flush_para(); flush_bullets()
        elif line.startswith("### "):
            flush_para(); flush_bullets(); out.append(f"<h3>{inline(line[4:])}</h3>")
        elif line.startswith("## "):
            flush_para(); flush_bullets(); out.append(f"<h2>{inline(line[3:])}</h2>")
        elif line.startswith("# "):
            flush_para(); flush_bullets(); out.append(f"<h1>{inline(line[2:])}</h1>")
        elif line.startswith("- "):
            flush_para(); bullets.append(inline(line[2:]))
        elif line.startswith("> "):
            flush_para(); flush_bullets(); out.append(f"<blockquote>{inline(line[2:])}</blockquote>")
        else:
            flush_bullets(); para.append(inline(line.strip()))
    flush_para(); flush_bullets()
    return "\n".join(out)


def main():
    if os.path.exists(OUT):
        shutil.rmtree(OUT)
    os.makedirs(OUT)

    # Static site files (index.html, account-deletion.html, .nojekyll, etc.) — but NOT the stub
    # legal pages, which the generated versions below replace.
    generated = set(LEGAL_PAGES.values())
    for name in os.listdir(SITE):
        src = os.path.join(SITE, name)
        if os.path.isfile(src) and name not in generated and not name.endswith(".py"):
            shutil.copy(src, os.path.join(OUT, name))

    for md_name, html_name in LEGAL_PAGES.items():
        md_path = os.path.join(LEGAL, md_name)
        if not os.path.exists(md_path):
            continue
        with open(md_path, encoding="utf-8") as f:
            md = f.read()
        title = md.split("\n", 1)[0].lstrip("# ").strip()
        page = TEMPLATE.format(title=html.escape(title), body=md_to_html(md))
        with open(os.path.join(OUT, html_name), "w", encoding="utf-8", newline="\n") as f:
            f.write(page)
        print(f"rendered {md_name} -> {html_name}")


if __name__ == "__main__":
    main()
