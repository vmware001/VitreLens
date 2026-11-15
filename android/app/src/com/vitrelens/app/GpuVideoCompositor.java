package com.vitrelens.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLExt;
import android.opengl.EGLSurface;
import android.opengl.GLES20;
import android.util.Log;
import android.view.Surface;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

/**
 * 视频转码的 GPU 合成器：把「照片 + 液态玻璃 + 文字覆盖层」直接画到编码器的输入 Surface 上。
 *
 * <p>原来的 CPU 链路是：YUV→ARGB（CPU，保留）→ 裁条带 → GL 渲染 → <b>glReadPixels 回读</b>
 * → Canvas 画回位图 → 再上传给编码 Surface。回读/回写一来一回每条带十几 MB，
 * 313 帧 1080p 实测「玻璃 3.1~3.4s + 编码上传 2.3s」。
 *
 * <p>这里保留 CPU 的 YUV→ARGB（HDR 色调映射逻辑不动，色彩零风险），
 * 但玻璃与文字直接在 GPU 上合成到 {@link Surface}：
 * <ul>
 *   <li>编码器 Surface 上建 EGL window surface；</li>
 *   <li>原图上传成纹理，两趟小尺寸降采样当模糊层（等价 CPU 的 scaleInto）；</li>
 *   <li>用与照片导出完全相同的 glass_frag_pbr 着色器整帧渲染（面板外就是原图）；</li>
 *   <li>文字/Logo 覆盖层作为一张透明纹理叠上去；</li>
 *   <li>每帧 eglPresentationTimeANDROID + eglSwapBuffers。</li>
 * </ul>
 * 不再有 glReadPixels，也不再需要 SurfaceBlitter。
 */
final class GpuVideoCompositor {

    private static final String TAG = "VitreLens";

    private static final String VERT = "attribute vec2 a_pos;\n"
            + "varying vec2 v_uv;\n"
            + "void main(){\n"
            + "  v_uv = vec2(a_pos.x*0.5+0.5, 0.5 - a_pos.y*0.5);\n"
            + "  gl_Position = vec4(a_pos,0.0,1.0);\n"
            + "}\n";

    /** 普通拷贝/降采样：把纹理缩到目标 FBO（LINEAR），当模糊层用 */
    private static final String COPY_FRAG = "precision mediump float;\n"
            + "varying vec2 v_uv;\n"
            + "uniform sampler2D u_tex;\n"
            + "void main(){ gl_FragColor = vec4(texture2D(u_tex, v_uv).rgb, 1.0); }\n";

    /** 文字覆盖层：预乘 alpha，直接混合 */
    private static final String OVERLAY_FRAG = "precision mediump float;\n"
            + "varying vec2 v_uv;\n"
            + "uniform sampler2D u_tex;\n"
            + "void main(){ gl_FragColor = texture2D(u_tex, v_uv); }\n";

    private static final int EGL_RECORDABLE_ANDROID = 0x3142;

    private EGLDisplay dpy = EGL14.EGL_NO_DISPLAY;
    private EGLContext ctx = EGL14.EGL_NO_CONTEXT;
    private EGLSurface surf = EGL14.EGL_NO_SURFACE;

    private final int outW, outH;

    private int glassProgram, glassVert, glassFrag;
    private int copyProgram, overlayProgram;
    private int quadBuf;
    private FloatBuffer quad;

    private int texPhoto, texBlur1, texBlur2, texOverlay;
    private int blur1W = -1, blur1H = -1, blur2W = -1, blur2H = -1;
    private int photoW = -1, photoH = -1;
    private int overlayW = -1, overlayH = -1;
    /** 上一次上传的覆盖层位图对象；Composer 换了新位图（同尺寸）也要重传 */
    private Bitmap overlayRef;
    private boolean overlaySet;

    private int fbo1, fbo2;

