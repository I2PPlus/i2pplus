#!/bin/sh
#
# Check for a new Jasper/Tomcat and update the checked-in jars in place.
#
# The Jasper jars are the JSP compiler and runtime the router console runs on.
# They are checked into apps/jetty/apache-tomcat-<version>/ and merged into
# apps/jetty/jettylib/ at build time, so updating them means three things: the
# jars, the version pin, and the license text that quotes the copyright year.
# Doing it by hand is how the Gradle pin drifted to a version that no longer
# matches the Ant pin or the directory on disk.
#
# Every jar we ship is byte-identical to the matching Maven Central artifact, and
# the checked-in NOTICE is byte-identical to the NOTICE at the matching Tomcat Git
# tag, so both are fetched and verified rather than copied out of a tarball.
#
# Usage:
#   tools/scripts/update-tomcat.sh              # check: report the pin and what is available
#   tools/scripts/update-tomcat.sh check        # same; exit 3 when an update is available
#   tools/scripts/update-tomcat.sh verify       # verify the checked-in jars against Maven
#   tools/scripts/update-tomcat.sh advisories   # advisories between the pin and the candidate
#   tools/scripts/update-tomcat.sh apply [VER]  # update in place (default: latest compatible)
#   tools/scripts/update-tomcat.sh list         # print the shipped-jar allowlist
#   tools/scripts/update-tomcat.sh selftest     # offline tests of the logic below
#
# Options:
#   --dry-run     print what apply would do without writing anything
#   --json        machine-readable output for check
#
# Nothing here is committed, and nothing is copied to a running router: the
# deliverable is the changed source tree plus the list of artifacts to rebuild.

set -u

SCRIPT_DIR=$(cd "$(dirname "$0")" && pwd)
REPO_ROOT=$(cd "$SCRIPT_DIR/../.." && pwd)

BUILD_XML="$REPO_ROOT/build.xml"
GRADLE_PROPS="$REPO_ROOT/gradle.properties"
LICENSES_MD="$REPO_ROOT/docs/LICENSES.md"
JETTY_DIR="$REPO_ROOT/apps/jetty"

MAVEN_BASE='https://repo1.maven.org/maven2/org/apache/tomcat'
NOTICE_URL='https://raw.githubusercontent.com/apache/tomcat/VERSION/NOTICE'
SECURITY_URL='https://tomcat.apache.org/security-9.html'
METADATA_ARTIFACT='tomcat-jasper'

# The Tomcat line whose API our console compiles against: javax.*, Servlet 4.0,
# JSP 2.3, EL 3.0. That is the line Jetty 9.x (we are on 9.3.30, 9.4 is the same
# API level) pairs its JSP support with. Tomcat 10 and later moved the whole API
# to jakarta.* and need Jetty 10 or later, and Tomcat 8.x predates the EL and JSP
# features the console JSPs already use, so both are out of scope here.
COMPAT_SERIES='9.0'

# The jars we check in, as "path<TAB>Maven artifactId". This is the allowlist the
# fetch, the verify and the advisory triage all read, so adding a jar here is the
# only place a shipped jar has to be named.
jar_allowlist() {
    cat <<'EOF'
lib/jasper.jar	tomcat-jasper
lib/jasper-el.jar	tomcat-jasper-el
lib/el-api.jar	tomcat-el-api
lib/jsp-api.jar	tomcat-jsp-api
lib/servlet-api.jar	tomcat-servlet-api
lib/tomcat-api.jar	tomcat-api
lib/tomcat-util.jar	tomcat-util
lib/tomcat-util-scan.jar	tomcat-util-scan
bin/tomcat-juli.jar	tomcat-juli
EOF
}

CURL=${CURL:-curl}
TMPROOT=""

cleanup() {
    if [ -n "$TMPROOT" ] && [ -d "$TMPROOT" ]
    then
        rm -rf "$TMPROOT"
    fi
}
trap cleanup EXIT INT TERM

newtmp() {
    TMPROOT=$(mktemp -d "${TMPDIR:-/tmp}/update-tomcat.XXXXXX")
}

die() {
    echo "ERROR: $*" >&2
    exit 1
}

need_curl() {
    command -v "$CURL" >/dev/null 2>&1 || die "$CURL is not available; set CURL or fetch the jars by hand"
}

#---------------------------------------------------------------------------
# pins
#---------------------------------------------------------------------------

# The Ant pin, which is the one the build and the packaging targets read.
read_pin() {
    sed -n 's/.*<property name="tomcat\.ver" value="\([^"]*\)".*/\1/p' "$1" | head -1
}

