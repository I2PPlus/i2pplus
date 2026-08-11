# Translating I2P+

I2P+ uses gettext PO files for all user-facing strings. Each module has a
`bundle-messages.cfg` describing its source paths and a `locale/` directory
holding one `messages_<lang>.po` file per language.

## Layout

- `apps/<module>/locale/messages_<lang>.po` - Application bundles
  (routerconsole, i2ptunnel, susimail, susidns, i2psnark, addressbook, SAM,
  desktopgui, ministreaming)
- `core/locale/`, `router/locale/` - Core and router bundles
- `installer/resources/locale/po/` - Installer bundle
- Secondary bundles (news, countries, proxy) use a variant config name:
  `bundle-messages.sh --cfg <name>`

## Workflow

1. **Refresh the English templates** with `ant poupdate-source`. Review the
   changed `messages_en.po` files for tagging errors in the Java source and
   revert files whose only changes are line-number shifts.
2. **Translate**. Only fill `msgstr` in the non-English PO files. The English
   `messages_en.po` is the template - its `msgstr` entries must stay empty and
   the system falls back to `msgid`.
3. **Generate the bundles** by running the build, or directly:

   ```sh
   tools/scripts/bundle-messages.sh --dir <module-dir>
   ```

   The script prints a translation coverage summary per language; pass
   `--no-coverage` to suppress it. Requires `xgettext`, `msgfmt`, and
   `msgmerge` on your PATH.

## Bulk translation of many entries

For large runs, read the untranslated entries with `polib` and apply them in a
batch:

```python
import polib
po = polib.Pofile("apps/routerconsole/locale/messages_de.po")
untranslated = po.untranslated_entries()   # msgid list, in file order
```

Write the translations to a small `trans_de.py` file holding a `t` list, then
loop `entry.msgstr = t[i]` and `po.save()`. Bash heredocs truncate silently on
large datasets - write the data as a file instead. Helper scripts live in
`tools/scripts/`: `extract_untranslated.py`, `batch_translate.py`.

## Diagnostics

- `tools/scripts/translation_status.sh <locale_folder>` - per-language status
  for a bundle
- `tools/scripts/cleanup_po.sh` - tidy PO files
- Translation coverage is also reported at the end of every `bundle-messages.sh`
  run

## Rules

- Never translate `messages_en.po` - the template's `msgstr` must remain empty
- Keep translations in the module's own `locale/` directory
- Verify with a build before merging; broken bundles surface as missing
  ResourceBundle classes