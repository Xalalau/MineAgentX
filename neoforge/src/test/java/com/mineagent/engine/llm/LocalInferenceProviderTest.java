package com.mineagent.engine.llm;

import org.junit.jupiter.api.Test;

/** JUnit entry points for the same suites exercised directly with the standalone JDK runner. */
class LocalInferenceProviderTest {
    @Test void localBonsaiDefaults() { LocalBonsaiProviderTest.defaultsUseLocalBonsai(); }
    @Test void optionalLocalCredentials() { LocalBonsaiProviderTest.providerAllowsUnauthenticatedLocalServer(); }
    @Test void localAndPrivateLanEndpointsOnly() { LocalBonsaiProviderTest.restrictsEndpointsToLocalNetwork(); }
    @Test void bonsaiNativeToolRoundTrip() throws Exception { LocalBonsaiProviderTest.preservesNativeToolCallRoundTrip(); }
    @Test void noCloudFactories() { LocalBonsaiProviderTest.exposesNoCloudFactories(); }
    @Test void automaticServerReasoning() { LocalBonsaiProviderTest.modelHintsPreserveServerPolicy(); }
    @Test void ollamaNativeToolRoundTrip() throws Exception { LocalOllamaProviderTest.preservesNativeToolCallRoundTrip(); }
    @Test void incompleteOllamaResponsesRejected() { LocalOllamaProviderTest.rejectsIncompleteResponses(); }
    @Test void uniqueOllamaCallIds() { LocalOllamaProviderTest.assignsUniqueMissingIds(); }
    @Test void ollamaModelMustBeConfigured() {
        LocalBonsaiProviderTest.check(new OllamaNativeProvider().defaultModels().isEmpty(),
                "Ollama model alias must be chosen by the user");
    }
}
