#!/usr/bin/env python3
"""check-build-files.py - check the ant and Gradle build files for defects.

The build files are 26 ant build.xml and 26 Gradle scripts that grew by accretion:
a target renamed in one file and left in another, a property set twice so the
second setting is dead, a jar copied from a directory that no longer exists, an
<ant> call into a target the other file does not define. Ant reports none of it -
a missing property expands to the literal "${name}", a call into a target that
exists only in the callee's imagination fails only when that path runs, and a
duplicate property is silently ignored after the first.

What the rules check:

  * XML that does not parse, and Gradle blocks left open.
  * A target or property defined twice in one file, a property set to itself, a
    target with no body, and a target nothing invokes, depends on, or prints in
    the help menu.
  * A depends, antcall or ant-contrib loop naming a target this file lacks, an
    <ant dir=...> naming one the other file lacks, and a project default naming one
    this file lacks, which is the first bare `ant` in that directory to fail.
  * A ${property} no build file, property file, Debian rule or -D switch defines.
  * A path the build reads that is not in the tree, not build output, not created
    by an <mkdir>, and not declared optional by the element that names it.
  * The build root, which is one directory: ${build.root} in ant and
    ${java.io.tmpdir}/build-i2p in Gradle. A file that reads it without setting
    it, a second root, a path spelled out of the tmpdir or hardcoded to /tmp, and
    a relative build path writing output into the workspace. dist/ is exempt -
    the final HTML reports belong there.
  * The same jar twice on one classpath, the same pattern twice in one fileset,
    the same javadoc <link> twice, and build XML left inside a comment.
  * Gradle: a dependency declared twice, the same include or exclude twice in one
    copy block, a task registered twice, and a build path hardcoded to /tmp.

Every rule runs a self-probe before the tree is scanned: it must flag its planted
defect and pass its clean fixture, and a rule that matches nothing exits 3 rather
than certifying a clean tree. Every run is a fresh pass over the current files:
there is no baseline, and any finding at all is a failure. Findings are reported
as file:line, and the default output is the per-rule tally the build log wants,
with --verbose listing each one.

Usage:
    tools/scripts/check-build-files.py                      # tally, exit 1 on any finding
    tools/scripts/check-build-files.py --verbose            # list every finding
    tools/scripts/check-build-files.py --json               # machine-readable, with locations
    tools/scripts/check-build-files.py --rule dup-target    # one rule only
    tools/scripts/check-build-files.py --list               # the rules and what they check
    tools/scripts/check-build-files.py --selftest           # prove every rule still checks
    tools/scripts/check-build-files.py --ant-report FILE    # counts, for the build
    tools/scripts/check-build-files.py build.xml apps/jetty/build.xml
                                                            # these files, tree as context

Exit codes: 0 clean, 1 findings, 3 tool failure.
"""

import argparse
import json
import os
import re
import subprocess
import sys
import textwrap
import xml.parsers.expat

CLEAN = 0
FINDINGS = 1
TOOL_FAILURE = 3


#---------------------------------------------------------------------------
# parsed document
#---------------------------------------------------------------------------

class Node:
    """One element of a build file, with the line it opened on."""

    __slots__ = ('tag', 'attrs', 'line', 'children', 'parent')

    def __init__(self, tag, attrs, line, parent):
        self.tag = tag
        self.attrs = attrs
        self.line = line
        self.children = []
        self.parent = parent

    def iter(self):
        """This node and every node below it."""
        stack = [self]
        while stack:
            node = stack.pop()
            yield node
            stack.extend(reversed(node.children))

    def findall(self, tag):
        """Direct children with this tag (Ant's own nesting semantics)."""
        return [n for n in self.children if n.tag == tag]

    def descendants(self, tag):
        """Every node below this one with this tag, at any depth."""
        return [n for n in self.iter() if n.tag == tag]


class Comment:
    """One XML comment, kept so the commented-out-XML rule can see it."""

    __slots__ = ('text', 'line')

    def __init__(self, text, line):
        self.text = text
        self.line = line


class Doc:
    """A build file: parsed tree plus comments, or raw text for Gradle."""

    def __init__(self, path, text):
        self.path = path
        self.text = text
        self.root = None
        self.comments = []
        self.error = None
        if path.endswith('.xml'):
            self._parse_xml(text)

    def _parse_xml(self, text):
        parser = xml.parsers.expat.ParserCreate()
        stack = []
        line = [0]

        def start(tag, attrs):
            parent = stack[-1] if stack else None
            node = Node(tag, attrs, line[0], parent)
            if parent is None:
                self.root = node
            else:
                parent.children.append(node)
            stack.append(node)

        def end(_tag):
            if stack:
                stack.pop()

        def comment(text):
            self.comments.append(Comment(text, line[0]))

        def char_data(_data):
            pass

        parser.StartElementHandler = start
        parser.EndElementHandler = end
        parser.CommentHandler = comment
        parser.CharacterDataHandler = char_data

        try:
            # The line number is only readable off the parser object, so wrap the
            # handlers to stamp it in before each one runs. expat reports the line
            # the token starts on, which is the line a reader looks for.
            def start_wrap(tag, attrs):
                line[0] = parser.CurrentLineNumber
                start(tag, attrs)

            def comment_wrap(text):
                line[0] = parser.CurrentLineNumber
                comment(text)

            parser.StartElementHandler = start_wrap
            parser.CommentHandler = comment_wrap
            parser.Parse(text, True)
        except xml.parsers.expat.ExpatError as exc:
            self.error = f'{exc} (line {getattr(exc, "lineno", "?")})'

    def targets(self):
        return [n for n in (self.root.findall('target') if self.root else [])]

    def project(self):
        return self.root if self.root and self.root.tag == 'project' else None


class Corpus:
    """Every build file in the tree, parsed once, plus the facts rules share."""

    def __init__(self, docs, texts, entry_points, defined_props, ignored, root=None):
        self.docs = docs
        self.texts = texts
        self.entry_points = entry_points
        self.defined_props = defined_props
        self.ignored = ignored
        # None for the in-memory fixtures, which have no git index to ask
        self.root = root
        self._mentions = {}
        # Tree-wide facts a rule cannot judge from one file alone: the element
        # names the build actually uses, the targets another file invokes, and
        # the names the help menus print for a human typing `ant <name>`.
        self.task_names = set()
        self.invoked_targets = set()
        self.documented = set()
        self.generated = set()
        for doc in docs:
            if doc.root is None:
                continue
            base = os.path.dirname(doc.path)
            for node in doc.root.iter():
                self.task_names.add(node.tag)
                if node.tag in TARGET_CALL_TAGS + ('ant', 'subant') and node.attrs.get('target'):
                    self.invoked_targets.add(node.attrs['target'])
                if node.tag == 'target' and node.attrs.get('depends'):
                    # a depends= is also a caller: an empty target named only by
                    # one is a no-op hook left there on purpose
                    self.invoked_targets.update(split_list(node.attrs['depends']))
                if node.tag in ('ant', 'subant'):
                    for child in node.children:
                        if child.tag == 'target' and child.attrs.get('name'):
                            self.invoked_targets.add(child.attrs['name'])
                elif node.tag == 'echo':
                    for value in node.attrs.values():
                        for line in value.split('\n'):
                            token = line.strip().split(' ')[0]
                            if token:
                                self.documented.add(token)
                elif node.tag == 'mkdir' and node.attrs.get('dir'):
                    value = node.attrs['dir']
                    if '${' not in value and not value.startswith('/'):
                        rel = os.path.normpath(os.path.join(base, value))
                        self.generated.add(rel.replace(os.sep, '/'))

    def by_path(self, path):
        for doc in self.docs:
            if doc.path == path:
                return doc
        return None

    def mentions(self, name):
        """Tracked lines naming this word, from git grep, cached per name.

        A target that no build file invokes is still a documented entry point if
        a help menu, a doc page, a CI job or a comment names it, so the mention
        index is the whole tree, not just the build files.
        """
        if self.root is None:
            # an in-memory fixture has no git index to ask, but it may still carry
            # a planted mention (see fixture_corpus)
            return self._mentions.get(name, [])
        if name not in self._mentions:
            out = subprocess.run(['git', 'grep', '-n', '-w', '--', name],
                                 cwd=self.root, capture_output=True, text=True)
            self._mentions[name] = out.stdout.splitlines()
        return self._mentions[name]


def tracked_files(root):
    """Every tracked path in the repository, repo-relative."""
    out = subprocess.run(['git', 'ls-files', '-z'], cwd=root,
                         capture_output=True, text=True, check=True).stdout
    return sorted(f for f in out.split('\0') if f)


def is_build_file(path):
    """An ant build file or a Gradle script."""
    return (path.endswith('.gradle') or path.endswith('build.xml') or
            (path.endswith('.xml') and os.path.basename(path) in
             ('build.gradle', 'build.xml')))


