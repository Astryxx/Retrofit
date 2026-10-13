package retrofit.legacy.gl;

import net.minecraft.launchwrapper.Launch;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Rendering test: renders vanilla 1.7.10's own ModelPig (ModelRenderer, Tessellator and
 * display lists all running as real 1.7.10 code) through the GL emulation, so the host can
 * draw the captured quads in a 1.20.1 world.
 */
public final class ModelTest {
    private static Object pig;
    private static Method render;

    private ModelTest() {
    }

    /**
     * Renders one frame of the pig model and returns the number of captured vertices.
     *
     * @param ageInTicks drives the walk cycle and head turn
     */
    public static int renderPig(float ageInTicks) throws Exception {
        if (pig == null) {
            Class<?> modelPig = Class.forName("net.minecraft.client.model.ModelPig", true, Launch.classLoader);
            Class<?> modelBase = Class.forName("net.minecraft.client.model.ModelBase", true, Launch.classLoader);
            Object model = modelPig.getConstructor().newInstance();
            Field isChild = modelBase.getDeclaredField("field_78091_s");
            isChild.setBoolean(model, false);
            render = modelBase.getMethod("func_78088_a", Class.forName("net.minecraft.entity.Entity", false, Launch.classLoader),
                    float.class, float.class, float.class, float.class, float.class, float.class);
            pig = model;
        }
        Capture.begin();
        float headYaw = (float) Math.sin(ageInTicks * 0.05) * 30;
        render.invoke(pig, null, ageInTicks, 0.6f, ageInTicks, headYaw, 0f, 0.0625f);
        return Capture.vertexCount();
    }
}
