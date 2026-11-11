# Plan: <short title>

- **Origin:** context | transcript | reconstructed (say from what)
- **Approved by the maintainer on:** YYYY-MM-DD
- **Branch / PR:** `<branch>` / <link when it exists>

## Goal
What is wanted and why, in a few sentences.

## Scope
What this task does.

## Out of scope
What it deliberately does not do, so nobody wonders.

## Acceptance criteria
Each one checkable by a test, a command or a concrete review; say which.
- [ ] <criterion> — checked by: <test name | command and expected result | review of X>
- [ ] An independent read-only review of the diff against the criteria above found nothing that blocks — checked by: the review report, which goes through each criterion

## Steps
- [ ] <step>

## Validation
- Commands to run and what they must show:
- Negative checks planned (break the code on purpose; the new tests must fail, then restore it):

## State model
Fill this in only if the task introduces or changes state. List the states, the transitions and the one place where the transitions are defined. If several fields would have to agree to mean one state, make the state explicit (a sealed type or an enum) instead, or justify here why a nullable or boolean flag is enough. Write `None.` if the task has no state.

## Decisions taken
Choices made and by whom (maintainer or agent), with the alternative that was left out.

## Status
Draft | Approved | In progress | Done | Abandoned | Superseded
- YYYY-MM-DD: what changed
