# Release Transition Specification

## Purpose

Preserve existing delivery protections during the shared-source migration while enabling independently planned releases for individually qualified lanes.

## Requirements

### Requirement: Independent qualified-lane release planning

The system MUST allow an individually qualified lane to be selected, identified, and versioned for release without requiring unrelated lanes to be selected or published. Lane release planning MUST fail closed when required evidence for the selected lane is missing. Release selection or planning MUST NOT constitute publication authorization or allow omission of another migration target from completion requirements.

Traceability: Proposal — Independent releases and Success criteria.

#### Scenario: One qualified lane is planned independently

- GIVEN one selected lane has complete required qualification and another selected lane remains unqualified
- WHEN the qualified lane is planned for release
- THEN its release identity and versioning MAY be planned independently without publishing the other lane

#### Scenario: Selected release lane lacks required evidence

- GIVEN a lane is selected for release planning without complete required qualification or artifact provenance
- WHEN release planning is validated
- THEN planning MUST fail closed and identify the missing evidence

### Requirement: Protected policy and trusted gates remain effective

The system MUST preserve strict checksum verification, repository-origin isolation, loader-bootstrap contract review, and authenticated exact-head Build and Packaged-E2E protections throughout the migration. Unpublished local implementation and tests MAY prepare restricted-surface changes under the user's migration authorization, but local readiness MUST NOT be treated as foundation-deployment or delivery authority. The initial controller foundation MUST NOT authorize its own deployment. Later restricted-path admission MUST use a foundation independently observed as active plus fresh authenticated base, controller-generation, candidate-head, owner-decision, and required-gate evidence.

Traceability: Proposal — Safety and delivery gates; Authorization plan — Initial foundation/bootstrap and controller-governed transitions.

#### Scenario: Authorized restricted work remains unpublished

- GIVEN the user authorized local migration implementation on the existing feature branch
- WHEN code, tests, or configuration for a restricted surface are developed locally
- THEN the work MAY proceed while remaining unpublished and MUST NOT be represented as admitted, deployed, or authorized for delivery

#### Scenario: Initial foundation candidate requests delivery

- GIVEN the live default branch does not contain the proposed controller foundation
- WHEN the exact reviewed foundation candidate is considered for delivery
- THEN it MUST require fresh governance observation and explicit human authority for that exact commit and action
- AND it MUST NOT rely on its candidate evaluator, an automatic direct push or PR, disabled protection, or a reusable bypass

#### Scenario: Restricted transition follows deployed foundation

- GIVEN the foundation was independently admitted and observed active on the exact live default branch
- WHEN a later matrix, verification-metadata, or version-specific transition is considered for delivery
- THEN the deployed evaluator MUST authenticate the exact base, controller generation, candidate head, owner decision, required graph, and newest gate evidence

#### Scenario: Candidate delivery weakens a trusted protection

- GIVEN a candidate transition removes or weakens a required checksum, repository-origin, bootstrap, Build, or Packaged-E2E protection
- WHEN the transition is evaluated
- THEN it MUST be rejected

### Requirement: Historical preservation and gated sync retirement

The system MUST preserve historical refs and existing user changes during migration. It MUST retain `.github/workflows/sync-release-branches.yml` until all twelve target lanes have validated build and packaged-E2E paths, equivalent replacement protections have been validated, and retirement is separately authorized. Sync retirement MUST NOT occur automatically as a consequence of qualification, release planning, or migration completion.

Traceability: Proposal — Controlled transition, rollback, and non-goals; Exploration — Migration seams.

#### Scenario: Replacement is not fully validated

- GIVEN one target lane or an equivalent replacement protection is not validated
- WHEN sync-workflow retirement is considered
- THEN the existing synchronization workflow MUST remain in place

#### Scenario: All replacement evidence exists but authorization is absent

- GIVEN all lanes and equivalent protections are validated but no separate retirement authorization exists
- WHEN sync-workflow retirement is considered
- THEN retirement MUST remain blocked

### Requirement: Delivery and remote facts remain explicitly limited

The system MUST record the authenticated live default branch at `35c72713ccdb94205df4bc5cbafcd5264dd64377` while distinguishing observed facts from API limitations. A branch response of `protected: false` and embedded required-status enforcement off MUST NOT be treated as conclusive absence of protection or rulesets when dedicated queries return HTTP 403 upgrade-required. Actions workflow metadata MUST NOT be treated as proof that workflow YAML is deployed at the exact live SHA. Delivery or cutover MUST use fresh observations; these limitations MUST NOT block authorized unpublished local implementation.

Traceability: Proposal — Safety and delivery gates; Exploration — Remote/default evidence.

#### Scenario: Local implementation proceeds with governance-query limitations

- GIVEN the live default SHA is known but complete protection/ruleset evidence is unavailable because dedicated endpoints return HTTP 403
- WHEN authorized unpublished local implementation is evaluated
- THEN it MAY proceed while recording that no delivery authority or complete governance conclusion was established

#### Scenario: Foundation delivery is requested without fresh governance evidence

- GIVEN live governance may have changed or remains only partly observable
- WHEN initial foundation delivery or cutover is requested
- THEN the action MUST remain blocked pending fresh observation and explicit human authority for the exact candidate and action
