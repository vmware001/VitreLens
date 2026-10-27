package com.vitrelens.app;

import android.graphics.Bitmap;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLSurface;
import android.opengl.GLES20;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

/**
 * 离屏 GLES2 渲染器：把「液态玻璃」着色器跑在水印条带上。
 * 与网页版共用同一份 GLSL（res/raw/glass.frag）。
 */
class GlassRenderer {

    private EGLDisplay display = EGL14.EGL_NO_DISPLAY;
    private EGLContext context = EGL14.EGL_NO_CONTEXT;
    private EGLSurface surface = EGL14.EGL_NO_SURFACE;

    private int program;
    private int uPhoto, uBlur, uBlur2, uRes, uPanel, uRadius, uWhite, uCrop;
    private int uIor, uDepth, uThickness, uRough, uDisp, uWarp, uRefl, uTrans, uSegments;
    private int uTint, uTintOn, uGloss, uShadow, uScale;
    private int aPos;
    private int fbo, texPhoto, texBlur, texBlur2;
    private int maxTex = 4096;
    private FloatBuffer quad;

    private Bitmap blurBitmap, blurBitmap2;

    GlassRenderer(MainActivity act) throws Exception {
        String frag = readRaw(act, R.raw.glass_frag_pbr);
        String vert = readRaw(act, R.raw.glass_vert);
        initEgl();
        // initEgl 之后上下文必须保持 current，着色器编译/纹理创建才能生效；
        // 全部做完了再交还（渲染在后台线程，那里会重新 makeCurrent）。
        try {
            buildProgram(vert, frag);

            int[] max = new int[1];
            GLES20.glGetIntegerv(GLES20.GL_MAX_TEXTURE_SIZE, max, 0);
            maxTex = Math.min(8192, max[0] > 0 ? max[0] : 4096);

            float[] q = {-1, -1, 1, -1, -1, 1, -1, 1, 1, -1, 1, 1};
            quad = ByteBuffer.allocateDirect(q.length * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
            quad.put(q).position(0);
        } finally {
            releaseCurrent();
        }
    }

    private static String readRaw(MainActivity act, int id) throws Exception {
        InputStream in = act.getResources().openRawResource(id);
        BufferedReader r = new BufferedReader(new InputStreamReader(in, "UTF-8"));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = r.readLine()) != null) sb.append(line).append('\n');
        r.close();
        return sb.toString();
    }

    private void initEgl() {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
        int[] ver = new int[2];
        EGL14.eglInitialize(display, ver, 0, ver, 1);

        int[] cfgAttr = {
                EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8, EGL14.EGL_DEPTH_SIZE, 0,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_NONE
        };
        EGLConfig[] cfg = new EGLConfig[1];
        int[] ncfg = new int[1];
        EGL14.eglChooseConfig(display, cfgAttr, 0, cfg, 0, 1, ncfg, 0);
        if (ncfg[0] == 0) throw new RuntimeException("no EGL config");

        int[] ctxAttr = {EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE};
        context = EGL14.eglCreateContext(display, cfg[0], EGL14.EGL_NO_CONTEXT, ctxAttr, 0);

        int[] pb = {EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE};
        surface = EGL14.eglCreatePbufferSurface(display, cfg[0], pb, 0);
        EGL14.eglMakeCurrent(display, surface, surface, context);

        int[] ids = new int[1];
        GLES20.glGenFramebuffers(1, ids, 0);
        fbo = ids[0];
        int[] tex = new int[3];
        GLES20.glGenTextures(3, tex, 0);
        texPhoto = tex[0];
        texBlur = tex[1];
        texBlur2 = tex[2];
    }

    /** 渲染在后台线程执行，初始化线程用完就把上下文交还出来 */
    private void releaseCurrent() {
        if (display != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
        }
    }

    private boolean bind() {
        if (!EGL14.eglMakeCurrent(display, surface, surface, context)) return false;
        GLES20.glUseProgram(program);
        return true;
    }

    private int compile(int type, String src) {
        int sh = GLES20.glCreateShader(type);
        if (sh == 0) {
            throw new RuntimeException("glCreateShader 返回 0（当前线程没有可用的 GL 上下文？）"
                    + " glGetError=" + GLES20.glGetError());
        }
        GLES20.glShaderSource(sh, src);
        GLES20.glCompileShader(sh);
        int[] ok = new int[1];
        GLES20.glGetShaderiv(sh, GLES20.GL_COMPILE_STATUS, ok, 0);
        if (ok[0] == 0) {
            String log = GLES20.glGetShaderInfoLog(sh);
            if (log == null || log.trim().isEmpty()) {
                log = "(驱动没给日志) glGetError=" + GLES20.glGetError()
                        + "，源码 " + src.length() + " 字节";
            }
            GLES20.glDeleteShader(sh);
            throw new RuntimeException((type == GLES20.GL_VERTEX_SHADER ? "顶点" : "片元")
                    + "着色器编译失败：" + log);
        }
        return sh;
    }

    private void buildProgram(String vert, String frag) {
        int vs = compile(GLES20.GL_VERTEX_SHADER, vert);
        int fs = compile(GLES20.GL_FRAGMENT_SHADER, frag);
        program = GLES20.glCreateProgram();
        GLES20.glAttachShader(program, vs);
        GLES20.glAttachShader(program, fs);
        GLES20.glLinkProgram(program);
        int[] ok = new int[1];
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, ok, 0);
        if (ok[0] == 0) throw new RuntimeException("link: " + GLES20.glGetProgramInfoLog(program));
        GLES20.glUseProgram(program);

        aPos = GLES20.glGetAttribLocation(program, "a_pos");
        uPhoto = GLES20.glGetUniformLocation(program, "u_photo");
        uBlur = GLES20.glGetUniformLocation(program, "u_blur");
        uBlur2 = GLES20.glGetUniformLocation(program, "u_blur2");
        uRes = GLES20.glGetUniformLocation(program, "u_res");
        uPanel = GLES20.glGetUniformLocation(program, "u_panel");
        uRadius = GLES20.glGetUniformLocation(program, "u_radius");
        uWhite = GLES20.glGetUniformLocation(program, "u_white");
        uIor = GLES20.glGetUniformLocation(program, "u_ior");
        uDepth = GLES20.glGetUniformLocation(program, "u_depth");
        uThickness = GLES20.glGetUniformLocation(program, "u_thickness");
        uRough = GLES20.glGetUniformLocation(program, "u_rough");
        uDisp = GLES20.glGetUniformLocation(program, "u_disp");
        uWarp = GLES20.glGetUniformLocation(program, "u_warp");
        uRefl = GLES20.glGetUniformLocation(program, "u_refl");
        uTrans = GLES20.glGetUniformLocation(program, "u_trans");
        uSegments = GLES20.glGetUniformLocation(program, "u_segments");
        uTint = GLES20.glGetUniformLocation(program, "u_tint");
        uTintOn = GLES20.glGetUniformLocation(program, "u_tintOn");
        uGloss = GLES20.glGetUniformLocation(program, "u_gloss");
        uShadow = GLES20.glGetUniformLocation(program, "u_shadow");
        uScale = GLES20.glGetUniformLocation(program, "u_scale");
        uCrop = GLES20.glGetUniformLocation(program, "u_crop");
    }

    private void upload(int tex, Bitmap bmp) {
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        android.opengl.GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bmp, 0);
    }

    /** 两级模糊近似 three.js 的 mip 采样（roughness 决定取哪一级） */
    private void makeBlur(Bitmap crop) {
        blurBitmap = scaleInto(crop, 22, blurBitmap);
        blurBitmap2 = scaleInto(crop, 72, blurBitmap2);
    }

    private Bitmap scaleInto(Bitmap src, int div, Bitmap dst) {
        int w = Math.max(4, src.getWidth() / div);
        int h = Math.max(4, src.getHeight() / div);
        if (dst == null || dst.getWidth() != w || dst.getHeight() != h) {
            dst = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        }
        android.graphics.Canvas c = new android.graphics.Canvas(dst);
        android.graphics.Paint p = new android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG);
        c.drawBitmap(src, null, new android.graphics.Rect(0, 0, w, h), p);
        return dst;
    }

    /**
     * 渲染一条水印条带。
     * @param crop   该条带的原始像素（尺寸即输出尺寸）
     * @param cropX  条带在整幅图里的左上角 x
     * @param cropY  条带在整幅图里的左上角 y
     * @param fullW  整幅输出宽度，用于统一比例
     * @param fullH  整幅输出高度
     * @param p      面板参数（条带坐标系）
     */
    Bitmap render(Bitmap crop, int cropX, int cropY, int fullW, int fullH,
                  WatermarkPainter.Panel p, WatermarkPainter.Style st) {
        int w = crop.getWidth();
        int h = crop.getHeight();
        if (!bind()) throw new RuntimeException("EGL makeCurrent 失败");

        int[] tex = new int[1];
        GLES20.glGenTextures(1, tex, 0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex[0]);
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, w, h, 0,
                GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);

        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo);
        GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0,
                GLES20.GL_TEXTURE_2D, tex[0], 0);
        GLES20.glViewport(0, 0, w, h);

        upload(texPhoto, crop);
        makeBlur(crop);
        upload(texBlur, blurBitmap);
        upload(texBlur2, blurBitmap2);

        GLES20.glUseProgram(program);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texBlur);
        GLES20.glUniform1i(uBlur, 1);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE2);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texBlur2);
        GLES20.glUniform1i(uBlur2, 2);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texPhoto);
        GLES20.glUniform1i(uPhoto, 0);

        GLES20.glUniform2f(uRes, w, h);
        GLES20.glUniform4f(uPanel, p.x, p.y, p.w, p.h);
        GLES20.glUniform1f(uRadius, p.r);
        float uu = fullW / 1000f;
        GLES20.glUniform1f(uWhite, st.whitePct / 100f);
        GLES20.glUniform1f(uIor, st.ior);
        GLES20.glUniform1f(uDepth, Math.max(2f, p.h * 0.5f * (st.depthPct / 100f)));
        GLES20.glUniform1f(uThickness, Math.max(1f, st.thicknessPct / 100f * p.h * 1.1f));
        GLES20.glUniform1f(uRough, st.roughPct / 100f);
        GLES20.glUniform1f(uDisp, st.dispPct / 100f);
        GLES20.glUniform1f(uWarp, st.warpPct / 100f);
        GLES20.glUniform1f(uRefl, st.reflPct / 100f);
        GLES20.glUniform1f(uTrans, st.transPct / 100f);
        GLES20.glUniform1f(uSegments, Math.max(4, st.segments));
        GLES20.glUniform3f(uTint, 0.93f, 0.965f, 1.045f);
        GLES20.glUniform1f(uTintOn, st.tintOn ? 1f : 0f);
        GLES20.glUniform1f(uGloss, st.glossPct / 100f);
        GLES20.glUniform1f(uShadow, st.shadowPct / 100f);
        GLES20.glUniform1f(uScale, uu);
        // 当前渲染块在整张照片里的位置与大小（uv 比例），着色器用它做越界限幅
        GLES20.glUniform4f(uCrop,
                cropX / (float) fullW, cropY / (float) fullH,
                w / (float) fullW, h / (float) fullH);

        GLES20.glEnableVertexAttribArray(aPos);
        GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 0, quad);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, 6);

        ByteBuffer buf = ByteBuffer.allocateDirect(w * h * 4);
        GLES20.glReadPixels(0, 0, w, h, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buf);
        buf.position(0);

        // Android 的 ARGB_8888 在内存里就是 R,G,B,A 字节序，与 GL_RGBA 一致，
        // 所以只需要上下翻转，不用再逐像素换通道（快一倍以上）。
        byte[] px = buf.array();
        byte[] out = new byte[px.length];
        int row = w * 4;
        for (int y = 0; y < h; y++) {
            System.arraycopy(px, (h - 1 - y) * row, out, y * row, row);
        }
        Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        bmp.copyPixelsFromBuffer(ByteBuffer.wrap(out));
        GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0,
                GLES20.GL_TEXTURE_2D, 0, 0);
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
        GLES20.glDeleteTextures(1, tex, 0);
        return bmp;
    }

    int maxTexture() { return maxTex; }
}
