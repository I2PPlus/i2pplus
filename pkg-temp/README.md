This folder is now persistent, which enables optional mounting via tmpfs to
minimize SSD wear and tear. The contents of this folder, this file excepted,
will be removed when invoking `ant clean` or `ant distclean`.

In Linux, to mount the temporary build folders via tmpfs, add the following
lines to your `/etc/fstab` file after running `ant distclean`:


`tmpfs {path/to/i2pplus/build}         tmpfs defaults,mode=1777   0  0`\
`tmpfs {path/to/i2pplus/dist}          tmpfs defaults,mode=1777   0  0`\
`tmpfs {path/to/i2pplus/pkg-temp}      tmpfs defaults,mode=1777   0  0`

You can now mount them without a restart with: `sudo mount -a`