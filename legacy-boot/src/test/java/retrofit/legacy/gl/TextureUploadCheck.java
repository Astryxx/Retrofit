package retrofit.legacy.gl;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.Map;

/** Executes the real capture API without a window or GPU; invalid inputs must not corrupt other pixels. */
public final class TextureUploadCheck {
    private static final int TEX=0x0DE1, RGBA=0x1908, BGRA=0x80E1, BYTE=0x1401, PACKED=0x8367;
    private static int checks;
    private static void check(boolean ok,String what) { if(!ok)throw new AssertionError(what);checks++; }
    private static int[] pixels(String name) { return (int[])GL11.uploadedPixels(name)[2]; }
    private static Object backing(String name) throws Exception {
        Field uploads=GL11.class.getDeclaredField("UPLOADED");uploads.setAccessible(true);
        Object texture=((Map<?,?>)uploads.get(null)).get(name);
        Field argb=texture.getClass().getDeclaredField("argb");argb.setAccessible(true);return argb.get(texture);
    }
    public static void main(String[] args) throws Exception {
        String name="test:upload";int id=GL11.textureId(name);GL11.glBindTexture(TEX,id);
        IntBuffer input=IntBuffer.wrap(new int[]{0x77777777,0xFF112233,0x80123456,0x55555555});input.position(1);input.limit(3);
        GL11.glTexImage2D(TEX,0,RGBA,2,1,0,BGRA,PACKED,input);
        check(Arrays.equals(pixels(name),new int[]{0xFF112233,0x80123456}),"packed pixels at nonzero position");
        check(input.position()==1 && input.limit()==3,"caller IntBuffer cursor unchanged");
        Object first=backing(name);
        for(int i=0;i<500;i++)GL11.glTexImage2D(TEX,0,RGBA,2,1,0,BGRA,PACKED,input);
        if(args.length==0)check(backing(name)==first,"same-size full uploads reuse capture storage");
        ByteBuffer bytes=ByteBuffer.wrap(new byte[]{99,1,2,3,(byte)255,4,5,6,(byte)128,99});bytes.position(1);bytes.limit(9);
        GL11.glTexImage2D(TEX,0,RGBA,2,1,0,RGBA,BYTE,bytes);
        check(Arrays.equals(pixels(name),new int[]{0xFF010203,0x80040506}),"heap RGBA bytes");
        check(bytes.position()==1 && bytes.limit()==9,"caller ByteBuffer cursor unchanged");
        GL11.glTexImage2D(TEX,0,RGBA,2,1,0,RGBA,BYTE,ByteBuffer.wrap(new byte[]{1,2,3,(byte)255,8}));
        check(Arrays.equals(pixels(name),new int[]{0xFF010203,0}),"truncated byte pixel zero padded without stale data");
        GL11.glTexImage2D(TEX,0,RGBA,2,1,0,BGRA,PACKED,IntBuffer.wrap(new int[]{0xFFABCDEF}));
        check(Arrays.equals(pixels(name),new int[]{0xFFABCDEF,0}),"truncated IntBuffer zero padded");
        GL11.glTexImage2D(TEX,0,RGBA,2,1,0,RGBA,BYTE,(ByteBuffer)null);
        check(Arrays.equals(pixels(name),new int[]{0,0}),"null full upload clears reused pixels");
        GL11.glTexImage2D(TEX,0,RGBA,3,2,0,BGRA,PACKED,IntBuffer.wrap(new int[]{1,2,3,4,5,6}));
        GL11.glTexSubImage2D(TEX,0,-1,0,3,1,BGRA,PACKED,IntBuffer.wrap(new int[]{7,8,9}));
        check(Arrays.equals(pixels(name),new int[]{8,9,3,4,5,6}),"clipped subimage preserves source row offsets and surrounding pixels");
        GL11.glTexSubImage2D(TEX,0,1,1,2,1,BGRA,PACKED,IntBuffer.wrap(new int[]{10}));
        check(Arrays.equals(pixels(name),new int[]{8,9,3,4,10,0}),"truncated subimage only fills its rectangle");
        GL11.glTexSubImage2D(TEX,0,Integer.MIN_VALUE,0,2,1,BGRA,PACKED,IntBuffer.wrap(new int[]{1,2}));
        check(Arrays.equals(pixels(name),new int[]{8,9,3,4,10,0}),"extreme negative offset does not overflow");
        GL11.glTexImage2D(TEX,0,RGBA,Integer.MAX_VALUE,2,0,RGBA,BYTE,(ByteBuffer)null);
        check(((Integer)GL11.uploadedPixels(name)[0])==3,"invalid dimensions preserve prior texture");
        GL11.glTexImage2D(TEX,0,RGBA,1,1,0,RGBA,BYTE,(ByteBuffer)ByteBuffer.allocateDirect(4).put(new byte[]{9,8,7,(byte)255}).flip());
        check(pixels(name)[0]==0xFF090807,"direct RGBA buffer");
        GL11.glTexImage2D(TEX,0,RGBA,1,1,0,BGRA,BYTE,ByteBuffer.wrap(new byte[]{7,8,9,(byte)255}));
        check(pixels(name)[0]==0xFF090807,"BGRA byte channel order");
        int version=GL11.uploadedVersion(name);
        int[] snapshot=pixels(name);snapshot[0]=123;
        check(pixels(name)[0]==0xFF090807,"host pixel snapshot does not alias capture storage");
        GL11.glTexImage2D(TEX,0,RGBA,1,1,0,BGRA,BYTE,ByteBuffer.wrap(new byte[]{7,8,9,(byte)255}).asReadOnlyBuffer());
        check(GL11.uploadedVersion(name)>version,"reused upload still invalidates host texture cache");
        check(pixels(name)[0]==0xFF090807,"read-only buffer remains readable");
        GL11.glDeleteTextures(id);check(GL11.uploadedPixels(name)==null,"delete releases capture storage");
        System.out.println("Texture upload checks: "+checks+" passed, including 500 same-size uploads without replacement pixel arrays");
    }
}
