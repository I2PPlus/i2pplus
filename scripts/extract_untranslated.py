#!/usr/bin/env python3
"""
Extract untranslated strings from a PO file.
Returns a list of (msgid, line_number) tuples for strings that have empty msgstr.
"""

import re
import sys

def extract_untranslated(po_file):
    """Extract untranslated msgid strings from a PO file."""
    with open(po_file, 'r', encoding='utf-8') as f:
        content = f.read()
    
    lines = content.split('\n')
    untranslated = []
    current_msgid = None
    current_line = 0
    in_msgid = False
    msgid_lines = []
    
    for i, line in enumerate(lines):
        if line.startswith('msgid "'):
            if current_msgid is not None and current_msgid not in ('""', ''):
                # Save the previous msgid if it was complete
                pass
            current_msgid = line[7:]  # Remove 'msgid "'
            current_line = i
            msgid_lines = [line]
            in_msgid = True
        elif line.startswith('msgstr "') and in_msgid:
            # Check if msgstr is empty
            if line.strip() == 'msgstr ""':
                # This is untranslated
                # Join all msgid lines (for multi-line strings)
                full_msgid = '\n'.join(msgid_lines)
                untranslated.append((full_msgid, current_line, '\n'.join(msgid_lines[1:-1]) if len(msgid_lines) > 2 else current_msgid.strip('"')))
            in_msgid = False
            current_msgid = None
        elif in_msgid and line.startswith('"'):
            msgid_lines.append(line)
        else:
            in_msgid = False
    
    return untranslated

def main():
    if len(sys.argv) < 2:
        print("Usage: python extract_untranslated.py <po_file>")
        sys.exit(1)
    
    po_file = sys.argv[1]
    untranslated = extract_untranslated(po_file)
    
    for msgid, line_num, content in untranslated:
        print(f"Line {line_num}: {content[:100]}")

if __name__ == '__main__':
    main()