def tracked_build_files(root):
    """Every tracked ant or Gradle build file, repo-relative."""
    return [f for f in tracked_files(root) if is_build_file(f)]


def load_texts(root, paths):
    """File contents for the files that are context, not a check target."""
    texts = {}
    for path in paths:
        try:
            with open(os.path.join(root, path), encoding='utf-8', errors='replace') as fh:
                texts[path] = fh.read()
        except OSError:
            continue
    return texts


def load_corpus(root, files):
    docs = []
    texts = {}
    for path in files:
        try:
            with open(os.path.join(root, path), encoding='utf-8', errors='replace') as fh:
                text = fh.read()
        except OSError:
            continue
        texts[path] = text
        docs.append(Doc(path, text))
    return docs, texts


PROP_USE = re.compile(r'\$\{([^{}]*)\}')

# Ant's own properties, plus the ones the JDK and Ant set for every project.
ANT_BUILTIN_PROPS = {
    'ant.file', 'ant.file.i2p', 'ant.home', 'ant.java.version', 'ant.lib.dir',
    'ant.project.default-target', 'ant.project.invoked-targets',
    'ant.project.name', 'ant.version', 'ant.java', 'basedir', 'build.dir',
    'build.root', 'java.class.path', 'java.home', 'java.io.tmpdir',
    'java.runtime.name', 'java.runtime.version', 'java.specification.version',
    'java.vendor', 'java.version', 'java.vm.name', 'os.arch', 'os.name',
    'path.separator', 'sun.boot.library.path', 'user.dir', 'user.home',
    'user.name', 'ant.core.lib', 'ant.file.i2p.test',
}

# Names the tree passes in from outside a build file.
EXTERNAL_PROPS = {'javac.version', 'javac.release', 'javadoc.version',
                  'with-debug', 'with-javadoc', 'with-tests', 'with-libjetty9-java',
                  'with-libtomcat6-java', 'with-libtomcat7-java',
                  'with-libtomcat8-java', 'with-libtomcat9-java',
                  'with-libservlet2.5-java', 'have-git', 'have-gpg',
                  'build.version', 'build.relname', 'build.unixname'}


def collect_defined_props(root, docs, texts):
    """Property names defined anywhere in the build files or the property files."""
    defined = set(ANT_BUILTIN_PROPS) | set(EXTERNAL_PROPS)
    for doc in docs:
        if doc.root is None:
            continue
        for node in doc.root.iter():
            # property=, outputproperty= and friends on any task, plus the
            # name= of a <property> or an ant-contrib <var>
            for attr in PROP_DEFINE_ATTRS:
                value = node.attrs.get(attr)
                if value:
                    defined.add(value)
            if node.tag in PROP_DEFINE_NAME_TAGS and node.attrs.get('name'):
                defined.add(node.attrs['name'])
            if node.tag in ('taskdef', 'typedef') and node.attrs.get('uri'):
                defined.add(node.attrs['uri'])
    for path, text in texts.items():
        if path.endswith('.gradle'):
            for m in re.finditer(r'\b(?:ext|project)\.(["\']?)([\w.]+)\1?\s*=', text):
                defined.add(m.group(2))
            for m in re.finditer(r'^\s*([\w.]+)\s*=\s*[\'"]', text, re.M):
                defined.add(m.group(1))
    defined.update(collect_property_keys(root, docs))
    defined.update(collect_outside_props(root, texts))
    return defined


ANT_INVOKE = re.compile(r'\bant\s+(?:-[^ ]+\s+)*([A-Za-z][\w.-]*)')


def collect_entry_points(root, texts):
    """Targets run from outside the build files: CI, makefiles, scripts, docs."""
    entry = set()
    for path, text in texts.items():
        if not path.endswith(('.yml', '.yaml', '.sh', '.mk', '.bat', '.gradle', '.md')):
            continue
        for m in ANT_INVOKE.finditer(text):
            entry.add(m.group(1))
        for m in re.finditer(r':[\w:]*:(build|clean|test|jar|war|check)\b', text):
            entry.add(m.group(1))
    return entry


def git_ignored(root, paths):
    """Which of paths are git-ignored, i.e. build output rather than source."""
    if not paths:
        return set()
    proc = subprocess.run(['git', 'check-ignore', '-z', '--stdin'], cwd=root,
                          input='\0'.join(paths), capture_output=True, text=True)
    if proc.returncode not in (0, 1):
        return set()
    return {p for p in proc.stdout.split('\0') if p}


#---------------------------------------------------------------------------
# findings
#---------------------------------------------------------------------------

class Finding:
    __slots__ = ('rule', 'path', 'line', 'detail')

    def __init__(self, rule, path, line, detail):
        self.rule = rule
        self.path = path
        self.line = line
        self.detail = detail

    def __str__(self):
        return f'{self.path}:{self.line}: [{self.rule}] {self.detail}'


class Rule:
    """One check, plus the fixture pair that proves it still checks."""

    def __init__(self, name, summary, run, bad, ok):
        self.name = name
        self.summary = summary
        self.run = run
        self.bad = bad
        self.ok = ok


def fixture_corpus(files):
    """A Corpus over an in-memory set of files, for the self-probes.

    A 'mentions' key maps a target name to the git grep hit lines that name it,
    standing in for the tree-wide mention index a fixture has no git checkout to
    build. Rules that treat a documented target as used need it to test that.
    """
    files = dict(files)
    mentions = files.pop('mentions', {})
    docs, texts = load_corpus_from_dict(files)
    props = collect_defined_props(None, docs, texts)
    entry = collect_entry_points('.', texts)
    corpus = Corpus(docs, texts, entry, props, set())
    corpus._mentions = dict(mentions)
    return corpus


def load_corpus_from_dict(files):
    docs = []
    texts = {}
    for path, text in files.items():
        texts[path] = text
        docs.append(Doc(path, text))
    return docs, texts


def xml_project(targets_xml, props='', extra='', default=''):
    default_attr = f' default="{default}"' if default else ''
    return ('<?xml version="1.0" encoding="UTF-8"?>\n'
            f'<project basedir="."{default_attr} name="t">\n'
            f'{props}{extra}{targets_xml}</project>\n')


#---------------------------------------------------------------------------
# rules - ant build files
#---------------------------------------------------------------------------

def rule_xml(doc, corpus):
    if doc.error:
        yield Finding('xml', doc.path, 0, doc.error)


def rule_dup_target(doc, corpus):
    seen = {}
    for node in doc.targets():
        name = node.attrs.get('name')
        if not name:
            continue
        if name in seen:
            yield Finding('dup-target', doc.path, node.line,
                          f'target "{name}" is also defined at line {seen[name]}')
        else:
            seen[name] = node.line


def rule_dup_property(doc, corpus):
    seen = {}
    for node in doc.project().findall('property') if doc.project() else []:
        name = node.attrs.get('name')
        if not name:
            continue
        if name in seen:
            old_value, old_location, old_line = seen[name]
            same = (node.attrs.get('value') == old_value and
                    node.attrs.get('location') == old_location)
            yield Finding('dup-property', doc.path, node.line,
                          f'property "{name}" is set again at line {old_line} '
                          f'({"same value, the second is dead" if same else "the first wins, this one is dead"})')
        else:
            seen[name] = (node.attrs.get('value'), node.attrs.get('location'), node.line)


def rule_self_property(doc, corpus):
    for node in doc.project().findall('property') if doc.project() else []:
        name = node.attrs.get('name')
        if name and node.attrs.get('value') == '${' + name + '}':
            yield Finding('self-property', doc.path, node.line,
                          f'property "{name}" is set to ${{{name}}}, a no-op')


# Tasks whose target= attribute names an Ant target to run. <javac target="1.8">
# is a bytecode level, not a target, so the list is explicit.
TARGET_CALL_TAGS = ('antcall', 'foreach', 'apply')


def rule_missing_target(doc, corpus):
    """A depends, antcall or ant-contrib loop names a target this file lacks."""
    if doc.root is None:
        return
    names = {n.attrs.get('name') for n in doc.targets() if n.attrs.get('name')}
    if doc.project() is not None:
        default = doc.project().attrs.get('default')
        if default:
            names.add(default)
    for node in doc.root.iter():
        if node.tag == 'target' and node.attrs.get('depends'):
            for dep in split_list(node.attrs['depends']):
                if dep not in names:
                    yield Finding('missing-target', doc.path, node.line,
                                  f'target "{node.attrs.get("name")}" depends on '
                                  f'"{dep}", which this file does not define')
        elif node.tag in TARGET_CALL_TAGS and node.attrs.get('target'):
            # these run in this project, so the target has to be defined here;
            # <ant> and <subant> are checked against the other file by
            # missing-ant-target
            if node.attrs['target'] not in names:
                yield Finding('missing-target', doc.path, node.line,
                              f'<{node.tag} target="{node.attrs["target"]}"> is not '
                              f'a target of this file')


def split_list(value):
    return [x.strip() for x in value.split(',') if x.strip()]


