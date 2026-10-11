package com.immersivecinematics.immersive_cinematics.math;

import java.util.List;

/**
 * chain — 串联组合：按数组顺序逐个施加子模型（{@code out = mₙ(…m₂(m₁(in)))}）。
 *
 * 参数：{@code models} = 子定义数组（子模型域必须与父定义一致；不一致的子模型在创建期回落 linear）。
 * 空数组 = 恒等（输出 = 输入）。任一子模型有状态 → 本定义有状态（子实例各自独立状态）。
 *
 * 中间缓冲在实例化时一次性分配（节点数 − 1 个，逐节点复用）；step 零分配。
 * 同一实例内子模型按数组顺序各推进一次，禁止同一实例一帧推进两次。
 */
public final class ChainModel implements MathModel {

    public static final String TYPE = "chain";

    private final ValueDomain domain;
    private final MathModel[] models;

    /** @param models 子模型定义（非 null 元素；缺省 / 无效由注册表回落 linear） */
    public ChainModel(ValueDomain domain, List<MathModel> models) {
        this.domain = domain;
        this.models = models.toArray(new MathModel[0]);
    }

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public ValueDomain domain() {
        return domain;
    }

    @Override
    public int inputArity() {
        return domain.channels();
    }

    @Override
    public int outputArity() {
        return domain.channels();
    }

    @Override
    public boolean stateful() {
        for (MathModel model : models) {
            if (model.stateful()) {
                return true;
            }
        }
        return false;
    }

    @Override
    public MathModelInstance instantiate() {
        return new ChainInstance(models, domain.channels());
    }

    private static final class ChainInstance implements MathModelInstance {

        private final MathModelInstance[] instances;
        private final double[][] buffers;
        private final int arity;

        ChainInstance(MathModel[] models, int arity) {
            this.arity = arity;
            this.instances = new MathModelInstance[models.length];
            for (int i = 0; i < models.length; i++) {
                this.instances[i] = models[i].instantiate();
            }
            this.buffers = new double[Math.max(0, models.length - 1)][arity];
        }

        @Override
        public void step(double t, double dt, double[] in, double[] out) {
            if (instances.length == 0) {
                System.arraycopy(in, 0, out, 0, arity);
                return;
            }
            double[] source = in;
            for (int i = 0; i < instances.length; i++) {
                double[] destination = i == instances.length - 1 ? out : buffers[i];
                instances[i].step(t, dt, source, destination);
                source = destination;
            }
        }

        @Override
        public void reset() {
            for (MathModelInstance instance : instances) {
                instance.reset();
            }
        }
    }
}
