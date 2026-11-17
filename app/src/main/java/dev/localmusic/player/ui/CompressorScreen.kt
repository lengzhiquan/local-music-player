package dev.localmusic.player.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.localmusic.player.dsp.CompressorRange
import java.util.Locale
import kotlin.math.ln
import kotlin.math.pow

@Composable
fun CompressorScreen(vm: PlayerViewModel, onBack: () -> Unit) {
    val params by vm.compressor.collectAsState()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        TextButton(onClick = onBack) { Text("返回") }
        Text("动态范围压缩", style = MaterialTheme.typography.headlineMedium)
        Text(
            "Dynamic Range Compressor。关闭时播放链路原样通过，不改变音量。",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surfaceVariant,
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("启用压缩", style = MaterialTheme.typography.titleMedium)
                    Text(
                        if (params.enabled) "正在处理播放中的音频" else "已旁路",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = params.enabled, onCheckedChange = vm::setCompressorEnabled)
            }
        }
        ParameterControl(
            title = "阈值",
            english = "Threshold",
            value = params.thresholdDb,
            range = CompressorRangeView.threshold,
            explanation = "开始压缩的电平。0 dBFS 是数字满幅，数值越负，越早开始压。",
            onChange = vm::setCompressorThreshold,
        )
        ParameterControl(
            title = "压缩比",
            english = "Ratio",
            value = params.ratio,
            range = CompressorRangeView.ratio,
            explanation = "超过阈值后，输入再大 1 dB，输出只增加 1/Ratio dB。1.0 : 1 等于不压缩。",
            onChange = vm::setCompressorRatio,
        )
        ParameterControl(
            title = "启动时间",
            english = "Attack",
            value = params.attackMs,
            range = CompressorRangeView.attack,
            explanation = "声音突然变响时，增益下降到目标约 63% 所需的时间。",
            logarithmic = true,
            onChange = vm::setCompressorAttack,
        )
        ParameterControl(
            title = "释放时间",
            english = "Release",
            value = params.releaseMs,
            range = CompressorRangeView.release,
            explanation = "声音回落时，增益恢复到目标约 63% 所需的时间。",
            logarithmic = true,
            onChange = vm::setCompressorRelease,
        )
        ParameterControl(
            title = "拐点软硬",
            english = "Knee",
            value = params.kneeDb,
            range = CompressorRangeView.knee,
            explanation = "0 dB 是硬拐点，一过阈值就按压缩比折弯。数值越大，过渡越缓。",
            onChange = vm::setCompressorKnee,
        )
        ParameterControl(
            title = "补偿增益",
            english = "Make-up Gain",
            value = params.makeupDb,
            range = CompressorRangeView.makeup,
            explanation = "压缩之后整体抬高的电平。0 dB 表示不补偿。",
            onChange = vm::setCompressorMakeup,
        )
        Text(
            "开启后若补偿把样本顶过满幅，内部会把该样本限制在 0 dBFS 以内，避免数字爆音。这项保护不是上面的参数，关闭压缩时也不会作用在声音上。",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(12.dp))
    }
}

private data class SliderSpec(
    val range: CompressorRange,
    val format: (Float) -> String,
)

private object CompressorRangeView {
    val threshold = SliderSpec(dev.localmusic.player.dsp.CompressorRanges.THRESHOLD) { "${num(it)} dBFS" }
    val ratio = SliderSpec(dev.localmusic.player.dsp.CompressorRanges.RATIO) { "${num(it)} : 1" }
    val attack = SliderSpec(dev.localmusic.player.dsp.CompressorRanges.ATTACK) { "${num(it)} ms" }
    val release = SliderSpec(dev.localmusic.player.dsp.CompressorRanges.RELEASE) { "${num(it, 0)} ms" }
    val knee = SliderSpec(dev.localmusic.player.dsp.CompressorRanges.KNEE) {
        if (it < 0.05f) "0 dB · 硬拐点" else "${num(it)} dB"
    }
    val makeup = SliderSpec(dev.localmusic.player.dsp.CompressorRanges.MAKEUP) { "${num(it, 1, signed = true)} dB" }
}

@Composable
private fun ParameterControl(
    title: String,
    english: String,
    value: Float,
    range: SliderSpec,
    explanation: String,
    logarithmic: Boolean = false,
    onChange: (Float) -> Unit,
) {
    val focus = LocalFocusManager.current
    var draft by remember(english) { mutableStateOf(plainNumber(value, range.range)) }
    var focused by remember { mutableStateOf(false) }
    LaunchedEffect(value, focused) {
        if (!focused) draft = plainNumber(value, range.range)
    }
    val sliderValue = if (logarithmic) logPosition(value, range.range) else linearPosition(value, range.range)
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("$title  $english", style = MaterialTheme.typography.titleMedium)
                    Text(range.format(value), color = MaterialTheme.colorScheme.primary)
                }
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    modifier = Modifier
                        .width(118.dp)
                        .onFocusChanged { state ->
                            if (focused && !state.isFocused) commitDraft(draft, range.range, onChange)
                            focused = state.isFocused
                        },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = {
                        commitDraft(draft, range.range, onChange)
                        focus.clearFocus()
                    }),
                    label = { Text(range.range.unit) },
                )
            }
            Slider(
                value = sliderValue,
                onValueChange = { position ->
                    val next = if (logarithmic) expPosition(position, range.range) else lerp(range.range.min, range.range.max, position)
                    onChange(next)
                    draft = plainNumber(next, range.range)
                },
                valueRange = 0f..1f,
            )
            Text(explanation, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
            Text(
                "${plainNumber(range.range.min, range.range)} – ${plainNumber(range.range.max, range.range)} ${range.range.unit}",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

private fun plainNumber(value: Float, range: CompressorRange): String {
    return if (range.step >= 1f) num(value, 0) else num(value)
}

private fun num(value: Float, digits: Int = 1, signed: Boolean = false): String {
    val pattern = if (signed) "%+.${digits}f" else "%.${digits}f"
    return String.format(Locale.US, pattern, value)
}

private fun commitDraft(draft: String, range: CompressorRange, onChange: (Float) -> Unit) {
    draft.toFloatOrNull()?.let { onChange(range.clamp(it)) }
}

private fun linearPosition(value: Float, range: CompressorRange): Float {
    return ((value - range.min) / (range.max - range.min)).coerceIn(0f, 1f)
}

private fun lerp(min: Float, max: Float, t: Float): Float = min + (max - min) * t

private fun logPosition(value: Float, range: CompressorRange): Float {
    val safe = value.coerceAtLeast(range.min)
    return (ln(safe / range.min) / ln(range.max / range.min)).toFloat().coerceIn(0f, 1f)
}

private fun expPosition(position: Float, range: CompressorRange): Float {
    return range.min * (range.max / range.min).pow(position)
}