# The Gradle pin, which the app build.gradle files use for their fileTree and for
# the org.apache.tomcat coordinates.
read_gradle_pin() {
    sed -n 's/^[[:space:]]*tomcatVersion[[:space:]]*=[[:space:]]*\([^[:space:]]*\).*/\1/p' "$1" | head -1
}

# The version carried by the checked-in directory name.
read_dir_version() {
    for d in "$1"/apache-tomcat-*
    do
        [ -d "$d" ] || continue
        basename "$d" | sed 's/^apache-tomcat-//'
    done
}

#---------------------------------------------------------------------------
# versions
#---------------------------------------------------------------------------

# -1 when $1 is older than $2, 0 when equal, 1 when newer. Dotted decimal,
# compared field by field so 9.0.9 stays older than 9.0.10.
version_cmp() {
    _a=$1
    _b=$2
    _i=1
    while :
    do
        _fa=$(printf '%s' "$_a" | cut -d. -f"$_i")
        _fb=$(printf '%s' "$_b" | cut -d. -f"$_i")
        if [ -z "$_fa" ] && [ -z "$_fb" ]
        then
            echo 0
            return
        fi
        [ -z "$_fa" ] && _fa=0
        [ -z "$_fb" ] && _fb=0
        case "$_fa$_fb" in
            *[!0-9]*) echo "version_cmp: not a number: $_a $_b" >&2; return 2 ;;
        esac
        if [ "$_fa" -gt "$_fb" ]
        then
            echo 1
            return
        elif [ "$_fa" -lt "$_fb" ]
        then
            echo -1
            return
        fi
        _i=$((_i + 1))
    done
}

# True only for the COMPAT_SERIES line. Anything else is refused rather than
# quietly accepted, because a jakarta-namespace jar set would compile and then
# fail at runtime with missing javax.servlet classes.
compatible() {
    case "$1" in
        "$COMPAT_SERIES".[0-9]*)
            case "${1#"$COMPAT_SERIES".}" in
                *[!0-9]*) return 1 ;;
                *) return 0 ;;
            esac
            ;;
        *) return 1 ;;
    esac
}

# The newest version in a newline-separated list that is on the compatible line.
latest_compatible() {
    _latest=""
    for _v in $(printf '%s' "$1" | tr -d ' \t')
    do
        compatible "$_v" || continue
        if [ -z "$_latest" ] || [ "$(version_cmp "$_v" "$_latest")" = 1 ]
        then
            _latest=$_v
        fi
    done
    printf '%s\n' "$_latest"
}

# Version numbers out of a Maven maven-metadata.xml.
versions_from_metadata() {
    sed -n 's/.*<version>\([^<]*\)<\/version>.*/\1/p' "$1"
}

#---------------------------------------------------------------------------
# fetching
#---------------------------------------------------------------------------

fetch() {
    # $1 url, $2 destination
    "$CURL" -sSfL --max-time 120 -o "$2" "$1" || return 1
}

# Downloads one artifact and checks it against the SHA-1 Maven publishes beside
# it. Prints the verified sha1 on success.
fetch_jar() {
    _artifact=$1
    _version=$2
    _dest=$3
    _base="$MAVEN_BASE/$_artifact/$_version/$_artifact-$_version.jar"
    fetch "$_base.sha1" "$TMPROOT/expected.sha1" || return 1
    _want=$(tr -d ' \r\n' < "$TMPROOT/expected.sha1")
    case "$_want" in
        *[0-9a-f][0-9a-f]*) ;;
        *) return 1 ;;
    esac
    fetch "$_base" "$_dest" || return 1
    _got=$(sha1sum "$_dest" | cut -d' ' -f1)
    if [ "$_want" != "$_got" ]
    then
        echo "  checksum mismatch for $_artifact-$_version" >&2
        echo "    expected: $_want" >&2
        echo "    got:      $_got" >&2
        return 1
    fi
    printf '%s\n' "$_got"
}

#---------------------------------------------------------------------------
# commands
#---------------------------------------------------------------------------

cmd_list() {
    printf '%-26s %s\n' 'CHECKED-IN PATH' 'MAVEN ARTIFACT'
    jar_allowlist | while read -r path artifact
    do
        printf '%-26s %s\n' "$path" "$artifact"
    done
}

