package net.irisshaders.iris.pipeline;

/**
 * Compile-only stub for the soft Oculus mixin target.
 *
 * <p>Never packaged: it exists only so Mixin's annotation processor can resolve
 * {@code net.irisshaders.iris.pipeline.CompositeRenderer} at build time. At runtime the real
 * Oculus class is used; the mixin plugin skips this mixin when Oculus is absent.</p>
 */
public class CompositeRenderer {
    public void renderAll() {
        // stub
    }
}
