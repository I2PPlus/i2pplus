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
# One version directory is checked in at a time: apps/jetty/apache-tomcat-<ver>.
# apply installs the new one, deletes the old one from the worktree and stages
# both halves of the change, because an unstaged deletion leaves the previous
# version's jars tracked and the next checkout ships two versions. check and
# verify report that state as DRIFT rather than calling the tree clean.
#
# Usage:
#   tools/scripts/update-tomcat.sh              # check: report the pin and what is available
#   tools/scripts/update-tomcat.sh check        # same; exit 3 when an update is available
#                                               # or a stale version directory is tracked
#   tools/scripts/update-tomcat.sh check-version # one line for a build log; exit 3 when behind,
#                                               # 4 when it cannot tell, never fatal
#   tools/scripts/update-tomcat.sh verify       # verify the checked-in jars against Maven
#   tools/scripts/update-tomcat.sh advisories   # advisories between the pin and the candidate
#   tools/scripts/update-tomcat.sh apply [VER]  # update in place (default: latest compatible)
#   tools/scripts/update-tomcat.sh commit [VER] # commit the update as one line
#                                               # (default: the version on disk, so a
#                                               # workspace left dirty by an earlier run
#                                               # or a hand update is picked up too)
#   tools/scripts/update-tomcat.sh list         # print the shipped-jar allowlist
#   tools/scripts/update-tomcat.sh selftest     # offline tests of the logic below
#
# Options:
#   --dry-run     print what apply or commit would do without writing anything
#   --json        machine-readable output for check
#   --offline     do not reach for the network; report what the tree says
#   --commit      with check or apply: report the commit the tree already
#                 implies and make it, unless --dry-run is given as well
#
# Nothing is committed unless you ask for it, and nothing is copied to a running
# router: the deliverable is the changed source tree plus the list of artifacts
# to rebuild.

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

# The version names carried by apps/jetty/apache-tomcat-<ver>/... paths read from
# stdin. The whole path is consumed by the match, so a jar two levels down names
# its version directory and not the file at the end of it.
dir_versions_from_paths() {
    sed -n 's|^apps/jetty/apache-tomcat-\([^/]*\)/.*$|\1|p' | sort -u
}

# The versions the git index still holds, whether or not they are on disk. After
# an apply removes the previous directory, an unstaged deletion leaves the old
# jars tracked, so a checkout of the tree still ships two versions.
tracked_dir_versions() {
    git -C "$REPO_ROOT" ls-files -- apps/jetty 2>/dev/null | dir_versions_from_paths
}

# Versions tracked in the index but no longer on disk, as a newline list. Both
# arguments are newline lists: the versions on disk first, the tracked ones second.
stale_versions() {
    printf '%s\n' "$2" | while read -r _v
    do
        [ -n "$_v" ] || continue
        printf '%s\n' "$1" | grep -qx -- "$_v" || printf '%s\n' "$_v"
    done
}

# How many files the index still holds for one version directory.
tracked_file_count() {
    git -C "$REPO_ROOT" ls-files -- "apps/jetty/apache-tomcat-$1" 2>/dev/null | wc -l |
        tr -d ' '
}

# The complete checked-in set for one version: every jar on the allowlist plus
# NOTICE. The repo's blanket '*.[rwjt]ar' and '**/bin/' rules hide exactly this
# kind of path, so a version directory can be on disk, staged, and still be
# missing its jars in the index.
expected_tracked_count() {
    echo $(( $(jar_allowlist | wc -l | tr -d ' ') + 1 ))
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
    _drift=0
    _incomplete=0
    _stale=$(stale_versions "$(read_dir_version "$JETTY_DIR")" "$(tracked_dir_versions)")

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
        printf '{"ant":"%s","gradle":"%s","directory":"%s","latest":"%s","stale":"%s","behind":%s}\n' \
            "$_pin" "$_gpin" "$_dir" "$_latest" \
            "$(printf '%s' "$_stale" | tr '\n' ' ' | sed 's/ $//')" \
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
        if [ "$(read_dir_version "$JETTY_DIR" | grep -c .)" -gt 1 ]
        then
            echo "DRIFT: two version directories on disk: $(read_dir_version "$JETTY_DIR" | tr '\n' ' ')"
            _drift=1
        fi
        for _v in $_stale
        do
            echo "DRIFT: apache-tomcat-$_v is off disk but still tracked ($(tracked_file_count "$_v") files)"
            echo "  run: git add -A -- apps/jetty/apache-tomcat-$_v"
            _drift=1
        done
        _want=$(expected_tracked_count)
        _have=$(tracked_file_count "$_dir")
        if [ "$_have" != "$_want" ]
        then
            echo "DRIFT: apache-tomcat-$_dir has only $_have of $_want files tracked"
            echo "  run: git add -f -- apps/jetty/apache-tomcat-$_dir"
            _drift=1
            _incomplete=1
        fi
        [ "$_drift" = 0 ] && echo "pins agree"
        _status=$(check_status_line "$_pin" "$_latest")
        echo "$_status"
        case "$_status" in
            "UPDATE AVAILABLE:"*)
                echo "  run: tools/scripts/update-tomcat.sh apply $_latest"
                ;;
        esac
        # --commit with check: the tree already holds a completed update, so
        # report the commit it implies, and make it unless --dry-run says not to.
        # The subshell keeps a refusal from ending the check before its exit code.
        if [ "$COMMIT" = 1 ]
        then
            echo ""
            ( cmd_commit "$_dir" ) || echo "  not committing - fix the drift above first"
        fi
    fi

    if [ -n "$_latest" ] && [ "$(version_cmp "$_latest" "$_pin")" = 1 ]
    then
        return 3
    fi
    [ -n "$_stale" ] && return 3
    [ "$_incomplete" = 1 ] && return 3
    return 0
}