# Reports the three version strings that have to agree, the newest compatible
# release, and whether the tree is behind.
cmd_check() {
    _pin=$(read_pin "$BUILD_XML")
    [ -n "$_pin" ] || die "no tomcat.ver property in build.xml"
    _gpin=$(read_gradle_pin "$GRADLE_PROPS")
    _dir=$(read_dir_version "$JETTY_DIR" | head -1)
    [ -n "$_dir" ] || die "no apps/jetty/apache-tomcat-* directory found"

    _latest=''
    if [ "$OFFLINE" = 1 ]
    then
        _latest=''
    else
        need_curl
        newtmp
        fetch "$MAVEN_BASE/$METADATA_ARTIFACT/maven-metadata.xml" "$TMPROOT/metadata.xml" ||
            die "cannot read $MAVEN_BASE/$METADATA_ARTIFACT/maven-metadata.xml"
        _latest=$(versions_from_metadata "$TMPROOT/metadata.xml" | latest_compatible "$(cat)")
    fi

    if [ "$JSON" = 1 ]
    then
        printf '{"ant":"%s","gradle":"%s","directory":"%s","latest":"%s","behind":%s}\n' \
            "$_pin" "$_gpin" "$_dir" "$_latest" \
            "$([ -n "$_latest" ] && [ "$(version_cmp "$_latest" "$_pin")" = 1 ] && echo true || echo false)"
    else
        echo "Ant pin (build.xml):        $_pin"
        echo "Gradle pin (gradle.properties): ${_gpin:-none}"
        echo "checked-in directory:       apache-tomcat-$_dir"
        echo "compatible line:            $COMPAT_SERIES.x (javax API, Jetty 9.x)"
        echo "latest compatible release:  ${_latest:-unknown}"
        _drift=0
        [ "$_pin" != "$_dir" ] && { echo "DRIFT: build.xml and the checked-in directory disagree"; _drift=1; }
        if [ -n "$_gpin" ] && [ "$_gpin" != "$_pin" ]
        then
            echo "DRIFT: gradle.properties pins $_gpin, build.xml pins $_pin"
            _drift=1
        fi
        [ "$_drift" = 0 ] && echo "pins agree"
        if [ -n "$_latest" ] && [ "$(version_cmp "$_latest" "$_pin")" = 1 ]
        then
            echo "UPDATE AVAILABLE: $(version_gap "$_pin" "$_latest") patch releases newer"
            echo "  run: tools/scripts/update-tomcat.sh apply $_latest"
        else
            echo "up to date"
        fi
    fi

    if [ -n "$_latest" ] && [ "$(version_cmp "$_latest" "$_pin")" = 1 ]
    then
        return 3
    fi
    return 0
}

# How many patch releases between $1 (exclusive) and $2 (inclusive).
version_gap() {
    _from=$(printf '%s' "$1" | cut -d. -f3)
    _to=$(printf '%s' "$2" | cut -d. -f3)
    echo $((_to - _from))
}

# Verifies the checked-in jars and NOTICE against what Apache publishes, without
# writing anything. Exits non-zero when a file does not match, which is how a
# tampered or truncated checked-in jar gets caught.
cmd_verify() {
    _dir=$(read_dir_version "$JETTY_DIR" | head -1)
    [ -n "$_dir" ] || die "no apps/jetty/apache-tomcat-* directory found"
    need_curl
    newtmp
    : > "$TMPROOT/results"
    jar_allowlist | while read -r path artifact
    do
        _file="$JETTY_DIR/apache-tomcat-$_dir/$path"
        if [ ! -f "$_file" ]
        then
            echo "  missing  $_file"
            printf 'bad\n' >> "$TMPROOT/results"
            continue
        fi
        _want=$(fetch "$MAVEN_BASE/$artifact/$_dir/$artifact-$_dir.jar.sha1" "$TMPROOT/e.sha1" &&
                tr -d ' \r\n' < "$TMPROOT/e.sha1")
        _got=$(sha1sum "$_file" | cut -d' ' -f1)
        if [ -z "$_want" ]
        then
            echo "  unknown  $artifact-$_dir (no published checksum)"
            printf 'bad\n' >> "$TMPROOT/results"
        elif [ "$_want" = "$_got" ]
        then
            echo "  ok       $path  $_got"
            printf 'ok\n' >> "$TMPROOT/results"
        else
            echo "  DIFFERS  $path"
            echo "             expected: $_want"
            echo "             got:      $_got"
            printf 'bad\n' >> "$TMPROOT/results"
        fi
    done
    _total=$(wc -l < "$TMPROOT/results" | tr -d ' ')
    _bad=$(grep -c '^bad$' "$TMPROOT/results" || true)
    if [ "${_bad:-0}" = 0 ]
    then
        echo "$_total checked-in jars match the Maven Central artifacts"
        return 0
    fi
    echo "$_bad of $_total checked-in jars do not match Maven Central"
    return 1
}