def defined_targets(doc):
    names = {n.attrs.get('name') for n in doc.targets() if n.attrs.get('name')}
    if doc.project() is not None and doc.project().attrs.get('default'):
        names.add(doc.project().attrs['default'])
    return names


def rule_missing_ant_target(doc, corpus):
    if doc.root is None:
        return
    for node in doc.root.iter():
        if node.tag not in ('ant', 'subant'):
            continue
        target = node.attrs.get('target')
        nested = [c.attrs.get('name') for c in node.children
                  if c.tag == 'target' and c.attrs.get('name')]
        if target:
            nested.append(target)
        if not nested:
            continue
        target_file = ant_file_for(node, doc)
        if target_file is None:
            continue
        callee = corpus.by_path(target_file)
        if callee is None or callee.root is None:
            yield Finding('missing-ant-target', doc.path, node.line,
                          f'invokes {target_file}, which is not a tracked build file')
            continue
        have = defined_targets(callee)
        for name in nested:
            if name not in have:
                yield Finding('missing-ant-target', doc.path, node.line,
                              f'invokes target "{name}" in {target_file}, which does not define it')


def ant_file_for(node, doc):
    """The build file an <ant dir=...> or <ant antfile=...> runs."""
    if node.attrs.get('antfile'):
        base = node.attrs.get('dir', '.')
        return os.path.normpath(os.path.join(os.path.dirname(doc.path), base,
                                             node.attrs['antfile'])).replace(os.sep, '/')
    if node.attrs.get('dir'):
        return os.path.normpath(os.path.join(os.path.dirname(doc.path),
                                             node.attrs['dir'], 'build.xml')).replace(os.sep, '/')
    return doc.path


# Attributes that define a property on any Ant task: property= and friends, plus
# ant-contrib's result= (<math>) and param= (<foreach>, <apply>).
PROP_DEFINE_ATTRS = ('property', 'addproperty', 'outputproperty', 'errorproperty',
                     'resultproperty', 'result', 'param')

# Elements whose name= attribute defines a property rather than naming the thing:
# <property name=>, ant-contrib <var name=> and <antcall><param name=>.
PROP_DEFINE_NAME_TAGS = ('property', 'var', 'param')

# Ant expands ${...} in an <arg value>, so a shell ${var} written there is left
# literal on purpose - the shell sets it, not Ant.
SHELL_ARG_ELEMENT = re.compile(r'<arg\b[^>]*/?>')

# Prefixes Ant resolves by itself, never out of a build file.
PROP_BUILTIN_PREFIXES = ('ant.', 'java.', 'javax.', 'os.', 'user.', 'path.',
                         'file.', 'line.', 'sun.', 'env.', 'toString:')


def strip_xml_comments(text):
    """Comment bodies blanked out, so a ${name} in prose is not a use."""
    out = []
    i = 0
    while True:
        start = text.find('<!--', i)
        if start < 0:
            out.append(text[i:])
            break
        end = text.find('-->', start + 4)
        if end < 0:
            out.append(text[i:])
            break
        out.append(text[i:start])
        out.append(''.join('\n' if ch == '\n' else ' ' for ch in text[start:end + 3]))
        i = end + 3
    return ''.join(out)


def collect_property_keys(root, docs):
    """Keys of every property file a build file loads, plus the tree's own two.

    A key left commented out in build.properties still names a switch the build
    accepts, so commented lines count too. root is None for the in-memory
    fixtures, which have no property files.
    """
    keys = set()
    names = set()
    for doc in docs:
        if doc.root is None:
            continue
        for node in doc.root.iter():
            if node.tag == 'property':
                for attr in ('file', 'location'):
                    if node.attrs.get(attr):
                        names.add(node.attrs[attr])
            elif node.tag in ('loadproperties', 'xmlproperty'):
                for attr in ('srcFile', 'file', 'location'):
                    if node.attrs.get(attr):
                        names.add(node.attrs[attr])
    names.update(('build.properties', 'override.properties'))
    for name in names:
        if root is None or '${' in name:
            continue
        path = os.path.normpath(os.path.join(root, name))
        try:
            with open(path, encoding='utf-8', errors='replace') as fh:
                text = fh.read()
        except OSError:
            continue
        for line in text.splitlines():
            m = re.match(r'\s*#?\s*([A-Za-z0-9_.-]+)\s*=', line)
            if m:
                keys.add(m.group(1))
    return keys


# Files that run the build from outside: the Debian rules write keys into
# override.properties, docker/ and the CI jobs pass -D switches.
OUTSIDE_BUILD_FILES = re.compile(r'^(distro/|docker/|\.github/|tools/scripts/)')
OUTSIDE_KEY = re.compile(r'(?:-D|\A\s*)([A-Za-z][A-Za-z0-9_.-]*)\s*=')


def collect_outside_props(root, texts):
    """Property names a build is run with from outside the build files."""
    names = set()
    for path in texts:
        if not OUTSIDE_BUILD_FILES.match(path):
            continue
        try:
            with open(os.path.join(root, path), encoding='utf-8', errors='replace') as fh:
                text = fh.read()
        except OSError:
            continue
        for line in text.splitlines():
            for m in re.finditer(r'-D([A-Za-z][A-Za-z0-9_.-]*)\s*=', line):
                names.add(m.group(1))
            if os.path.basename(path) == 'rules':
                m = re.match(r'\s*([A-Za-z][A-Za-z0-9_.-]*)\s*=', line)
                if m:
                    names.add(m.group(1))
    return names


def rule_missing_default(doc, corpus):
    """<project default=...> naming a target this file does not define.

    Running ant in that directory with no target resolves the default and fails
    with 'Target "all" does not exist' before anything runs, so the module cannot
    be built standalone even though the top-level build, which always names a
    target, never notices.
    """
    project = doc.project()
    if project is None:
        return
    name = project.attrs.get('default')
    if not name:
        return
    if any(t.attrs.get('name') == name for t in project.findall('target')):
        return
    yield Finding('missing-default', doc.path, project.line,
                  'default="%s" but this file has no such target - ant with no '
                  'target fails before it runs anything' % name)


def rule_undefined_property(doc, corpus):
    """A ${name} no build file, property file or Ant itself defines.

    Names are collected from every site Ant has them - property=, outputproperty=
    and friends on any task, result= and param= from ant-contrib, name= on
    <property>, <var> and <antcall>'s <param> - because a property set in one
    target is readable in every later one. Keys written by the Debian rules or
    passed as -D by a script count as defined, and a ${var} inside an <arg> is
    the shell's, not Ant's.
    """
    if doc.root is None:
        return
    local = set()
    for node in doc.root.iter():
        for attr in PROP_DEFINE_ATTRS:
            value = node.attrs.get(attr)
            if value:
                local.add(value)
        if node.tag in PROP_DEFINE_NAME_TAGS and node.attrs.get('name'):
            local.add(node.attrs['name'])
        if node.tag in ('taskdef', 'typedef') and node.attrs.get('uri'):
            local.add(node.attrs['uri'])
    scan = SHELL_ARG_ELEMENT.sub(lambda m: ''.join('\n' if c == '\n' else ' '
                                                   for c in m.group(0)),
                                 strip_xml_comments(doc.text))
    for m in PROP_USE.finditer(scan):
        name = m.group(1)
        if not re.fullmatch(r'[A-Za-z_][\w.-]*', name):
            continue
        if name.startswith(PROP_BUILTIN_PREFIXES):
            continue
        if name in corpus.defined_props or name in local:
            continue
        line = doc.text.count('\n', 0, m.start()) + 1
        yield Finding('undefined-property', doc.path, line,
                      f'${{{name}}} is not defined in any build or property file')


def rule_dup_pathelement(doc, corpus):
    if doc.root is None:
        return
    for node in doc.root.iter():
        if node.tag not in ('classpath', 'path', 'sourcepath', 'modulepath'):
            continue
        seen = {}
        for child in node.children:
            if child.tag != 'pathelement':
                continue
            loc = child.attrs.get('location') or child.attrs.get('path')
            if not loc:
                continue
            if loc in seen:
                yield Finding('dup-pathelement', doc.path, child.line,
                              f'{loc} is already on this path at line {seen[loc]}')
            else:
                seen[loc] = child.line


PATTERN_TAGS = ('include', 'exclude', 'includesfile', 'excludesfile')


def rule_dup_fileset_pattern(doc, corpus):
    if doc.root is None:
        return
    for node in doc.root.iter():
        if node.tag not in ('fileset', 'dirset', 'filelist', 'zipfileset',
                            'tarfileset', 'restrict', 'resources'):
            continue
        seen = {}
        for child in node.children:
            if child.tag not in PATTERN_TAGS:
                continue
            key = (child.tag, child.attrs.get('name'))
            if key[1] is None:
                continue
            if key in seen:
                yield Finding('dup-fileset-pattern', doc.path, child.line,
                              f'<{child.tag} name="{key[1]}"> already appears at line {seen[key]}')
            else:
                seen[key] = child.line