# How many patch releases between $1 (exclusive) and $2 (inclusive).
version_gap() {
    _from=$(printf '%s' "$1" | cut -d. -f3)
    _to=$(printf '%s' "$2" | cut -d. -f3)
    echo $((_to - _from))
}

# The last line of the human-readable check report. Kept apart from the fetch so
# selftest can drive every branch without a network: an offline check has never
# seen a release list, so it must not report "up to date".
check_status_line() {
    # $1 the checked-in pin, $2 the newest release on our line, empty if unknown
    _pin=$1
    _latest=$2
    if [ -z "$_latest" ]
    then
        echo "not checked - run without --offline to consult Maven Central"
        return 0
    fi
    if [ "$(version_cmp "$_latest" "$_pin")" = 1 ]
    then
        echo "UPDATE AVAILABLE: $(version_gap "$_pin" "$_latest") patch releases newer"
        return 0
    fi
    echo "up to date"
}

# The one line a build prints, and the exit code it branches on: 0 current, 3 a
# newer release is on our line, 4 we could not tell. Kept apart from the fetch so
# selftest can drive every branch without a network.
check_version_line() {
    # $1 the checked-in pin, $2 the newest release on our line, empty if unknown
    _pin=$1
    _latest=$2
    if [ -z "$_latest" ]
    then
        echo "Jasper/Tomcat: $_pin (not checked)"
        return 4
    fi
    case "$(version_cmp "$_latest" "$_pin")" in
        1)
            echo "Jasper/Tomcat: $_pin is $(version_gap "$_pin" "$_latest") patch releases behind, $_latest available - run tools/scripts/update-tomcat.sh apply $_latest"
            return 3
            ;;
        0)
            echo "Jasper/Tomcat: $_pin (up to date)"
            return 0
            ;;
        *)
            echo "Jasper/Tomcat: $_pin is newer than the newest release Apache lists ($_latest)"
            return 0
            ;;
    esac
}

# What the build calls: one line, writes nothing, and never fails a build - a
# host with no curl or no network gets a "(not checked, ...)" line and exit 4.
cmd_check_version() {
    _pin=$(read_pin "$BUILD_XML")
    if [ -z "$_pin" ]
    then
        echo "Jasper/Tomcat: no tomcat.ver in build.xml"
        return 4
    fi
    if [ "$OFFLINE" = 1 ]
    then
        check_version_line "$_pin" ''
        return 4
    fi
    if ! command -v "$CURL" >/dev/null 2>&1
    then
        echo "Jasper/Tomcat: $_pin (not checked, no curl)"
        return 4
    fi
    newtmp
    if ! fetch "$MAVEN_BASE/$METADATA_ARTIFACT/maven-metadata.xml" "$TMPROOT/metadata.xml"
    then
        echo "Jasper/Tomcat: $_pin (not checked, cannot reach Maven Central)"
        return 4
    fi
    _latest=$(versions_from_metadata "$TMPROOT/metadata.xml" | latest_compatible "$(cat)")
    check_version_line "$_pin" "$_latest"
}

