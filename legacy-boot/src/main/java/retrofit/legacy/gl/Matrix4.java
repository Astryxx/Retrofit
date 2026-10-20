package retrofit.legacy.gl;

/** Column-major 4x4 matrix with the multiply-on-the-right semantics of fixed-function OpenGL. */
final class Matrix4 {
    final float[] m = new float[16];

    Matrix4() {
        identity();
    }

    Matrix4 copy() {
        Matrix4 copy = new Matrix4();
        System.arraycopy(m, 0, copy.m, 0, 16);
        return copy;
    }

    void set(Matrix4 other) {
        System.arraycopy(other.m, 0, m, 0, 16);
    }

    void identity() {
        for (int i = 0; i < 16; i++) {
            m[i] = (i % 5 == 0) ? 1 : 0;
        }
    }

    void translate(float x, float y, float z) {
        m[12] += m[0] * x + m[4] * y + m[8] * z;
        m[13] += m[1] * x + m[5] * y + m[9] * z;
        m[14] += m[2] * x + m[6] * y + m[10] * z;
        m[15] += m[3] * x + m[7] * y + m[11] * z;
    }

    void scale(float x, float y, float z) {
        for (int i = 0; i < 4; i++) {
            m[i] *= x;
            m[4 + i] *= y;
            m[8 + i] *= z;
        }
    }

    /** glRotatef: angle in degrees around (x, y, z). */
    void rotate(float angleDegrees, float x, float y, float z) {
        float length = (float) Math.sqrt(x * x + y * y + z * z);
        if (length == 0) {
            return;
        }
        x /= length;
        y /= length;
        z /= length;
        double radians = Math.toRadians(angleDegrees);
        float c = (float) Math.cos(radians);
        float s = (float) Math.sin(radians);
        float t = 1 - c;
        // Rotation matrix r[row][col]
        float r00 = x * x * t + c, r01 = x * y * t - z * s, r02 = x * z * t + y * s;
        float r10 = y * x * t + z * s, r11 = y * y * t + c, r12 = y * z * t - x * s;
        float r20 = x * z * t - y * s, r21 = y * z * t + x * s, r22 = z * z * t + c;
        for (int row = 0; row < 4; row++) {
            float a0 = m[row], a1 = m[4 + row], a2 = m[8 + row];
            m[row] = a0 * r00 + a1 * r10 + a2 * r20;
            m[4 + row] = a0 * r01 + a1 * r11 + a2 * r21;
            m[8 + row] = a0 * r02 + a1 * r12 + a2 * r22;
        }
    }

    /** this = this * other (other in column-major order). */
    void multiply(float[] other) {
        float[] result = new float[16];
        for (int col = 0; col < 4; col++) {
            for (int row = 0; row < 4; row++) {
                float sum = 0;
                for (int k = 0; k < 4; k++) {
                    sum += m[k * 4 + row] * other[col * 4 + k];
                }
                result[col * 4 + row] = sum;
            }
        }
        System.arraycopy(result, 0, m, 0, 16);
    }
}
