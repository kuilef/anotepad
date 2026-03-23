#!/usr/bin/env python3
"""
Explore editor restore scenarios without Gradle or an Android device.

The model intentionally mirrors the navigation/editor behavior in this repo:
- `new_note` opens editor without a file binding.
- first `autosave` creates a real file and binds `fileUri` in memory.
- legacy behavior does not persist that binding for task restoration.
- patched behavior persists the binding in the editor back stack entry.

The script enumerates action sequences and reports:
- paths where Android recreation restores the editor as `new note` even though
  the note already has a saved file.
- paths where the next autosave would create a duplicate file such as `(2).txt`.
"""

from __future__ import annotations

import argparse
from collections import deque
from dataclasses import dataclass, replace
from typing import Iterable


BROWSER = "browser"
EDITOR_NEW = "editor_new"
EDITOR_FILE = "editor_file"
EDITOR_SHARED = "editor_shared"
TEMPLATES = "templates"
SETTINGS = "settings"

ORIGIN_NONE = "none"
ORIGIN_NEW = "new"
ORIGIN_FILE = "file"
ORIGIN_SHARED = "shared"

MODE_LEGACY = "legacy"
MODE_PATCHED = "patched"

ACTIONS = (
    "new_note",
    "open_file",
    "open_shared_draft",
    "autosave",
    "rename",
    "open_templates",
    "open_settings",
    "close_overlay",
    "back_to_browser",
    "recreate",
)


@dataclass(frozen=True)
class ScenarioState:
    stack: tuple[str, ...] = (BROWSER,)
    origin: str = ORIGIN_NONE
    current_file_bound: bool = False
    persisted_file_bound: bool = False
    original_saved_file_exists: bool = False
    duplicate_created: bool = False

    @property
    def top(self) -> str:
        return self.stack[-1]

    @property
    def base(self) -> str:
        if not self.stack:
            return BROWSER
        return self.stack[0]

    @property
    def in_editor(self) -> bool:
        return self.base in {EDITOR_NEW, EDITOR_FILE, EDITOR_SHARED}

    @property
    def overlay(self) -> str | None:
        return self.stack[1] if len(self.stack) > 1 else None


@dataclass(frozen=True)
class ScenarioFinding:
    category: str
    actions: tuple[str, ...]
    state_before: ScenarioState
    state_after: ScenarioState


def step(state: ScenarioState, action: str, mode: str) -> ScenarioState | None:
    if action == "new_note" and state.top == BROWSER:
        return ScenarioState(stack=(EDITOR_NEW,), origin=ORIGIN_NEW)

    if action == "open_file" and state.top == BROWSER:
        return ScenarioState(
            stack=(EDITOR_FILE,),
            origin=ORIGIN_FILE,
            current_file_bound=True,
            persisted_file_bound=True,
            original_saved_file_exists=True,
        )

    if action == "open_shared_draft" and state.top == BROWSER:
        return ScenarioState(stack=(EDITOR_SHARED,), origin=ORIGIN_SHARED)

    if action == "autosave" and state.in_editor and state.overlay is None:
        if state.base in {EDITOR_NEW, EDITOR_SHARED} and not state.current_file_bound:
            duplicate_created = state.duplicate_created or state.original_saved_file_exists
            persisted = True if mode == MODE_PATCHED else state.persisted_file_bound
            return replace(
                state,
                current_file_bound=True,
                persisted_file_bound=persisted,
                original_saved_file_exists=True,
                duplicate_created=duplicate_created,
            )
        return state

    if action == "rename" and state.in_editor and state.current_file_bound and state.overlay is None:
        persisted = True if mode == MODE_PATCHED else state.persisted_file_bound
        return replace(state, persisted_file_bound=persisted)

    if action == "open_templates" and state.in_editor and state.overlay is None:
        return replace(state, stack=(state.base, TEMPLATES))

    if action == "open_settings" and state.in_editor and state.overlay is None:
        return replace(state, stack=(state.base, SETTINGS))

    if action == "close_overlay" and state.overlay is not None:
        return replace(state, stack=(state.base,))

    if action == "back_to_browser" and state.in_editor and state.overlay is None:
        return ScenarioState(stack=(BROWSER,))

    if action == "recreate":
        return recreate_state(state)

    return None


def recreate_state(state: ScenarioState) -> ScenarioState:
    if not state.in_editor:
        return state

    if state.base == EDITOR_FILE:
        restored_base = EDITOR_FILE
        current_file_bound = True
        persisted_file_bound = True
    elif state.persisted_file_bound:
        restored_base = EDITOR_FILE
        current_file_bound = True
        persisted_file_bound = True
    elif state.base == EDITOR_SHARED:
        restored_base = EDITOR_NEW
        current_file_bound = False
        persisted_file_bound = False
    else:
        restored_base = state.base
        current_file_bound = False
        persisted_file_bound = False

    restored_stack = (restored_base,)
    if state.overlay is not None:
        restored_stack = (restored_base, state.overlay)

    return ScenarioState(
        stack=restored_stack,
        origin=state.origin,
        current_file_bound=current_file_bound,
        persisted_file_bound=persisted_file_bound,
        original_saved_file_exists=state.original_saved_file_exists,
        duplicate_created=state.duplicate_created,
    )


