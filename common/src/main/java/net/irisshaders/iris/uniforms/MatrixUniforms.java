package net.irisshaders.iris.uniforms;

import org.joml.Matrix4f;

/**
 * Compile-only stub for the soft Iris probe mixin.
 *
 * Never packaged: it exists only so Mixin's annotation processor can resolve
 * {@code net.irisshaders.iris.uniforms.MatrixUniforms} at build time. At runtime the real
 * Oculus class is used; the mixin plugin skips this mixin when Oculus is absent.
 */
public final class MatrixUniforms {
    /** 占位内部类（永不执行：本类不进包）；mixin 靠它定位真实 Oculus 方法。 */
    public static final class Previous {
        private Previous() {
        }

        /** 占位签名，仅满足编译期类型解析。 */
        public Matrix4f get() {
            return new Matrix4f();
        }
    }
}