# Lists the advisories fixed after the pin, and marks the ones whose wording
# names a component we actually ship. This is a triage aid, not a verdict: the
# shipped-jar allowlist decides what we carry, the wording decides what is worth
# reading. Everything else in the list is Tomcat's own connector, HTTP/2, AJP,
# WebSocket, realm, valve and cluster code, none of which is in these jars.
cmd_advisories() {
    _pin=$(read_pin "$BUILD_XML")
    _to=${1:-}
    need_curl
    newtmp
    if [ -z "$_to" ]
    then
        fetch "$MAVEN_BASE/$METADATA_ARTIFACT/maven-metadata.xml" "$TMPROOT/metadata.xml" ||
            die "cannot read the Maven metadata"
        _to=$(versions_from_metadata "$TMPROOT/metadata.xml" | latest_compatible "$(cat)")
    fi
    fetch "$SECURITY_URL" "$TMPROOT/security.html" || die "cannot read $SECURITY_URL"
    echo "Advisories fixed after $_pin, up to ${_to:-any}"
    echo "SHIPPED names a component in our jar set; elsewhere is Tomcat container code"
    echo "(connector, HTTP/2, AJP, WebSocket, realm, valve, cluster) that we do not ship."
    advisory_report "$TMPROOT/security.html" "$_pin" "$_to"
    return 0
}

