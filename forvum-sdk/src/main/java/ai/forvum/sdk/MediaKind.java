package ai.forvum.sdk;

/**
 * The kind of media a {@link GenerationProvider} turns a prompt into (#187): the closed set the three
 * model-callable generation tools ({@code image.generate} / {@code video.generate} /
 * {@code music.generate}) span. A backend declares which kinds it supports via
 * {@link GenerationProvider#supportedKinds()}; a tool requesting an unsupported kind gets an actionable
 * "backend does not support" error instead of a call.
 */
public enum MediaKind {
    IMAGE,
    VIDEO,
    MUSIC
}
