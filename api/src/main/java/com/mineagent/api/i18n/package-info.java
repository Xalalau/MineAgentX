/**
 * Explicit English/Brazilian Portuguese runtime localization, separate from Minecraft's client locale.
 *
 * <p>The server selects {@link com.mineagent.api.i18n.RuntimeLanguage#configure(String)} after
 * loading configuration. Long-running requests should capture one
 * {@link com.mineagent.api.i18n.Language} and pass it explicitly to every producer so a reload
 * cannot mix languages within a request. Rendering does not depend on the JVM locale.</p>
 *
 * <p>{@link com.mineagent.api.i18n.MessageCatalog} requires every supported language, identical
 * nonblank keys, and identical contiguous argument positions. Its numbered arguments are
 * inserted literally in one pass. Missing translations and argument-count errors throw;
 * no English fallback exists. Feature owners may supply their own immutable catalogs.
 * {@link com.mineagent.api.i18n.Messages} contains only shared boundary messages, not a
 * translation table for the inherited engine or every registered tool.</p>
 *
 * <p>Persist {@link com.mineagent.api.i18n.LocalizedMessage} keys and arguments for new trusted
 * system events, alongside stable semantic codes and technical data. Render only at the
 * model/player boundary. Do not infer control flow by searching translated prose. Player
 * text, historical memories, names, arbitrary model replies, and unknown external text
 * remain verbatim; {@link com.mineagent.api.i18n.ModelText#external} supplies localized,
 * JSON-quoted framing, not translation or an instruction-injection security guarantee.</p>
 *
 * <p>{@link com.mineagent.api.i18n.ToolSchemaLocalizer} replaces only registered description/title
 * metadata in a copied schema. Explicit RFC 6901 pointers must cover that metadata exactly.
 * Function names, property names, required lists, enums, defaults, and other technical data
 * are not translated. Tool owners must provide real bilingual prose; wrapping an English
 * tool result in Portuguese is not full localization.</p>
 *
 * <p>Adopting this package does not by itself migrate existing prompts, observations,
 * task results, skill documents, memories, or player-facing literals. Migrate their
 * producers and any consumers of old prose together, then verify actual outbound provider
 * requests in both languages. Minecraft UI resources are selected by each client's locale,
 * independently of the server's model-input language.</p>
 */
package com.mineagent.api.i18n;
