# Shared Source Qualification Specification

## Purpose

Require shared production and packaged-E2E coverage to preserve lane behavior while making compatibility differences explicit, bounded, and verifiable.

## Requirements

### Requirement: Shared sources with narrow compatibility overlays

The system MUST provide canonical shared production and harness sources for behavior common to the selected lanes. Loader- or version-specific overlays MUST be explicit, narrow, and limited to demonstrated incompatibilities. The migration MUST NOT adopt unrelated Quick-Skin-Mod domain code, lane scope, or advanced orchestration machinery.

Traceability: Proposal — Shared sources and non-goals; Exploration — Migration seams.

#### Scenario: Shared behavior is reconciled once

- GIVEN behavior that is compatible across two or more selected lanes
- WHEN its migration representation is reviewed
- THEN it MUST be represented in canonical shared sources rather than routine per-lane copies

#### Scenario: Compatibility difference requires an overlay

- GIVEN a demonstrated loader or version incompatibility with shared behavior
- WHEN the affected lane is qualified
- THEN its overlay MUST state the affected lane scope and preserve the applicable behavior evidence

#### Scenario: Unrelated reference machinery is proposed

- GIVEN a proposed migration element justified only by Quick-Skin-Mod architecture rather than a BlockPops lane requirement
- WHEN scope is reviewed
- THEN it MUST be excluded from this migration

### Requirement: Separate production and packaged-E2E artifacts with provenance

The system MUST produce a production JAR and a physically separate packaged-E2E harness JAR for every selected lane. Production artifacts and release selections MUST exclude harness code and resources. Each production and harness artifact MUST retain explicit provenance linking it to its matching lane and qualification inputs.

Traceability: Proposal — Artifact boundary; Exploration — Migration seams.

#### Scenario: A lane produces matching artifacts

- GIVEN a selected lane with completed artifact build inputs
- WHEN its artifacts are assembled
- THEN the output MUST contain distinct production and harness JARs whose provenance identifies that lane and their matching relationship

#### Scenario: Harness content leaks into a production artifact

- GIVEN a production artifact containing harness code or resources, or a release selection containing a harness artifact
- WHEN artifact-boundary validation runs
- THEN validation MUST fail for that lane

### Requirement: Matrix-derived packaged qualification

The system MUST derive each selected lane's production build, harness build, runtime selection, CI row, and deterministic packaged-E2E scenarios from the central matrix. Packaged-E2E qualification MUST exercise the real packaged production artifact with its matching harness and deterministic scenario assertions. Advisory visual review MUST remain separate from authoritative qualification gates.

Traceability: Proposal — Matrix-derived CI/E2E; CONTRIBUTING — Required checks and Packaged scenario changes.

#### Scenario: Every declared lane receives qualification coverage

- GIVEN a valid twelve-lane matrix
- WHEN qualification planning is generated
- THEN it MUST contain production, harness, runtime, CI, and applicable deterministic packaged-E2E coverage for every lane

#### Scenario: Runtime pairing is mismatched or a lane is skipped

- GIVEN a planned packaged-E2E execution that uses a different lane's artifact/harness or lacks a required matrix lane
- WHEN the plan is validated
- THEN validation MUST fail and identify the missing or mismatched lane

### Requirement: Migration completion requires all-lane qualification

The system MUST require every one of the twelve selected lanes to complete matrix validation, serialized production and harness builds, artifact-boundary validation, and applicable deterministic packaged-E2E qualification before the migration is declared complete. Baseline environmental failures MUST be recorded separately from migration qualification results and MUST NOT be reported as passing evidence.

Traceability: Proposal — Success criteria and risks; config.yaml — Testing evidence.

#### Scenario: One target lane remains unqualified

- GIVEN eleven lanes have completed required qualification and one selected lane has failed or unavailable evidence
- WHEN migration completion is evaluated
- THEN completion MUST be denied and the unresolved lane and limitation MUST be reported

#### Scenario: Existing host baseline blocks a check

- GIVEN Python baseline failures or the current Gradle/JDK incompatibility prevents a planned check
- WHEN qualification evidence is recorded
- THEN the record MUST distinguish the baseline limitation from migration outcomes and MUST NOT mark the blocked check as passed