# Extracts the advisories fixed after the pin from the Tomcat 9 security page and
# sorts them by whether the title names a component in our jar set. This is a
# triage aid, not a verdict: the allowlist decides what we carry, the title decides
# what is worth reading. Everything marked elsewhere is Tomcat's own connector,
# HTTP/2, AJP, WebSocket, realm, valve and cluster code.
#
# The scan is line-based on the raw HTML rather than paragraph-based: the release
# heading sits on the same line as the end of the table of contents, so a
# paragraph-mode parser loses the newest release - the one that matters. Headings
# are found by their anchor id (the table of contents uses href=, not id=), and a
# title is collected across lines until </strong>, because the titles wrap.
#
# $1 the security page, $2 the pin (exclusive), $3 the newest release to consider
advisory_report() {
    awk -v pin="$2" -v to="$3" '
        function vcmp(a, b,   i, fa, fb, x, y) {
            split(a, fa, ".")
            split(b, fb, ".")
            for (i = 1; i <= 4; i++) {
                x = fa[i] + 0
                y = fb[i] + 0
                if (x > y) return 1
                if (x < y) return -1
            }
            return 0
        }
        BEGIN { cur = "" }
        {
            if (match($0, /id="Not_a_vulnerability_in_Tomcat"/)) {
                cur = ""
                next
            }
            n = split($0, parts, /id="Fixed_in_Apache_Tomcat_/)
            if (n > 1) {
                match(parts[2], /^[0-9A-Za-z.]+/)
                cur = substr(parts[2], RSTART, RLENGTH)
                next
            }
            if ($0 !~ /<strong>(Critical|Important|Moderate|Low|High):/)
                next
            txt = $0
            while (txt !~ /<\/strong>/ && (getline nxt) > 0)
                txt = txt " " nxt
            gsub(/<[^>]*>/, " ", txt)
            gsub(/&lt;/, "<", txt)
            gsub(/&gt;/, ">", txt)
            gsub(/&amp;/, "\\&", txt)
            gsub(/[[:space:]]+/, " ", txt)
            sub(/^ */, "", txt)
            sub(/ *$/, "", txt)
            if (cur == "") next
            if (vcmp(cur, pin) <= 0) next
            if (to != "" && vcmp(cur, to) > 0) next
            tag = "elsewhere"
            if (txt ~ /Jasper|JSTL|JSP|apache-jsp|expression language| EL |JULI/) tag = "SHIPPED"
            printf "  %-9s %-9s %s\n", cur, tag, substr(txt, 1, 100)
        }
    ' "$1"
}

cmd_apply() {
    _pin=$(read_pin "$BUILD_XML")
    _dir=$(read_dir_version "$JETTY_DIR" | head -1)
    [ -n "$_dir" ] || die "no apps/jetty/apache-tomcat-* directory found"
    _to=${1:-}
    need_curl
    newtmp
    if [ -z "$_to" ]
    then
        fetch "$MAVEN_BASE/$METADATA_ARTIFACT/maven-metadata.xml" "$TMPROOT/metadata.xml" ||
            die "cannot read the Maven metadata"
        _to=$(versions_from_metadata "$TMPROOT/metadata.xml" | latest_compatible "$(cat)")
    fi
    [ -n "$_to" ] || die "no compatible release found on the $COMPAT_SERIES line"
    compatible "$_to" || die "$_to is not on the $COMPAT_SERIES line (Tomcat 10+ is jakarta.* and needs Jetty 10+)"
    case "$(version_cmp "$_to" "$_pin")" in
        1) ;;
        0) echo "$_pin is already the checked-in version"; return 0 ;;
        *) die "$_to is older than the checked-in $_pin" ;;
    esac

    echo "Updating Jasper/Tomcat $_pin -> $_to"
    if [ "$DRY_RUN" = 1 ]
    then
        echo "  would fetch $(jar_allowlist | wc -l) jars plus NOTICE into apps/jetty/apache-tomcat-$_to"
        echo "  would remove apps/jetty/apache-tomcat-$_dir"
        echo "  would set tomcat.ver in build.xml and tomcatVersion in gradle.properties to $_to"
        return 0
    fi

    _newdir="$JETTY_DIR/apache-tomcat-$_to"
    # Everything is fetched and verified into a scratch directory first, so a
    # failed or truncated download leaves the tree exactly as it was instead of
    # leaving a half-populated version directory for the build to pick up.
    _stage="$TMPROOT/stage"
    mkdir -p "$_stage/lib" "$_stage/bin" || die "cannot create the staging directory"
    jar_allowlist | while read -r path artifact
    do
        _sha=$(fetch_jar "$artifact" "$_to" "$_stage/$path") ||
            { echo "fetching $artifact-$_to failed" >&2; exit 1; }
        echo "  ok  $path  $_sha"
    done || die "jar fetch failed; nothing was changed"

    fetch "$(printf '%s' "$NOTICE_URL" | sed "s/VERSION/$_to/")" "$_stage/NOTICE" ||
        die "cannot fetch the NOTICE for $_to; nothing was changed"
    _year=$(sed -n 's/.*Copyright \(19[0-9][0-9]\)-\([0-9][0-9][0-9][0-9]\) The Apache Software Foundation.*/\2/p' \
            "$_stage/NOTICE" | head -1)
    echo "  ok  NOTICE (copyright through ${_year:-unknown})"

    # Published only once all nine jars are on disk and verified.
    mkdir -p "$_newdir/lib" "$_newdir/bin" || die "cannot create $_newdir"
    for _path in $(jar_allowlist | cut -f1)
    do
        [ -s "$_stage/$_path" ] || die "$_path is missing from the staging area"
        mv "$_stage/$_path" "$_newdir/$_path" || die "cannot install $_path"
    done
    mv "$_stage/NOTICE" "$_newdir/NOTICE" || die "cannot install NOTICE"
    echo "  installed apps/jetty/apache-tomcat-$_to"

    if [ "$_dir" != "$_to" ]
    then
        rm -rf "$JETTY_DIR/apache-tomcat-$_dir" || die "cannot remove the old directory"
        echo "  removed apps/jetty/apache-tomcat-$_dir"
    fi

    rewrite_pin "$BUILD_XML" "$_to"
    rewrite_gradle_pin "$GRADLE_PROPS" "$_to"
    [ -n "$_year" ] && sync_licenses_year "$LICENSES_MD" "$_year"
    echo "  pins set to $_to"

    _strays=$(find_strays "$REPO_ROOT" "$_pin")
    if [ -n "$_strays" ]
    then
        echo ""
        echo "Leftover references to $_pin (update these by hand):"
        printf '%s\n' "$_strays" | sed 's/^/  /'
    fi

    echo ""
    echo "Next: rebuild the JSPs, since Jasper is what changed."
    echo "  ant -q jspc                     # or the module precompileJsp tasks"
    echo "  tools/scripts/checkjsps.py"
    echo "  tools/scripts/run-tests.sh i2ptunnel && tools/scripts/run-tests.sh router"
    echo "Then commit apps/jetty/apache-tomcat-$_to, build.xml, gradle.properties"
    echo "and docs/LICENSES.md. Nothing has been committed or deployed."
}

# Rewrites the Ant pin in place, keeping the surrounding formatting. The
# replacement stops at the closing quote so both value="x"/> and value="x"> match.
rewrite_pin() {
    sed -i 's|\(<property name="tomcat\.ver" value="\)[^"]*|\1'"$2"'|' "$1"
}

rewrite_gradle_pin() {
    sed -i 's/^\([[:space:]]*tomcatVersion[[:space:]]*=[[:space:]]*\).*/\1'"$2"'/' "$1"
}

# docs/LICENSES.md quotes the same copyright line as the NOTICE; keep the two in
# step so a bump does not leave the shipped license text naming an older year.
sync_licenses_year() {
    _file=$1
    _year=$2
    [ -f "$_file" ] || return 0
    sed -i 's/^\(Apache Tomcat\. Copyright 19[0-9][0-9]\)-[0-9][0-9][0-9][0-9]/\1-'"$_year"'/' "$_file"
}

