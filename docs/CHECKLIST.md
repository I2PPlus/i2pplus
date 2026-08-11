# I2P+ Release Checklist and Process

I2P+ releases are built and published by GitHub Actions: pushing a release tag
builds every artifact and creates the GitHub Release automatically. Most of the
manual work is version bumps, testing, and updating the download site.

## One week before

- [ ] Update local English po files: `ant poupdate-source`
- [ ] Review changes in English po files, fix up any necessary tagged strings in
    Java source
- [ ] Revert English po files with no actual changes (i.e. line number changes
    only)
- [ ] Check in remaining English po files (and any files with changed strings)

- [ ] GeoIP: db-ip.com updates are usually first of the month, time accordingly
    - `installer/resources/geoip/makegeoip.sh`
    - `git commit installer/resources/geoip/GeoLite2-Country.mmdb.gz`
- [ ] Tor blocklist: `installer/resources/blocklist/maketorblocklist-ipv4+ipv6.sh`
    - `git commit installer/resources/blocklist/blocklist_tor.txt`

- [ ] BuildTime: not needed every release, but update the EARLIEST and
    EARLIEST_LONG values in core/java/src/net/i2p/time/BuildTime.java to the
    current date, more or less

- [ ] GitHub issues: check if any blocker or critical issues remain open for
    this release; get them fixed and closed, or reclassified

- [ ] Initial review: review the complete diff from the last release tag
    (`git diff 0.9.69+..HEAD`), fix any issues

- [ ] Trial build: `ant distclean && ant updaterCompact` and `ant testscripts`;
    fix any issues

- [ ] Javadoc test: `ant javadoc` and fix any issues

- [ ] Write the release announcement (draft it from the docs/history.txt
    entries since the last release)

## On release day

### Preparation

- [ ] Start with a clean checkout of the release branch (dev or master)
- [ ] Final GitHub issues check for blockers
- [ ] Ensure all translation updates and GeoIP/blocklist updates are committed
- [ ] Bump the version:
    - Point release: increment BUILD in
      router/java/src/net/i2p/router/RouterVersion.java
    - Major release: bump VERSION and PUBLISHED_VERSION in
      core/java/src/net/i2p/CoreVersion.java, reset BUILD to 0 in
      RouterVersion.java, and update the appversion in
      installer/lib/izpack/4/install.xml and installer/lib/izpack/5/install5.xml
      (or run `ant bumpMajorVersion`)
    - Typical commit style: `bump to -N+; GeoIP/ASN/Tor blocklist updates`
- [ ] Add a dated entry to docs/history.txt (see existing entries for the
    format)
- [ ] Reference a past release commit to confirm the expected contents of a
    bump/release commit: `git show 8731befc67` (CoreVersion.java,
    RouterVersion.java, both izpack files, docs/history.txt, blocklist)
- [ ] `git commit`

### Build and test

- [ ] `ant distclean && ant updaterCompact` - the clean build must pass
- [ ] `ant testscripts` - verify all translations and scripts are valid
- [ ] Optional full local release: `ant releaseRepack` (or
    releaseWithGeoIPRepack when including GeoIP, releaseWithJbigiRepack when
    jbigi changed):
    - Save the output about checksums, sizes, and torrents to a file
    - Verify sha256sums for release files
    - Check file sizes vs. previous release, shouldn't be smaller
    - Unzip or list files from `i2pupdate.zip`, see if it looks right
- [ ] Install test (at least for the first release, or major changes):
    - Run installer, install to temp dir
    - Unplug ethernet / turn off wifi so the network doesn't leak
    - Start the router
    - Verify release number in console
    - Verify welcome news
    - Click through all the app, status, eepsite, and config pages
    - Click through each of the translations, see if the console looks right
    - Look for errors in /log (other than can't reseed errors)
    - Shutdown, delete temp config dir, reconnect network

### Tag and release

- [ ] Tag the release: the tag tracks the API version (e.g. 0.9.70+) while the
    release message carries the marketing version (e.g. 2.13.0+):

    ```
    git tag -a 0.9.70+ -m "I2P+ Release: 0.9.70+ / 2.13.0+"
    ```

    (Alternatively `ant git-tag`, which creates an annotated `v<marketing>-<BUILD>+`
    tag such as `v2.13.0-4+`.)
- [ ] `git push origin 0.9.70+` - GitHub Actions builds all artifacts and
    creates the GitHub Release automatically (i2pupdate.zip, i2pinstall.exe,
    install.jar, i2psnark-standalone.zip, javadoc.zip, jsdoc.zip, source zip,
    .deb)
- [ ] Check the GitHub Release on github.com/I2PPlus/i2pplus: exists, artifacts
    uploaded, release notes generated

### Distribute

- [ ] Copy the artifacts to the i2pplus.github.io pages repo (i2pupdate.zip,
    installers/, javadoc.zip, i2psnark-standalone.zip)
- [ ] Publish the release announcement to the news feed (news.su3)
- [ ] Verify the Docker image built on ghcr.io/i2pplus/i2pplus (built on master
    push by .github/workflows/docker.yml)
- [ ] Optional: `ant buildAppImage` for the AppImage; Debian packages per
    distro/debian-alt/doc/ (launchpad.txt, debian-build.txt)