def rule_dup_javadoc_link(doc, corpus):
    if doc.root is None:
        return
    for node in doc.root.iter():
        if node.tag != 'javadoc':
            continue
        seen = {}
        for child in node.children:
            if child.tag != 'link':
                continue
            href = child.attrs.get('href')
            if not href:
                continue
            if href in seen:
                yield Finding('dup-javadoc-link', doc.path, child.line,
                              f'<link href="{href}"> already appears at line {seen[href]}')
            else:
                seen[href] = child.line


COMMENT_ELEMENT = re.compile(r'<\s*([A-Za-z][\w:.-]*)([^<>]*?)(/?)>')


def rule_commented_xml(doc, corpus):
    """Build XML left inside a comment.

    The tag must be one the tree really uses, must carry an attribute value, and
    must be a whole element - self-closing or closed later in the same comment.
    Prose that quotes a fragment, like "this must be <arg line=...>, not
    <arg value=...>", is not commented-out code and is not reported. One finding
    per comment, naming the first element and how many the comment holds.
    """
    for comment in doc.comments:
        if 'NOSONAR' in comment.text:
            continue
        hits = []
        for m in COMMENT_ELEMENT.finditer(comment.text):
            tag, attrs, selfclose = m.group(1), m.group(2), m.group(3)
            if tag not in corpus.task_names:
                continue
            if '="' not in attrs:
                continue
            if selfclose != '/' and f'</{tag}' not in comment.text:
                continue
            hits.append(m.group(0).strip())
        if not hits:
            continue
        first = hits[0]
        more = '' if len(hits) == 1 else ' (%d elements)' % len(hits)
        yield Finding('commented-xml', doc.path, comment.line,
                      f'commented-out build XML: {first}{more}')


def rule_empty_target(doc, corpus):
    """A target with no body and no depends.

    An empty target another file calls through <ant target="..."/> is a hook put
    there on purpose - builddep is the house example - so only the ones nothing
    invokes are dead.
    """
    for node in doc.targets():
        if node.children or node.attrs.get('depends'):
            continue
        name = node.attrs.get('name')
        if not name or name in corpus.invoked_targets:
            continue
        yield Finding('empty-target', doc.path, node.line,
                      f'target "{name}" has no body and no depends')


CONVENTIONAL_TARGETS = {
    'all', 'build', 'clean', 'distclean', 'cleandep', 'reallyclean', 'totallyclean',
    'jar', 'war', 'test', 'javadoc', 'install', 'deps', 'help', 'usage',
    'builddep', 'builddepSmall', 'rebuild', 'compile', 'dist', 'package',
    'sourcesJar', 'javadocJar', 'jarJar', 'precompileJsp', 'ensureJettylib',
}


def rule_unused_target(doc, corpus):
    """A target no build file, script, help menu or person invokes.

    Ant build files are also a menu: a target with a description shows in
    `ant -p`, and the help target echoes the names a developer types. Both are
    entry points, so they are not dead weight.
    """
    if doc.root is None:
        return
    referenced = set()
    for other in corpus.docs:
        if other.root is None:
            continue
        for node in other.root.iter():
            # <ant>, <subant>, <antcall> and ant-contrib's <foreach> and <apply>
            # all name a target to run
            if node.tag in TARGET_CALL_TAGS + ('ant', 'subant') and node.attrs.get('target'):
                referenced.add(node.attrs['target'])
            if node.tag in ('ant', 'subant'):
                for child in node.children:
                    if child.tag == 'target' and child.attrs.get('name'):
                        referenced.add(child.attrs['name'])
            if node.tag == 'target' and node.attrs.get('depends'):
                referenced.update(split_list(node.attrs['depends']))
        if other.project() is not None and other.project().attrs.get('default'):
            referenced.add(other.project().attrs['default'])
    entry = corpus.entry_points | corpus.documented
    for node in doc.targets():
        name = node.attrs.get('name')
        if not name or name in referenced or name in entry:
            continue
        if name in CONVENTIONAL_TARGETS:
            continue
        if node.attrs.get('description'):
            continue
        if doc.project() is not None and doc.project().attrs.get('default') == name:
            continue
        # a name a doc page, a CI job, a SlackBuild or a comment still names is a
        # human entry point even if no build file invokes it
        if any(not _is_definition(hit, name)
               for hit in corpus.mentions(name)):
            continue
        yield Finding('unused-target', doc.path, node.line,
                      f'target "{name}" is not invoked, described, documented or '
                      f'mentioned anywhere in the tree')


def _is_definition(hit, name):
    """A git grep line that is only a <target name="..."> definition.

    Any file's definition line counts, not just the finding's own: a module that
    defines its own `test.report` says nothing about whether the root build's
    target of the same name is reached from anywhere.
    """
    parts = hit.split(':', 2)
    return len(parts) == 3 and '<target name="%s"' % name in parts[2]


# What each task's path attributes mean: only the ones naming an input the
# build has to find are checked, so a zip() destfile or a javadoc sourcepath
# never reads as a missing file.
PATH_INPUT_ATTRS = {
    'pathelement': ('location', 'path'),
    'fileset': ('dir',),
    'dirset': ('dir',),
    'zipfileset': ('dir',),
    'tarfileset': ('dir',),
    'filelist': ('dir',),
    'copy': ('file',),
    'move': ('file',),
    'loadfile': ('srcFile',),
}

# An attribute saying this file may be absent. The polarity differs by task:
# ignoreerrors is off by default, failonerror and erroronmissingdir are on by
# default. quiet is house style on a copy, so it only means optional on a
# delete, where its whole job is to tolerate a missing file.
OPTIONAL_TRUE = ('ignoreerrors',)
OPTIONAL_TRUE_ON_DELETE = ('quiet',)
OPTIONAL_FALSE = ('failonerror', 'erroronmissingdir')

# A path component that means "a build writes this here".
GENERATED_DIRS = {'build', 'dist', 'obj', 'classes', 'reports', 'javadoc'}


def says_optional(node):
    """True if this element or an ancestor declares the file it names optional."""
    while node is not None:
        for attr in OPTIONAL_TRUE:
            if node.attrs.get(attr, '').lower() in ('true', 'yes', 'on'):
                return True
        if node.tag == 'delete':
            for attr in OPTIONAL_TRUE_ON_DELETE:
                if node.attrs.get(attr, '').lower() in ('true', 'yes', 'on'):
                    return True
        for attr in OPTIONAL_FALSE:
            if node.attrs.get(attr, '').lower() in ('false', 'no', 'off'):
                return True
        node = node.parent
    return False


def rule_missing_path(doc, corpus):
    """A path a build file reads that is neither in the tree nor build output.

    Deliberately narrow: property-expanded, wildcard, absolute and outside-the-
    tree values are left alone, as are tasks that name their own output, any
    element that says the file is optional, and directories another target
    creates with <mkdir>. An <available file="git"> with no slash is a PATH
    probe, not a file under basedir.
    """
    if doc.root is None:
        return
    base = os.path.dirname(doc.path)
    for node in doc.root.iter():
        attrs = PATH_INPUT_ATTRS.get(node.tag)
        if not attrs or says_optional(node):
            continue
        for attr in attrs:
            value = node.attrs.get(attr)
            if not value or '${' in value or '@{' in value:
                continue
            if value.startswith('/') or any(c in value for c in '*?,:'):
                continue
            if node.tag == 'available' and '/' not in value:
                continue
            rel = os.path.normpath(os.path.join(base, value)).replace(os.sep, '/')
            if rel.startswith('..'):
                continue
            if os.path.exists(os.path.join(corpus_root(), rel)):
                continue
            if rel in corpus.ignored or any(p in GENERATED_DIRS for p in rel.split('/')):
                continue
            if any(rel == made or rel.startswith(made + '/') for made in corpus.generated):
                continue
            yield Finding('missing-path', doc.path, node.line,
                          f'<{node.tag} {attr}="{value}"> resolves to {rel}, '
                          f'which is not in the tree and not build output')


_CORPUS_ROOT = os.getcwd()


def corpus_root():
    return _CORPUS_ROOT


#---------------------------------------------------------------------------
# rules - the build root
#---------------------------------------------------------------------------

# The one place the tree says where build output goes. Ant files set it as
# ${build.root}, Gradle scripts reach the same directory through
# System.getProperty('java.io.tmpdir'), and the final HTML reports are the only
# output that lands in the workspace, under dist/.
BUILD_ROOT_VALUE = '${java.io.tmpdir}/build-i2p/'
BUILD_ROOT_DEF = '<property name="build.root" value="%s"/>' % BUILD_ROOT_VALUE

# Attributes that name a directory or file the build writes or reads. 'target'
# and 'name' are deliberately absent - a value of 'build' there is a target name
# or a property name, not a path.
BUILD_PATH_ATTRS = ('dir', 'srcdir', 'todir', 'destdir', 'file', 'destfile',
                    'tofile', 'tempfile', 'classesdir', 'src')


