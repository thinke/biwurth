#!/usr/bin/env python3
"""
Makes a biwurth theme from a web design system's tokens.

    tools/theme-from-tokens.py tokens.json [out-dir]

tokens.json names the look in web terms; this maps it onto theme.json, recolours the line icons
in tools/icons/ to the system's icon colour and adds the Overview page template. out-dir defaults
to app/src/main/assets/themes/<id> (a built-in theme); zip that folder to install it on a panel
instead (POST /api/themes).

    {
      "id": "bootstrap", "name": "Bootstrap", "description": "...",
      "dark": false,
      "page": "#f8f9fa", "surface": "#ffffff", "text": "#212529", "textSecondary": "#6c757d",
      "primary": "#0d6efd", "onPrimary": "#ffffff", "border": "#dee2e6", "icon": "#0d6efd",
      "radius": 6, "padding": 16, "gap": 16, "borderWidth": 1, "shadow": 1,
      "headingWeight": "bold" | "medium" | "regular", "headingColor": "text" | "muted",
      "cardHeaderLine": true, "numberWeight": "light" | "regular" | "bold",
      "clockWeight": "thin" | "light" | "regular", "switches": true,
      "chartColors": ["#...", ...], "chartGrid": true, "chartFill": true, "chartLineWidth": 2,
      "listLines": true, "font": "system" | "inter", "animations": true,
      "toggle": "switch" | "fill" | "icon", "tileLayout": "stacked" | "compact",
      "icons": "none" | "badge" | "bubble", "buttonShape": "card" | "pill",
      "thermostat": "dial" | "numbers", "slider": "bar" | "fill"
    }
"""
import json
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
ICONS = ROOT / "tools" / "icons"
OVERVIEW = ROOT / "tools" / "templates" / "overview.json"


def theme(t):
    return {
        "id": t["id"],
        "name": t["name"],
        "description": t.get("description", ""),
        "base": "dark" if t.get("dark") else "light",
        "colors": {
            "background": t["page"], "card": t["surface"], "text": t["text"], "muted": t["textSecondary"],
            "accent": t["primary"], "onAccent": t.get("onPrimary", "#ffffff"),
        },
        "font": t.get("font", "system"),
        "textScale": 1,
        "tileOpacity": 100,
        "gap": t.get("gap", 16),
        "animations": t.get("animations", True),
        "cards": True,
        "tiles": {
            "radius": t.get("radius", 8), "padding": t.get("padding", 16),
            "borderWidth": t.get("borderWidth", 1), "borderColor": t.get("border", ""), "shadow": t.get("shadow", 0),
            "titleWeight": t.get("headingWeight", "medium"), "titleColor": t.get("headingColor", "text"),
            "titleDivider": t.get("cardHeaderLine", False),
            "valueWeight": t.get("numberWeight", "regular"), "clockWeight": t.get("clockWeight", "regular"),
            "toggleStyle": t.get("toggle", "switch" if t.get("switches", True) else "fill"),
            "graphLineWidth": t.get("chartLineWidth", 2), "graphFill": t.get("chartFill", True),
            "graphGrid": t.get("chartGrid", False), "graphColors": t.get("chartColors", []),
            "listDividers": t.get("listLines", False), "calendarColors": True,
            "tileLayout": t.get("tileLayout", "stacked"), "icons": t.get("icons", "none"),
            "buttonShape": t.get("buttonShape", "card"),
            "thermostatStyle": t.get("thermostat", "numbers"), "sliderStyle": t.get("slider", "bar"),
        },
        "background": {"type": "color", "color": t["page"]},
    }


def main():
    tokens = json.loads(pathlib.Path(sys.argv[1]).read_text())
    out = pathlib.Path(sys.argv[2]) if len(sys.argv) > 2 else ROOT / "app/src/main/assets/themes" / tokens["id"]
    (out / "pictures").mkdir(parents=True, exist_ok=True)
    (out / "templates").mkdir(parents=True, exist_ok=True)
    (out / "theme.json").write_text(json.dumps(theme(tokens), indent=2) + "\n")
    icon = tokens.get("icon", tokens["primary"])
    for svg in sorted(ICONS.glob("*.svg")):
        (out / "pictures" / svg.name).write_text(re.sub(r'stroke="#[0-9a-fA-F]{6}"', f'stroke="{icon}"', svg.read_text()))
    template = json.loads(OVERVIEW.read_text())
    (out / "templates" / "overview.json").write_text(json.dumps(template, indent=1) + "\n")
    print(f"{out}: theme.json, {len(list(ICONS.glob('*.svg')))} icons, 1 template")


if __name__ == "__main__":
    main()
