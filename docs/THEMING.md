# I2P+ Console & Webapp Theming Guide

## Overview

The I2P+ theming system supports four built-in themes (`dark`, `light`, `classic`, `midnight`) and allows full customization via `override.css` files. Themes span the router console and all webapps (susimail, susidns, i2psnark, login, i2ptunnel). Each webapp has its own theme directory under the same theme name so a single theme selection applies across all apps.

### Theme directory layout

```
docs/themes/           <-- deployed at runtime under $I2P/
├── fonts/             <-- shared web fonts (OpenSans, Sora, FiraCode)
├── console/           <-- Router Console themes
│   ├── shared.css     <-- CSS rules shared by all console themes
│   ├── confignav.css  <-- config navigation
│   ├── helpnav.css    <-- help navigation
│   ├── tablesort.css  <-- sortable tables
│   ├── tunnels.css    <-- tunnel page styling
│   ├── viewprofile.css<-- peer profile page
│   ├── mobile.css     <-- mobile responsive defaults
│   ├── graphConfig.css
│   ├── images/        <-- shared SVG icons (as CSS custom properties)
│   │   ├── images.css <-- all icon data-URIs
│   │   ├── itooplus.css
│   │   └── i2ptunnel.css
│   ├── dark/          <-- default theme
│   │   ├── global.css <-- CSS custom properties (colors, gradients, shadows)
│   │   ├── console.css<-- main stylesheet, @imports global.css, shared.css
│   │   ├── chromescroll.css
│   │   ├── console_big.css, console_ar.css, mobile.css, wizard.css
│   │   ├── i2ptunnel.css
│   │   ├── images/    <-- theme-specific images (logo, favicon, thumbnail)
│   │   └── override.css.ocean.blue  <-- example override files (rename to override.css to use)
│   ├── light/
│   │   ├── global.css, console.css, ...
│   │   └── override.css.*
│   ├── classic/
│   │   └── ...
│   └── midnight/
│       └── ...
├── susimail/          <-- webmail themes
│   ├── shared.css
│   ├── images/
│   ├── dark/, light/, classic/, midnight/
│       └── susimail.css, mobile.css, images/
├── susidns/           <-- address book themes
│   ├── shared.css, lazyload.css
│   ├── images/
│   └── dark/, light/, classic/, midnight/
│       └── susidns.css, images/
├── snark/             <-- BitTorrent client themes
│   ├── shared.css
│   ├── dark/, light/, classic/, midnight/, ubergine/, vanilla/, zilvero/
│       └── snark.css, nocollapse.css, snark_big.css, images/
├── login/             <-- login page themes
│   ├── shared.css
│   └── dark/, light/, classic/, midnight/
│       └── login.css
├── geomap/
│   └── geomap.css
└── imagegen/
    └── imagegen.css
```

> **Source location:** `installer/resources/console/themes/` in the source tree.  
> **Deployed to:** `$I2P/docs/themes/` at runtime (copied by the `prepthemeupdates` build target).  
> **Not bundled in WAR files** — themes live on the filesystem so they survive upgrades and can be edited in place.

---

## Theme Selection

### Config property

The active theme is controlled by the router config property:

```
routerconsole.theme=dark
```

Default is `dark`. Valid values are any directory name under `docs/themes/console/`.

### How it's read

`CSSHelper.java` (`apps/routerconsole/java/src/net/i2p/router/web/CSSHelper.java`) resolves the theme path:

```java
public static final String PROP_THEME_NAME = "routerconsole.theme";
public static final String DEFAULT_THEME = "dark";
public static final String BASE_THEME_PATH = "/themes/console/";

public String getTheme(String userAgent) {
    String url = BASE_THEME_PATH;
    if (userAgent != null && userAgent.contains("MSIE") && !userAgent.contains("Trident/6")) {
        url += "classic/";  // force classic for old IE
    } else {
        String theme = _context.getProperty(PROP_THEME_NAME, DEFAULT_THEME);
        url += theme + "/";
    }
    return url;
}
```

### Universal theming

