# Flue Watchface Agent Web — prototype plan

User authorized a runnable local web prototype with Chat Completions, Responses,
Claude Messages, image generation and Tavily configuration; embedded WatchMaker
format instructions; image tools and whole-face / single-layer previews. General
workspace browsing and Bash are deferred. Do not modify Android source or existing
dirty work. No deployment, git commit or global installation is required.

1. Python local server: static assets, provider protocol adapters, image/search
   proxies, fixed Pillow/NumPy operations, safe .watch import/export; request and
   archive limits, same-origin localhost binding, no key persistence or shell.
2. Browser application: warm neutral editor with live round watch canvas, chat,
   layer/material/XML panels, settings and prompt inspector; cancellable tool loop,
   undo, import/export, offline example explicitly marked as a sample.
3. Shared domain: native .watch XML as editable source; validated DOM patching;
   browser renderer supports documented subset, reports unsupported behavior;
   built-in authored format guides with source links and runtime capability list.
4. Verification: mock HTTP provider/tool round trips for all three protocols,
   image operations and archives, actual browser workflow/screenshots, responsive
   layout, review specification first then code quality and fix findings.

The browser preview is a prototype, not the Android Flue rendering engine. Lua
scripts must remain exportable; unsupported script behavior must be reported.
The backend has Python 3.12, Pillow and NumPy available, exposed as fixed tools.

Sources: https://gitlab.com/ailife8881/watchmaker-definition
https://gitlab.com/ailife8881/clox and https://gitlab.com/ailife8881/wftools
