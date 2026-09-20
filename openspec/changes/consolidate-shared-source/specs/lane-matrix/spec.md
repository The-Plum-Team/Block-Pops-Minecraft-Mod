# Lane Matrix Specification

## Purpose

Define one authoritative, twelve-lane inventory from which lane-aware migration consumers obtain identity and qualification inputs, without treating historical evidence as support or publication proof.

## Requirements

### Requirement: Exact target inventory

The system MUST define exactly these migration-target lanes: Minecraft 1.20.1 on Fabric and Forge; and Minecraft 1.21.1, 1.21.4, 1.21.5, 1.21.6, and 1.21.7 each on Fabric and NeoForge. It MUST reject a missing, duplicate, or additional target lane.

Traceability: Proposal — Product scope and evidence; Exploration — Product scope decision.

#### Scenario: Complete target inventory is accepted

- GIVEN a matrix containing each of the twelve selected version/loader pairs exactly once
- WHEN its target inventory is validated
- THEN validation MUST identify exactly twelve lanes and accept the inventory

#### Scenario: Target inventory is incomplete or divergent

- GIVEN a matrix that omits, duplicates, or adds a selected version/loader pair
- WHEN its target inventory is validated
- THEN validation MUST fail and identify the divergent lane identity

### Requirement: Central source of truth and derived consumers

The system MUST make the central matrix authoritative for each lane's identity, source routing, dependency and toolchain choices, production and harness task/output identities, runtime and CI coverage, and lane-specific release identity/versioning. Build, artifact, release-selection, CI, and packaged-E2E consumers MUST derive lane identity from that matrix and MUST reject inconsistent lane data rather than maintain divergent inventories.

Traceability: Proposal — In scope / Central matrix; CONTRIBUTING — Source and release ownership.

#### Scenario: Consumers resolve a declared lane consistently

- GIVEN a valid declared lane in the central matrix
- WHEN build, artifact, release-selection, CI, and packaged-E2E planning resolve that lane
- THEN each consumer MUST use the matrix-defined lane identity and associated values

#### Scenario: A consumer presents unrepresented or inconsistent lane data

- GIVEN consumer lane data that is absent from or conflicts with the central matrix
- WHEN the consumer plan is validated
- THEN validation MUST fail before that lane is qualified or selected for release

### Requirement: Evidence and support status remain distinct

The system MUST represent migration-target inclusion, historical implementation/configuration evidence, qualification status, support status, and publication status as distinct facts. Historical-ref source or configuration evidence MUST NOT by itself mark a lane qualified, supported, built, packaged-E2E verified, or published.

Traceability: Proposal — Product scope and evidence; Exploration — Evidence boundary.

#### Scenario: Later lane has historical configuration evidence only

- GIVEN a selected 1.21.4 through 1.21.7 lane with historical source/configuration evidence but no completed qualification evidence
- WHEN its status is reported
- THEN it MUST remain a migration target with unresolved qualification and MUST NOT be reported as supported or published

#### Scenario: Qualification evidence is unavailable

- GIVEN a selected lane whose required qualification cannot be completed
- WHEN migration status is reported
- THEN the lane MUST remain visibly unresolved and MUST NOT be silently removed or counted as complete

### Requirement: Per-era loader and execution isolation

The system MUST represent lane-specific Java/toolchain and loader dependency/repository contexts. Forge and NeoForge contexts MUST be isolated per applicable lane; a lane MUST NOT activate both. Lane build execution MUST be serialized: no concurrent lane Gradle processes and no parallel Gradle execution.

Traceability: Proposal — Build isolation and Serialized execution; Exploration — Toolchain and checks; CONTRIBUTING — Source and release ownership.

#### Scenario: Serialized mixed-era build plan

- GIVEN a plan containing Forge and NeoForge lanes with distinct Java-era requirements
- WHEN the aggregate lane build plan is generated
- THEN it MUST schedule one lane Gradle execution at a time with parallel Gradle execution disabled and retain each lane's declared context

#### Scenario: A lane combines incompatible loader contexts

- GIVEN a lane configuration that activates Forge and NeoForge together or borrows the other loader family's repository context
- WHEN the configuration is validated
- THEN validation MUST fail before build execution