# Verifies the checked-in jars and NOTICE against what Apache publishes, without
# writing anything. Exits non-zero when a file does not match, which is how a
# tampered or truncated checked-in jar gets caught.
cmd_verify() {
    _dir=$(read_dir_version "$JETTY_DIR" | head -1)
    [ -n "$_dir" ] || die "no apps/jetty/apache-tomcat-* directory found"
    _stale=$(stale_versions "$(read_dir_version "$JETTY_DIR")" "$(tracked_dir_versions)")
    _stale_n=$(printf '%s\n' "$_stale" | grep -c . || true)
    _incomplete=0
    for _v in $_stale
    do
        echo "  stale    apache-tomcat-$_v is off disk but still tracked ($(tracked_file_count "$_v") files)"
    done
    _want=$(expected_tracked_count)
    _have=$(tracked_file_count "$_dir")
    if [ "$_have" != "$_want" ]
    then
        echo "  untracked  apache-tomcat-$_dir has only $_have of $_want files in the git index"
        _incomplete=1
    fi
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
    if [ "${_bad:-0}" = 0 ] && [ "${_stale_n:-0}" = 0 ] && [ "${_incomplete:-0}" = 0 ]
    then
        echo "$_total checked-in jars match the Maven Central artifacts"
        return 0
    fi
    if [ "${_bad:-0}" != 0 ]
    then
        echo "$_bad of $_total checked-in jars do not match Maven Central"
    fi
    [ "${_stale_n:-0}" != 0 ] && echo "$_stale_n stale version directory(s) still tracked in git"
    [ "${_incomplete:-0}" = 1 ] && echo "the version directory on disk is not fully tracked"
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
        [ "$COMMIT" = 1 ] && echo "  would commit the result as: $(commit_subject "$_to")"
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
    _year=$(notice_year "$_stage/NOTICE")
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

    # The old jars are only gone once git stops tracking them: an unstaged
    # deletion leaves the previous version in the tree, and the next checkout of
    # it ships both. Stage the new files and every stale removal together.
    _specs="apps/jetty/apache-tomcat-$_to"
    for _v in $(stale_versions "$(read_dir_version "$JETTY_DIR")" "$(tracked_dir_versions)")
    do
        _specs="$_specs apps/jetty/apache-tomcat-$_v"
    done
    if git -C "$REPO_ROOT" rev-parse --git-dir >/dev/null 2>&1
    then
        # -f because the repo's blanket '*.[rwjt]ar' and '**/bin/' rules hide these
        # paths: a plain add stages the NOTICE and silently skips every jar.
        # shellcheck disable=SC2086
        git -C "$REPO_ROOT" add -A -f -- $_specs ||
            echo "  git add failed - stage $_specs by hand" >&2
        echo "  staged: $_specs"
        _want=$(expected_tracked_count)
        _have=$(tracked_file_count "$_to")
        [ "$_have" = "$_want" ] ||
            echo "  WARNING: only $_have of $_want files are tracked for $_to" >&2
    else
        echo "  not a git checkout - remove apps/jetty/apache-tomcat-$_dir by hand"
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
    echo "  ant -f apps/routerconsole/java/build.xml precompilejsp"
    echo "  ant -f apps/susidns/src/build.xml precompilejsp"
    echo "  ant -q buildI2PTunnel       # i2ptunnel's build.xml needs ant-contrib from here"
    echo "  ant -q checkjsps"
    echo "  tools/scripts/checkjsps.py"
    echo "  tools/scripts/run-tests.sh i2ptunnel && tools/scripts/run-tests.sh router"
    echo "Then commit the staged apps/jetty/apache-tomcat-$_to together with the"
    echo "removal of apache-tomcat-$_dir, build.xml, gradle.properties and"
    echo "docs/LICENSES.md - or run: tools/scripts/update-tomcat.sh commit $_to"
    if [ "$COMMIT" = 1 ]
    then
        echo ""
        cmd_commit "$_to"
    else
        echo "Nothing has been committed or deployed."
    fi
}

# Rewrites the Ant pin in place, keeping the surrounding formatting. The
# replacement stops at the closing quote so both value="x"/> and value="x"> match.
rewrite_pin() {
    sed -i 's|\(<property name="tomcat\.ver" value="\)[^"]*|\1'"$2"'|' "$1"
}

rewrite_gradle_pin() {
    sed -i 's/^\([[:space:]]*tomcatVersion[[:space:]]*=[[:space:]]*\).*/\1'"$2"'/' "$1"
}

# The end year of the Apache copyright line in a Tomcat NOTICE - the value an
# update writes into docs/LICENSES.md. Empty when the file has no such line.
notice_year() {
    sed -n 's/.*Copyright 19[0-9][0-9]-\([0-9][0-9][0-9][0-9]\) The Apache Software Foundation.*/\1/p' \
        "$1" | head -1
}

