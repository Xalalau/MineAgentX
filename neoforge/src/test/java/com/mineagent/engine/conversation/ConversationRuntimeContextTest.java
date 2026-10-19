package com.mineagent.engine.conversation;

import com.mineagent.api.i18n.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

final class ConversationRuntimeContextTest {
    @ParameterizedTest @ValueSource(strings={"en_us", "pt_br"})
    void storedAndObservedContextIsNotGloballyRewrittenInRequest(String language) throws Exception {
        RuntimeLanguage.configure(language);
        try (var fixture = new ConversationRuntimeFixture()) {
            String external = "Historic words [TEAM] [BODY] [TASK_FINISHED] {0} $\\ literal";
            fixture.loop.cognitiveState().append("observed", "fixture", external, 2);
            fixture.recordRequest();
            String live = fixture.request.messages().getLast().content();
            assertTrue(live.contains(external), "context must preserve external/history tokens; language is chosen by trusted producers only");
        } finally { RuntimeLanguage.configure("en_us"); }
    }
}