def build_root_defs(doc):
    """Every <property name="build.root"> in this file."""
    return [n for n in doc.root.descendants('property')
            if n.attrs.get('name') == 'build.root']


def rule_build_root(doc, corpus):
    """The build root is one property, and the file that reads it sets it.

    Each ant file is its own project: an <ant dir=...> call hands its properties
    down to the subbuild, but a build started directly in that directory does
    not, so a file that reads ${build.root} has to define it.
    """
    defs = build_root_defs(doc)
    if defs:
        return
    for node in doc.root.iter():
        if any('${build.root}' in v for v in node.attrs.values()):
            yield Finding('build-root', doc.path, node.line,
                          '${build.root} is used but this file never sets it - add %s'
                          % BUILD_ROOT_DEF)
            return


def rule_build_root_value(doc, corpus):
    """A file that sets the build root somewhere else gives the tree two roots.

    Output then lands outside the directory the test, report and clean targets
    work from, and the workspace is written into again.
    """
    for node in build_root_defs(doc):
        value = node.attrs.get('value')
        if 'location' in node.attrs or value != BUILD_ROOT_VALUE:
            yield Finding('build-root-value', doc.path, node.line,
                          'build.root is set to "%s", not the one build root "%s"'
                          % (node.attrs.get('location', value), BUILD_ROOT_VALUE))


def rule_build_root_location(doc, corpus):
    """build.root set with location= instead of value=.

    location= is resolved to an absolute path with no trailing slash, and every
    use in the tree joins onto the slash - ${build.root}reports - so the two run
    together into build-i2preports.
    """
    for node in build_root_defs(doc):
        if 'location' in node.attrs:
            yield Finding('build-root-location', doc.path, node.line,
                          'build.root is set with location=, which drops the trailing '
                          'slash every use joins onto - set it with value=')


def rule_tmpdir_literal(doc, corpus):
    """A build path spelled out of ${java.io.tmpdir} instead of ${build.root}.

    Only the build.root definition itself may name the tmpdir, so the place the
    root is chosen stays one line per file.
    """
    for node in doc.root.iter():
        for attr, value in node.attrs.items():
            if '${java.io.tmpdir}/build-i2p' not in value:
                continue
            if node.tag == 'property' and node.attrs.get('name') == 'build.root':
                continue
            yield Finding('tmpdir-literal', doc.path, node.line,
                          '<%s %s="%s"> spells out the build root - use "${build.root}"'
                          % (node.tag, attr, value))


def rule_abs_build_path(doc, corpus):
    """A build path hardcoded to /tmp, which is not where the root is.

    java.io.tmpdir is /tmp on Linux and somewhere else everywhere else, so a
    literal /tmp/build-i2p writes the report where nothing looks for it.
    """
    for node in doc.root.iter():
        for attr, value in node.attrs.items():
            if '/tmp/build-i2p' not in value:
                continue
            yield Finding('abs-build-path', doc.path, node.line,
                          '<%s %s="%s"> hardcodes /tmp/build-i2p - use "${build.root}"'
                          % (node.tag, attr, value))


def rule_relative_build_path(doc, corpus):
    """Build output written into the source tree as a relative 'build' path.

    Every file that sets build.root puts its output there, so a bare dir="build"
    is a path the build no longer writes to - a clean target deleting a directory
    that only exists if a stale run left it, or worse, deleting a real one.
    dist/ is exempt: the final HTML reports belong in it by design.
    """
    if not build_root_defs(doc):
        return
    for node in doc.root.iter():
        for attr in BUILD_PATH_ATTRS:
            value = node.attrs.get(attr)
            if not value or value.startswith(('/', '${', '@{')):
                continue
            if value.split('/')[0] != 'build':
                continue
            yield Finding('relative-build-path', doc.path, node.line,
                          '<%s %s="%s"> is a build path in the source tree - the build '
                          'writes under ${build.root}' % (node.tag, attr, value))


#---------------------------------------------------------------------------
# rules - Gradle build files
#---------------------------------------------------------------------------

# The character before a '/' that means it opens a slashy string (a Groovy
# regex) instead of a division. Getting this wrong is what made the brace
# counter see quotes inside /JAVA_VERSION="(\d+)/ as the start of a string.
REGEX_POSITION_AFTER = set('=([{,;:~!&|?^*%<>+-')


def strip_gradle_noise(text):
    """Blank out comments and string literals so bracket counting is honest."""
    out = []
    i = 0
    n = len(text)
    prev = ''

    def blank(ch):
        out.append('\n' if ch == '\n' else ' ')

    while i < n:
        ch = text[i]
        if ch == '/' and i + 1 < n and text[i + 1] == '/':
            while i < n and text[i] != '\n':
                blank(text[i])
                i += 1
            continue
        if ch == '/' and i + 1 < n and text[i + 1] == '*':
            while i < n and not (text[i] == '*' and i + 1 < n and text[i + 1] == '/'):
                blank(text[i])
                i += 1
            out.append('  ')
            i += 2
            continue
        if ch == '/' and (not prev or prev in REGEX_POSITION_AFTER):
            # slashy string: /.../ or $/.../$, ends at an unescaped /
            i += 1
            dollar = i < n and text[i] == '$'
            if dollar:
                i += 1
            while i < n:
                if text[i] == '\\':
                    blank(text[i])
                    i += 1
                    if i < n:
                        blank(text[i])
                        i += 1
                    continue
                if text[i] == '/' and not (dollar and i + 1 < n and text[i + 1] == '$'):
                    i += 1
                    break
                blank(text[i])
                i += 1
            prev = '/'
            continue
        if ch in ('"', "'") and text[i:i + 3] in ('"""', "'''"):
            quote = text[i:i + 3]
            i += 3
            while i < n and text[i:i + 3] != quote:
                blank(text[i])
                i += 1
            i += 3
            prev = quote[0]
            continue
        if ch in '"\'':
            quote = ch
            out.append(' ')
            i += 1
            while i < n and text[i] != quote:
                if text[i] == '\\':
                    out.append(' ')
                    i += 1
                    if i >= n:
                        break
                out.append('\n' if text[i] == '\n' else ' ')
                i += 1
            i += 1
            prev = quote
            continue
        out.append(ch)
        if not ch.isspace():
            prev = ch
        i += 1
    return ''.join(out)


def rule_gradle_balance(doc, corpus):
    """A Gradle block left open, or closed by an extra brace.

    Only braces are counted. Groovy brackets and parens sit inside GStrings and
    list literals in ways a scanner reads wrongly, and a block that never closes
    is the failure that changes what a task does.
    """
    if not doc.path.endswith('.gradle'):
        return
    text = strip_gradle_noise(doc.text)
    stack = []
    for idx, ch in enumerate(text):
        if ch == '{':
            stack.append(doc.text.count('\n', 0, idx) + 1)
        elif ch == '}':
            if not stack:
                line = doc.text.count('\n', 0, idx) + 1
                yield Finding('gradle-balance', doc.path, line, "unmatched '}'")
                return
            stack.pop()
    for line in stack:
        yield Finding('gradle-balance', doc.path, line, 'block is never closed')


def rule_gradle_dup_dependency(doc, corpus):
    if not doc.path.endswith('.gradle'):
        return
    seen = {}
    for idx, line in enumerate(doc.text.splitlines(), 1):
        m = re.match(r'\s*(implementation|api|compileOnly|runtimeOnly|testImplementation|'
                     r'testRuntimeOnly|annotationProcessor|classpath)\s*\(?\s*[\'"]([^\'"]+)[\'"]',
                     line)
        if not m:
            continue
        key = (m.group(1), m.group(2))
        if key in seen:
            yield Finding('gradle-dup-dependency', doc.path, idx,
                          f'{key[0]} {key[1]} is declared again at line {seen[key]}')
        else:
            seen[key] = idx


def rule_gradle_dup_copy_pattern(doc, corpus):
    """The same include or exclude twice inside one copy block.

    A Gradle copy spec applies its patterns in order, so a repeated pattern is
    dead weight. Patterns are tracked per brace scope, so two different blocks
    that each include the same file are not reported.
    """
    if not doc.path.endswith('.gradle'):
        return
    scopes = [{}]
    for idx, line in enumerate(doc.text.splitlines(), 1):
        m = re.match(r'(include|exclude)\s*\(?\s*[\'"]([^\'"]+)[\'"]', line.strip())
        if m:
            key = (m.group(1), m.group(2))
            if key in scopes[-1]:
                yield Finding('gradle-dup-copy-pattern', doc.path, idx,
                              f'{key[0]} {key[1]} appears twice in the same block '
                              f'(first at line {scopes[-1][key]})')
            else:
                scopes[-1][key] = idx
        for ch in strip_gradle_noise(line):
            if ch == '{':
                scopes.append({})
            elif ch == '}' and len(scopes) > 1:
                scopes.pop()