# The same year as it stands in docs/LICENSES.md.
licenses_year() {
    sed -n 's/^Apache Tomcat\. Copyright 19[0-9][0-9]-\([0-9][0-9][0-9][0-9]\).*/\1/p' \
        "$1" | head -1
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
#
# git grep, not grep -rn: the noise all lives in ignored trees - the downloaded
# static-analysis tools under tools/, jsdoc's node_modules, every **/bin/ build
# output - and .gitignore already says they are not ours. --untracked keeps a
# packaging file we have just written searchable. -F because the version is a
# literal: as a pattern its dots are wildcards, so an EdDSA test vector
# (920c123) and SVG glyph path data (9q0 123) both read as strays. This script's
# own selftest assertions name versions in fixtures, and they are not pins.
find_strays() {
    if git -C "$1" rev-parse --git-dir >/dev/null 2>&1
    then
        git -C "$1" grep --untracked -n -F -e "$2" -- \
            . ':(exclude)*/update-tomcat.sh'
    else
        grep -rn -F --exclude-dir=.git --exclude-dir=build --exclude-dir=dist \
             --exclude-dir=wip --exclude-dir=jettylib --exclude-dir=node_modules \
             --exclude='update-tomcat.sh' --exclude='*-test.sh' -- "$2" "$1" 2>/dev/null |
        sed 's|'"$1"'/||'
    fi
}

#---------------------------------------------------------------------------
# commit
#---------------------------------------------------------------------------

# The one-line subject. There is no separate Jasper release to name alongside
# Tomcat: every jar on the allowlist is an org.apache.tomcat artifact published
# at one version from one release train, so the pair always moves together and
# the subject names the pair the way the tree already does.
commit_subject() {
    printf 'build: update Jasper/Tomcat to %s\n' "$1"
}

# The version directories HEAD itself holds. The index is the wrong source for
# this: once apply stages the deletion, the old version is no longer in the
# index, and a commit built from the index's versions would leave the deletion
# uncommitted - which is the exact drift this script exists to prevent.
head_dir_versions() {
    git -C "$REPO_ROOT" ls-tree -r --name-only HEAD -- apps/jetty 2>/dev/null |
        dir_versions_from_paths
}

# The paths the commit is limited to: the version directory being shipped, every
# version directory HEAD holds but the disk does not (its deletion is the other
# half of the change), and the three files an update rewrites. $2 and $3 are the
# newline lists from read_dir_version and head_dir_versions.
commit_paths() {
    printf 'apps/jetty/apache-tomcat-%s\n' "$1"
    for _v in $(stale_versions "$2" "$3")
    do
        printf 'apps/jetty/apache-tomcat-%s\n' "$_v"
    done
    printf 'build.xml\ngradle.properties\ndocs/LICENSES.md\n'
}

# One of the three files an update rewrites, read out of HEAD rather than out of
# the worktree, so the script can tell a change it made from a change that was
# already there.
head_file() {
    _rel=${1#"$REPO_ROOT"/}
    git -C "$REPO_ROOT" show "HEAD:$_rel" 2>/dev/null
}

# The paths to commit, out of the candidates from commit_paths. A version
# directory moves as a whole, so every path under it that differs from HEAD
# belongs to the update. The three loose files are not like that: each holds one
# line the update writes, and the script knows the value it writes, so one is
# only ours when the worktree carries that value and HEAD does not. A
# docs/LICENSES.md whose year already matches stays out, and one that differs
# from HEAD only because of an unrelated license edit stays out too.
changed_paths() {
    _ver=$1
    _year=$(notice_year "$JETTY_DIR/apache-tomcat-$_ver/NOTICE")
    for _p in $(commit_paths "$_ver" "$2" "$3")
    do
        case "$_p" in
            apps/jetty/apache-tomcat-*)
                # shellcheck disable=SC2086
                git -C "$REPO_ROOT" diff --name-only --no-renames HEAD -- $_p 2>/dev/null
                ;;
            build.xml)
                if [ "$(read_pin "$BUILD_XML")" = "$_ver" ] &&
                    [ "$(head_file "$BUILD_XML" | read_pin -)" != "$_ver" ]
                then
                    printf '%s\n' "$_p"
                fi
                ;;
            gradle.properties)
                if [ "$(read_gradle_pin "$GRADLE_PROPS")" = "$_ver" ] &&
                    [ "$(head_file "$GRADLE_PROPS" | read_gradle_pin -)" != "$_ver" ]
                then
                    printf '%s\n' "$_p"
                fi
                ;;
            docs/LICENSES.md)
                if [ -n "$_year" ] && [ "$(licenses_year "$LICENSES_MD")" = "$_year" ] &&
                    [ "$(head_file "$LICENSES_MD" | licenses_year -)" != "$_year" ]
                then
                    printf '%s\n' "$_p"
                fi
                ;;
        esac
    done
}

