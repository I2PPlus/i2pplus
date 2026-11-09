#!/usr/bin/env python3
"""
checkjsps.py - build-time JSP validation, one entry point.

Wired into the ant and gradle builds of the three webapps that ship JSPs
(susidns, routerconsole, i2ptunnel) alongside uglifyjs and htmlmin: it is
skipped when the environment cannot run it, and it fails the build when it
finds a problem.

Two phases, because the two kinds of failure are different:

  audit     the default, and all the builds need.  Runs jsp-audit.py
            (correctness: bean properties and ids, EL roots, includes, message
            keys, encoding) and jsp-security-audit.py (request data reaching the
            response, CSRF checks that fail open, unescaped output inside script
            tags).  Both report every finding they see on every run, so the
            phase fails on any finding.  This is the only check that catches a
            bean property a page asks for but the bean does not have: Jasper
            emits a reflective introspecthelper() or proprietaryEvaluate() call
            for those, which compiles clean and fails at runtime with an HTTP
            500.

  compile   JspC generate-only, then javac of the generated sources: JSP syntax
            errors, an EL that does not parse, a <jsp:useBean> class that is not
            on the classpath, Java type errors in scriptlets.  Opt in with
            --compile.  The ant and gradle builds already run JspC themselves
            (precompilejsp / precompileJsp) with failonerror set, so they do not
            need this phase; it is here so a manual run can do both at once.

Usage:
  checkjsps.py --app NAME [--webapp DIR] [--package JSP_PKG]
               [--classes PATH]... [--classpath PATH]...
               [--tomcat DIR] [--out DIR] [--compile] [--skip-audit]

--classes and --classpath add compiled application classes and libraries for the
generated servlets to compile against.  Without them the phase uses what is
already built (dist/lib and the ant build root) plus the app's own bundled jars,
and skips when nothing has been built yet.  The Jasper and servlet-api jars are
found under apps/jetty and added automatically.
Each audit prints its own result in the house '* <info>' form used by the other
build checkers: one line per pass, and a third line naming the command that shows
the detail when something was found.  Nothing else is printed, so the build log
stays compact under the concise logger.
Exit status: 0 if nothing failed (including when a phase was skipped), 1 otherwise.
"""

import argparse
import glob
import importlib.util
import os
import subprocess
import sys
import tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(os.path.dirname(HERE))

# The correctness checker, reused here for where compiled classes live
_spec = importlib.util.spec_from_file_location('jsp_audit', os.path.join(HERE, 'jsp-audit.py'))
ja = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(ja)

JASPER_JARS = ('jasper.jar', 'jasper-el.jar', 'el-api.jar', 'jsp-api.jar',
               'servlet-api.jar', 'tomcat-util.jar', 'tomcat-api.jar',
               'tomcat-util-scan.jar')

# Where each app keeps the JSPs it ships, relative to the repo root
APP_DIRS = {'susidns': 'susidns/src/jsp',
            'routerconsole': 'routerconsole/jsp',
            'i2ptunnel': 'i2ptunnel/jsp'}

# Jars an app ships alongside its own sources, needed to compile its pages
APP_LIB_DIRS = {'susidns': ('susidns/src/lib',)}


def find_tomcat(explicit):
    """A Tomcat install under apps/jetty, or None."""
    cands = []
    if explicit:
        cands.append(explicit)
    cands.extend(sorted(glob.glob(os.path.join(ROOT, 'apps', 'jetty', 'apache-tomcat-*'))))
    for c in cands:
        jars = [os.path.join(c, 'lib', j) for j in JASPER_JARS]
        juli = os.path.join(c, 'bin', 'tomcat-juli.jar')
        if all(os.path.isfile(j) for j in jars) and os.path.isfile(juli):
            return c, jars + [juli]
    return None, []


def run(cmd, **kw):
    sys.stdout.flush()
    return subprocess.run(cmd, **kw)


def tool(name):
    """java or javac, honouring JAVA_HOME only when that install really exists."""
    home = os.environ.get('JAVA_HOME')
    if home:
        p = os.path.join(home, 'bin', name)
        if os.path.exists(p):
            return p
    return name