def rule_gradle_abs_build_path(doc, corpus):
    """A Gradle script that hardcodes /tmp instead of the JVM's tmpdir.

    settings.gradle puts every project's output at
    ${java.io.tmpdir}/build-i2p/gradle/<project>, mirroring the ant ${build.root}.
    A script that writes the literal /tmp/build-i2p instead follows a different
    root on every platform that is not Linux.
    """
    for idx, line in enumerate(doc.text.split('\n'), 1):
        stripped = line.strip()
        if '/tmp/build-i2p' not in line:
            continue
        if stripped.startswith(('//', '*', '/*')):
            continue
        yield Finding('gradle-abs-build-path', doc.path, idx,
                      'hardcodes /tmp/build-i2p - use '
                      '"${System.getProperty(\'java.io.tmpdir\')}/build-i2p"')


def rule_gradle_dup_task(doc, corpus):
    if not doc.path.endswith('.gradle'):
        return
    seen = {}
    for idx, line in enumerate(doc.text.splitlines(), 1):
        m = re.match(r"\s*tasks\.register\(\s*['\"]([^'\"]+)['\"]", line)
        if not m:
            m = re.match(r'\s*task\s+([A-Za-z][\w]*)', line)
        if not m:
            continue
        name = m.group(1)
        if name in seen:
            yield Finding('gradle-dup-task', doc.path, idx,
                          f'task "{name}" is registered again at line {seen[name]}')
        else:
            seen[name] = idx


#---------------------------------------------------------------------------
# rule table
#---------------------------------------------------------------------------

def rules():
    return [
        Rule('xml', 'file is not well-formed XML', rule_xml,
             {'build.xml': '<?xml version="1.0"?>\n<project name="t"><target name="a">'
                           '</project>\n'},
             {'build.xml': xml_project('<target name="a"><echo/></target>\n')}),
        Rule('dup-target', 'two targets with the same name', rule_dup_target,
             {'build.xml': xml_project('<target name="a"><echo/></target>\n'
                                       '<target name="a"><echo/></target>\n')},
             {'build.xml': xml_project('<target name="a"><echo/></target>\n'
                                       '<target name="b"><echo/></target>\n')}),
        Rule('dup-property', 'a property set twice, the second one dead', rule_dup_property,
             {'build.xml': xml_project('', '<property name="x" value="1"/>\n'
                                           '<property name="x" value="1"/>\n')},
             {'build.xml': xml_project('', '<property name="x" value="1"/>\n'
                                           '<property name="y" value="1"/>\n')}),
        Rule('self-property', 'a property set to itself', rule_self_property,
             {'build.xml': xml_project('', '<property name="x" value="${x}"/>\n')},
             {'build.xml': xml_project('', '<property name="x" value="${y}"/>\n')}),
        Rule('missing-target', 'depends or antcall names an undefined target', rule_missing_target,
             {'build.xml': xml_project('<target name="a" depends="nope"><echo/></target>\n'
                                       '<target name="b"><antcall target="gone"/></target>\n'
                                       '<target name="c"><foreach target="alsoGone"/></target>\n')},
             {'build.xml': xml_project('<target name="a" depends="b"><echo/></target>\n'
                                       '<target name="b"><antcall target="a"/>\n'
                                       '<foreach target="a"/><ant dir="sub" target="whoCares"/></target>\n')}),
        Rule('missing-ant-target', 'an <ant> call names a target the other file lacks',
             rule_missing_ant_target,
             {'build.xml': xml_project('<target name="a"><ant dir="sub" target="nope"/></target>\n'),
              'sub/build.xml': xml_project('<target name="b"><echo/></target>\n')},
             {'build.xml': xml_project('<target name="a"><ant dir="sub" target="b"/></target>\n'),
              'sub/build.xml': xml_project('<target name="b"><echo/></target>\n')}),
        Rule('missing-default', 'the project default names a target this file lacks',
             rule_missing_default,
             {'build.xml': xml_project('<target name="jar"><echo/></target>\n', default='all')},
             {'build.xml': xml_project('<target name="all" depends="clean, jar"><echo/></target>\n'
                                       '<target name="clean"><echo/></target>\n'
                                       '<target name="jar"><echo/></target>\n', default='all'),
              # no default at all is fine - ant just lists the targets
              'apps/x/build.xml': xml_project('<target name="b"><echo/></target>\n')}),
        Rule('undefined-property', '${name} is defined nowhere', rule_undefined_property,
             {'build.xml': xml_project('<target name="a"><echo file="${nowhere.f}"/></target>\n')},
             {'build.xml': xml_project('<target name="a"><exec executable="sh" outputproperty="git.out"/>\n'
                                       '<echo message="${git.out} ${known.f}"/></target>\n'
                                       '<target name="b"><foreach target="compress" param="file"/>\n'
                                       '<arg value="saved=$((before - ${file})) ${z_mb}"/>\n'
                                       '<math result="js.saved" operand1="1" operand2="2" operation="-"/>\n'
                                       '<echo message="${js.saved}"/></target>\n'
                                       '<!-- ${not.a.use} in prose is not a use -->\n',
                                       '<property name="known.f" value="x"/>\n')}),
        Rule('dup-pathelement', 'the same jar twice on one classpath', rule_dup_pathelement,
             {'build.xml': xml_project('<target name="a"><classpath>'
                                       '<pathelement location="lib/a.jar"/>'
                                       '<pathelement location="lib/a.jar"/></classpath></target>\n')},
             {'build.xml': xml_project('<target name="a"><classpath>'
                                       '<pathelement location="lib/a.jar"/>'
                                       '<pathelement location="lib/b.jar"/></classpath></target>\n')}),
        Rule('dup-fileset-pattern', 'the same include or exclude twice in one fileset',
             rule_dup_fileset_pattern,
             {'build.xml': xml_project('<target name="a"><fileset dir=".">'
                                       '<include name="*.jar"/><include name="*.jar"/>'
                                       '</fileset></target>\n')},
             {'build.xml': xml_project('<target name="a"><fileset dir=".">'
                                       '<include name="*.jar"/><include name="*.war"/>'
                                       '</fileset></target>\n')}),
        Rule('dup-javadoc-link', 'the same javadoc <link> repeated', rule_dup_javadoc_link,
             {'build.xml': xml_project('<target name="a"><javadoc>'
                                       '<link href="https://x/"/><link href="https://x/"/>'
                                       '</javadoc></target>\n')},
             {'build.xml': xml_project('<target name="a"><javadoc>'
                                       '<link href="https://x/"/><link href="https://y/"/>'
                                       '</javadoc></target>\n')}),
        Rule('commented-xml', 'build XML left in a comment', rule_commented_xml,
             {'build.xml': xml_project('<!-- <echo message="kept for reference"/> -->\n'
                                       '<target name="a"><echo/></target>\n')},
             {'build.xml': xml_project('<!-- a missing <property file> is ignored, and this\n'
                                       '         must be <arg line="...">, not <arg value="..."> -->\n'
                                       '<property name="p" value="1"/>\n'
                                       '<target name="a"><echo/><exec executable="sh"><arg line="-x"/></exec></target>\n')}),
        Rule('empty-target', 'a target with no body and no depends', rule_empty_target,
             {'build.xml': xml_project('<target name="a"/>\n<target name="b"><echo/></target>\n')},
             {'build.xml': xml_project('<target name="a" depends="b, noophook"/>\n'
                                       '<target name="hook"/>\n'
                                       '<target name="noophook"/>\n'
                                       '<target name="b"><echo/></target>\n'),
              'sub/build.xml': xml_project('<target name="c"><ant target="hook"/></target>\n')}),
        Rule('unused-target', 'a target nothing invokes', rule_unused_target,
             # the only unused target here is `dup`, and the sole line naming it is a
             # definition in another build file - so a rule that counts a foreign
             # definition as a mention reports nothing and fails this fixture
             {'build.xml': xml_project('<target name="build" depends="work"><echo/></target>\n'
                                       '<target name="work"><echo/></target>\n'
                                       '<target name="dup"><echo/></target>\n',
                                       default='build'),
              'apps/x/build.xml': xml_project('<target name="dup"><echo/></target>\n'),
              'mentions': {'dup': ['apps/x/build.xml:2:  <target name="dup"><echo/></target>']}},
             {'build.xml': xml_project('<target name="build"><ant dir="apps/x" target="keep"/>\n'
                                       '<echo message="  spotbugs   Run SpotBugs"/></target>\n'
                                       '<target name="spotbugs"><echo/></target>\n'
                                       '<target name="described" description="Run the thing">'
                                       '<echo/></target>\n'
                                       '<target name="byhand"><echo/></target>\n',
                                       default='build'),
              'apps/x/build.xml': xml_project('<target name="keep"><echo/></target>\n'),
              # nothing invokes it, but a doc page names it, so it is an entry point
              'mentions': {'byhand': ['docs/x.md:12:run `ant byhand` to build it']}}),
        Rule('missing-path', 'a static path that does not exist', rule_missing_path,
             {'build.xml': xml_project('<target name="a"><pathelement location="gone.jar"/></target>\n')},
             {'build.xml': xml_project('<target name="a"><pathelement location="build.xml"/>\n'
                                       '<available file="git"/>\n'
                                       '<copy file="build/gone.jar" todir="x"/>\n'
                                       '<delete quiet="true"><fileset dir="gone-logs"/></delete>\n'
                                       '<copy todir="x"><fileset dir="gone-src" erroronmissingdir="false"/></copy>\n'
                                       '<zip destfile="gone.zip"><fileset dir="made-by-mkdir"/></zip>\n'
                                       '<mkdir dir="made-by-mkdir"/></target>\n')}),
        Rule('build-root', 'the build root read without being set, or set to something else',
             rule_build_root,
             {'build.xml': xml_project('<target name="a"><mkdir dir="${build.root}reports"/>\n'
                                       '<delete dir="${build.root}core"/></target>\n')},
             {'build.xml': xml_project('<target name="a"><mkdir dir="${build.root}reports"/>\n'
                                       '<delete dir="${build.root}core"/></target>\n',
                                       BUILD_ROOT_DEF + '\n'),
              # a subbuild inherits the caller's properties, so a file that never
              # reads the root needs no definition of its own
              'apps/x/build.xml': xml_project('<target name="keep"><echo/></target>\n')}),
        Rule('build-root-value', 'the build root set to a second location',
             rule_build_root_value,
             {'build.xml': xml_project('<target name="a"><mkdir dir="${build.root}x"/></target>\n',
                                       '  <property name="build.root" value="/var/tmp/build-i2p/"/>\n')},
             {'build.xml': xml_project('<target name="a"><mkdir dir="${build.root}x"/></target>\n',
                                       BUILD_ROOT_DEF + '\n')}),
        Rule('build-root-location', 'the build root set with location=, which drops the slash',
             rule_build_root_location,
             {'build.xml': xml_project('<target name="a"><mkdir dir="${build.root}x"/></target>\n',
                                       '  <property name="build.root" location="/tmp/build-i2p"/>\n')},
             {'build.xml': xml_project('<target name="a"><mkdir dir="${build.root}x"/></target>\n',
                                       BUILD_ROOT_DEF + '\n')}),
        Rule('tmpdir-literal', 'a build path spelled out instead of ${build.root}',
             rule_tmpdir_literal,
             {'build.xml': xml_project('<target name="a"><mkdir dir="${java.io.tmpdir}/build-i2p/reports"/>'
                                       '</target>\n', BUILD_ROOT_DEF + '\n')},
             {'build.xml': xml_project('<target name="a"><mkdir dir="${build.root}reports"/></target>\n',
                                       BUILD_ROOT_DEF + '\n'),
              # the definition is the one line allowed to name the tmpdir
              'apps/x/build.xml': xml_project('', BUILD_ROOT_DEF + '\n')}),
        Rule('abs-build-path', 'a build path hardcoded to /tmp', rule_abs_build_path,
             {'build.xml': xml_project('<target name="a"><mkdir dir="/tmp/build-i2p/reports"/>\n'
                                       '<arg value="/tmp/build-i2p/reports/pmd-java.xml"/></target>\n',
                                       BUILD_ROOT_DEF + '\n')},
             {'build.xml': xml_project('<target name="a"><mkdir dir="${build.root}reports"/>\n'
                                       '<arg value="${build.root}reports/pmd-java.xml"/></target>\n',
                                       BUILD_ROOT_DEF + '\n')}),
        Rule('relative-build-path', 'a build path written into the source tree',
             rule_relative_build_path,
             {'build.xml': xml_project('<target name="clean"><delete dir="mo"/>\n'
                                       '<delete dir="build"/></target>\n',
                                       BUILD_ROOT_DEF +
                                       '  <property name="build.dir" value="${build.root}x/build"/>\n')},
             {'build.xml': xml_project('<target name="clean"><delete dir="${build.dir}"/></target>\n'
                                       # dist/ is where the final HTML reports belong
                                       '<target name="report"><mkdir dir="dist"/>\n'
                                       '<arg value="dist/checkstyle.html"/></target>\n',
                                       BUILD_ROOT_DEF +
                                       '  <property name="build.dir" value="${build.root}x/build"/>\n'),
              # a file with no build.root is the build-root rule's business, and its
              # relative paths are its own
              'apps/x/build.xml': xml_project('<target name="a"><mkdir dir="build"/></target>\n')}),
        Rule('gradle-abs-build-path', 'a Gradle script hardcoded to /tmp/build-i2p',
             rule_gradle_abs_build_path,
             {'a.gradle': 'buildDir = file("/tmp/build-i2p/gradle")\n'},
             {'a.gradle': 'buildDir = file("${System.getProperty(\'java.io.tmpdir\')}/build-i2p/gradle")\n'
                          '// a comment may still say /tmp/build-i2p\n'}),
        Rule('gradle-balance', 'unbalanced braces in a Gradle file', rule_gradle_balance,
             {'a.gradle': 'tasks.register("a") {\n  doLast {\n    println "x"\n}\n'},
             {'a.gradle': 'tasks.register("a") {\n'
                          '  def m = text =~ /VERSION="(?:1\\.)?(\\d+)"/\n'
                          '  if (m.find()) { println m[0][1] }\n'
                          '  println "x { not a brace"\n}\n'}),
        Rule('gradle-dup-dependency', 'the same dependency declared twice', rule_gradle_dup_dependency,
             {'a.gradle': "dependencies {\n  implementation 'x:y:1'\n  implementation 'x:y:1'\n}\n"},
             {'a.gradle': "dependencies {\n  implementation 'x:y:1'\n  implementation 'x:z:1'\n}\n"}),
        Rule('gradle-dup-copy-pattern', 'the same include or exclude twice in one block',
             rule_gradle_dup_copy_pattern,
             {'a.gradle': "into('lib') {\n  include 'a.jar'\n  include 'a.jar'\n}\n"},
             {'a.gradle': "into('lib') {\n  include 'a.jar'\n  include 'b.jar'\n}\n"}),
        Rule('gradle-dup-task', 'the same task registered twice', rule_gradle_dup_task,
             {'a.gradle': 'tasks.register("a") {}\ntasks.register("a") {}\n'},
             {'a.gradle': 'tasks.register("a") {}\ntasks.register("b") {}\n'}),
    ]


