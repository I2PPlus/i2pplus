#!/bin/sh
# Post-process Java 8 javadoc HTML:
#  1. Replace script.js references with navigation.js
#  2. Replace inline javascript:show(N) with data-method-filter attributes
#  3. Strip the inline URL-target-page script block from index.html
#  4. Inject navigation.js reference into index.html (it has no <script src=...>)
find dist/javadoc -name '*.html' -exec sed -i \
  -e 's|script\.js|navigation.js|g' \
  -e 's|href=javascript:show(\([0-9][0-9]*\));|href="#" data-method-filter="\1"|g' \
  -e 's|<script>tmpTargetPage=.*</script>||g' {} +
sed -i 's|</title><frameset|</title><script src=navigation.js></script><frameset|' dist/javadoc/index.html
