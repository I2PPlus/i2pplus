/**
 * @module jdenticon
 * @file Identicons for the susidns address book and details pages.
 * @description An I2P+ fork of jdenticon 3.3.0, kept for the one path those
 * pages use - paint the `<svg data-jid="…">` they ship, once, when the
 * document is ready. A `data-jid-hash` attribute holds a hash the page computed
 * itself, and skips the SHA-1 pass.
 *
 * Page config: `padding`, `saturation`, `lightness`, `hues` on `window.jdenticon_config`.
 *
 * An icon is a function of its hash alone: a 4x4 cell grid where three colour
 * groups each draw one hash-picked shape across their cells at hash-picked
 * rotations, at a hue from the hash. That is an address's visual identity.
 * @author Daniel Mester Pirttijärvi
 * @copyright 2014-2024
 * @see {@link https://jdenticon.com}
 * @license MIT
 */

(function (global) {
  'use strict';

  /** Attribute carrying a hash the page already computed. */
  const ATTR_HASH = 'data-jid-hash';

  /** Attribute carrying an arbitrary value, hashed here with SHA-1. */
  const ATTR_VALUE = 'data-jid';

  /** Page-level config object the JSPs set before this loads. */
  const CONFIG_KEY = 'jdenticon_config';

  /** JS property, not an attribute, marking an element as painted. */
  const PAINTED = 'jdenticonRendered';

  /** Every element this script looks at. */
  const SELECTOR = `[${ATTR_HASH}],[${ATTR_VALUE}]`;

  /** Namespace every painted element and its children belong to. */
  const XMLNS = 'http://www.w3.org/2000/svg';

  /** Fraction of the icon edge left as padding when the page sets none. */
  const PAD = 0.08;

  /** Hue-dependent brightness correction per hue sector, without which the
   * greens and yellows of a palette read far brighter than the blues. */
  const CORRECT = [0.55, 0.5, 0.5, 0.46, 0.6, 0.55, 0.55];

  /** The cells of the 4x4 grid as x, y pairs, in the order each group draws
   * them: the eight edge cells, the four corners, the four centre cells. */
  const EDGE = [1, 0, 2, 0, 2, 3, 1, 3, 0, 1, 3, 1, 3, 2, 0, 2];
  const CORNER = [0, 0, 3, 0, 3, 3, 0, 3];
  const CENTRE = [1, 1, 2, 1, 2, 2, 1, 2];

  /** Clamps one colour channel to two hex digits, out of range to 00 or ff. */
  function hexPair(value) {
    const channel = value | 0;
    return channel < 0 ? '00' :
      channel < 16 ? `0${channel.toString(16)}` :
      channel < 256 ? channel.toString(16) : 'ff';
  }

  /** One channel of an HSL-to-RGB conversion at a hue position, wrapped to 0-6. */
  function hueToRgb(m1, m2, h) {
    const w = h < 0 ? h + 6 : h > 6 ? h - 6 : h;
    return hexPair(255 * (
      w < 1 ? m1 + (m2 - m1) * w :
      w < 3 ? m2 :
      w < 4 ? m1 + (m2 - m1) * (4 - w) : m1));
  }

  /** HSL to a CSS hex colour. */
  function hslToHex(hue, saturation, lightness) {
    if (saturation === 0) {
      const grey = hexPair(lightness * 255);
      return `#${grey}${grey}${grey}`;
    }
    const m2 = lightness <= 0.5 ?
      lightness * (saturation + 1) :
      lightness + saturation - lightness * saturation;
    const m1 = lightness * 2 - m2;
    return `#${hueToRgb(m1, m2, hue * 6 + 2)}${hueToRgb(m1, m2, hue * 6)}${hueToRgb(m1, m2, hue * 6 - 2)}`;
  }

  /** HSL to hex with the brightness correction for the hue's sector. */
  function hsl(hue, saturation, lightness) {
    const corrector = CORRECT[(hue * 6 + 0.5) | 0];
    const corrected = lightness < 0.5 ?
      lightness * corrector * 2 :
      corrector + (lightness - 0.5) * (1 - corrector) * 2;
    return hslToHex(hue, saturation, corrected);
  }

  /**
   * Flattens a config into the values the renderer reads. The page's
   * `window.jdenticon_config` is the source, and a passed object wins over it.
   * This runs once per paint pass, not once per icon.
   *
   * @param {Object} [localConfig] config for this pass only
   * @returns {Object} hue mapper, saturations, lightness mappers and padding
   */
  function parseConfig(localConfig) {
    const config = localConfig || global[CONFIG_KEY] || {};
    const lightnessConfig = config.lightness || {};
    const saturation = config.saturation;
    const objectForm = typeof saturation === 'object' && saturation !== null;
    const colorSat = objectForm ? saturation.color : saturation;
    const graySat = objectForm ? saturation.grayscale : undefined;

    /** Maps a 0-1 palette position onto a lightness inside a configured range. */
    const range = (name, fallback) => {
      const span = lightnessConfig[name] && lightnessConfig[name].length > 1 ?
        lightnessConfig[name] : fallback;
      return (value) => {
        const scaled = span[0] + value * (span[1] - span[0]);
        return scaled < 0 ? 0 : scaled > 1 ? 1 : scaled;
      };
    };

    /** Snaps the hue the hash produced onto the page's hue list, if it set one. */
    const hueAt = (originalHue) => {
      const hues = config.hues;
      const hue = hues && hues.length > 0 ?
        hues[0 | (0.999 * originalHue * hues.length)] : undefined;
      return typeof hue === 'number' ? (((hue / 360) % 1) + 1) % 1 : originalHue;
    };

    return {
      hue: hueAt,
      colorSat: typeof colorSat === 'number' ? colorSat : 0.5,
      graySat: typeof graySat === 'number' ? graySat : 0,
      colorLit: range('color', [0.4, 0.8]),
      grayLit: range('grayscale', [0.3, 0.9]),
      padding: typeof config.padding === 'number' ? config.padding : PAD,
    };
  }

  /**
   * The five palette entries: two greys and three colours at three lightnesses,
   * all at the hue the hash chose. An icon then paints with three of them.
   */
  function palette(hue, config) {
    const h = config.hue(hue);
    return [
      hsl(h, config.graySat, config.grayLit(0)),
      hsl(h, config.colorSat, config.colorLit(0.5)),
      hsl(h, config.graySat, config.grayLit(1)),
      hsl(h, config.colorSat, config.colorLit(1)),
      hsl(h, config.colorSat, config.colorLit(0)),
    ];
  }

  /** Palette pairs that must not share an icon: entry 0 and entry 4 are the dark
   * grey and the dark colour, and 2 and 3 the light pair of the same families. */
  const DARK_PAIRS = [[0, 4], [2, 3]];

  /**
   * The three palette entries one hash paints with. A dark grey next to a dark
   * colour would read as one flat block, so a hash that asks for such a pairing
   * gets the mid-tone entry instead.
   *
   * @param {Uint8Array} bits the decoded hash
   * @param {string[]} colors the five palette entries
   * @returns {number[]} three indexes into colors, one per colour group
   */
  function pickColors(bits, colors) {
    const picked = [];
    for (let i = 0; i < 3; i++) {
      let index = bits[8 + i] % colors.length;
      if (DARK_PAIRS.some((pair) =>
        pair.includes(index) && pair.some((entry) => picked.includes(entry)))) {
        index = 1;
      }
      picked.push(index);
    }
    return picked;
  }

  /**
   * Decodes a hex hash into its half-bytes once, so the shape and colour
   * selectors index it instead of slicing and re-parsing the string per icon.
   */
  function toNibbles(hash) {
    const bits = new Uint8Array(hash.length);
    for (let i = 0; i < hash.length; i++) {
      const code = hash.charCodeAt(i);
      // hex digits only, which isValidHash guarantees: 0-9 then A-F/a-f
      bits[i] = code < 58 ? code - 48 : (code & ~32) - 55;
    }
    return bits;
  }

  /**
   * The icon's hue: its hash's last seven half-bytes, scaled into 0-1. The tail
   * is read because an address book is full of addresses that differ early.
   */
  function hueOf(bits) {
    let value = 0;
    for (let i = Math.max(bits.length - 7, 0); i < bits.length; i++) {
      value = value * 16 + bits[i];
    }
    return value / 0xfffffff;
  }

  /**
   * Draws shapes into one cell of the grid. A cell's position and rotation are
   * set once per cell and every shape maps through them, so the shape tables
   * below stay in plain cell coordinates. The three coordinate buffers are
   * reused from shape to shape: a renderer reads them at once and must not keep
   * them, which is what keeps a drawn icon allocation-free.
   */
  class Cell {
    /** @param {Object} renderer consumes finished polygons and circles */
    constructor(renderer) {
      this._renderer = renderer;
      // the cell box, with the right and bottom edges kept for _map
      this._x = 0;
      this._y = 0;
      this._size = 0;
      this._right = 0;
      this._bottom = 0;
      this._rotation = 0;
      // shape corners, mapped icon coordinates, one mapped point
      this._points = [];
      this._coords = [];
      this._point = [];
    }

    /** Selects the cell the next shapes go into, in icon pixels. */
    setCell(x, y, size, rotation) {
      this._x = x;
      this._y = y;
      this._size = size;
      this._rotation = rotation;
      this._right = x + size;
      this._bottom = y + size;
    }

    /**
     * Maps one cell coordinate into icon coordinates under the cell's rotation.
     * The rotations that flip an axis need the shape's own extent to stay put.
     */
    _map(x, y, w, h, out) {
      if (this._rotation === 1) {
        out.push(this._right - y - h, this._y + x);
      } else if (this._rotation === 2) {
        out.push(this._right - x - w, this._bottom - y - h);
      } else if (this._rotation === 3) {
        out.push(this._x + y, this._bottom - x - w);
      } else {
        out.push(this._x + x, this._y + y);
      }
    }

    /** @param {number[]} points cell coordinates as x, y pairs
     *  @param {boolean} [invert] reverses the winding, cutting the shape out of
     * what was already drawn in this colour group */
    addPolygon(points, invert) {
      const coords = this._coords;
      coords.length = 0;
      const step = invert ? -2 : 2;
      for (let i = invert ? points.length - 2 : 0; i < points.length && i >= 0; i += step) {
        this._map(points[i], points[i + 1], 0, 0, coords);
      }
      this._renderer.addPolygon(coords);
    }

    /** A circle inscribed in a cell square. */
    addCircle(x, y, diameter, invert) {
      const point = this._point;
      point.length = 0;
      this._map(x, y, diameter, diameter, point);
      this._renderer.addCircle(point[0], point[1], diameter, invert);
    }

    /** An axis-aligned rectangle. */
    addRectangle(x, y, w, h, invert) {
      const points = this._points;
      points.length = 0;
      points.push(x, y, x + w, y, x + w, y + h, x, y + h);
      this.addPolygon(points, invert);
    }

    /** A cell square with one corner dropped: the rotation index decides which,
     * which is how one shape entry covers four orientations. */
    addTriangle(x, y, w, h, rotation, invert) {
      const points = this._points;
      points.length = 0;
      points.push(x + w, y, x + w, y + h, x, y + h, x, y);
      points.splice(((rotation || 0) % 4) * 2, 2);
      this.addPolygon(points, invert);
    }

    /** A diamond inscribed in a cell square. */
    addRhombus(x, y, w, h, invert) {
      const points = this._points;
      points.length = 0;
      points.push(x + w / 2, y, x + w, y + h / 2, x + w / 2, y + h, x, y + h / 2);
      this.addPolygon(points, invert);
    }
  }

  /**
   * The shapes for the four centre cells. `index` picks one out of the hash;
   * `pos` matters only to the last, which paints in the first centre
   * cell alone. Sizes are floored where a shape needs whole pixels.
   */
  function centreShape(index, cell, size, pos) {
    const shape = index % 14;
    let k, m, w, h, inner, outer;

    if (!shape) {
      k = size * 0.42;
      cell.addPolygon([0, 0, size, 0, size, size - k * 2, size - k, size, 0, size]);
    } else if (shape === 1) {
      w = (size * 0.5) | 0;
      h = (size * 0.8) | 0;
      cell.addTriangle(size - w, 0, w, h, 2);
    } else if (shape === 2) {
      w = (size / 3) | 0;
      cell.addRectangle(w, w, size - w, size - w);
    } else if (shape === 3) {
      inner = size * 0.1;
      outer = size < 6 ? 1 : size < 8 ? 2 : (size * 0.25) | 0;
      inner = inner > 1 ? (inner | 0) : inner > 0.5 ? 1 : inner;
      cell.addRectangle(outer, outer, size - inner - outer, size - inner - outer);
    } else if (shape === 4) {
      m = (size * 0.15) | 0;
      w = (size * 0.5) | 0;
      cell.addCircle(size - w - m, size - w - m, w);
    } else if (shape === 5) {
      inner = size * 0.1;
      outer = inner * 4;
      if (outer > 3) {
        outer = (outer | 0);
      }
      cell.addRectangle(0, 0, size, size);
      cell.addPolygon([outer, outer, size - inner, outer, outer + (size - outer - inner) / 2, size - inner], true);
    } else if (shape === 6) {
      cell.addPolygon([0, 0, size, 0, size, size * 0.7, size * 0.4, size * 0.4, size * 0.7, size, 0, size]);
    } else if (shape === 7) {
      cell.addTriangle(size / 2, size / 2, size / 2, size / 2, 3);
    } else if (shape === 8) {
      cell.addRectangle(0, 0, size, size / 2);
      cell.addRectangle(0, size / 2, size / 2, size / 2);
      cell.addTriangle(size / 2, size / 2, size / 2, size / 2, 1);
    } else if (shape === 9) {
      inner = size * 0.14;
      outer = size < 4 ? 1 : size < 6 ? 2 : (size * 0.35) | 0;
      inner = size < 8 ? inner : (inner | 0);
      cell.addRectangle(0, 0, size, size);
      cell.addRectangle(outer, outer, size - outer - inner, size - outer - inner, true);
    } else if (shape === 10) {
      inner = size * 0.12;
      outer = inner * 3;
      cell.addRectangle(0, 0, size, size);
      cell.addCircle(outer, outer, size - inner - outer, true);
    } else if (shape === 11) {
      cell.addTriangle(size / 2, size / 2, size / 2, size / 2, 3);
    } else if (shape === 12) {
      m = size * 0.25;
      cell.addRectangle(0, 0, size, size);
      cell.addRhombus(m, m, size - m, size - m, true);
    } else if (!pos) {
      m = size * 0.4;
      w = size * 1.2;
      cell.addCircle(m, m, w);
    }
  }

  /** The shapes for the eight edge and four corner cells. */
  function outerShape(index, cell, size) {
    const shape = index % 4;
    let m;

    if (!shape) {
      cell.addTriangle(0, 0, size, size, 0);
    } else if (shape === 1) {
      cell.addTriangle(0, size / 2, size, size / 2, 0);
    } else if (shape === 2) {
      cell.addRhombus(0, 0, size, size);
    } else {
      m = size / 6;
      cell.addCircle(m, m, size - 2 * m);
    }
  }

  /**
   * Paints one whole icon: three colour groups, each one shape repeated over its
   * cells at rotations taken from the hash.
   *
   * @param {Object} renderer consumes the finished geometry
   * @param {string} hash at least 11 hex digits
   * @param {Object} config the parsed config for this pass
   */
  function iconGenerator(renderer, hash, config) {
    const bits = toNibbles(hash);
    let size = renderer.iconSize;
    const padding = (0.5 + size * config.padding) | 0;
    size -= padding * 2;
    const cell = new Cell(renderer);
    const gridSize = (size / 4) | 0;
    // the grid is centred, so both origins are the same value
    const origin = (padding + size / 2 - gridSize * 2) | 0;
    const colors = palette(hueOf(bits), config);
    const picked = pickColors(bits, colors);

    /** Draws one colour group; rotationAt null means every cell unrotated. The
     * shape and rotation selectors are single half-bytes in the first six, which
     * every usable hash has. */
    const renderGroup = (colorIndex, shapes, shapeAt, rotationAt, cells) => {
      const shapeIndex = bits[shapeAt];
      let rotation = rotationAt === null ? 0 : bits[rotationAt];
      renderer.beginShape(colors[picked[colorIndex]]);
      for (let i = 0; i < cells.length; i += 2) {
        cell.setCell(origin + cells[i] * gridSize, origin + cells[i + 1] * gridSize, gridSize, rotation++ % 4);
        shapes(shapeIndex, cell, gridSize, i / 2);
      }
    };

    renderGroup(0, outerShape, 2, 3, EDGE);
    renderGroup(1, outerShape, 4, 5, CORNER);
    renderGroup(2, centreShape, 1, null, CENTRE);
    renderer.finish();
  }

  /** SHA-1's block size in words, its schedule length, and its output length in
   * hex digits. */
  const SHA_BLOCK = 16;
  const SHA_WORDS = 80;
  const SHA_HEX = 40;

  /**
   * The schedule buffer, reused from hash to hash: every word is written by the
   * block that reads it, so no hash can see another's leftovers.
   */
  const SCHEDULE = new Array(SHA_WORDS);

  /** @returns {number} `value` rotated left by `shift` bits */
  function rotl(value, shift) {
    return (value << shift) | (value >>> (32 - shift));
  }

  /**
   * SHA-1 as a hex string, for a value the page did not pre-hash. Bytes written
   * as a percent escape are decoded first, so a percent-encoded value hashes to
   * the same icon as the raw one.
   *
   * @param {string} message the value to hash
   * @returns {string} 40 hex digits
   */
  function sha1(message) {
    let i = 0;
    let f = 0;
    const data = [];
    let a = 0x67452301;
    let b = 0xefcdab89;
    let c = ~a;
    let d = ~b;
    let e = 0xc3d2e1f0;
    const hash = [a, b, c, d, e];
    let blockStart = 0;
    let hexHash = '';

    for (; i < message.length; f++) {
      data[f >> 2] = data[f >> 2] |
        ((message[i] === '%'
          ? parseInt(message.substring(i + 1, (i += 3)), 16)
          : message.charCodeAt(i++)) << ((3 - (f & 3)) * 8));
    }

    const dataSize = (((f + 7) >> 6) + 1) * SHA_BLOCK;
    data[dataSize - 1] = f * 8 - 8;

    for (; blockStart < dataSize; blockStart += SHA_BLOCK) {
      for (i = 0; i < SHA_WORDS; i++) {
        f = rotl(a, 5) + e + (
          i < 20 ? ((b & c) ^ (~b & d)) + 0x5a827999 :
          i < 40 ? (b ^ c ^ d) + 0x6ed9eba1 :
          i < 60 ? ((b & c) ^ (b & d) ^ (c & d)) + 0x8f1bbcdc :
          (b ^ c ^ d) + 0xca62c1d6) +
          (SCHEDULE[i] = i < SHA_BLOCK
            ? data[blockStart + i] | 0
            : rotl(SCHEDULE[i - 3] ^ SCHEDULE[i - 8] ^ SCHEDULE[i - 14] ^ SCHEDULE[i - 16], 1));

        e = d;
        d = c;
        c = rotl(b, 30);
        b = a;
        a = f;
      }

      hash[0] = a = (hash[0] + a) | 0;
      hash[1] = b = (hash[1] + b) | 0;
      hash[2] = c = (hash[2] + c) | 0;
      hash[3] = d = (hash[3] + d) | 0;
      hash[4] = e = (hash[4] + e) | 0;
    }

    for (i = 0; i < SHA_HEX; i++) {
      hexHash += ((hash[i >> 3] >>> ((7 - (i & 7)) * 4)) & 0xf).toString(16);
    }

    return hexHash;
  }

  /** The value of a hash attribute when it is a usable hash, else false. */
  function isValidHash(candidate) {
    return /^[0-9a-f]{11,}$/i.test(candidate) && candidate;
  }

  /** The hash an icon for this address or label is drawn from. */
  function computeHash(value) {
    return sha1(value == null ? '' : `${value}`);
  }

  /** Rounds a coordinate to a whole unit of the icon's drawing space - a
   * quarter of a device pixel at the 100-unit space the susidns pages draw in.
   * Keeping the fraction costs a decimal digit in every coordinate of every
   * shape, which is most of the path data and a good part of the paint. */
  function pathValue(value) {
    return (value + 0.5) | 0;
  }

  /** One colour group's geometry, as SVG path data. */
  class PathData {
    constructor() {
      this.data = '';
    }

    /** @param {number[]} coords icon coordinates as x, y pairs, read here only */
    addPolygon(coords) {
      let d = '';
      for (let i = 0; i < coords.length; i += 2) {
        d += `${i ? 'L' : 'M'}${pathValue(coords[i])} ${pathValue(coords[i + 1])}`;
      }
      this.data += `${d}Z`;
    }

    /** A circle as two arcs, path data having no circle command. A clockwise
     * sweep is what makes the fill rule keep the circle rather than cut it. */
    addCircle(x, y, diameter, ccw) {
      const sweep = ccw ? 0 : 1;
      const radius = pathValue(diameter / 2);
      // Each arc is a semicircle, so the chord must be exactly twice the rounded
      // radius. Rounding the chord on its own can make it longer than the
      // diameter, and the browser then scales the radii to fit and draws a
      // different circle from the one the shape asked for.
      const edge = radius * 2;
      const arc = `a${radius},${radius} 0 1,${sweep} `;
      this.data += `M${pathValue(x)} ${pathValue(y + diameter / 2)}${arc}${edge},0${arc}${-edge},0`;
    }
  }

  /**
   * Collects path data per colour and hands each finished group to the target.
   * The same palette entry paints several cells, so one path per colour is what
   * keeps a painted icon down to three elements.
   */
  class SvgRenderer {
    /** @param {Object} target where finished geometry goes: iconSize, addPath */
    constructor(target) {
      this.iconSize = target.iconSize;
      this._target = target;
      this._paths = new Map();
      this._current = null;
    }

    /** Opens a colour group, reusing its path if the colour repeats. */
    beginShape(colour) {
      let path = this._paths.get(colour);
      if (!path) {
        path = new PathData();
        this._paths.set(colour, path);
      }
      this._current = path;
    }

    /** @param {number[]} coords forwarded to the group being built */
    addPolygon(coords) {
      this._current.addPolygon(coords);
    }

    /** @param {number} x icon x of the bounding square's corner
     *  @param {number} y icon y of that corner
     *  @param {number} diameter the square's edge
     *  @param {boolean} ccw true when the circle cuts a hole */
    addCircle(x, y, diameter, ccw) {
      this._current.addCircle(x, y, diameter, ccw);
    }

    /** Emits one path per colour used, in the order the groups were started. */
    finish() {
      for (const [colour, path] of this._paths) {
        this._target.addPath(colour, path.data);
      }
    }
  }

  /** The `<svg>` element an icon is painted into. */
  class SvgTarget {
    /**
     * Sizes the drawing space, then clears the element, so a repaint cannot
     * leave the old paths behind. An element carrying width and height
     * attributes is drawn at that scale, one unit to one device pixel; one
     * sized only by CSS, as the susidns pages size theirs, is drawn in a
     * 100-unit space the browser scales.
     */
    constructor(element) {
      this.iconSize = Math.min(
        Number(element.getAttribute('width')) || 100,
        Number(element.getAttribute('height')) || 100);
      this._element = element;
      element.replaceChildren();
      element.setAttribute('viewBox', `0 0 ${this.iconSize} ${this.iconSize}`);
    }

    addPath(fill, data) {
      const path = document.createElementNS(XMLNS, 'path');
      path.setAttribute('fill', fill);
      path.setAttribute('d', data);
      this._element.appendChild(path);
    }
  }

  /**
   * Paints one icon element. A `data-jid-hash` attribute is used as it
   * stands when it is a usable hash, otherwise a `data-jid` attribute is
   * hashed. An element with neither, or with a hash too short to
   * read, is left alone rather than painted blank.
   *
   * @param {Element} element an element matched by SELECTOR
   * @param {Object} config the parsed config for this pass
   */
  function paintElement(element, config) {
    if (!/^svg$/i.test(element.tagName)) {
      return;
    }
    const hash = isValidHash(element.getAttribute(ATTR_HASH)) ||
      (element.hasAttribute(ATTR_VALUE) && computeHash(element.getAttribute(ATTR_VALUE)));
    if (!hash) {
      return;
    }
    iconGenerator(new SvgRenderer(new SvgTarget(element)), hash, config);
    element[PAINTED] = true;
  }

  /** Paints every icon on the page that has not been painted yet. */
  function paintPage(config) {
    for (const element of document.querySelectorAll(SELECTOR)) {
      if (!element[PAINTED]) {
        paintElement(element, config);
      }
    }
  }

  /** @param {function(): void} callback the paint pass, once the document can be queried */
  function whenReady(callback) {
    if (typeof document === 'undefined' || typeof window === 'undefined') {
      return;
    }
    if (document.readyState === 'loading') {
      document.addEventListener('DOMContentLoaded', callback);
    } else {
      callback();
    }
  }

  /** What a `<script>` tag runs: read the page config, then paint its icons. */
  function start() {
    paintPage(parseConfig());
  }

  whenReady(start);
})(typeof self !== 'undefined' ? self : this);