def find_scenarios(max_depth: int, mode: str) -> list[ScenarioFinding]:
    findings: list[ScenarioFinding] = []
    queue = deque([(ScenarioState(), tuple())])
    seen = set()

    while queue:
        state, path = queue.popleft()
        if len(path) >= max_depth:
            continue

        for action in ACTIONS:
            next_state = step(state, action, mode)
            if next_state is None:
                continue

            next_path = path + (action,)
            signature = (next_state, next_path)
            if signature in seen:
                continue
            seen.add(signature)

            if (
                action == "recreate"
                and state.original_saved_file_exists
                and state.base in {EDITOR_NEW, EDITOR_SHARED}
                and not state.persisted_file_bound
                and next_state.base == EDITOR_NEW
                and not next_state.current_file_bound
            ):
                findings.append(
                    ScenarioFinding(
                        category="restore_as_new_note",
                        actions=next_path,
                        state_before=state,
                        state_after=next_state,
                    )
                )

            if not state.duplicate_created and next_state.duplicate_created:
                findings.append(
                    ScenarioFinding(
                        category="duplicate_file_created",
                        actions=next_path,
                        state_before=state,
                        state_after=next_state,
                    )
                )

            queue.append((next_state, next_path))

    return findings


def summarize(findings: Iterable[ScenarioFinding], limit: int) -> str:
    restore_paths = []
    duplicate_paths = []
    for finding in findings:
        rendered = " -> ".join(finding.actions)
        if finding.category == "restore_as_new_note":
            restore_paths.append(rendered)
        elif finding.category == "duplicate_file_created":
            duplicate_paths.append(rendered)

    lines = [
        f"restore_as_new_note: {len(restore_paths)}",
        f"duplicate_file_created: {len(duplicate_paths)}",
    ]
    if restore_paths:
        lines.append("shortest restore_as_new_note paths:")
        lines.extend(f"  - {path}" for path in sorted(restore_paths, key=len)[:limit])
    if duplicate_paths:
        lines.append("shortest duplicate_file_created paths:")
        lines.extend(f"  - {path}" for path in sorted(duplicate_paths, key=len)[:limit])
    return "\n".join(lines)


def verify_expectations(max_depth: int) -> tuple[bool, str]:
    legacy_findings = find_scenarios(max_depth=max_depth, mode=MODE_LEGACY)
    patched_findings = find_scenarios(max_depth=max_depth, mode=MODE_PATCHED)

    legacy_duplicates = [f for f in legacy_findings if f.category == "duplicate_file_created"]
    patched_duplicates = [f for f in patched_findings if f.category == "duplicate_file_created"]
    legacy_restores = [f for f in legacy_findings if f.category == "restore_as_new_note"]
    patched_restores = [f for f in patched_findings if f.category == "restore_as_new_note"]

    ok = bool(legacy_duplicates) and bool(legacy_restores) and not patched_duplicates and not patched_restores
    details = [
        f"legacy restore_as_new_note={len(legacy_restores)} duplicate_file_created={len(legacy_duplicates)}",
        f"patched restore_as_new_note={len(patched_restores)} duplicate_file_created={len(patched_duplicates)}",
    ]
    return ok, "\n".join(details)


def main() -> int:
    parser = argparse.ArgumentParser(description="Explore editor restore scenarios.")
    parser.add_argument("--max-depth", type=int, default=6, help="Maximum sequence length to explore.")
    parser.add_argument("--limit", type=int, default=8, help="Number of shortest paths to print per category.")
    parser.add_argument(
        "--mode",
        choices=(MODE_LEGACY, MODE_PATCHED, "compare"),
        default="compare",
        help="Which model to inspect.",
    )
    parser.add_argument(
        "--verify",
        action="store_true",
        help="Exit non-zero if the patched model still contains vulnerable paths.",
    )
    args = parser.parse_args()

    if args.mode == "compare":
        legacy_findings = find_scenarios(max_depth=args.max_depth, mode=MODE_LEGACY)
        patched_findings = find_scenarios(max_depth=args.max_depth, mode=MODE_PATCHED)
        print("== LEGACY MODEL ==")
        print(summarize(legacy_findings, limit=args.limit))
        print()
        print("== PATCHED MODEL ==")
        print(summarize(patched_findings, limit=args.limit))
    else:
        findings = find_scenarios(max_depth=args.max_depth, mode=args.mode)
        print(f"== {args.mode.upper()} MODEL ==")
        print(summarize(findings, limit=args.limit))

    if args.verify:
        ok, details = verify_expectations(max_depth=args.max_depth)
        print()
        print("== VERIFICATION ==")
        print(details)
        return 0 if ok else 1

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
