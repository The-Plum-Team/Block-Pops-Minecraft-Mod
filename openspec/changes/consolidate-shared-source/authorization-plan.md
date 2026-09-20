# AUTH-1 authorization procedure proposal

> **DRAFT / NOT DELIVERY AUTHORIZATION**
>
> This planning record distinguishes authorized local migration work from published admission. It does not authorize a push, pull request, merge, release, direct default-branch update, protection change, or controller decision.

## Decision

Use a two-phase governance transition:

1. **Initial foundation/bootstrap:** develop and review the missing controller foundation locally, then deliver one exact reviewed foundation commit only under future explicit human delivery authority and after an independent recheck of the repository's actual governance.
2. **Controller-governed transitions:** only after that foundation is deployed and observed on the exact live default branch may later restricted-path candidates use its authenticated, exact-head decisions.

The initial foundation cannot approve its own deployment. There is no automatic direct push or PR, no protection-disabling route, and no reusable bootstrap bypass. This proposal defines planning constraints only; the implementation must still specify and test the schema and evaluator behavior.

Local code, tests, and configuration may be developed **unpublished** on the existing feature branch under the user's migration and conventional work-unit commit authorization. That local readiness is not initial-bootstrap authority, delivery authority, or evidence that candidate policy is active. Candidate trust protections must remain fail-closed while the future evaluator transition is implemented and tested.

## Current state and evidence limits

The authenticated read-only recheck after switching `gh` to `AkaNebur` resolved the earlier HTTP 404 as an account-access problem:

- `gh repo view AkaNebur/BlockPops` reports a private repository, viewer permission `ADMIN`, and default branch `master`.
- `git ls-remote` resolves live `master` to `35c72713ccdb94205df4bc5cbafcd5264dd64377`.
- The branch endpoint reports `protected: false` and embedded required-status enforcement off.
- The dedicated branch-protection and rulesets endpoints return HTTP 403 with an upgrade-required response. Therefore complete protection/ruleset absence is **not proven**, and the repository must not be described conclusively as unprotected.
- The exact recursive tree at `35c7271` is complete (`truncated: false`) and contains no `.github/*`, `scripts/ci/pr_gate.py`, `CONTRIBUTING.md`, or `docs/operations.md`; exact-SHA content requests also return 404 for those paths.
- The Actions workflows API lists `Build gate` and `Packaged E2E` metadata, but that metadata is not proof that workflow YAML or a default-branch gate is deployed at the exact live SHA.
- The environments endpoint reports `total_count: 0`.
- Feature commit `a200152` contains the proposed strict controller and policy documentation, but those candidate bytes are not deployed live authority.

The historical 404 remains relevant evidence but is resolved for current access; do not repeat the account question. Fresh delivery-time observation is still required because repository governance can change and the 403 responses leave ruleset coverage unknown.

## Phase A — initial foundation/bootstrap

### Purpose and boundary

Prepare the smallest reviewed foundation that can later enforce bounded restricted-path transitions. Development may occur locally, but publication and admission remain pending.

Before any foundation delivery, the responsible human must explicitly authorize the exact reviewed foundation commit and chosen delivery action. Immediately before that action, independently verify:

1. live default branch and exact base SHA;
2. branch protection, rulesets, required checks, environments, and available admission paths to the extent the host/API permits;
3. the exact candidate head/tree and authored-line budget;
4. that the candidate contains no migration payload hidden inside its authority implementation; and
5. that no candidate-owned policy is being treated as already active.

If governance remains partly unknowable, record that limitation and use only a separately explicit human decision for the exact foundation candidate and action. `ADMIN` permission, an apparently unprotected branch, this draft, local commits, or a successful local test does not substitute for that decision.

### Foundation implementation surface

The locally developed foundation should be limited to the smallest demonstrated set:

- `scripts/ci/pr_gate.py`
- `scripts/ci/tests/test_pr_gate.py`
- `docs/operations.md`
- `CONTRIBUTING.md`

Include these only when focused evidence proves they are needed:

- `scripts/ci/gate_controller.py`
- `scripts/ci/tests/test_gate_controller.py`

Keep loader-bootstrap work separate. A later contract-policy unit may contain only:

- `scripts/ci/loader_bootstrap.py`
- `scripts/ci/tests/test_loader_bootstrap.py`

The foundation candidate must leave the release matrix, verification metadata, `VanillaShim.java`, `pack.mcmeta`, product source, and every undeclared path unchanged. It must not contain Phase B migration bytes or candidate-controlled authority.

### Required policy properties

