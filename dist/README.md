Distribution archives (installers, updaters) are placed here.
Removed by `ant clean` / `ant distclean`. Build output goes to
`/tmp/build-i2p/` (configurable via `build.root` in `override.properties`).

To mount `dist/` via tmpfs (reduces SSD wear), add to `/etc/fstab`:
`tmpfs {path/to/i2pplus/dist}   tmpfs defaults,mode=1777   0  0`
Then `sudo mount -a`.