    // glass shader 的 uniform 位置
    private int uPhoto, uBlur, uBlur2, uRes, uPanel, uCrop, uRadius, uDepth, uThickness;
    private int uIor, uRough, uRefl, uTrans, uDisp, uWarp, uEdge, uSegments;
    private int uTint, uTintOn, uScale, uShadow, uWhite, uOpaque, uGloss;
    private int aPos;

    private int uCopyTex, uOverlayTex;
    private int aCopyPos, aOverlayPos;

    /** 上一帧已经设置过的面板参数；style 不变时省掉重复 uniform 调用（很小，但顺手） */
    private boolean uniformsReady;

    /** 分阶段累计耗时（纳秒），release 时打印一次，用于定位瓶颈 */
    private long upNs, blurNs, glassNs, overlayNs, swapNs;
    private int frames;
    /** 调试开关：打印 GPU 合成各阶段耗时 */
    private static final boolean TIMING = false;

    static boolean isSupported() {
        // 简单探测：能创建 EGL14 就认为可用；真正的失败在构造时抛异常，由调用方回退
        try {
            EGLDisplay d = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
            return d != EGL14.EGL_NO_DISPLAY;
        } catch (Throwable t) {
            return false;
        }
    }

    GpuVideoCompositor(Context ctx, Surface encoderSurface, int outW, int outH) throws Exception {
        this.outW = Math.max(2, outW);
        this.outH = Math.max(2, outH);
        String vert = readRaw(ctx, R.raw.glass_vert);
        String frag = readRaw(ctx, R.raw.glass_frag_pbr);
        initEgl(encoderSurface);
        try {
            buildGlassProgram(vert, frag);
            buildAuxPrograms();
            createTextures();
        } catch (Throwable t) {
            release();
            throw new Exception("GPU 合成器初始化失败：" + t.getMessage(), t);
        }
    }

    String backendLabel() {
        String renderer = GLES20.glGetString(GLES20.GL_RENDERER);
        return "GPU直通(" + (renderer == null ? "?" : renderer) + ")";
    }

    // ───────────────────────── EGL ─────────────────────────

    private void initEgl(Surface encoderSurface) {
        dpy = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
        if (dpy == EGL14.EGL_NO_DISPLAY) throw new RuntimeException("eglGetDisplay 失败");
        int[] ver = new int[2];
        if (!EGL14.eglInitialize(dpy, ver, 0, ver, 1)) throw new RuntimeException("eglInitialize 失败");

        int[] cfgAttr = {
                EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 0,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT,
                EGL_RECORDABLE_ANDROID, 1,
                EGL14.EGL_NONE
        };
        EGLConfig[] cfg = new EGLConfig[1];
        int[] n = new int[1];
        if (!EGL14.eglChooseConfig(dpy, cfgAttr, 0, cfg, 0, 1, n, 0) || n[0] == 0) {
            throw new RuntimeException("没有支持 RECORDABLE 的 EGL config");
        }
        int[] ctxAttr = {EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE};
        ctx = EGL14.eglCreateContext(dpy, cfg[0], EGL14.EGL_NO_CONTEXT, ctxAttr, 0);
        surf = EGL14.eglCreateWindowSurface(dpy, cfg[0], encoderSurface, new int[]{EGL14.EGL_NONE}, 0);
        if (surf == null || surf == EGL14.EGL_NO_SURFACE) throw new RuntimeException("eglCreateWindowSurface 失败");
        if (!EGL14.eglMakeCurrent(dpy, surf, surf, ctx)) throw new RuntimeException("eglMakeCurrent 失败");
    }

    // ───────────────────────── 初始化 ─────────────────────────

