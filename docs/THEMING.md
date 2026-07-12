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

`GraphRenderer.java` reads `routerconsole.theme` to select graph color schemes:

```java
String theme = _context.getProperty("routerconsole.theme", "dark");
if (theme.equals("midnight")) {
    /* purple tones */
} else if (theme.equals("dark")) {
    /* orange tones */
} else {
    /* light/classic tones */
}
```

---

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
