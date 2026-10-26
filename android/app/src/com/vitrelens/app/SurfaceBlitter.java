package com.vitrelens.app;

import android.graphics.Bitmap;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLExt;
import android.opengl.EGLSurface;
import android.opengl.GLES20;
import android.opengl.GLUtils;
import android.view.Surface;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

/**
 * 把一帧 Bitmap 贴到 MediaCodec 的输入 Surface 上。
 *
 * 两个必须注意的点：
 *  1. EGL config 必须带 EGL_RECORDABLE_ANDROID，否则编码器收不到帧；
 *  2. 每帧要 eglPresentationTimeANDROID 打时间戳，否则生成的 MP4 时间轴是乱的。
 */
class SurfaceBlitter {

    private static final int EGL_RECORDABLE_ANDROID = 0x3142;

    private static final String VERT =
            "attribute vec2 a_pos;\n" +
            "varying vec2 v_uv;\n" +
            "void main(){\n" +
            "  v_uv = vec2(a_pos.x * 0.5 + 0.5, 0.5 - a_pos.y * 0.5);\n" +
            "  gl_Position = vec4(a_pos, 0.0, 1.0);\n" +
            "}\n";

    private static final String FRAG =
            "precision mediump float;\n" +
            "varying vec2 v_uv;\n" +
            "uniform sampler2D u_tex;\n" +
            "void main(){ gl_FragColor = vec4(texture2D(u_tex, v_uv).rgb, 1.0); }\n";

    private EGLDisplay dpy = EGL14.EGL_NO_DISPLAY;
    private EGLContext ctx = EGL14.EGL_NO_CONTEXT;
    private EGLSurface surf = EGL14.EGL_NO_SURFACE;

    private int program, aPos, uTex, texture;
    private final FloatBuffer quad;
    private final int width, height;

    SurfaceBlitter(Surface target, int w, int h) {
        width = w;
        height = h;
        float[] q = {-1, -1, 1, -1, -1, 1, -1, 1, 1, -1, 1, 1};
        quad = ByteBuffer.allocateDirect(q.length * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        quad.put(q).position(0);

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
        surf = EGL14.eglCreateWindowSurface(dpy, cfg[0], target, new int[]{EGL14.EGL_NONE}, 0);
        if (surf == null || surf == EGL14.EGL_NO_SURFACE) throw new RuntimeException("eglCreateWindowSurface 失败");
        if (!EGL14.eglMakeCurrent(dpy, surf, surf, ctx)) throw new RuntimeException("eglMakeCurrent 失败");

        program = buildProgram(VERT, FRAG);
        aPos = GLES20.glGetAttribLocation(program, "a_pos");
        uTex = GLES20.glGetUniformLocation(program, "u_tex");

        int[] t = new int[1];
        GLES20.glGenTextures(1, t, 0);
        texture = t[0];
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, w, h, 0,
                GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null);

        GLES20.glViewport(0, 0, w, h);
        GLES20.glUseProgram(program);
    }

    /** 送一帧进去；ptsUs 为这一帧的显示时间（微秒） */
    void draw(Bitmap bmp, long ptsUs) {
        // 同一个线程上还有 GlassRenderer 自己的 EGL 上下文（每帧合成时会把 current 抢走），
        // 所以每次上传前必须把上下文切回自己，否则 program/texture 全是无效 ID。
        EGL14.eglMakeCurrent(dpy, surf, surf, ctx);
        GLES20.glViewport(0, 0, width, height);
        GLES20.glUseProgram(program);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture);
        GLUtils.texSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0, bmp);
        GLES20.glUniform1i(uTex, 0);

        GLES20.glClearColor(0f, 0f, 0f, 1f);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
        GLES20.glEnableVertexAttribArray(aPos);
        GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 0, quad);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, 6);

        EGLExt.eglPresentationTimeANDROID(dpy, surf, ptsUs * 1000L);
        if (!EGL14.eglSwapBuffers(dpy, surf)) throw new RuntimeException("eglSwapBuffers 失败");
    }

    void release() {
        if (dpy != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(dpy, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
            if (surf != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(dpy, surf);
            if (ctx != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(dpy, ctx);
            EGL14.eglTerminate(dpy);
        }
        dpy = EGL14.EGL_NO_DISPLAY;
        surf = EGL14.EGL_NO_SURFACE;
        ctx = EGL14.EGL_NO_CONTEXT;
    }

    private int buildProgram(String vert, String frag) {
        int vs = compile(GLES20.GL_VERTEX_SHADER, vert);
        int fs = compile(GLES20.GL_FRAGMENT_SHADER, frag);
        int p = GLES20.glCreateProgram();
        GLES20.glAttachShader(p, vs);
        GLES20.glAttachShader(p, fs);
        GLES20.glLinkProgram(p);
        int[] ok = new int[1];
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, ok, 0);
        if (ok[0] == 0) throw new RuntimeException("blit link: " + GLES20.glGetProgramInfoLog(p));
        return p;
    }

    private int compile(int type, String src) {
        int s = GLES20.glCreateShader(type);
        GLES20.glShaderSource(s, src);
        GLES20.glCompileShader(s);
        int[] ok = new int[1];
        GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0);
        if (ok[0] == 0) {
            String log = GLES20.glGetShaderInfoLog(s);
            GLES20.glDeleteShader(s);
            throw new RuntimeException("blit shader: " + log);
        }
        return s;
    }
}
