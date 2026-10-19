package com.mineagent.engine.conversation;

import com.mineagent.api.i18n.Language;
import com.mineagent.api.i18n.MessageCatalog;
import com.mineagent.api.i18n.RuntimeLanguage;

import java.util.Map;
import java.util.function.Consumer;

/** Preserve ordinary speech instead of interpreting it as a command protocol. */
public final class ConversationDelivery {
    private static final MessageCatalog TEXT = new MessageCatalog(Map.of(
            Language.EN_US, Map.of("owner", "{0} said: {1}"),
            Language.PT_BR, Map.of("owner", "{0} disse: {1}")));

    private ConversationDelivery() {}

    public static void deliverSpeech(String speaker, String text, Consumer<String> receiver) {
        receiver.accept(TEXT.text(RuntimeLanguage.language(), "owner", speaker, text));
    }
}
