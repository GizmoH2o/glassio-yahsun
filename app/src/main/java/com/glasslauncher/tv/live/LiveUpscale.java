package com.glasslauncher.tv.live;

import android.content.Context;
import android.opengl.GLES20;

import androidx.media3.common.VideoFrameProcessingException;
import androidx.media3.common.util.GlProgram;
import androidx.media3.common.util.GlUtil;
import androidx.media3.common.util.Size;
import androidx.media3.effect.BaseGlShaderProgram;
import androidx.media3.effect.GlEffect;
import androidx.media3.effect.GlShaderProgram;

/**
 * Upscaling (Live TV › Settings › Playback): an SD or 720p picture is sharpened on the TV's graphics chip as AMD's
 * FidelityFX CAS does (contrast-adaptive: soft areas more, edges already sharp less, so no halos), at its own size, and
 * the TV's own scaler enlarges it to the screen. Live only uses it below 1080 lines (Live.applyUpscale). Needs the
 * player without tunneled playback (the frames go through the graphics chip).
 */
final class LiveUpscale implements GlEffect {
    /** How much: CAS's own range, 1/8 (light) to 1/5 (strong). Below 1080 lines strong, else light. */
    private static final float SD_SHARPEN = 0.2f, HD_SHARPEN = 0.14f;
    private static final int TARGET = 1080;

    @Override public GlShaderProgram toGlShaderProgram(Context context, boolean useHdr) throws VideoFrameProcessingException {
        return new Program(useHdr);
    }

    private static final String VERTEX = ""
            + "attribute vec4 aFramePosition;\n"
            + "varying vec2 vTexSamplingCoord;\n"
            + "void main() {\n"
            + "  gl_Position = aFramePosition;\n"
            + "  vTexSamplingCoord = aFramePosition.xy * 0.5 + 0.5;\n"
            + "}\n";

    /** CAS: the cross around each point gives its local contrast, which sets how much the neighbours are taken off. */
    private static final String FRAGMENT = ""
            + "precision mediump float;\n"
            + "uniform sampler2D uTexSampler;\n"
            + "uniform vec2 uTexel;\n"
            + "uniform float uSharpen;\n"
            + "varying vec2 vTexSamplingCoord;\n"
            + "void main() {\n"
            + "  vec2 p = vTexSamplingCoord;\n"
            + "  vec3 c = texture2D(uTexSampler, p).rgb;\n"
            + "  vec3 n = texture2D(uTexSampler, p - vec2(0.0, uTexel.y)).rgb;\n"
            + "  vec3 s = texture2D(uTexSampler, p + vec2(0.0, uTexel.y)).rgb;\n"
            + "  vec3 w = texture2D(uTexSampler, p - vec2(uTexel.x, 0.0)).rgb;\n"
            + "  vec3 e = texture2D(uTexSampler, p + vec2(uTexel.x, 0.0)).rgb;\n"
            + "  vec3 lo = min(c, min(min(n, s), min(w, e)));\n"
            + "  vec3 hi = max(c, max(max(n, s), max(w, e)));\n"
            + "  vec3 amp = sqrt(clamp(min(lo, 1.0 - hi) / max(hi, vec3(0.0001)), 0.0, 1.0));\n"
            + "  vec3 wt = -amp * uSharpen;\n"
            + "  vec3 o = (c + (n + s + w + e) * wt) / (1.0 + 4.0 * wt);\n"
            + "  gl_FragColor = vec4(clamp(o, 0.0, 1.0), 1.0);\n"
            + "}\n";

    private static final class Program extends BaseGlShaderProgram {
        private final GlProgram gl;
        private float texelX, texelY, sharpen;

        Program(boolean useHdr) throws VideoFrameProcessingException {
            super(useHdr, 3);       // three frames in flight: the screen doesn't wait for the next one to be drawn
            try {
                gl = new GlProgram(VERTEX, FRAGMENT);
            } catch (GlUtil.GlException e) {
                throw new VideoFrameProcessingException(e);
            }
            gl.setBufferAttribute("aFramePosition", GlUtil.getNormalizedCoordinateBounds(), GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE);
        }

        /**
         * At the picture's own size: the TV's scaler (free, in its display hardware) enlarges it to the screen. Drawn
         * at 1080 here it cost the graphics chip frames (about 22 of an SD channel's 25 on a TCL Google TV), at its own
         * size it keeps up. Under 1080 lines strong, else light.
         */
        @Override public Size configure(int width, int height) {
            texelX = 1f / Math.max(1, width);
            texelY = 1f / Math.max(1, height);
            sharpen = height > 0 && height < TARGET ? SD_SHARPEN : HD_SHARPEN;
            return new Size(width, height);
        }

        @Override public void drawFrame(int inputTexId, long presentationTimeUs) throws VideoFrameProcessingException {
            try {
                gl.use();
                gl.setSamplerTexIdUniform("uTexSampler", inputTexId, 0);
                gl.setFloatsUniform("uTexel", new float[] {texelX, texelY});
                gl.setFloatUniform("uSharpen", sharpen);
                gl.bindAttributesAndUniforms();
                GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
            } catch (GlUtil.GlException e) {
                throw new VideoFrameProcessingException(e, presentationTimeUs);
            }
        }

        @Override public void release() throws VideoFrameProcessingException {
            super.release();
            try {
                gl.delete();
            } catch (GlUtil.GlException e) {
                throw new VideoFrameProcessingException(e);
            }
        }
    }
}