# Commits one completed update as a single commit. The version defaults to the
# directory on disk, so this also picks up an update made by hand or left
# uncommitted by an earlier run.
cmd_commit() {
    git -C "$REPO_ROOT" rev-parse --git-dir >/dev/null 2>&1 ||
        die "not a git checkout - nothing to commit"
    git -C "$REPO_ROOT" rev-parse HEAD >/dev/null 2>&1 ||
        die "this checkout has no commits yet - stage and commit the jars by hand"
    _pin=$(read_pin "$BUILD_XML")
    [ -n "$_pin" ] || die "no tomcat.ver property in build.xml"
    _dirs=$(read_dir_version "$JETTY_DIR")
    _dir=$(printf '%s\n' "$_dirs" | head -1)
    [ -n "$_dir" ] || die "no apps/jetty/apache-tomcat-* directory found"
    [ "$(printf '%s\n' "$_dirs" | grep -c .)" = 1 ] ||
        die "two version directories on disk: $(printf '%s' "$_dirs" | tr '\n' ' ')"

    _ver=${1:-$_dir}
    compatible "$_ver" || die "$_ver is not on the $COMPAT_SERIES line"
    # The pins are checked against the directory rather than trusted, because
    # the whole point of this script is that the three of them can disagree.
    [ "$_pin" = "$_ver" ] ||
        die "build.xml pins $_pin while apache-tomcat-$_ver is on disk - set the pin first"
    _gpin=$(read_gradle_pin "$GRADLE_PROPS")
    if [ -n "$_gpin" ] && [ "$_gpin" != "$_ver" ]
    then
        die "gradle.properties pins $_gpin while build.xml pins $_ver - set the pin first"
    fi
    _want=$(expected_tracked_count)
    _have=$(tracked_file_count "$_ver")
    [ "$_have" = "$_want" ] ||
        die "apache-tomcat-$_ver has only $_have of $_want files tracked - run: git add -f -- apps/jetty/apache-tomcat-$_ver"

    _paths=$(changed_paths "$_ver" "$_dirs" "$(head_dir_versions)")
    # A rewritten file left out of that list is one this update did not move. Say
    # so, rather than leaving its difference from HEAD unexplained.
    for _p in build.xml gradle.properties docs/LICENSES.md
    do
        git -C "$REPO_ROOT" diff --quiet HEAD -- "$_p" ||
            case "$_paths" in
                *"$_p"*) ;;
                *) echo "  not committing $_p - it differs from HEAD, but not by the pin or the year" ;;
            esac
    done
    if [ -z "$_paths" ]
    then
        echo "apache-tomcat-$_ver is already committed - nothing to do"
        return 0
    fi

    echo "Committing:"
    printf '%s\n' "$_paths" | sed 's/^/  /'
    echo "Proposed commit message:"
    echo "  $(commit_subject "$_ver")"
    if [ "$DRY_RUN" = 1 ]
    then
        echo "Dry run - nothing committed"
        return 0
    fi
    # A pathspec commit, not a plain one: the workspace is normally dirty with
    # unrelated work and a plain commit takes everything the index holds.
    # shellcheck disable=SC2046,SC2086
    git -C "$REPO_ROOT" commit -m "$(commit_subject "$_ver")" -- $(printf '%s\n' "$_paths") ||
        die "git commit failed - nothing was committed, commit by hand"
    echo "Committed. Nothing is deployed: rebuild the JSPs and the console jars first."
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

    echo "-- stale version directories --"
    assert_eq 9.0.107 "$(printf '%s\n' apps/jetty/apache-tomcat-9.0.107/lib/el-api.jar \
        apps/jetty/apache-tomcat-9.0.107/NOTICE apps/jetty/build.xml |
        dir_versions_from_paths)" "a jar two levels down names its version, not its file name"
    assert_eq '' "$(printf '%s\n' apps/jetty/build.xml apps/jetty/jettylib/jetty-util.jar |
        dir_versions_from_paths)" "paths outside a version directory name no version"
    assert_eq 9.0.107 "$(stale_versions '9.0.123' '9.0.107
9.0.123')" "a version off disk but still tracked is stale"
    assert_eq '' "$(stale_versions '9.0.123' '9.0.123')" "the version on disk and tracked is not stale"
    assert_eq '' "$(stale_versions '9.0.123' '')" "nothing tracked is nothing stale"
    assert_eq '' "$(stale_versions '9.0.107
9.0.123' '9.0.107
9.0.123')" "two versions on disk and tracked are both current"
    assert_eq '9.0.107