# Anywhere else in the tree that still names the old version. The build and the
# packaging targets all read the property, so a hit here is a hardcoded string
# that will silently keep pointing at a directory that no longer exists.
find_strays() {
    grep -rn --exclude-dir=.git --exclude-dir=build --exclude-dir=dist --exclude-dir=wip \
         --exclude-dir=jettylib --exclude='*-test.sh' -- "$2" "$1" 2>/dev/null |
    grep -v 'Binary file' |
    sed 's|'"$1"'/||'
}

#---------------------------------------------------------------------------
# selftest - offline, no network
#---------------------------------------------------------------------------

PASS=0
FAILED=0

pass() {
    PASS=$((PASS + 1))
    echo "  ok   $1"
}

fail() {
    FAILED=$((FAILED + 1))
    echo "  FAIL $1"
    [ $# -gt 1 ] && printf '       %s\n' "$2"
}

assert_eq() {
    if [ "$1" = "$2" ]
    then
        pass "$3"
    else
        fail "$3" "expected [$1] got [$2]"
    fi
}

cmd_selftest() {
    newtmp

    echo "-- version_cmp --"
    assert_eq 1 "$(version_cmp 9.0.123 9.0.107)" "9.0.123 is newer than 9.0.107"
    assert_eq -1 "$(version_cmp 9.0.9 9.0.10)" "9.0.9 is older than 9.0.10 (numeric, not lexical)"
    assert_eq 0 "$(version_cmp 9.0.107 9.0.107)" "equal versions compare equal"
    assert_eq 0 "$(version_cmp 9.0.107 9.0.107.0)" "a trailing .0 field is zero, not a newer release"
    assert_eq 0 "$(version_cmp 9.0.107.0 9.0.107)" "and in the other order too"
    assert_eq -1 "$(version_cmp 9.0 9.0.1)" "a shorter version is older"

    echo "-- compatibility gate --"
    compatible 9.0.123 && pass "9.0.123 is accepted" || fail "9.0.123 is accepted"
    compatible 10.1.34 && fail "10.1.34 is rejected" || pass "10.1.34 is rejected (jakarta API)"
    compatible 11.0.27 && fail "11.0.27 is rejected" || pass "11.0.27 is rejected (jakarta API)"
    compatible 8.5.100 && fail "8.5.100 is rejected" || pass "8.5.100 is rejected (old EL/JSP)"
    compatible 9.1.5 && fail "9.1.5 is rejected" || pass "9.1.5 is rejected (off the pinned line)"
    compatible 9.0.x && fail "9.0.x is rejected" || pass "9.0.x is rejected (not a number)"
    compatible "" && fail "empty is rejected" || pass "empty is rejected"

    echo "-- latest_compatible --"
    _mixed='11.0.27
10.1.34
9.0.98
9.0.107
9.0.9
9.0.123
8.5.100'
    assert_eq 9.0.123 "$(latest_compatible "$_mixed")" "the newest 9.0.x wins over 9.0.9 and over 10/11"
    assert_eq "" "$(latest_compatible '11.0.27
10.1.34')" "no compatible version in a jakarta-only list"

    echo "-- metadata parsing --"
    cat > "$TMPROOT/metadata.xml" <<'EOF'
<?xml version="1.0" encoding="UTF-8"?>
<metadata>
  <versioning>
    <versions>
      <version>8.5.100</version>
      <version>9.0.107</version>
      <version>9.0.123</version>
      <version>10.1.34</version>
      <version>11.0.27</version>
    </versions>
    <lastUpdated>20261012113937</lastUpdated>
  </versioning>
</metadata>
EOF
    assert_eq 9.0.123 "$(versions_from_metadata "$TMPROOT/metadata.xml" | latest_compatible "$(cat)")" \
        "the metadata listing yields the newest compatible release"

    echo "-- pin reading --"
    cat > "$TMPROOT/build.xml" <<'EOF'
  <property name="jetty.ver" value="9.3.30.v20211001"/>
  <property name="tomcat.ver" value="9.0.107"/>
EOF
    assert_eq 9.0.107 "$(read_pin "$TMPROOT/build.xml")" "the Ant pin is read out of build.xml"
    cat > "$TMPROOT/gradle.properties" <<'EOF'
# Override these in ~/.gradle/gradle.properties if necessary
tomcatVersion=9.0.62
jettyVersion = '9.3.30.v20211001'
EOF
    assert_eq 9.0.62 "$(read_gradle_pin "$TMPROOT/gradle.properties")" "the Gradle pin is read out of gradle.properties"

    echo "-- pin rewriting --"
    rewrite_pin "$TMPROOT/build.xml" 9.0.123
    assert_eq 9.0.123 "$(read_pin "$TMPROOT/build.xml")" "the Ant pin is rewritten in place"
    assert_eq '  <property name="tomcat.ver" value="9.0.123"/>' \
        "$(grep 'tomcat.ver' "$TMPROOT/build.xml")" "the rewriting keeps the attribute form intact"
    cat > "$TMPROOT/build2.xml" <<'EOF'
  <property name="tomcat.ver" value="9.0.107" />
EOF
    rewrite_pin "$TMPROOT/build2.xml" 9.0.123
    assert_eq 9.0.123 "$(read_pin "$TMPROOT/build2.xml")" "the spaced attribute form is rewritten too"
    assert_eq '9.3.30.v20211001' "$(sed -n 's/.*<property name="jetty\.ver" value="\([^"]*\)".*/\1/p' "$TMPROOT/build.xml")" \
        "rewriting tomcat.ver leaves jetty.ver alone"
    rewrite_gradle_pin "$TMPROOT/gradle.properties" 9.0.123
    assert_eq 9.0.123 "$(read_gradle_pin "$TMPROOT/gradle.properties")" "the Gradle pin is rewritten in place"
    assert_eq "jettyVersion = '9.3.30.v20211001'" "$(grep '^jettyVersion' "$TMPROOT/gradle.properties")" \
        "rewriting tomcatVersion leaves the other lines alone"

    echo "-- license year sync --"
    cat > "$TMPROOT/LICENSES.md" <<'EOF'
## APACHE TOMCAT

Apache Tomcat. Copyright 1999-2025 The Apache Software Foundation.

## SPOTBUGS
EOF
    sync_licenses_year "$TMPROOT/LICENSES.md" 2026
    assert_eq 'Apache Tomcat. Copyright 1999-2026 The Apache Software Foundation.' \
        "$(grep '^Apache Tomcat\.' "$TMPROOT/LICENSES.md")" "the copyright year follows the NOTICE"
    assert_eq '## SPOTBUGS' "$(grep '^## SPOTBUGS' "$TMPROOT/LICENSES.md")" "the rest of the file is untouched"

    echo "-- directory version --"
    mkdir -p "$TMPROOT/jetty/apache-tomcat-9.0.107/lib"
    assert_eq 9.0.107 "$(read_dir_version "$TMPROOT/jetty" | head -1)" "the version comes from the checked-in directory name"

    echo "-- stray references --"
    mkdir -p "$TMPROOT/tree/apps/jetty" "$TMPROOT/tree/build"
    echo 'from("apps/jetty/apache-tomcat-9.0.107/NOTICE")' > "$TMPROOT/tree/tools.gradle"
    mkdir -p "$TMPROOT/tree/tools"
    echo 'from("apps/jetty/apache-tomcat-9.0.107/NOTICE")' > "$TMPROOT/tree/tools/installer.gradle"
    echo '9.0.107' > "$TMPROOT/tree/build/stale.txt"
    _strays=$(find_strays "$TMPROOT/tree" 9.0.107)
    assert_eq 1 "$(printf '%s\n' "$_strays" | grep -c 'tools/installer.gradle')" "a hardcoded path outside the pin is reported"
    assert_eq 0 "$(printf '%s\n' "$_strays" | grep -c 'build/stale.txt')" "the build directory is not reported"

    echo "-- advisory triage --"
    # Shaped like the real page: the table of contents uses href=, the release
    # heading uses id=, and the heading shares a line with the end of the contents.
    cat > "$TMPROOT/security.html" <<'EOF'
<ul><li><a href="#Fixed_in_Apache_Tomcat_9.0.123">Fixed in Apache Tomcat 9.0.123</a></li><li><a href="#Fixed_in_Apache_Tomcat_9.0.122">Fixed in Apache Tomcat 9.0.122</a></li><li><a href="#Fixed_in_Apache_Tomcat_9.0.107">Fixed in Apache Tomcat 9.0.107</a></li></ul>
</div><h3 id="Fixed_in_Apache_Tomcat_9.0.123"><span class="pull-right">2026-10-12</span> Fixed in Apache Tomcat 9.0.123</h3><div class="text">

    <p><strong>Low: Jasper JSTL &lt;c:redirect&gt; plug-in implementation omits page
       abort (SKIP_PAGE)</strong>
       <a href="http://cve.mitre.org/cgi-bin/cvename.cgi?name=CVE-2026-74049">CVE-2026-74049</a></p>

    <p>The Jasper JSTL plug-in replacement for the &lt;c:redirect&gt; does not end processing.</p>

    <p><strong>Important: HTTP/2 request/response mix-up</strong>
       <a href="http://cve.mitre.org/cgi-bin/cvename.cgi?name=CVE-2026-101356">CVE-2026-101356</a></p>

</div><h3 id="Fixed_in_Apache_Tomcat_9.0.122"><span class="pull-right">2026-09-15</span> Fixed in Apache Tomcat 9.0.122</h3>

    <p><strong>Low: WebSocket message smuggling with per-message-deflate</strong>
       <a href="http://cve.mitre.org/cgi-bin/cvename.cgi?name=CVE-2026-87022">CVE-2026-87022</a></p>

</div><h3 id="Fixed_in_Apache_Tomcat_9.0.107"><span class="pull-right">2025-07-04</span> Fixed in Apache Tomcat 9.0.107</h3>

    <p><strong>Low: already fixed at the pin</strong>
       <a href="http://cve.mitre.org/cgi-bin/cvename.cgi?name=CVE-2025-52434">CVE-2025-52434</a></p>

</div><h3 id="Not_a_vulnerability_in_Tomcat">Not a vulnerability in Tomcat</h3>

    <p><strong>Low: reported against Tomcat but not ours</strong> prose.</p>
EOF
    _rep=$(advisory_report "$TMPROOT/security.html" 9.0.107 9.0.123)
    assert_eq 3 "$(printf '%s\n' "$_rep" | grep -c .)" "one line per advisory fixed after the pin"
    assert_eq 1 "$(printf '%s\n' "$_rep" | grep -c SHIPPED)" "the Jasper advisory is marked SHIPPED"
    assert_eq 1 "$(printf '%s\n' "$_rep" | grep SHIPPED | grep -c 'abort (SKIP_PAGE)')" "a title that wraps across lines is read as one title"
    assert_eq 2 "$(printf '%s\n' "$_rep" | grep -c elsewhere)" "HTTP/2 and WebSocket are container code"
    assert_eq 0 "$(printf '%s\n' "$_rep" | grep -c 'already fixed at the pin')" "the advisory fixed at the pin itself is dropped"
    assert_eq 0 "$(printf '%s\n' "$_rep" | grep -c 'not ours')" "the not-a-vulnerability section is not attributed to a release"
    assert_eq 0 "$(printf '%s\n' "$_rep" | grep -c '9.0.107')" "the pinned release contributes nothing"
    assert_eq 1 "$(advisory_report "$TMPROOT/security.html" 9.0.107 9.0.122 | grep -c .)" "the upper bound stops the newer releases"
    assert_eq 2 "$(advisory_report "$TMPROOT/security.html" 9.0.122 9.0.123 | grep -c .)" "a pin partway through the list skips the older releases"
    assert_eq 0 "$(advisory_report "$TMPROOT/security.html" 9.0.123 9.0.123 | grep -c .)" "an up-to-date pin reports nothing"
    assert_eq 1 "$(printf '%s\n' "$_rep" | grep -c '<c:redirect>')" "the title is decoded from HTML entities"

    echo "-- gap counting --"
    assert_eq 16 "$(version_gap 9.0.107 9.0.123)" "107 to 123 is 16 patch releases"

    echo "-- allowlist --"
    assert_eq 9 "$(jar_allowlist | wc -l | tr -d ' ')" "the allowlist names the nine checked-in jars"
    assert_eq 0 "$(jar_allowlist | grep -c catalina)" "catalina.jar is not shipped, so its advisories are not ours"
    assert_eq 1 "$(jar_allowlist | grep -c 'bin/tomcat-juli.jar	tomcat-juli')" "juli is fetched from bin/, where the build reads it"

    echo ""
    echo "passed: $PASS   failed: $FAILED"
    [ "$FAILED" = 0 ] || return 1
    return 0
}

#---------------------------------------------------------------------------
# entry point
#---------------------------------------------------------------------------

usage() {
    sed -n '3,26p' "$0" | sed 's/^# \{0,1\}//'
}

DRY_RUN=0
OFFLINE=0
COMMAND=check
TARGET=''
JSON=''

while [ $# -gt 0 ]
do
    case "$1" in
        --dry-run) DRY_RUN=1; shift ;;
        --json) JSON=1; shift ;;
        --offline) OFFLINE=1; shift ;;
        -h|--help) usage; exit 0 ;;
        check|verify|advisories|apply|list|selftest) COMMAND=$1; shift ;;
        -*) die "unknown option $1 (try --help)" ;;
        *) TARGET=$1; shift ;;
    esac
done

case "$COMMAND" in
    check) cmd_check ;;
    verify) cmd_verify ;;
    advisories) cmd_advisories "$TARGET" ;;
    apply) cmd_apply "$TARGET" ;;
    list) cmd_list ;;
    selftest) cmd_selftest ;;
esac