def compile_phase(args, jspc_pkg):
    """JspC generate-only plus javac of the generated sources."""
    tomcat, jasper_cp = find_tomcat(args.tomcat)
    if not tomcat:
        print('SKIP compile: no Tomcat jasper jars under apps/jetty '
              '(pass --tomcat, or unpack Tomcat into apps/jetty)')
        return 0
    cp = app_cp(args)
    if not cp:
        print('SKIP compile: nothing compiled to check against yet '
              '(build the app, or pass --classes)')
        return 0
    java, javac = tool('java'), tool('javac')
    if run([java, '-version'], stderr=subprocess.DEVNULL,
           stdout=subprocess.DEVNULL).returncode != 0:
        print('SKIP compile: no usable JDK (java not found)')
        return 0

    webapp = os.path.abspath(args.webapp)
    out = args.out or tempfile.mkdtemp(prefix='checkjsps-')
    gen = os.path.join(out, 'gen')
    classes = os.path.join(out, 'classes')
    for d in (gen, classes):
        os.makedirs(d, exist_ok=True)

    java = tool('java')
    javac = tool('javac')

    # JspC needs the app's own classes on its classpath to validate useBean
    # classes, and ant's own classes for its writer helpers.
    jspc_cp = [java_cp(args)] + jasper_cp + ant_jars()
    rc = run([java, '-cp', os.pathsep.join(jspc_cp), 'org.apache.jasper.JspC',
              '-die', '-l', '-v', '-webapp', webapp, '-d', gen,
              '-threadCount', '1'] + (['-p', jspc_pkg] if jspc_pkg else []),
             stdout=sys.stderr).returncode
    if rc != 0:
        sys.stderr.write('JspC generation failed for %s\n' % webapp)
        return 1

    sources = sorted(glob.glob(os.path.join(gen, '**', '*.java'), recursive=True))
    if not sources:
        sys.stderr.write('JspC produced no sources for %s\n' % webapp)
        return 1
    argsfile = os.path.join(out, 'javac.args')
    with open(argsfile, 'w', encoding='utf-8') as f:
        f.write('-nowarn\n-encoding\nUTF-8\n-d\n%s\n-cp\n%s\n' %
                (classes, os.pathsep.join([java_cp(args)] + jasper_cp)))
        for s in sources:
            f.write(s + '\n')
    rc = run([javac, '@' + argsfile], stdout=sys.stderr).returncode
    if rc != 0:
        sys.stderr.write('javac of the generated JSP sources failed for %s\n' % webapp)
        return 1
    print('compile: %d JSP sources compiled for %s' % (len(sources), args.app))
    return 0


def ant_jars():
    """ant's own jars, which JspC's writers reference."""
    cands = sorted(glob.glob('/usr/share/java/ant.jar')) + \
        sorted(glob.glob('/usr/share/java/ant-*.jar')) + \
        sorted(glob.glob(os.path.join(ROOT, 'tools', 'ant-*.jar')))
    return [c for c in cands if os.path.isfile(c)]


def app_cp(args):
    """
    What the generated servlets compile against: what the caller named, then the
    app's own bundled jars, then whatever the tree has already compiled, so a
    plain --compile run needs no flags.
    """
    parts = list(args.classes or []) + list(args.classpath or [])
    for lib in APP_LIB_DIRS.get(args.app, ()):
        parts.extend(sorted(glob.glob(os.path.join(ROOT, 'apps', lib, '*.jar'))))
    parts.extend(ja.build_dirs())
    return [os.path.abspath(p) for p in parts if os.path.exists(p)]


def java_cp(args):
    return os.pathsep.join(app_cp(args))


def audit_phase(args):
    """The two static audits.  Each one reports every finding it sees."""
    rc = 0
    for script in ('jsp-audit.py', 'jsp-security-audit.py'):
        path = os.path.join(HERE, script)
        if not os.path.isfile(path):
            sys.stderr.write('missing %s\n' % script)
            rc = 1
            continue
        r = run([sys.executable, path, '--app', args.app])
        # each audit already says what it found, in the house '* ' form
        rc |= r.returncode
    return rc


def main():
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument('--app', required=True, choices=('susidns', 'routerconsole', 'i2ptunnel'))
    ap.add_argument('--webapp', help='directory of JSPs (default: the app''s own jsp dir)')
    ap.add_argument('--package', help='package for the generated servlet sources')
    ap.add_argument('--classes', action='append', help='compiled app classes dir or jar')
    ap.add_argument('--classpath', action='append', help='jar or classes the JSPs compile against')
    ap.add_argument('--tomcat', help='Tomcat install to take jasper from (default: apps/jetty/*)')
    ap.add_argument('--out', help='where to write generated sources (default: a temp dir)')
    ap.add_argument('--compile', action='store_true',
                    help='also run JspC and javac (the builds already do this themselves)')
    ap.add_argument('--skip-audit', action='store_true', help='compile phase only')
    args = ap.parse_args()

    if not args.webapp:
        args.webapp = os.path.join(ROOT, 'apps', APP_DIRS[args.app])

    rc = 0
    if args.compile:
        rc |= compile_phase(args, args.package)
    if not args.skip_audit:
        rc |= audit_phase(args)
    return rc


if __name__ == '__main__':
    sys.exit(main())