#---------------------------------------------------------------------------
# scanning
#---------------------------------------------------------------------------

def is_context_file(path):
    """A file that says how the build is run, without being one: docs, CI jobs,
    the Debian rules, docker, and the scripts under tools/."""
    return (path.endswith(('.md', '.sh', '.yml', '.yaml', '.mk', '.bat', '.txt')) or
            path.startswith(('distro/', 'docker/', '.github/')))


def build_corpus(root, only=None):
    all_files = tracked_files(root)
    files = [f for f in all_files if is_build_file(f)]
    wanted = set()
    if only:
        wanted = {os.path.relpath(os.path.abspath(f), root).replace(os.sep, '/')
                  for f in only}
        missing = wanted - set(files)
        if missing:
            print('* Build file check cannot run: not a tracked build file: %s'
                  % ', '.join(sorted(missing)), file=sys.stderr)
            return None, []
    docs, texts = load_corpus(root, files)
    texts.update(load_texts(root, [f for f in all_files
                                   if not is_build_file(f) and is_context_file(f)]))
    props = collect_defined_props(root, docs, texts)
    entry = collect_entry_points(root, texts)
    candidates = set()
    for doc in docs:
        if doc.root is None:
            continue
        base = os.path.dirname(doc.path)
        for node in doc.root.iter():
            for attr in ('dir', 'location', 'file', 'src'):
                value = node.attrs.get(attr)
                if value and '${' not in value and not value.startswith('/'):
                    rel = os.path.normpath(os.path.join(base, value)).replace(os.sep, '/')
                    if not rel.startswith('..'):
                        candidates.add(rel)
    ignored = git_ignored(root, sorted(candidates))
    corpus = Corpus(docs, texts, entry, props, ignored, root)
    if wanted:
        # the whole tree still supplies the context - defined properties, invoked
        # targets, task names - only the files reported on are narrowed
        corpus.docs = [d for d in corpus.docs if d.path in wanted]
        files = [f for f in files if f in wanted]
    return corpus, files


def scan_rule(rule, corpus):
    out = []
    for doc in corpus.docs:
        if rule.name.startswith('gradle-'):
            if not doc.path.endswith('.gradle'):
                continue
        elif doc.path.endswith('.gradle'):
            continue
        elif doc.root is None:
            continue
        out.extend(rule.run(doc, corpus))
    return out


