package com.mineagent.construction;

import org.junit.jupiter.api.Test;

/** Registered regression entry points; each runs real pure subsystem behavior. */
public final class ConstructionSubsystemTest {
    @Test void validatesCoordinatesAndTransforms() { ConstructionChecks.geometry(); }
    @Test void persistsRevisionHistoryAndObservedEvidence() throws Exception { ConstructionChecks.persistence(); }
    @Test void assessesActualSpaceMaterialsTerrainAndPermissions() { ConstructionChecks.assessment(); }
    @Test void rendersSixDeterministicHeadlessPngViews() throws Exception { ConstructionChecks.renderer(); }
    @Test void dispatchesProgressivelyAndVerifiesWorldInsteadOfTaskClaims() throws Exception { ConstructionChecks.execution(); }
    @Test void exposesLocalizedVersionedToolAndOptionalImages() throws Exception { ConstructionChecks.tool(); }
    @Test void boundsBatchesRecoversRestartAndComparesCandidateSites() throws Exception { ConstructionChecks.boundedBatchesAndRestart(); }
    @Test void keepsCompanionStorageIdentityAcrossRestarts() { ConstructionChecks.bridgeIdentity(); }
}
