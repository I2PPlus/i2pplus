/**
 * @module markdown
 * @file SusiMail Markdown renderer.
 * Converts Markdown-formatted mail bodies (rendered as `<p class="mailbody">`)
 * into HTML using the Markdown.Converter library.
 * @license GPL-2.0 http://www.gnu.org/licenses/gpl-2.0.html
 * @see licenses/LICENSE-GPLv2.txt
 */

/**
 * Finds all `<p>` elements with class "mailbody" and converts their
 * inner content from Markdown to HTML via {@link Markdown.Converter}.
 * @function initMarkdown
 * @returns {void}
 */
function initMarkdown() {
	var mailbodies = document.getElementsByClassName("mailbody");
	for(index = 0; index < mailbodies.length; index++)
	{
		var mailbody = mailbodies[index];
		if (mailbody.nodeName === "P") {
			var converter = new Markdown.Converter();
			mailbody.innerHTML = converter.makeHtml(mailbody.innerHTML);
		}
	}
}

document.addEventListener("DOMContentLoaded", function() {
    initMarkdown();
}, true);

/* @license-end */
