# User-approved Bubble Actions artifact cleanup — completed

The user explicitly authorized removing superseded build artifacts while keeping the latest verified build. Run 33998830385 / job 101393949831 completed successfully on 2026-09-05 at 23:30 UTC. It used one small maintenance job, no checkout, no Android compilation, no caches and no artifact uploads.

The job inventoried 146 repository artifacts before deletion. It matched only the two known names Bubble-rebuild-ARM64 and Bubble-runtime-evidence on rebuild-v2, repository 1345570523, older than protected run 33995262749 and created before 2026-09-05T22:13:00Z. It then re-fetched and validated each exact artifact ID before deletion.

GitHub confirmed DELETE 204 for all 31 eligible artifacts: 1,465,484,865 bytes (about 1.46 GB / 1.36 GiB). No candidates were already absent. This is the sum of the deleted API-reported artifact sizes, not an assertion that account billing/usage counters have already refreshed or that every account storage problem is solved.

The protected 0.7.1 APK archive (9977882487) and runtime evidence (9978067645) were fetched before and after cleanup and verified against their recorded digests and run ID. They remain available. No source commits, branches, signing keys, Releases, caches, workflow runs, logs, other artifact types/branches, or other repositories were deleted.

Deleted IDs: 9959364763, 9959418310, 9959503221, 9959644067, 9959705079, 9959738896, 9959833543, 9959955081, 9960040217, 9960161434, 9960262173, 9970600078, 9970692397, 9970820451, 9970915073, 9971614075, 9971671604, 9971739203, 9971869607, 9973305658, 9973402415, 9973438633, 9973576509, 9973669201, 9973802892, 9973898676, 9974026283, 9974715020, 9974868455, 9975026676, 9975173122.

The build workflow was already changed at 67571a8 to manual-only builds and Release-asset publishing rather than Actions artifact uploads. That configuration remains intact. The cleanup workflow is bounded to this repository/cutoff, defaults to preview for manual execution, and only applies on a workflow-file push carrying the explicit cleanup-approved marker. It is not an account-wide recurring purge.