def self_test(rule):
    """The rule must flag its bad fixture and pass its good one."""
    bad = fixture_corpus(rule.bad)
    ok = fixture_corpus(rule.ok)
    hits_bad = scan_rule(rule, bad)
    hits_ok = scan_rule(rule, ok)
    if not hits_bad:
        return f'{rule.name}: does not flag its own defect fixture'
    if hits_ok:
        return (f'{rule.name}: flags its clean fixture '
                f'({hits_ok[0].path}: {hits_ok[0].detail})')
    return ''


def probe_rules(active):
    """Run every active rule's self-probe; return the ones that are not checking."""
    bad = []
    for rule in active:
        probe = self_test(rule)
        if probe:
            bad.append(probe)
    return bad


def scan(active_rules, root=None, only=None):
    """Every finding from the active rules, after their self-probes have passed.

    A rule that no longer flags its own fixture is a rule that stopped checking,
    so the run stops with it rather than reporting a clean tree. only= narrows the
    files scanned while the tree-wide context (defined properties, invoked
    targets, task names) stays whole, so one finding can be re-checked on its own.
    """
    global _CORPUS_ROOT
    root = root or os.getcwd()
    _CORPUS_ROOT = root
    corpus, files = build_corpus(root, only)
    if corpus is None:
        return None, files
    broken = probe_rules(active_rules)
    if broken:
        for line in broken:
            print('* FAIL %s' % line)
        print('* Build file check cannot run: %d rule(s) are not checking'
              % len(broken), file=sys.stderr)
        return None, files
    found = []
    for rule in active_rules:
        found.extend(scan_rule(rule, corpus))
    return found, files


# What each rule means, in the words a reader of the build log wants. The rule id
# stays in the --verbose listing; a tally line that says 'dup-target: 9' names the
# check, not the defect, and tells the reader nothing about the file.
RULE_LABELS = {
    'xml': 'build file that does not parse',
    'dup-target': 'target defined twice in one file',
    'dup-property': 'property set twice, the second setting dead',
    'self-property': 'property set to itself',
    'missing-target': 'depends or antcall names a target this file lacks',
    'missing-ant-target': 'ant call names a target the other build file lacks',
    'missing-default': 'project default names a target this file lacks',
    'undefined-property': 'property used but never defined',
    'dup-pathelement': 'same entry twice on one classpath',
    'dup-fileset-pattern': 'same include or exclude twice in one fileset',
    'dup-javadoc-link': 'same javadoc link repeated',
    'commented-xml': 'build XML left in a comment',
    'empty-target': 'target with no body and no depends',
    'unused-target': 'target nothing invokes or documents',
    'missing-path': 'path the build reads does not exist',
    'build-root': 'build root used but never set in that file',
    'build-root-value': 'build root set to a second location',
    'build-root-location': 'build root set with location=, losing the trailing slash',
    'tmpdir-literal': 'build path spelled out instead of using the build root',
    'abs-build-path': 'build path hardcoded to /tmp',
    'relative-build-path': 'build output written into the source tree',
    'gradle-abs-build-path': 'Gradle build path hardcoded to /tmp',
    'gradle-balance': 'Gradle block left open or closed twice',
    'gradle-dup-dependency': 'dependency declared twice',
    'gradle-dup-copy-pattern': 'same include or exclude twice in one copy block',
    'gradle-dup-task': 'task registered twice',
}


def rule_label(rule):
    """The defect a rule reports, for a report line."""
    return RULE_LABELS.get(rule, rule)


def summary_line(count, files, hits):
    """The one line the check says about the tree, in the house '* <info>' form."""
    many = '' if len(files) == 1 else 's'
    if count == 0:
        return '* Build file check passed for: %d ant and Gradle build file%s' % (len(files), many)
    return ('* Build file check identified %d issue%s in %d of %d build file%s'
            % (count, '' if count == 1 else 's', hits, len(files), many))


def report_cmd():
    """Where to go for the detail, as a copy-pasteable command."""
    return '* Run tools/scripts/check-build-files.py --verbose to view the full report.'


def finding_lines(finding):
    """One finding as report lines: file, line, the rule id, and what it found.

    Wrapped at word boundaries only, and the text between words is kept exactly as
    the rule wrote it - a commented-out element quoted here should read the way it
    reads in the file, and a value too long to fit stays whole on its own line.
    """
    prefix = '  - '
    budget = 100
    parts = re.split(r'(\s+)', '%s:%d %s %s'
                     % (finding.path, finding.line, finding.rule, finding.detail))
    lines = []
    cur = ''
    gap = ''
    for part in parts:
        if not part:
            continue
        if part.isspace():
            # keep the rule's own spacing so a quoted element reads as it does in
            # the file; a newline in a detail becomes one space
            gap = ' ' if '\n' in part else part
            continue
        if not cur:
            cur = part
        elif len(prefix) + len(cur) + len(gap) + len(part) <= budget:
            cur += gap + part
        else:
            lines.append(cur)
            cur = part
    if cur:
        lines.append(cur)
    return [prefix + lines[0]] + [' ' * len(prefix) + ln for ln in lines[1:]]


def _rule_counts(items):
    """rule -> how many findings, for the tally a failing run prints."""
    counts = {}
    for item in items:
        counts[item.rule] = counts.get(item.rule, 0) + 1
    return counts


def _tally_sites(items, rule):
    """Where one rule fired, as 'file:line' values, for the tally's continuations."""
    sites = ['%s:%d' % (i.path, i.line)
             for i in sorted(items, key=lambda x: (x.path, x.line)) if i.rule == rule]
    out = []
    for line in textwrap.wrap(', '.join(sites), 96):
        out.append('      ' + line)
    return out


def ant_report(path, items):
    """Append the finding counts to 'path' as ant properties.

    The build runs the checker with <exec>, and an exec failure reaches ant only
    as 'exec returned: 1', so the build log blames the ant line rather than the
    build file. A target that loads this file can fail with a message that says
    what was found.
    """
    counts = _rule_counts(items)
    tally = ', '.join('%s %d' % (rule_label(r), counts[r]) for r in sorted(counts))
    try:
        with open(path, 'a', encoding='utf-8') as fh:
            fh.write('buildfiles.check.count=%d\n' % len(items))
            fh.write('buildfiles.check.rules=%s\n' % (tally or 'none'))
    except OSError as exc:
        print('* Build file report not written: %s' % exc)


def cmd_selftest(active):
    """Prove every rule still detects its planted defect and passes its clean one."""
    broken = probe_rules(active)
    for line in broken:
        print('* FAIL %s' % line)
    print('* build file selftest: %d rule(s), %d failure(s)' % (len(active), len(broken)))
    return TOOL_FAILURE if broken else CLEAN


def main():
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument('--verbose', action='store_true', help='list every finding')
    ap.add_argument('--json', action='store_true', help='machine-readable output')
    ap.add_argument('--rule', help='only run this rule')
    ap.add_argument('--list', action='store_true', help='list the rules and exit')
    ap.add_argument('--selftest', action='store_true',
                    help='run the rule fixtures and exit')
    ap.add_argument('--ant-report', metavar='FILE',
                    help='append the finding counts to FILE as ant properties')
    ap.add_argument('paths', nargs='*', metavar='FILE',
                    help='check only these build files, with the tree as context')
    args = ap.parse_args()

    active = rules()
    if args.list:
        for rule in active:
            print('%-22s %s' % (rule.name, rule.summary))
        return CLEAN
    if args.rule:
        active = [r for r in active if r.name == args.rule]
        if not active:
            print('unknown rule: %s (try --list)' % args.rule, file=sys.stderr)
            return TOOL_FAILURE
    if args.selftest:
        return cmd_selftest(active)

    found, files = scan(active, only=args.paths or None)
    if found is None:
        return TOOL_FAILURE

    if args.json:
        print(json.dumps({'files': len(files),
                          'findings': [{'rule': f.rule, 'file': f.path,
                                        'line': f.line, 'msg': f.detail}
                                       for f in found]}, indent=2))
        return FINDINGS if found else CLEAN

    hits = len({f.path for f in found})
    print(summary_line(len(found), files, hits))
    if found:
        if args.verbose:
            for finding in sorted(found, key=lambda f: (f.path, f.line, f.rule)):
                for line in finding_lines(finding):
                    print(line)
        else:
            # The tally has to stand on its own, so each kind of defect carries the
            # files it fired on: the build log then says what to fix without a
            # second run.
            for rule, count in sorted(_rule_counts(found).items(),
                                      key=lambda kv: (-kv[1], rule_label(kv[0]))):
                print('  - %s: %d' % (rule_label(rule), count))
                for line in _tally_sites(found, rule):
                    print(line)
            print('  - Total: %d' % len(found))
        print(report_cmd())

    if args.ant_report:
        ant_report(args.ant_report, found)

    # a fresh pass every run: no baseline, so any finding at all is a failure
    return FINDINGS if found else CLEAN


if __name__ == '__main__':
    sys.exit(main())
