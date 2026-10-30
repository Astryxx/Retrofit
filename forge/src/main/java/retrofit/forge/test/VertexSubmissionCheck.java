package retrofit.forge.test;

import com.mojang.blaze3d.vertex.VertexConsumer;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;

/** Compare the optimized capture submission with the existing Minecraft vertex API. */
public final class VertexSubmissionCheck {
    private VertexSubmissionCheck() {}

    public static String verify() {
        try {
            var method = retrofit.forge.client.LegacyCapture.class.getDeclaredMethod("emitVertex", VertexConsumer.class,
                    Matrix4f.class, Matrix3f.class, float[].class, int.class, int.class, int.class, Vector4f.class, Vector3f.class);
            method.setAccessible(true);
            int cases = 0;
            for (int pose = 0; pose < 8; pose++) {
                var position = new Matrix4f().translation(pose * 3.25f, -pose * .5f, pose * .75f)
                        .rotateXYZ(pose * .11f, pose * -.23f, pose * .07f)
                        .scale(pose % 2 == 0 ? 1.25f : -1.25f, .75f + pose * .1f, 2f);
                var normal = new Matrix3f(position).invert().transpose();
                for (float alpha : new float[]{0, .25f, 1}) {
                    float[] data = new float[]{1.25f, -.75f, 2.5f, .125f, .875f, .25f, -.5f, 1, .25f, .5f, .75f, alpha};
                    var expected = new Recording();
                    expected.vertex(position, data[0], data[1], data[2]).color(data[8], data[9], data[10], data[11])
                            .uv(data[3], data[4]).overlayCoords(0x000A000B).uv2(0x00F00070)
                            .normal(normal, data[5], data[6], data[7]).endVertex();
                    var actual = new Recording();
                    method.invoke(null, actual, position, normal, data, 0, 0x000A000B, 0x00F00070, new Vector4f(), new Vector3f());
                    if (actual.vertices != 1) throw new IllegalStateException("Vertex boundary changed");
                    for (int i = 0; i < expected.values.length; i++) {
                        if (Math.abs(expected.values[i] - actual.values[i]) > 0.000001)
                            throw new IllegalStateException("Vertex attribute " + i + " differs: " + expected.values[i] + " / " + actual.values[i]);
                    }
                    cases++;
                }
            }
            return cases + " transformed vertices match Minecraft's existing API";
        } catch (ReflectiveOperationException failure) { throw new IllegalStateException(failure); }
    }

    private static final class Recording implements VertexConsumer {
        final double[] values = new double[16];
        int vertices;
        public VertexConsumer vertex(double x,double y,double z){values[0]=x;values[1]=y;values[2]=z;return this;}
        public VertexConsumer color(int r,int g,int b,int a){values[3]=r;values[4]=g;values[5]=b;values[6]=a;return this;}
        public VertexConsumer uv(float u,float v){values[7]=u;values[8]=v;return this;}
        public VertexConsumer overlayCoords(int u,int v){values[9]=u;values[10]=v;return this;}
        public VertexConsumer uv2(int u,int v){values[11]=u;values[12]=v;return this;}
        public VertexConsumer normal(float x,float y,float z){values[13]=x;values[14]=y;values[15]=z;return this;}
        public void endVertex(){vertices++;}
        public void defaultColor(int r,int g,int b,int a){}
        public void unsetDefaultColor(){}
    }
}
