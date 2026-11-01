/**
 * @module logTags
 * @description The bracketed words in a log line that name a subsystem or a
 * state, not a hash, kept free of the DOM so they can be tested without a browser.
 *
 * The log linkifier turns any bracketed six characters into a router link and
 * any bracketed eight into a lease set link, because that is the shape the
 * house uses for a truncated hash: six characters of base64 for a router, eight
 * of base32 for a destination. A greppable tag written in brackets - the
 * leading [Eepget] of the fetch helper, a state a tally reports - lands in the
 * same shape, so it becomes a netdb link that resolves to nothing and, for a
 * lease set, to the wrong object. The house keeps those tags, so the linkifier
 * has to know them; add a word here when a new bracketed tag is introduced.
 * @author dr|z3d
 * @license AGPLv3 or later
 */

/** The tag words, lower-cased, with the bracketed form each one is seen as. */
const TAG_WORDS = new Set([
  // [Eepget] - leads every fetch log line
  "eepget",
  // [DEFERRED] and [accepted] - a state a tally reports
  "deferred",
  "accepted",
  // [TunnelId] - names the field a value is being given for
  "tunnelid"
]);

/**
 * Whether the text between a pair of brackets is a tag rather than a hash, in
 * which case it must stay plain text.
 *
 * @param {string} word - the text between the brackets, as written
 * @returns {boolean} true when the word is a tag, so it is not linkified
 */
export function isLogTag(word) {
  return TAG_WORDS.has(word.toLowerCase());
}