    private static String readRaw(Context ctx, int id) throws Exception {
        InputStream in = ctx.getResources().openRawResource(id);
        BufferedReader r = new BufferedReader(new InputStreamReader(in, "UTF-8"));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = r.readLine()) != null) sb.append(line).append('\n');
        r.close();
        return sb.toString();
    }

    private int compile(int type, String src) {
        int sh = GLES20.glCreateShader(type);
        GLES20.glShaderSource(sh, src);
        GLES20.glCompileShader(sh);
        int[] ok = new int[1];
        GLES20.glGetShaderiv(sh, GLES20.GL_COMPILE_STATUS, ok, 0);
        if (ok[0] == 0) {
            String log = GLES20.glGetShaderInfoLog(sh);
            GLES20.glDeleteShader(sh);
            throw new RuntimeException("shader 编译失败：" + log);
        }
        return sh;
    }

    private int link(int vs, int fs) {
        int p = GLES20.glCreateProgram();
        GLES20.glAttachShader(p, vs);
        GLES20.glAttachShader(p, fs);
        GLES20.glLinkProgram(p);
        int[] ok = new int[1];
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, ok, 0);
        GLES20.glDeleteShader(vs);
        GLES20.glDeleteShader(fs);
        if (ok[0] == 0) throw new RuntimeException("program link 失败：" + GLES20.glGetProgramInfoLog(p));
        return p;
    }

    private void buildGlassProgram(String vert, String frag) {
        glassVert = compile(GLES20.GL_VERTEX_SHADER, vert);
        glassFrag = compile(GLES20.GL_FRAGMENT_SHADER, frag);
        glassProgram = link(glassVert, glassFrag);
        glassVert = glassFrag = 0;
        aPos = GLES20.glGetAttribLocation(glassProgram, "a_pos");
        uPhoto = GLES20.glGetUniformLocation(glassProgram, "u_photo");
        uBlur = GLES20.glGetUniformLocation(glassProgram, "u_blur");
        uBlur2 = GLES20.glGetUniformLocation(glassProgram, "u_blur2");
        uRes = GLES20.glGetUniformLocation(glassProgram, "u_res");
        uPanel = GLES20.glGetUniformLocation(glassProgram, "u_panel");
        uCrop = GLES20.glGetUniformLocation(glassProgram, "u_crop");
        uRadius = GLES20.glGetUniformLocation(glassProgram, "u_radius");
        uDepth = GLES20.glGetUniformLocation(glassProgram, "u_depth");
        uThickness = GLES20.glGetUniformLocation(glassProgram, "u_thickness");
        uIor = GLES20.glGetUniformLocation(glassProgram, "u_ior");
        uRough = GLES20.glGetUniformLocation(glassProgram, "u_rough");
        uRefl = GLES20.glGetUniformLocation(glassProgram, "u_refl");
        uTrans = GLES20.glGetUniformLocation(glassProgram, "u_trans");
        uDisp = GLES20.glGetUniformLocation(glassProgram, "u_disp");
        uWarp = GLES20.glGetUniformLocation(glassProgram, "u_warp");
        uEdge = GLES20.glGetUniformLocation(glassProgram, "u_edge");
        uSegments = GLES20.glGetUniformLocation(glassProgram, "u_segments");
        uTint = GLES20.glGetUniformLocation(glassProgram, "u_tint");
        uTintOn = GLES20.glGetUniformLocation(glassProgram, "u_tintOn");
        uScale = GLES20.glGetUniformLocation(glassProgram, "u_scale");
        uShadow = GLES20.glGetUniformLocation(glassProgram, "u_shadow");
        uWhite = GLES20.glGetUniformLocation(glassProgram, "u_white");
        uOpaque = GLES20.glGetUniformLocation(glassProgram, "u_opaque");
        uGloss = GLES20.glGetUniformLocation(glassProgram, "u_gloss");
    }

    private void buildAuxPrograms() {
        int v1 = compile(GLES20.GL_VERTEX_SHADER, VERT);
        int f1 = compile(GLES20.GL_FRAGMENT_SHADER, COPY_FRAG);
        copyProgram = link(v1, f1);
        aCopyPos = GLES20.glGetAttribLocation(copyProgram, "a_pos");
        uCopyTex = GLES20.glGetUniformLocation(copyProgram, "u_tex");

        int v2 = compile(GLES20.GL_VERTEX_SHADER, VERT);
        int f2 = compile(GLES20.GL_FRAGMENT_SHADER, OVERLAY_FRAG);
        overlayProgram = link(v2, f2);
        aOverlayPos = GLES20.glGetAttribLocation(overlayProgram, "a_pos");
        uOverlayTex = GLES20.glGetUniformLocation(overlayProgram, "u_tex");

        float[] q = {-1, -1, 1, -1, -1, 1, -1, 1, 1, -1, 1, 1};
        quad = ByteBuffer.allocateDirect(q.length * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        quad.put(q).position(0);
        int[] b = new int[1];
        GLES20.glGenBuffers(1, b, 0);
        quadBuf = b[0];
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, quadBuf);
        GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, q.length * 4, quad, GLES20.GL_STATIC_DRAW);

        int[] f = new int[2];
        GLES20.glGenFramebuffers(2, f, 0);
        fbo1 = f[0];
        fbo2 = f[1];
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
    }

    private void createTextures() {
        int[] t = new int[4];
        GLES20.glGenTextures(4, t, 0);
        texPhoto = t[0];
        texBlur1 = t[1];
        texBlur2 = t[2];
        texOverlay = t[3];
        bindLinear(texPhoto);
        bindLinear(texBlur1);
        bindLinear(texBlur2);
        bindLinear(texOverlay);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0);
    }

    private static void bindLinear(int tex) {
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
    }

    // ───────────────────────── 每帧 ─────────────────────────

    /**
     * 画一帧：上传照片纹理 → 两趟降采样出模糊层 → 玻璃整帧渲染 → 文字覆盖 → 送编码器。
     *
     * @param frame     已经由 CPU 合成好「照片（含旋转/降尺度）」的位图，尺寸必须是 outW×outH
     * @param overlay   文字/Logo 覆盖层（透明底，预乘 alpha）；null 表示没有
     * @param overlayX  覆盖层左上角在输出坐标里的 x
     * @param overlayY  覆盖层左上角在输出坐标里的 y
     */
    void drawFrame(Bitmap frame,
                   WatermarkPainter.Panel panel,
                   WatermarkPainter.Style st,
                   Bitmap overlay, int overlayX, int overlayY,
                   long ptsUs) {
        if (frame == null) throw new RuntimeException("GPU 合成收到空帧");
        if (!EGL14.eglMakeCurrent(dpy, surf, surf, ctx)) throw new RuntimeException("eglMakeCurrent 失败");
        if (frame.getWidth() != outW || frame.getHeight() != outH) {
            throw new RuntimeException("GPU 合成帧尺寸不符：" + frame.getWidth() + "x" + frame.getHeight()
                    + " != " + outW + "x" + outH);
        }

        // 1) 上传原图（尺寸固定，用 texSubImage2D 原地更新）
        long t0 = System.nanoTime();
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texPhoto);
        if (photoW != outW || photoH != outH) {
            android.opengl.GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, frame, 0);
            photoW = outW;
            photoH = outH;
        } else {
            android.opengl.GLUtils.texSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0, frame);
        }
        long t1 = System.nanoTime();
        upNs += t1 - t0;

        // 2) 模糊层：把原图缩到 1/22 与 1/72（等价 CPU 路径的 scaleInto，观感一致）
        int bw1 = Math.max(8, outW / 22);
        int bh1 = Math.max(8, outH / 22);
        int bw2 = Math.max(6, outW / 72);
        int bh2 = Math.max(6, outH / 72);
        renderBlur(fbo1, texBlur1, bw1, bh1, 1);
        renderBlur(fbo2, texBlur2, bw2, bh2, 2);
        long t2 = System.nanoTime();
        blurNs += t2 - t1;

        // 3) 玻璃整帧渲染到编码器 Surface
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
        GLES20.glViewport(0, 0, outW, outH);
        GLES20.glUseProgram(glassProgram);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texPhoto);
        GLES20.glUniform1i(uPhoto, 0);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texBlur1);
        GLES20.glUniform1i(uBlur, 1);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE2);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texBlur2);
        GLES20.glUniform1i(uBlur2, 2);

        bindQuadAndSetupCommon(panel, st);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, 6);
        long t3 = System.nanoTime();
        glassNs += t3 - t2;

        // 4) 文字覆盖层（只在换图时上传一次）
        if (overlay != null && overlay.getWidth() > 0 && overlay.getHeight() > 0) {
            if (!overlaySet || overlay != overlayRef
                    || overlayW != overlay.getWidth() || overlayH != overlay.getHeight()) {
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texOverlay);
                android.opengl.GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, overlay, 0);
                overlayW = overlay.getWidth();
                overlayH = overlay.getHeight();
                overlayRef = overlay;
                overlaySet = true;
            }
            drawOverlayQuad(overlayX, overlayY, overlayW, overlayH);
        }
        long t4 = System.nanoTime();
        overlayNs += t4 - t3;

        EGLExt.eglPresentationTimeANDROID(dpy, surf, ptsUs * 1000L);
        if (!EGL14.eglSwapBuffers(dpy, surf)) throw new RuntimeException("eglSwapBuffers 失败");
        swapNs += System.nanoTime() - t4;
        frames++;
    }

    private void bindQuadAndSetupCommon(WatermarkPainter.Panel p, WatermarkPainter.Style st) {
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, quadBuf);
        GLES20.glEnableVertexAttribArray(aPos);
        GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 0, 0);

        GLES20.glUniform2f(uRes, outW, outH);
        GLES20.glUniform4f(uPanel, p.x, p.y, p.w, p.h);
        GLES20.glUniform4f(uCrop, 0, 0, 1, 1);
        GLES20.glUniform1f(uRadius, p.r);
        float uu = st.pixelUnits ? 1.0f : outW / 1000f;
        GLES20.glUniform1f(uWhite, st.whitePct / 100f);
        boolean darkTone = st.frostTone == 1 || (st.frostTone == 0 && st.themeDark);
        if (darkTone) GLES20.glUniform3f(uOpaque, 0.07f, 0.07f, 0.08f);
        else GLES20.glUniform3f(uOpaque, 0.97f, 0.97f, 0.98f);
        GLES20.glUniform1f(uIor, st.ior);
        float depthPx = st.pixelUnits
                ? Math.max(2f, st.depthPct)
                : Math.max(2f, p.h * 0.5f * (st.depthPct / 100f));
        GLES20.glUniform1f(uDepth, depthPx);
        GLES20.glUniform1f(uEdge, st.pixelUnits
                ? Math.max(0f, st.edgePct)
                : Math.min(Math.max(0f, st.edgePct / 100f) * depthPx * 2.0f, p.h * 0.45f));
        GLES20.glUniform1f(uThickness, st.pixelUnits
                ? Math.max(1f, st.thicknessPct)
                : Math.max(1f, st.thicknessPct / 100f * p.h * 1.1f));
        GLES20.glUniform1f(uRough, st.roughPct / 100f);
        GLES20.glUniform1f(uDisp, st.dispPct / 100f);
        GLES20.glUniform1f(uWarp, st.warpPct / 100f);
        GLES20.glUniform1f(uRefl, st.reflPct / 100f);
        GLES20.glUniform1f(uTrans, st.transPct / 100f);
        GLES20.glUniform1f(uSegments, Math.max(4, st.segments));
        GLES20.glUniform3f(uTint, 0.99f, 0.99f, 1.0f);
        GLES20.glUniform1f(uTintOn, st.tintOn ? 1f : 0f);
        GLES20.glUniform1f(uGloss, st.glossPct / 100f);
        GLES20.glUniform1f(uShadow, st.shadowPct / 100f);
        GLES20.glUniform1f(uScale, uu);
    }

    /** 每帧把最新原图缩进模糊层；只有尺寸变化时才重新分配纹理/FBO。 */
    private void renderBlur(int fbo, int tex, int w, int h, int which) {
        boolean sizeChanged = which == 1 ? (blur1W != w || blur1H != h) : (blur2W != w || blur2H != h);
        if (sizeChanged) {
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex);
            GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, w, h, 0,
                    GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null);
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo);
            GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0,
                    GLES20.GL_TEXTURE_2D, tex, 0);
            if (GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER)
                    != GLES20.GL_FRAMEBUFFER_COMPLETE) {
                throw new RuntimeException("模糊 FBO 不完整");
            }
            if (which == 1) {
                blur1W = w;
                blur1H = h;
            } else {
                blur2W = w;
                blur2H = h;
            }
        }
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo);
        GLES20.glViewport(0, 0, w, h);
        GLES20.glUseProgram(copyProgram);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texPhoto);
        GLES20.glUniform1i(uCopyTex, 0);
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, quadBuf);
        GLES20.glEnableVertexAttribArray(aCopyPos);
        GLES20.glVertexAttribPointer(aCopyPos, 2, GLES20.GL_FLOAT, false, 0, 0);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, 6);
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
    }

    private void drawOverlayQuad(int x, int y, int w, int h) {
        // 视图矩形换算到窗口坐标（GL 原点在左下，图像 y 轴朝下）
        int vx = Math.max(0, Math.min(outW - 1, x));
        int vy = Math.max(0, Math.min(outH - 1, outH - (y + h)));
        int vw = Math.max(1, Math.min(outW - vx, w));
        int vh = Math.max(1, Math.min(outH - vy, h));
        GLES20.glViewport(vx, vy, vw, vh);
        GLES20.glUseProgram(overlayProgram);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texOverlay);
        GLES20.glUniform1i(uOverlayTex, 0);
        GLES20.glEnable(GLES20.GL_BLEND);
        // Canvas 生成的位图是预乘 alpha
        GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA);
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, quadBuf);
        GLES20.glEnableVertexAttribArray(aOverlayPos);
        GLES20.glVertexAttribPointer(aOverlayPos, 2, GLES20.GL_FLOAT, false, 0, 0);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, 6);
        GLES20.glDisable(GLES20.GL_BLEND);
    }

    // ───────────────────────── 释放 ─────────────────────────

    synchronized void release() {
        if (dpy == EGL14.EGL_NO_DISPLAY) return;
        if (TIMING && frames > 0) {
            Log.i(TAG, String.format(java.util.Locale.US,
                    "GPU合成均耗时：帧=%d 上传=%.2fms 模糊=%.2fms 玻璃=%.2fms 覆盖=%.2fms 交换=%.2fms",
                    frames, upNs / 1e6 / frames, blurNs / 1e6 / frames,
                    glassNs / 1e6 / frames, overlayNs / 1e6 / frames, swapNs / 1e6 / frames));
        }
        try {
            EGL14.eglMakeCurrent(dpy, surf, surf, ctx);
            int[] t = {texPhoto, texBlur1, texBlur2, texOverlay};
            GLES20.glDeleteTextures(4, t, 0);
            int[] f = {fbo1, fbo2};
            GLES20.glDeleteFramebuffers(2, f, 0);
            if (glassProgram != 0) GLES20.glDeleteProgram(glassProgram);
            if (copyProgram != 0) GLES20.glDeleteProgram(copyProgram);
            if (overlayProgram != 0) GLES20.glDeleteProgram(overlayProgram);
            if (quadBuf != 0) {
                int[] b = {quadBuf};
                GLES20.glDeleteBuffers(1, b, 0);
            }
        } catch (Throwable t) {
            Log.w(TAG, "GPU 合成器清理 GL 资源失败", t);
        }
        try {
            EGL14.eglMakeCurrent(dpy, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
            if (surf != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(dpy, surf);
            if (ctx != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(dpy, ctx);
            EGL14.eglTerminate(dpy);
        } catch (Throwable ignored) {
        }
        dpy = EGL14.EGL_NO_DISPLAY;
        surf = EGL14.EGL_NO_SURFACE;
        ctx = EGL14.EGL_NO_CONTEXT;
        photoW = photoH = -1;
        blur1W = blur1H = blur2W = blur2H = -1;
        overlaySet = false;
        overlayW = overlayH = -1;
    }
}