The foundation design must:

1. name every permitted restricted path exactly; forbid wildcards, directory grants, inferred paths, and caller-expanded scope;
2. keep static transition scope and controller-generation constraints in trusted deployed content;
3. obtain the actual base and candidate head from authenticated repository state and require a separate runtime owner decision bound to the exact candidate head;
4. ensure candidate content cannot create, widen, replace, or interpret its own authority;
5. authenticate expected Build and Packaged E2E graphs, newest exact-head attempts, and immutable artifacts rather than trusting candidate declarations;
6. fail closed for unknown schemas/generations, missing or stale evidence, newer pending/failed gates, and source/merge identity mismatch; and
7. model revocation and rollback as fresh authorized transitions, never resets, direct-push conventions, disabled protections, or reusable bypasses.

### Required tests

Before foundation delivery is considered, focused tests must reject:

- stale, revoked, edited, deleted, wrong-owner, or different-head decisions;
- extra/wildcard/ambiguous paths and undeclared restricted files;
- candidate-controlled evaluator logic, declarations, expected graphs, or evidence selection;
- missing, pending, failed, stale, mixed, or mismatched gate evidence;
- unknown schemas/generations, malformed declarations, and duplicate records;
- symlinks, submodules, special files, and executable-mode additions; and
- combining loader executable-byte changes with the contract transition that first authorizes them.

Positive tests must bind a trusted static scope and observed controller generation to authenticated base/candidate identities plus a separate exact-head owner decision. Existing rejection behavior must remain covered. Local green tests demonstrate readiness only; they do not authorize delivery.

### Foundation delivery checks

Each review slice, including tests and documentation, must be at most 400 authored additions plus deletions. Split enforcement, optional controller-model changes, and documentation into independently safe units where possible.

Phase A is complete only when the exact foundation commit has been explicitly authorized for delivery, admitted without using its own candidate policy, and independently observed as active on the exact live default branch. No automatic direct push, PR, merge, or follow-on transition is implied.

## Phase B — controller-governed restricted transitions

Phase B may begin only after Phase A is deployed and independently verified active against fresh governance observations. The deployed base-owned controller may then evaluate one exact restricted-path declaration and a separate authenticated owner decision bound to the actual candidate head.

Each Phase B unit must:

- contain no undeclared path or candidate-authored authority;
- use the deployed base evaluator and its authenticated controller generation;
- preserve required contexts and newest exact-head evidence;
- stay at or below the 400-line review budget;
- make no publication, PR, merge, or release automatic; and
- require a fresh exact-head decision after every head change.

A deployed foundation does not grant blanket migration or delivery authority. Every restricted transition remains independently bounded and authenticated.

## Loader-bootstrap sequence

Loader bootstrap remains contract-first and separate from general path admission:

1. Through the deployed foundation, authorize a controller transition that accepts bounded `current` + `next` contracts while executable bytes remain identical.
2. After that generation is active, separately authorize the exact-head candidate that changes only the declared loader bytes to exact `next` and collapses the contract to one current digest.

Do not invent a future digest under a single-digest parser, combine both transitions, or leave permanent dual acceptance.

## Stop, revocation, and rollback

Stop delivery when the exact live governance cannot be observed sufficiently for the proposed action, the foundation candidate depends on its own new policy, candidate bytes can widen authority, authenticated graph/artifact/base/head binding is missing, or a cohesive unit exceeds the 400-line limit without an explicit exception.

Revocation requires a fresh authenticated decision. Rollback is another reviewed, explicitly authorized commit or deployed-controller transition. Never reset user work, disable protections, rewrite history, or reuse evidence from another base, tree, matrix, contract, or head.

These delivery stop conditions do not prohibit authorized unpublished local implementation and verification. Local work must remain clearly labeled non-deployed and must not alter `.gitignore` or `.pi`.

## Evidence required to resolve AUTH-1

Task 1 remains open until evidence records:

- the exact reviewed Phase A foundation commit and its test results;
- fresh observed governance and the explicit human authority for its exact delivery action;
- admission of that foundation without candidate self-authorization;
- proof that the foundation generation is active on the exact current default branch;
- for each Phase B transition, the exact declared paths, base/controller generation, candidate head, authenticated owner decision, newest required gate evidence, and rollback boundary.

Until then, AUTH-1 is unresolved for **published admission and delivery**. This does not block the user's already authorized local migration implementation, tests, configuration, or conventional work-unit commits on the existing feature branch. It creates no branch, commit, push, PR, merge, release, or runtime gate result.