When `routerconsole.universal.theme=true`, all webapps (susimail, susidns, i2psnark) use the same `routerconsole.theme` value. When false, each webapp can have its own saved theme preference (stored in the respective app's config file).

### Plugin themes

Third-party themes can be registered via `routerconsole.theme.<name>=/path/to/theme/dir`. The `viewtheme.jsp` servlet resolves these paths (see [Theme Serving](#theme-serving)).

### Per-webapp theme override

Each webapp reads the console theme independently:

| Webapp    | Class / Method                      | Theme path                                      |
| --------- | ----------------------------------- | ----------------------------------------------- |
| Console   | `CSSHelper.getTheme()`              | `/themes/console/<theme>/`                      |
| I2PTunnel | `IndexBean.getTheme()`              | `/themes/console/<theme>/` (shares console dir) |
| SusiDNS   | `BaseBean.getTheme()`               | `/themes/susidns/<theme>/`                      |
| Susimail  | `WebMail.java`                      | `/themes/susimail/<theme>/`                     |
| I2PSnark  | `SnarkManager.getTheme()`           | `/themes/snark/<theme>/`                        |
| Login     | reads `docs/themes/login/theme.txt` | `/themes/login/<theme>/`                        |

---

## CSS Loading Order (Console)

From `head.jsi` (`apps/routerconsole/jsp/head.jsi`), CSS is loaded in this exact order:

```
 1. <theme>/global.css              -- CSS custom property definitions (variables)
 2. font stylesheet                 -- /themes/fonts/OpenSans.css or Sora.css
 3. <theme>/console.css             -- main theme stylesheet (may @import shared.css)
     └─ @import url(global.css)     --   (already loaded, cached)
     └─ @import url(../shared.css)  --   cross-theme reusable rules
     └─ @import url(../images/itooplus.css) -- I2P+-specific icon overrides
 4. /themes/console/images/images.css  -- shared SVG icon data-URIs
 5. <theme>/images/images.css          -- theme-specific icon overrides
 6. Language-specific CSS              -- console_big.css (zh), console_ar.css (ar/fa), etc.
 7. <theme>/override.css               -- user customization (loaded LAST → highest priority)
```

> `override.css` is only included if the file exists on disk at `docs/themes/console/<theme>/override.css`.

---

## The `override.css` System

`override.css` is the primary customization mechanism. It is a user-created CSS file placed in a theme directory. Because it loads after all other CSS, any rule in `override.css` wins over the theme defaults.

### How it works

1. **Create the file:** Place `override.css` in `$I2P/docs/themes/console/<theme>/override.css`
2. **It's checked on every page load** — `head.jsi` tests for existence via `new File(themeBase + "override.css").exists()`
3. **It survives upgrades** — `override.css` is not shipped with the router and is never overwritten
4. **Cache behavior:** `viewtheme.jsp` sends `Cache-Control: no-cache, private, max-age=2628000` for `override.css` URLs, bypassing the `immutable` cache policy of regular theme assets

### Shipping example files

Each theme directory may include example override files with descriptive names rather than `override.css` itself. Users rename one to activate it:

| Directory           | Example files                                                                                                                   |
| ------------------- | ------------------------------------------------------------------------------------------------------------------------------- |
| `console/dark/`     | `override.css.ocean.blue`, `override.css.purple`, `override.css.red`                                                            |
| `console/light/`    | `override.css.charcoal`, `override.css.flat`, `override.css.lowlight`, `override.css.solar.green`, `override.css.solarsurprise` |
| `console/midnight/` | `override.css.purple`                                                                                                           |
| `snark/dark/`       | `override.css.ocean.blue`                                                                                                       |
| `snark/ubergine/`   | `override.css.bandwidth`, `override.css.no_status_text`                                                                         |
| `susimail/light/`   | `override.css.bottom.notifications`, `override.css.charcoal`                                                                    |
| `susidns/light/`    | `override.css.charcoal`                                                                                                         |

### Example: Ocean Blue override

```css
/* Override the dark theme with a blue/cold hue rotation */
html {
    filter: hue-rotate(120deg);
}
body {
    background: repeating-linear-gradient(to right, rgba(0,0,0,0.8) 1px,
                rgba(0,0,0,0.8) 2px, rgba(0,0,32,0.6) 3px) #000;
}
img, #sb_localtunnels img, .sb *::before, #routerlogs li, .tunnelBuildStatus {
    filter: hue-rotate(-120deg) !important;
}
```

Each webapp also checks for its own `override.css`:
- **Susimail** (`WebMail.java`, ~line 2270): `<link rel=stylesheet href=.../override.css>`
- **SusiDNS** (`BaseBean.isOverrideCssActive()`): checks `docs/themes/susidns/<theme>/override.css`
- **I2PSnark** (`I2PSnarkServlet.java`): includes it conditionally at line ~4745

---

## Theme Architecture

### CSS Custom Properties (variables)

Each theme defines its visual identity in `global.css` via `:root` CSS custom properties. The dark theme defines ~169 variables including:

| Variable         | Purpose                               |
| ---------------- | ------------------------------------- |
| `--bodybg`       | Page background (gradient)            |
| `--a`            | Link color                            |
| `--active`       | Active link/highlight color           |
| `--hover`        | Hover color (typically `#f60` orange) |
| `--ink`          | Primary text color                    |
| `--border`       | Standard border                       |
| `--btn`          | Button background gradient            |
| `--input_txt`    | Input field background                |
| `--helpbox`      | Help box background                   |
| `--badge`        | Badge/pill background                 |
| `--download_bar` | Download progress bar pattern         |
| `--highlight`    | Highlight shadow inset                |
| `--camo`         | Camouflage SVG filter overlay         |
| `--graphoverlay` | Graph overlay pattern                 |

Webapps `@import` the console's `global.css` to inherit these variables:

```css
/* susimail/dark/susimail.css */
@import url(/themes/console/dark/global.css);
@import url(../images/images.css);
@import url(images/images.css);
```

### Icon system

All icons are delivered as **inline SVG data URIs** stored in CSS custom properties. The shared file `console/images/images.css` defines ~100+ icons:

```css
:root {
    --abook:url("data:image/svg+xml,%3Csvg viewBox='0 0 64 64'...");
    --add:url("data:image/svg+xml,%3Csvg ...");
    --ban:url("data:image/svg+xml,%3Csvg ...");
    --clock:url("data:image/svg+xml,%3Csvg ...");
    --configure:url("data:image/svg+xml,%3Csvg ...");
    ...
}
```

Usage in CSS:
```css
.someElement {
    background: var(--add) no-repeat center;
}
```

Theme-specific icon overrides live in `<theme>/images/images.css`. The I2P+-specific icons are in `console/images/itooplus.css`.

### Theme serving

All theme files are served at runtime by `viewtheme.jsp` (`apps/routerconsole/jsp/viewtheme.jsp`), which:

1. Resolves content type from file extension (`css`, `png`, `svg`, `woff2`, etc.)
2. Resolves plugin theme paths via `routerconsole.theme.<name>` properties
3. Serves from `$I2P/docs/` (not from the WAR)
4. Sets cache headers:
   - `override.css`: `no-cache, private, max-age=2628000`
   - Static assets: `private, max-age=2628000, immutable`
   - Everything else: `no-cache, private, max-age=2628000`

Theme resources at `/themes/console/<theme>/images/thumbnail.png`, `favicon.svg`, `i2plogo.png` are public (no auth required via `AuthFilter.java`).

---

## Graph Color Integration

Graph images — the RRD plots on `graphs.jsp` — take **seventeen** themeable values, and the
stylesheet is the only place a theme states them. There is no per-theme table in the code to
keep in step; see [Defaults live in one place](#defaults-live-in-one-place).

### The full set

```css
:root{
  /* the two plots */
  --graph_plotLine1:#37c88e;     /* stroke of the first plot    */
  --graph_plotLine2:#ff6710;     /* stroke of the second plot   */
  --graph_plotFill1:#004808dc;   /* fill under the first plot   */
  --graph_plotFill2:#64c8a0dc;   /* fill under the second plot  */
  --graph_plotDash:1;            /* dot pattern, see below      */

  /* how heavy a plot line is drawn */
  --graph_plotLineWidth:2;       /* up to 800px wide            */
  --graph_plotLineWidthWide:2.5; /* past 800px wide             */

  /* text */
  --graph_textColor:#c9ceff;     /* labels, legend, title       */
  --graph_axisColor:#c9ceff;     /* the two axis rules          */

  /* the grids */
  --graph_gridMinor:#20408040;   /* minor gridlines             */
  --graph_gridMajor:#ff20c070;   /* major gridlines             */
  --graph_gridMinorDash:1 3;     /* minor gridline dot pattern  */
  --graph_gridMajorDash:0;       /* solid major gridlines       */
  --graph_gridCompact:#ff20c070; /* what a too-small tile keeps */

  /* the plot area */
  --graph_background:#000000c0; /* the plot and its margin ring */
  --graph_edgeShade:#00000000;   /* shading along bottom and left */
  --graph_restartMarker:#dc1030dc; /* the restart rule          */
}
```

### Why two plots, and why four colours

**A graph tile draws at most two plots.** Three are too close together to tell apart at tile
size. Combined graphs therefore hold two stats at most (`GraphGroups.MAX_SERIES`), and any
member past that limit gets a graph of its own rather than being squeezed in or dropped.

**Each of those two plots can be drawn three ways:**

| Mode                          | Drawn as                         |
| ----------------------------- | -------------------------------- |
| default, single stat          | a filled area                   |
| line mode                     | a stroke                        |
| filled paths (`graphFill`)    | a filled area plus a thin stroke |

So one plot needs a colour for its stroke *and* a colour for its fill, and with two that is
four values: `--graph_plotLine1` / `--graph_plotLine2` for the strokes, `--graph_plotFill1` /
`--graph_plotFill2` for the fills. A given mode uses a subset, but every one of the four is
read by some mode, which is why all four exist.

Note that the two are independent: `--graph_plotLine1` may be a saturated green while
`--graph_plotFill1` is a dark translucent version of it. Choosing them separately is the
point, because the fill sits under the line rather than being it.

**The two-plot limit is enforced above jrobin.** jrobin itself will draw any number of plots;
the cap lives in the console's group registry, and `GraphThemeColors` clamps a plot ordinal
past the second onto slot 2 rather than throwing. So a third plot, should one ever appear,
would collide on colour with the second — a visible bug rather than a crash. `GraphGroupsTest`
fails the build if any group exceeds the limit, so that has to be fixed before it can happen.

### Value syntax by kind

| Kind | Variables | Accepted |
| ---- | --------- | -------- |
| Colour | everything below except the dash and length ones | any CSS colour, see below |
| Dash pair | `--graph_plotDash`, `--graph_gridMinorDash`, `--graph_gridMajorDash` | `1`, `1 3`, `1,3`, `0` |
| Length | `--graph_plotLineWidth`, `--graph_plotLineWidthWide` | a bare number of pixels |
| Colour list | `--graph_plotLine1/2`, `--graph_plotFill1/2` with 2+ colours | space-separated, see Gradients below |

### Colour syntax

Any CSS colour is accepted, because the value is handed to `SvgColor.parse()`:

| Form                       | Example                |
| -------------------------- | ---------------------- |
| 3-digit hex                | `#0f9`                 |
| 4-digit hex (hex + alpha)  | `#ee9d`                |
| 6-digit hex                | `#00ff99`              |
| 8-digit hex (hex + alpha)  | `#00ff9980`            |
| `rgb()` / `rgba()`         | `rgba(0,255,153,.5)`   |
| percent channels and alpha | `rgb(0%,100%,60%)`     |

Shorthand digits are doubled, per CSS, so `#ee9d` is `#eeee99dd` — not `#e9d9`.

### Gradients on fills and lines

Both plot variables accept two or more colours, in which case they become a **vertical
gradient** rather than a flat colour. Two spellings, equivalent:

```css
--graph_plotFill1:#2ec23e40 #f0000008 #d0000008;              /* bare list   */
--graph_plotFill1:linear-gradient(#2ec23e40,#f0000008,#d0000008);  /* explicit */
```

Alpha needs no extra variable — put it in the colour, as `#2ec23e40` does. Both
spellings accept 3/4/6/8-digit hex and `rgb()`/`rgba()`, in any mix. The bare list is split on
**spaces**, because a colour may itself contain commas; the `linear-gradient(...)` form is split
on top-level commas, so `rgba(46,194,62,.25)` stays intact.

**Stops read bottom to top**: the first colour is the lowest value, the last the highest. This
is the reverse of CSS, where `linear-gradient(to top, a, b)` puts `a` at the bottom, so a bare
list here ascends where the equivalent CSS descends. Reversing your stops reverses the fade:

```css
--graph_plotLine1:#2ec23e40 #f0000008 #d0000008;   /* low is green, high is dark red */
```

All four are independent: shade one and leave the other flat, mix notations freely, or set any
number of stops.

On a **fill** the gradient spans the frame. On a **line** it spans the *plot area*, which is what
makes the shading read by value — a point's height above the baseline is its magnitude, so the
colour at that height is the colour for that value. The first colour is still what the legend
swatch shows, since a swatch cannot show a gradient.

Three limits:

- **Stops are spread evenly.** Three land at 0%, 50% and 100%; there is no way to place one at
  90%, and a fractional position inside a list is ignored.
- **Direction is ignored.** The gradient is always vertical, since an area under a series is a
  vertical wash. A stated `to right` or `45deg` is skipped rather than guessed at.
- **One colour is not a gradient.** It stays flat, which is what makes naming two the way to opt
  in.

Before reaching for a line gradient, weigh that a value-shaded line no longer reads as one series
colour — it becomes a heatmap treatment, which suits a single-series tile and muddies the
two-series case where the plots are told apart by hue. It also sits awkwardly beside
`--graph_plotDash`, the dot pattern that distinguishes a second plot on one axis.

### The dot pattern

When two plots share an axis the second is drawn dotted, so the two stay separable where
they cross. `--graph_plotDash` is the length of one dot, or a CSS dash pair:

```css
--graph_plotDash:1;      /* 1px dot, gap derived from the line width */
--graph_plotDash:1 3;    /* 1px dot, then 3px of space   */
--graph_plotDash:1,3;    /* identical; comma or space    */
--graph_plotDash:0;      /* no dots at all: solid lines  */
--graph_plotDash:0 3;    /* the same; a gap needs a dot to follow it */
```

A dot length of zero is the way to turn the pattern off: both plots are drawn solid.
A zero *gap* does not do that — `--graph_plotDash:1 0` keeps the dots and simply lets the
gap be derived, because the pair's second value is only spacing.

Comma and space are interchangeable. A stated gap is honoured only when it leaves the dots
visibly separate: with round caps each dot lays down `dot + width` of ink, so the gap has to
be at least `max(dot × 2, width × 2)` or consecutive dots merge and a "dotted" line renders
solid. A tighter request is raised to that floor rather than obeyed, so a theme cannot
break the pattern by accident. The bare-length form is the safe default, since a theme that
does not care about spacing cannot get it wrong.

Lists longer than two values are refused, not truncated. CSS would cycle an odd-length
`stroke-dasharray` to make it even, but the floor above is stated for one dot and one gap,
and dropping the tail would render something the stylesheet never asked for.

### Line width

`--graph_plotLineWidth` sets the stroke weight of a plot line, and
`--graph_plotLineWidthWide` replaces it past a frame width of **800px** — a layout constant
rather than a theme choice, since a tile is drawn wider when the console has room and a longer
series shows a thinner stroke's gaps more readily. The two are separate so a theme can weight
a long plot more heavily without changing the rest of the console.

**These are seeded to `2` and `2.5`** in every shipped theme, and two things are worth knowing
before you tune them:

- **A width of exactly `1` emits no SVG at all.** The writer suppresses `stroke-width:1` as the
  default, so setting `1` produces output identical to leaving the variable out. Use `1.2` if
  you want *barely* heavier and are surprised to get nothing.
- **The `Wide` value only applies past 800px, and a default tile is 400px wide.** It is
  unreachable until a user raises the graph width themselves, so treat it as opt-in for
  wide-screen layouts rather than something the standard console will show you.

A width past roughly `0.1`–`16` is refused and falls back, because a width that small drops
the line and one that large covers the data it is meant to show.

**Stepped plots look heavier than smoothed ones at the same width, and that is correct.** The
emitted stylesheet asks for `shape-rendering:crispEdges` and `vector-effect:non-scaling-stroke`,
so axis-aligned steps snap to the pixel grid while curves are resampled across it. Raise the
width if you want a smoothed plot to match a stepped one visually; there is no compensation
for it in the renderer.

Note that this governs **stroked plots only**. The default single-stat tile is a filled area
with no outline at all, so there is nothing there for a width to control. The filled-path mode
(`graphFill`) does draw an outline, and that one is **half** the plot line width rather than a
constant — so a theme that draws heavy plots gets a proportionally heavy edge.

Three further cases are **not** the theme's, and override whatever you declare:

| Case | Width | Why |
| ---- | ----- | --- |
| A whole group on one axis | `1.5` | up to six plots share the axis and a heavier line merges them |
| The sidebar sparkline | `3` | 250×50 and unlabelled, where a hairline nearly vanishes |
| A tile carrying many periods | `1` | the plots are crowded, which is the grouped case's problem too |

### Fonts are not yours to set

A graph takes its faces from the console's own font variables, `--monospaced` for the axis
labels, tick labels and units, and `--bodyfont` for the legend and title. Those live in
`themes/fonts/*.css` and are what the rest of the console draws with, so a graph matches the
page it sits on and changing the console's font set moves the graphs with it. There is
deliberately no `--graph_fontFamily*` to set: a second pair could only disagree with the
first.

Each graph is served as an isolated document, so it cannot inherit the page's properties — it
`<link>`s the font stylesheet instead, which is what lets `var()` resolve inside it. If that
link is ever removed, the text falls back to `monospace`/`sans-serif` rather than losing its
family, so a broken stylesheet degrades instead of blanking.

Two escape hatches still exist, both deliberate and neither a theme property:

- `routerconsole.graphFont.unit`, `.legend` and `.title` name a family outright, for an
  installation that wants a different face from the one the console uses.
- For `zh`, `jp` and `ko` the renderer picks a CJK face ahead of the generic one, because
  those are the families that carry the glyphs at all. A console whose `--bodyfont` has no
  CJK coverage still gets readable labels.

### Where the values are read, and why server-side

Unlike the minigraph, which resolves `--minigraph_*` in the browser with
`getComputedStyle`, graph values are read by the **server**, in `GraphThemeColors.java`.
A graph is served as `<img src="/viewstat.jsp?stat=...">`, which makes the SVG an isolated
document: page CSS does not reach inside it and page custom properties are invisible to it.
Declaring the variables in the SVG itself would only let a user edit a file they cannot
reach, so `GraphThemeColors` parses the theme's own `console.css` and hands the resolved
values to the renderer.

Consequences worth knowing:

- The stylesheet is re-read when its last-modified timestamp changes, so **editing a theme and
  refreshing the page is enough** to see the new values - no router restart, matching how the
  minigraph behaves.
- It is also re-read every 30 seconds regardless. That is the fallback for a filesystem that
  does not maintain last-modified reliably - a network mount, a container overlay - or whose
  timestamps are too coarse to distinguish two saves in the same second. A periodic read is
  what makes the update "more or less realtime" rather than dependent on the clock the
  filesystem keeps.
- A warm lookup costs one `stat()`, not a re-read. The re-read is coalesced: the tiles of one
  page share it, so a page of twenty graphs reads the file once rather than twenty times.
- A stylesheet missing when first asked about is remembered as missing, but re-checked on
  every lookup, so a theme deployed after startup still takes effect.

Note that none of this involves the browser's copy of the stylesheet. The values are resolved
on the router, which reads the theme file itself; how the browser caches the same file for the
rest of the page is a separate question.
- Any value a theme omits, or states something unparseable in, falls back to the built-in
  value on its own. One bad declaration costs one value, not the graph.

### Defaults live in one place

`GraphThemeColors` holds a single fallback set, and it is **light's** — the console's default
look. It is reached only when a theme's stylesheet declares nothing at all: an unknown theme
name, or a layout with no stylesheet. Neither case has a theme identity to honour, so there is
nothing per-theme about those values.

This is deliberate. The same values used to appear in `GraphThemeColors.java` *and* in every
theme's `console.css`, mirrored row for row, and a test asserted the two agreed. It worked
until it didn't: the code copy drifted from the CSS, and the mirror had to be policed by a
test that could only notice after the fact. With CSS as the sole statement of a theme's look,
there is nothing to keep in step.

Two tests guard this instead, and between them they catch the two ways this goes wrong:

- `everyShippedThemeDeclaresEveryGraphVariable` — every `--graph_*` variable the code reads is
  declared by every shipped theme. Catches a theme that omits one and would silently render in
  light's value while looking, from its own stylesheet, like a deliberate choice.
- `theLineWidthFallbackMatchesTheLightStylesheet` — the Java fallback equals what light
  declares. Nothing keeps these in step automatically, so changing light's width without
  changing the fallback would otherwise go unnoticed until some unthemed layout rendered at the
  wrong weight.

`everyThemeKeepsOneOfItsOwnGridsOnASmallTile` adds a third, narrower rule: a theme's
`--graph_gridCompact` must equal one of its own two grids.

### The tuning-page history bar is a different mechanism

The tuning page (`tuning.jsp`) draws a small inline diverging bar chart of a parameter's
recent values against its default. That one is **not** an RRD image and does not go through
jrobin: `TuningHelper` emits the `<svg>` inline and paints the bars with
`fill="var(--tunerGraph)"`, which the browser resolves like any other custom property.

It was named `--graphbar` until it was renamed to **`--tunerGraph`**, because `--graphbar`
read as though it coloured the graph images above when it only ever coloured this bar. The
rename touched three places, all of which must agree or the bars go unpainted:

| Location                                                            | Role                                  |
| ------------------------------------------------------------------- | ------------------------------------- |
| `themes/console/shared.css`                                         | base value, `#292`, for every theme  |
| `themes/console/dark/console.css`                                   | dark override, `#f60`                 |
| `themes/console/light/console.css`                                  | light override, `#78a`                |
| `helpers/TuningHelper.java` (`var(--tunerGraph)`)                   | the consumer                          |

`midnight` and `classic` do not override it and take the `shared.css` base. Note there is
no fallback in the `var()` call, so a theme that overrode the variable to nothing would leave
those bars unpainted rather than falling back — keep the `shared.css` declaration.

### The grids

`--graph_textColor` covers every glyph on the tile: axis labels, tick labels, the legend and
the title. `--graph_gridMinorDash` and `--graph_gridMajorDash` take the same dot-and-gap form
as `--graph_plotDash` and go through the same length rules, so a value too tight to keep the
dots apart is dropped rather than drawn solid. The two grids are dashed independently, which
is what lets a theme read its major divisions without weakening its minor ones.

On a tile too small for a grid to register, the minor grid is dropped — a size decision made in
the renderer, which holds for every theme. The gridline that survives comes from
`--graph_gridCompact`, and need not be either of your two grids: light and classic keep their
major, dark and midnight their minor. Set it to one of your own two or the shipped appearance
changes; a third colour here is almost certainly a mistake.

A tile smaller still, the wide sidebar sparkline, is deliberately a neutral grey rather than
`--graph_plotFill1`, because it carries no legend and its colour must not read as a first plot.

### What is still not themeable

Only the fully transparent structural items and one deliberate neutral:

| Item | Why not |
| ---- | ------- |
| Axis rules on a tile with no axes | hidden, so nothing to theme |
| The plot frame | fully transparent on every theme |
| The axis-break arrow | transparent; no theme draws it |
| The minor grid on a too-small tile | replaced by `--graph_gridCompact` instead |
| The sidebar sparkline fill | a neutral grey on purpose, so it does not read as a plot |

## Theme Picker UI

The theme selection UI is rendered by `ConfigUIHelper.getSettings()` (also `ConfigUIHelper.java`). It:

1. Scans subdirectories of `docs/themes/console/`
2. Scans properties matching `routerconsole.theme.*` for plugin themes
3. Renders radio buttons with 48x48 thumbnails from `/themes/console/<theme>/images/thumbnail.png`
4. Includes a "universal theming" checkbox

On form submission, `ConfigUIHandler.java` saves `routerconsole.theme=<name>` and writes `docs/themes/login/theme.txt` for the login page.

---

## Tutorial: Creating a New Console/Webapp Theme

### Step 1: Create the theme directory

Create a directory under the console theme root:

```
mkdir -p installer/resources/console/themes/console/mytheme/images
```

### Step 2: Create `global.css`

Define your CSS custom properties. At minimum:

```css
:root {
    --a: #494;
    --active: #f90;
    --hover: #f60;
    --ink: #ee9;
    --ink_bright: #aa3;
    --bodybg: #000;
    --border: 1px solid #242;
    --border_hard: 1px solid #252;
    --border_soft: 1px solid #2529;
    --btn: linear-gradient(180deg, #001000, #000);
    --badge: linear-gradient(180deg, #020, #010);
}
```

You can reference the built-in themes for a complete list of variables:
- `console/dark/global.css` (169 variables)
- `console/light/global.css` (143 variables)
- `console/midnight/global.css` (137 variables)

### Step 3: Create `console.css`

```css
@import url(global.css);
@import url(../shared.css);
@import url(../images/itooplus.css);

/* Your theme styles */
body {
    background: var(--bodybg);
    color: var(--ink);
}
a { color: var(--a); }
a:hover { color: var(--hover); }
/* ... */
```

Graph plot values are optional — a theme that omits one falls back to the built-in default —
but shipping them keeps the plots discoverable to whoever edits the theme next. Copy the block
from an existing theme and adjust:

```css
:root{
--graph_plotLine1:#37c8a0;   /* copy the values from a theme you like, then vary them */
--graph_plotLine2:#f09060;
--graph_plotFill1:#0a4a20b0;
--graph_plotFill2:#64c8a0b0;
--graph_plotDash:1;
}
```

Keep `--graph_plotLine1` and `--graph_plotLine2` visibly different: they are the only thing
distinguishing two plots that share an axis. Copy the rest of the block too — a theme that
declares only these still falls back on the other fourteen, which is a per-value cost rather
than a broken plot, but not what anyone would call finished. See
[Graph Color Integration](#graph-color-integration) for the full set.

### Step 4: Create theme images

Place at minimum:
- `images/thumbnail.png` — 48x48 picker thumbnail
- `images/i2plogo.png` — console logo
- `images/favicon.svg` — favicon
- `images/images.css` — theme-specific icon overrides (empty is fine)

### Step 5: Create webapp theme directories

For each webapp you want themed, create a parallel directory:

```
installer/resources/console/themes/susimail/mytheme/susimail.css
installer/resources/console/themes/susidns/mytheme/susidns.css
installer/resources/console/themes/snark/mytheme/snark.css
installer/resources/console/themes/login/mytheme/login.css
```

Each webapp CSS should `@import` the console's `global.css`:

```css
/* susimail/mytheme/susimail.css */
@import url(/themes/console/mytheme/global.css);
@import url(../images/images.css);
@import url(images/images.css);

body {
    background: var(--bodybg);
    color: var(--ink);
}
/* ... */
```

### Step 6: Build and deploy

```
ant prepthemeupdates     # copies themes to build temp dir
ant pkg                  # full build including themes
```

Or for quick development, copy directly to your running router:

```
cp -r installer/resources/console/themes/console/mytheme $I2P/docs/themes/console/
```

Then set the theme in router console → Config → UI, or edit `$I2P/router.config`:

```
routerconsole.theme=mytheme
```

### Step 7: Add an `override.css` example (optional)

Ship example override files alongside your theme:

```
installer/resources/console/themes/console/mytheme/override.css.warm
installer/resources/console/themes/console/mytheme/override.css.contrast
```

Users rename one to `override.css` to activate it.

---

## Tutorial: Creating an `override.css` Only (No Full Theme)

For quick customizations without creating a full theme:

### 1. Target the dark theme

Create `$I2P/docs/themes/console/dark/override.css`:

```css
/* Make links purple instead of green */
a { color: #a6f !important; }
a:hover { color: #f60 !important; }

/* Custom background */
body {
    background: #0a0a12 !important;
}
```

### 2. Target all webapps

Since webapps also check for `override.css`, create it in each:

```
$I2P/docs/themes/susimail/dark/override.css
$I2P/docs/themes/susidns/dark/override.css
$I2P/docs/themes/snark/dark/override.css
```

Or link them:
```bash
ln -s ../console/dark/override.css $I2P/docs/themes/susimail/dark/override.css
```

### 3. Hue rotation trick

A single-line override can completely shift the color scheme:

```css
/* Turn green theme to ocean blue */
html { filter: hue-rotate(120deg); }
/* Un-rotate images so they stay natural colors */
img { filter: hue-rotate(-120deg) !important; }
```

---

## Summary

| Concept          | File/Location                                            |
| ---------------- | -------------------------------------------------------- |
| Theme selection  | `routerconsole.theme` config property                    |
| Theme resolution | `CSSHelper.java` → `getTheme()`                          |
| CSS variables    | `<theme>/global.css`                                     |
| Main stylesheet  | `<theme>/console.css`                                    |
| Shared CSS rules | `console/shared.css`                                     |
| Shared icons     | `console/images/images.css` (~100 SVG data URIs)         |
| User overrides   | `<theme>/override.css` (user-created, survives upgrades) |
| Theme serving    | `viewtheme.jsp` (serves from `$I2P/docs/themes/`)        |
| Theme picker UI  | `ConfigUIHelper.getSettings()`                           |
| Build target     | `ant prepthemeupdates` copies themes to `docs/themes/`   |
| Source root      | `installer/resources/console/themes/`                    |
| Runtime root     | `$I2P/docs/themes/`                                      |
| Plugin themes    | `routerconsole.theme.<name>=/path/`                      |
| Graph colors     | `GraphRenderer.java` reads `routerconsole.theme`         |
