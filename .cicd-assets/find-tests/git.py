import re
import subprocess
import os
import sys

ZERO_SHA = "0000000000000000000000000000000000000000"


class BaselineUnresolvableError(Exception):
    """Raised when the requested baseline ref cannot be resolved to a commit."""


def get_current_branch():
    return subprocess.check_output(['git', 'rev-parse', '--abbrev-ref', 'HEAD']).decode('utf-8').strip()


def get_parent_branch(repo_path):
    parent_branch = None
    with open(os.path.join(repo_path, ".nightly"), "r") as nightly:
        for line in nightly.readlines():
            match = re.match(r'parent_branch: (.*)$', line, re.M | re.I)
            if match:
                parent_branch = match.group(1)
    if parent_branch is None:
        raise ValueError("Could not find 'parent_branch:' entry in .nightly file")
    return parent_branch.strip()


def _resolve_or_raise(ref):
    if ref == ZERO_SHA or not ref:
        raise BaselineUnresolvableError("baseline ref is empty or zero SHA: %r" % ref)
    try:
        subprocess.check_output(['git', 'rev-parse', '--verify', ref + '^{commit}'],
                                stderr=subprocess.DEVNULL)
    except subprocess.CalledProcessError:
        raise BaselineUnresolvableError("git rev-parse failed for baseline ref: %r" % ref)


def get_changed_files(repo_path, baseline_ref=None):
    if baseline_ref is not None:
        _resolve_or_raise(baseline_ref)
        compare_to = baseline_ref
        print("Comparing HEAD to baseline ref: %s" % baseline_ref)
    else:
        parent_branch = get_parent_branch(repo_path)
        current_branch = get_current_branch()
        print("Comparing: %s to: %s" % (current_branch, parent_branch))
        compare_to = 'origin/' + parent_branch

    try:
        files_changed_in_commits = subprocess.check_output(
            ['git', 'diff', '--name-only', compare_to + '...HEAD']) \
            .decode('utf-8') \
            .split('\n')
        files_changed_in_tree = subprocess.check_output(['git', 'diff', '--name-only']) \
            .decode('utf-8') \
            .split('\n')
    except subprocess.SubprocessError as e:
        print("Some problems occurred with: ", e.cmd, file=sys.stderr)
        print("Return code: ", e.returncode, file=sys.stderr)
        with open("error.log", "w") as errorFile:
            errorFile.write(e.output.decode("utf-8"))
            print("Check error.log for details", file=sys.stderr)
        raise e

    files_changed = set(files_changed_in_commits + files_changed_in_tree)
    return list(os.path.join(repo_path, f) for f in files_changed if f != "" and not f.isspace())