9.0.98' "$(stale_versions '9.0.123' '9.0.107
9.0.123
9.0.98')" "every tracked version off disk is reported, in the order tracked"

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
    # Both of these hold the version's digits with other characters where the
    # dots are, so only a pattern match reads them as a reference.
    mkdir -p "$TMPROOT/tree/core/java/test/resources" "$TMPROOT/tree/fonts"
    echo '{ f422362e426c82af2d503398872920c12391382be1b7c19b892495a91223bb24 },' \
        > "$TMPROOT/tree/core/java/test/resources/basePrecmp"
    echo '<glyph unicode="h" d="M977 0h-301l137 653q16 68 16 119q0 123"/>' \
        > "$TMPROOT/tree/fonts/OpenSans-Italic-webfont.svg"
    _strays=$(find_strays "$TMPROOT/tree" 9.0.107)
    assert_eq 1 "$(printf '%s\n' "$_strays" | grep -c 'tools/installer.gradle')" "a hardcoded path outside the pin is reported"
    assert_eq 0 "$(printf '%s\n' "$_strays" | grep -c 'build/stale.txt')" "the build directory is not reported"
    assert_eq 0 "$(printf '%s\n' "$_strays" | grep -c 'basePrecmp')" "a hex digit run is not a version reference"
    assert_eq 0 "$(printf '%s\n' "$_strays" | grep -c 'webfont.svg')" "glyph path data is not a version reference"

    if command -v git >/dev/null 2>&1
    then
        # A real checkout is searched with git grep, so the ignored trees - the
        # downloaded tools, node_modules, **/bin/ output - never show up.
        mkdir -p "$TMPROOT/gittree/node_modules/jsdoc" "$TMPROOT/gittree/core/bin/test" \
            "$TMPROOT/gittree/tools" "$TMPROOT/gittree/tools/scripts"
        printf 'node_modules/\nbin/\n' > "$TMPROOT/gittree/.gitignore"
        echo '9.0.107' > "$TMPROOT/gittree/node_modules/jsdoc/OpenSans-Italic-webfont.svg"
        echo '9.0.107' > "$TMPROOT/gittree/core/bin/test/basePrecmp"
        echo 'from("apps/jetty/apache-tomcat-9.0.107/NOTICE")' > "$TMPROOT/gittree/tools/installer.gradle"
        echo 'assert_eq 9.0.107 9.0.107 "a current pin reports up to date"' > "$TMPROOT/gittree/tools/scripts/update-tomcat.sh"
        git -C "$TMPROOT/gittree" init -q .
        _strays=$(find_strays "$TMPROOT/gittree" 9.0.107)
        assert_eq 1 "$(printf '%s\n' "$_strays" | grep -c 'tools/installer.gradle')" "an untracked packaging file in a checkout is still reported"
        assert_eq 0 "$(printf '%s\n' "$_strays" | grep -c 'node_modules')" "the ignored node_modules tree is not reported"
        assert_eq 0 "$(printf '%s\n' "$_strays" | grep -c 'core/bin')" "ignored build output is not reported"
        assert_eq 0 "$(printf '%s\n' "$_strays" | grep -c 'update-tomcat.sh')" "this script's own fixtures are not reported"
    fi

    echo "-- commit --"
    assert_eq 'build: update Jasper/Tomcat to 9.0.124' "$(commit_subject 9.0.124)" \
        "the subject is one line naming the pair and the release"
    _paths=$(commit_paths 9.0.124 '9.0.124' '9.0.107
9.0.124')
    assert_eq 5 "$(printf '%s\n' "$_paths" | grep -c .)" \
        "the new directory, the stale one, and the three rewritten files"
    assert_eq 1 "$(printf '%s\n' "$_paths" | grep -c 'apps/jetty/apache-tomcat-9.0.107')" \
        "the tracked deletion of the old version belongs to the same commit"

    if command -v git >/dev/null 2>&1
    then
        # A checkout of its own, so the commit test cannot touch the real tree.
        _mkver() {
            mkdir -p "$JETTY_DIR/apache-tomcat-$1"
            jar_allowlist | while read -r _p _a
            do
                mkdir -p "$JETTY_DIR/apache-tomcat-$1/$(dirname "$_p")"
                printf '%s\n' "$_a-$2" > "$JETTY_DIR/apache-tomcat-$1/$_p"
            done
            printf 'Copyright 1999-%s The Apache Software Foundation\n' "$3" \
                > "$JETTY_DIR/apache-tomcat-$1/NOTICE"
        }
        _save_ROOT=$REPO_ROOT _save_BUILD=$BUILD_XML _save_GRADLE=$GRADLE_PROPS
        _save_LIC=$LICENSES_MD _save_JETTY=$JETTY_DIR
        REPO_ROOT=$TMPROOT/commitrepo
        BUILD_XML=$REPO_ROOT/build.xml
        GRADLE_PROPS=$REPO_ROOT/gradle.properties
        LICENSES_MD=$REPO_ROOT/docs/LICENSES.md
        JETTY_DIR=$REPO_ROOT/apps/jetty
        mkdir -p "$REPO_ROOT/docs"
        printf '*.[rwjt]ar\n' > "$REPO_ROOT/.gitignore"
        printf '  <property name="tomcat.ver" value="9.0.107"/>\n' > "$BUILD_XML"
        printf 'tomcatVersion = 9.0.107\n' > "$GRADLE_PROPS"
        printf 'Apache Tomcat. Copyright 1999-2025 The Apache Software Foundation.\n' > "$LICENSES_MD"
        printf 'unrelated\n' > "$REPO_ROOT/unrelated.txt"
        _mkver 9.0.107 107 2025
        git -C "$REPO_ROOT" init -q .
        git -C "$REPO_ROOT" config user.email selftest@i2p.invalid
        git -C "$REPO_ROOT" config user.name selftest
        git -C "$REPO_ROOT" config commit.gpgsign false
        git -C "$REPO_ROOT" add -A -f .
        git -C "$REPO_ROOT" commit -qm base

        # The update, left exactly as apply leaves it: new directory staged, old
        # deletion staged, Gradle pin and license year moved, Ant pin not yet.
        _mkver 9.0.124 124 2026
        rm -rf "$JETTY_DIR/apache-tomcat-9.0.107"
        rewrite_gradle_pin "$GRADLE_PROPS" 9.0.124
        sync_licenses_year "$LICENSES_MD" 2026
        printf 'dirty\n' >> "$REPO_ROOT/unrelated.txt"
        git -C "$REPO_ROOT" add -A -f -- apps/jetty/apache-tomcat-9.0.124 apps/jetty/apache-tomcat-9.0.107
        _rc=0
        ( cmd_commit 9.0.124 ) >/dev/null 2>&1 || _rc=$?
        assert_eq 1 "$_rc" "a directory that disagrees with the Ant pin is not committed"
        rewrite_pin "$BUILD_XML" 9.0.124
        # What the commit will hold, read the way cmd_commit reads it.
        _cpaths=$(changed_paths 9.0.124 "$(read_dir_version "$JETTY_DIR")" "$(head_dir_versions)")
        assert_eq 1 "$(printf '%s\n' "$_cpaths" | grep -c '^build.xml')" \
            "the Ant pin the update moved is ours to commit"
        assert_eq 1 "$(printf '%s\n' "$_cpaths" | grep -c '^gradle.properties')" \
            "and so is the Gradle pin"
        assert_eq 1 "$(printf '%s\n' "$_cpaths" | grep -c '^docs/LICENSES.md')" \
            "and the copyright year the NOTICE moved"
        # check --commit --dry-run: the same commit, reported rather than made.
        _out=$( OFFLINE=1 COMMIT=1 DRY_RUN=1 cmd_check )
        case "$_out" in
            *"Proposed commit message:"*"build: update Jasper/Tomcat to 9.0.124"*)
                pass "check --commit prints the message it would use" ;;
            *) fail "check --commit prints the message it would use" "got [$_out]" ;;
        esac
        case "$_out" in
            *"Dry run - nothing committed"*) pass "and says it committed nothing" ;;
            *) fail "and says it committed nothing" "got [$_out]" ;;
        esac
        assert_eq base "$(git -C "$REPO_ROOT" log -1 --pretty=%s)" "a dry run leaves HEAD alone"
        cmd_commit 9.0.124 >/dev/null
        assert_eq 'build: update Jasper/Tomcat to 9.0.124' \
            "$(git -C "$REPO_ROOT" log -1 --pretty=%s)" "one line naming the release"
        assert_eq 10 "$(git -C "$REPO_ROOT" ls-tree -r --name-only HEAD | grep -c 'apache-tomcat-9.0.124/')" \
            "every shipped file of the new version is committed"
        assert_eq 0 "$(git -C "$REPO_ROOT" ls-tree -r --name-only HEAD | grep -c 'apache-tomcat-9.0.107/')" \
            "and the old version is gone from the tree"
        assert_eq 0 "$(git -C "$REPO_ROOT" show --name-only --pretty=format: HEAD | grep -c unrelated.txt)" \
            "the unrelated dirty file is not swept into the commit"
        assert_eq 1 "$(git -C "$REPO_ROOT" status --porcelain | grep -c unrelated.txt)" \
            "and it is still uncommitted afterwards"
        # Now the license file carries an edit nobody here made: HEAD already
        # holds the year the shipped NOTICE holds, so the file is not ours.
        printf 'Some Other Library. Copyright 2024 Someone Else.\n' >> "$LICENSES_MD"
        _cpaths=$(changed_paths 9.0.124 "$(read_dir_version "$JETTY_DIR")" "$(head_dir_versions)")
        assert_eq 0 "$(printf '%s\n' "$_cpaths" | grep -c 'docs/LICENSES.md')" \
            "a license edit the update did not make is not ours to commit"
        _out=$(cmd_commit 9.0.124)
        case "$_out" in
            *"not committing docs/LICENSES.md"*) pass "the excluded file is named, not left unexplained" ;;
            *) fail "the excluded file is named, not left unexplained" "got [$_out]" ;;
        esac
        case "$_out" in
            *"already committed"*) pass "a second run finds nothing left to do" ;;
            *) fail "a second run finds nothing left to do" "got [$_out]" ;;
        esac
        assert_eq 1 "$(git -C "$REPO_ROOT" status --porcelain | grep -c 'docs/LICENSES.md')" \
            "and that edit is still sitting in the workspace afterwards"
        REPO_ROOT=$_save_ROOT BUILD_XML=$_save_BUILD GRADLE_PROPS=$_save_GRADLE
        LICENSES_MD=$_save_LIC JETTY_DIR=$_save_JETTY
    fi

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

    echo "-- check status line --"
    assert_eq "up to date" "$(check_status_line 9.0.123 9.0.123)" "a current pin reports up to date"
    assert_eq "up to date" "$(check_status_line 9.0.123 9.0.107)" "a pin ahead of the published list is not behind"
    assert_eq "UPDATE AVAILABLE: 16 patch releases newer" "$(check_status_line 9.0.107 9.0.123)" "a pin behind the newest release reports the gap"
    assert_eq "not checked - run without --offline to consult Maven Central" "$(check_status_line 9.0.123 '')" "a check that never saw a release list does not claim up to date"

    echo "-- check-version line --"
    _line=$(check_version_line 9.0.107 9.0.123)
    _rc=$?
    assert_eq 3 "$_rc" "a pin behind the newest release exits 3"
    case $_line in
        *"9.0.123 available"*) pass "the line names the release to apply" ;;
        *) fail "the line names the release to apply" "got [$_line]" ;;
    esac
    case $_line in
        *"16 patch releases behind"*) pass "the line says how far behind we are" ;;
        *) fail "the line says how far behind we are" "got [$_line]" ;;
    esac
    _line=$(check_version_line 9.0.107 9.0.107)
    _rc=$?
    assert_eq 0 "$_rc" "a current pin exits 0"
    case $_line in
        *"up to date"*) pass "a current pin says up to date" ;;
        *) fail "a current pin says up to date" "got [$_line]" ;;
    esac
    _line=$(check_version_line 9.0.123 9.0.107)
    _rc=$?
    assert_eq 0 "$_rc" "a pin ahead of the published list is not an error"
    _line=$(check_version_line 9.0.107 '')
    _rc=$?
    assert_eq 4 "$_rc" "an unknown newest release exits 4, not a clean 0"
    case $_line in
        *"not checked"*) pass "an unknown newest release says not checked" ;;
        *) fail "an unknown newest release says not checked" "got [$_line]" ;;
    esac

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
    # The whole header comment: from the line after the shebang to the first
    # blank line, which is the end of the comment block. A fixed line range goes
    # stale the first time the usage list grows.
    sed -n '3,/^$/p' "$0" | sed 's/^# \{0,1\}//'
}

DRY_RUN=0
OFFLINE=0
COMMIT=0
COMMAND=check
TARGET=''
JSON=''

while [ $# -gt 0 ]
do
    case "$1" in
        --dry-run) DRY_RUN=1; shift ;;
        --json) JSON=1; shift ;;
        --offline) OFFLINE=1; shift ;;
        --commit) COMMIT=1; shift ;;
        -h|--help) usage; exit 0 ;;
        check|verify|advisories|apply|commit|list|selftest) COMMAND=$1; shift ;;
        check-version|--check-version) COMMAND=check-version; shift ;;
        -*) die "unknown option $1 (try --help)" ;;
        *) TARGET=$1; shift ;;
    esac
done

case "$COMMAND" in
    check) cmd_check ;;
    check-version) cmd_check_version ;;
    verify) cmd_verify ;;
    advisories) cmd_advisories "$TARGET" ;;
    apply) cmd_apply "$TARGET" ;;
    commit) cmd_commit "$TARGET" ;;
    list) cmd_list ;;
    selftest) cmd_selftest ;;
esac
