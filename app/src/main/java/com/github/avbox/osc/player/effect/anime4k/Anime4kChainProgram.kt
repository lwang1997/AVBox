package com.github.avbox.osc.player.effect.anime4k

import android.content.Context
import android.opengl.GLES20
import androidx.media3.common.VideoFrameProcessingException
import androidx.media3.common.util.GlProgram
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.Size
import androidx.media3.effect.BaseGlShaderProgram
import com.github.avbox.osc.player.core.EffectCanvas
import com.github.avbox.osc.util.AppLog
import kotlin.math.max

internal class Anime4kChainProgram(
    private val context: Context,
    private val effect: Anime4kEffect,
    private val hdrEnabled: Boolean,
) : BaseGlShaderProgram(hdrEnabled, if (hdrEnabled) 1 else 3) {

    private val passes = ArrayList<CompiledPass>()
    private val targets = ArrayList<Target>()
    private var copyProgram: GlProgram? = null
    private var splitProgram: GlProgram? = null
    private var splitTarget: Target? = null
    private var inputWidth = 0
    private var inputHeight = 0
    private var outputWidth = 0
    private var outputHeight = 0
    private var effectiveTier: Anime4kTier? = null
    private var failed = hdrEnabled
    private var builtTier: Anime4kTier? = null
    private var builtMode: Anime4kMode = Anime4kMode.default
    private var builtDeblur = false
    private var firstFrameLogged = false
    private var splitLogged = false

    override fun configure(inputWidth: Int, inputHeight: Int): Size {
        releaseGl()
        firstFrameLogged = false
        splitLogged = false
        this.inputWidth = inputWidth
        this.inputHeight = inputHeight
        outputWidth = inputWidth
        outputHeight = inputHeight
        failed = hdrEnabled
        builtTier = null
        val tier = resolveTier(inputWidth, inputHeight)
        effectiveTier = tier
        if (!failed && tier != null) {
            try {
                build(tier, effect.mode, inputWidth, inputHeight, EffectCanvas.get())
                builtTier = tier
                builtMode = effect.mode
                builtDeblur = Anime4kSettings.effectiveDeblur(effect.mode)
                Anime4kStatus.onBuildSucceeded()
            } catch (e: Exception) {
                failed = true
                passes.clear()
                releaseTargets()
                Anime4kStatus.onBuildFailed()
                AppLog.e(
                    "Anime4kChain",
                    "echo-anime4k chain build failed, fallback to passthrough: " +
                        "${e.javaClass.simpleName}: ${e.message}",
                    e,
                )
            }
        }
        Anime4kStatus.onChainResolved(
            tierActive = !failed && tier != null,
            autoActive = !failed && tier != null && !Anime4kSettings.manualActive(),
        )
        return Size(outputWidth, outputHeight)
    }

    private fun resolveTier(inputWidth: Int, inputHeight: Int): Anime4kTier? {
        val desired = effect.tier ?: return null
        if (effect.manualEnabled) return desired
        val limit = effect.autoMaxWidth
        if (limit > 0 && max(inputWidth, inputHeight) <= limit) return desired
        return null
    }

    /** 当前会话状态（面板开关 + 自动上限，均读实时值）下链是否应带档位。 */
    private fun desiredTier(): Anime4kTier? {
        val tier = effect.tier ?: return null
        if (Anime4kStatus.sessionDisabled) return null
        if (Anime4kSettings.manualActive()) return tier
        val limit = effect.autoMaxWidth
        return if (limit > 0 && max(inputWidth, inputHeight) <= limit) tier else null
    }

    private fun chainReady(): Boolean =
        !failed && passes.isNotEmpty() && desiredTier() != null

    /**
     * 面板改「本次播放」开关 / 档位 / 模式 / 去模糊后就地重建链：
     * 不重建内核、不重新请求数据源（直链 m3u8 过期会让整段播放失败）。
     */
    private fun syncChain() {
        if (hdrEnabled || inputWidth <= 0 || inputHeight <= 0) return
        val tier = desiredTier() ?: return
        val mode = effect.mode
        val deblur = Anime4kSettings.effectiveDeblur(mode)
        if (!failed && passes.isNotEmpty() && tier == builtTier && mode == builtMode &&
            deblur == builtDeblur
        ) {
            return
        }
        rebuild(tier, mode, deblur)
    }

    private fun rebuild(tier: Anime4kTier, mode: Anime4kMode, deblur: Boolean) {
        releaseGl()
        outputWidth = inputWidth
        outputHeight = inputHeight
        try {
            build(tier, mode, inputWidth, inputHeight, liveCanvas())
            failed = false
            effectiveTier = tier
            builtTier = tier
            builtMode = mode
            builtDeblur = deblur
            Anime4kStatus.onBuildSucceeded()
            Anime4kStatus.onChainResolved(
                tierActive = true,
                autoActive = !Anime4kSettings.manualActive(),
            )
        } catch (e: Exception) {
            failed = true
            passes.clear()
            releaseTargets()
            Anime4kStatus.onBuildFailed()
            AppLog.e(
                "Anime4kChain",
                "echo-anime4k rebuild failed, fallback to passthrough: " +
                    "${e.javaClass.simpleName}: ${e.message}",
                e,
            )
        }
    }

    /** 热更新时的超分目标：等价于 App 按新的会话状态重推输出尺寸。 */
    private fun liveCanvas(): IntArray {
        val scale = Anime4kAutoPolicy.outputScale(
            inputWidth,
            inputHeight,
            Anime4kSettings.manualActive(),
            Anime4kSettings.autoEnabled(),
            Anime4kSettings.autoMaxWidth(),
        )
        return intArrayOf(inputWidth * scale, inputHeight * scale)
    }

    override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
        try {
            // 先记下 media3 绑好的输出 framebuffer / viewport：面板热更新会在 drawFrame 中间
            // 重建链（createFramebuffer 会把绑定复位到 0），必须原样恢复，否则最后一趟
            // 屏幕 pass 会画到默认 framebuffer 上、输出直接错位/黑屏。
            val previousFramebuffer = IntArray(1)
            val previousViewport = IntArray(4)
            GLES20.glGetIntegerv(GLES20.GL_FRAMEBUFFER_BINDING, previousFramebuffer, 0)
            GLES20.glGetIntegerv(GLES20.GL_VIEWPORT, previousViewport, 0)
            if (Anime4kStatus.takeChainSync()) {
                syncChain()
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, previousFramebuffer[0])
                GLES20.glViewport(
                    previousViewport[0],
                    previousViewport[1],
                    previousViewport[2],
                    previousViewport[3],
                )
            }
            if (!chainReady()) {
                drawCopy(inputTexId)
                logFirstFrame()
                return
            }
            val compare = effect.compareSplit
            if (compare && !splitLogged) {
                splitLogged = true
                AppLog.i(
                    "echo-anime4k split: inTex=$inputTexId out=${outputWidth}x$outputHeight" +
                        " passes=${passes.size}",
                )
            }
            passes.forEachIndexed { index, compiled ->
                try {
                    if (compare && index == passes.lastIndex) {
                        val target = ensureSplitTarget()
                        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, target.framebuffer)
                        GLES20.glViewport(0, 0, target.width, target.height)
                    } else if (compiled.writesScreen) {
                        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, previousFramebuffer[0])
                        GLES20.glViewport(
                            previousViewport[0],
                            previousViewport[1],
                            previousViewport[2],
                            previousViewport[3],
                        )
                    } else {
                        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, compiled.targetFramebuffer)
                        GLES20.glViewport(0, 0, compiled.width, compiled.height)
                    }
                    val program = compiled.program
                    program.use()
                    compiled.bindings.forEachIndexed { unit, binding ->
                        val textureId = if (binding.isInput) inputTexId else binding.textureId
                        if (binding.sampler) {
                            try {
                                program.setSamplerTexIdUniform("uTex${binding.name}", textureId, unit)
                            } catch (e: RuntimeException) {
                                binding.sampler = false
                            }
                        }
                        program.setFloatsUniformIfPresent(
                            "uPt${binding.name}",
                            floatArrayOf(1f / binding.width, 1f / binding.height),
                        )
                        program.setFloatsUniformIfPresent(
                            "uSize${binding.name}",
                            floatArrayOf(binding.width.toFloat(), binding.height.toFloat()),
                        )
                    }
                    if (compiled.writesScreen) {
                        program.setFloatsUniformIfPresent(
                            "uSharpen",
                            floatArrayOf(Anime4kSettings.effectiveSharpen(effect.mode)),
                        )
                    }
                    program.bindAttributesAndUniforms()
                    GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
                } catch (e: RuntimeException) {
                    AppLog.e("Anime4kChain", "echo-anime4k draw failed at pass #${index + 1}/${passes.size}", e)
                    throw e
                }
            }
            if (compare) {
                drawSplitComposite(inputTexId, previousFramebuffer[0], previousViewport)
            }
            logFirstFrame()
        } catch (e: GlUtil.GlException) {
            throw VideoFrameProcessingException(e, presentationTimeUs)
        }
    }

    override fun release() {
        super.release()
        releaseGl()
    }

    private fun build(
        tier: Anime4kTier,
        mode: Anime4kMode,
        inputWidth: Int,
        inputHeight: Int,
        canvas: IntArray?,
    ) {
        fun load(asset: String): List<Anime4kPass> = Anime4kShader.parse(
            context.assets.open(ANIME4K_ASSET_DIR + asset).bufferedReader().use { it.readText() },
        )

        val clamp = load(ANIME4K_CLAMP_HIGHLIGHTS)
        val restore = tier.restore(mode)?.let { load(it) }.orEmpty()
        val upscale = tier.upscale(mode)?.let { load(it) }.orEmpty()
        val deblur = load(ANIME4K_DEBLUR_DOG)
        val deblurOn = Anime4kSettings.effectiveDeblur(mode)
        val composed = Anime4kShader.compose(restore, upscale, deblur, clamp, inputWidth, canvas, deblurOn)
        val planned = Anime4kShader.planPasses(composed.passes, inputWidth, inputHeight, canvas)
        if (planned.isEmpty()) throw IllegalStateException("no pass parsed: tier=${tier.name}")
        if (planned.last().pass.save != Anime4kShader.MAIN) {
            throw IllegalStateException("last pass must SAVE MAIN: ${planned.last().pass.name}")
        }
        val byTarget = HashMap<Int, Target>()
        Anime4kShader.assignTargets(planned).forEach { item ->
            val plannedPass = item.planned
            val program = GlProgram(VERTEX_SHADER, plannedPass.pass.fragmentShader)
            program.setBufferAttribute(
                "aFramePosition",
                GlUtil.getNormalizedCoordinateBounds(),
                GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE,
            )
            val bindings = item.bindings.map { binding ->
                val target = byTarget[binding.target]
                BoundBinding(binding.name, target?.texture ?: 0, target == null, binding.width, binding.height)
            }
            val targetFramebuffer: Int
            if (item.outputTarget == Anime4kShader.SCREEN_TARGET) {
                targetFramebuffer = 0
            } else {
                val target = byTarget[item.outputTarget]
                    ?: newTarget(plannedPass.width, plannedPass.height).also { byTarget[item.outputTarget] = it }
                targetFramebuffer = target.framebuffer
            }
            passes.add(
                CompiledPass(
                    program = program,
                    bindings = bindings,
                    targetFramebuffer = targetFramebuffer,
                    width = plannedPass.width,
                    height = plannedPass.height,
                    writesScreen = plannedPass.writesScreen,
                ),
            )
        }
        val last = planned.last()
        outputWidth = last.width
        outputHeight = last.height
        AppLog.i(
            "echo-anime4k chain: tier=${tier.name} mode=${mode.name} passes=${passes.size}" +
                " in=${inputWidth}x$inputHeight out=${outputWidth}x$outputHeight" +
                " canvas=${canvas?.get(0)}x${canvas?.get(1)}" +
                " clamp=${clamp.size} restore=${restore.size}" +
                " deblur=${if (deblurOn) deblur.size else 0} upscale=${upscale.size}x${composed.upscaleCount}" +
                " targets=${targets.size}",
        )
    }

    private fun logFirstFrame() {
        if (firstFrameLogged) return
        firstFrameLogged = true
        AppLog.i(
            "echo-anime4k draw: tier=${effectiveTier?.name ?: "off"} passes=${passes.size}" +
                " out=${outputWidth}x$outputHeight" +
                if (failed || passes.isEmpty()) " (passthrough)" else "",
        )
    }

    private fun newTarget(width: Int, height: Int): Target {
        val texture = GlUtil.createTexture(width, height, false)
        val target = Target(texture, createFramebuffer(texture), width, height)
        targets.add(target)
        return target
    }

    private fun ensureSplitTarget(): Target {
        val existing = splitTarget
        if (existing != null && existing.width == outputWidth && existing.height == outputHeight) {
            return existing
        }
        if (existing != null) {
            targets.remove(existing)
            releaseTarget(existing)
        }
        return newTarget(outputWidth, outputHeight).also { splitTarget = it }
    }

    private fun drawCopy(inputTexId: Int) {
        val program = copyProgram ?: GlProgram(VERTEX_SHADER, COPY_SHADER).also {
            it.setBufferAttribute(
                "aFramePosition",
                GlUtil.getNormalizedCoordinateBounds(),
                GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE,
            )
            copyProgram = it
        }
        program.use()
        program.setSamplerTexIdUniform("uTexSampler", inputTexId, 0)
        program.bindAttributesAndUniforms()
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
    }

    private fun drawSplitComposite(inputTexId: Int, framebuffer: Int, viewport: IntArray) {
        val target = splitTarget ?: return
        val program = splitProgram ?: GlProgram(VERTEX_SHADER, SPLIT_SHADER).also {
            it.setBufferAttribute(
                "aFramePosition",
                GlUtil.getNormalizedCoordinateBounds(),
                GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE,
            )
            splitProgram = it
        }
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, framebuffer)
        GLES20.glViewport(viewport[0], viewport[1], viewport[2], viewport[3])
        program.use()
        program.setSamplerTexIdUniform("uProcessed", target.texture, 0)
        program.setSamplerTexIdUniform("uOriginal", inputTexId, 1)
        program.bindAttributesAndUniforms()
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
    }

    private fun createFramebuffer(textureId: Int): Int {
        val ids = IntArray(1)
        GLES20.glGenFramebuffers(1, ids, 0)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, ids[0])
        GLES20.glFramebufferTexture2D(
            GLES20.GL_FRAMEBUFFER,
            GLES20.GL_COLOR_ATTACHMENT0,
            GLES20.GL_TEXTURE_2D,
            textureId,
            0,
        )
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        return ids[0]
    }

    private fun deleteFramebuffer(id: Int) {
        GLES20.glDeleteFramebuffers(1, intArrayOf(id), 0)
    }

    private fun releaseTarget(target: Target) {
        try {
            GlUtil.deleteTexture(target.texture)
        } catch (e: GlUtil.GlException) {
            AppLog.e("Anime4kChain", "delete target texture failed", e)
        }
        deleteFramebuffer(target.framebuffer)
    }

    private fun releaseTargets() {
        targets.forEach { releaseTarget(it) }
        targets.clear()
        splitTarget = null
    }

    private fun releaseGl() {
        try {
            passes.forEach { it.program.delete() }
            copyProgram?.delete()
            splitProgram?.delete()
        } catch (e: GlUtil.GlException) {
            AppLog.e("Anime4kChain", "release programs failed", e)
        }
        passes.clear()
        releaseTargets()
        copyProgram = null
        splitProgram = null
    }

    private class Target(val texture: Int, val framebuffer: Int, val width: Int, val height: Int)

    private class BoundBinding(
        val name: String,
        val textureId: Int,
        val isInput: Boolean,
        val width: Int,
        val height: Int,
    ) {
        var sampler = true
    }

    private class CompiledPass(
        val program: GlProgram,
        val bindings: List<BoundBinding>,
        val targetFramebuffer: Int,
        val width: Int,
        val height: Int,
        val writesScreen: Boolean,
    )

    private companion object {

        const val VERTEX_SHADER = """
            attribute vec4 aFramePosition;
            varying vec2 vTexCoord;
            void main() {
              gl_Position = aFramePosition;
              vTexCoord = aFramePosition.xy * 0.5 + 0.5;
            }
            """

        const val COPY_SHADER = """
            precision mediump float;
            uniform sampler2D uTexSampler;
            varying vec2 vTexCoord;
            void main() {
              gl_FragColor = texture2D(uTexSampler, vTexCoord);
            }
            """

        const val SPLIT_SHADER = """
            precision mediump float;
            uniform sampler2D uProcessed;
            uniform sampler2D uOriginal;
            varying vec2 vTexCoord;
            void main() {
              if (abs(vTexCoord.x - 0.5) < 0.0015) {
                gl_FragColor = vec4(1.0);
              } else if (vTexCoord.x < 0.5) {
                gl_FragColor = texture2D(uOriginal, vTexCoord);
              } else {
                gl_FragColor = texture2D(uProcessed, vTexCoord);
              }
            }
            """
    }
}
