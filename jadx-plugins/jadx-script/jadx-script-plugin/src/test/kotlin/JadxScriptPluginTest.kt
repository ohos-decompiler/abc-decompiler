package jadx.plugins.script

import jadx.api.JadxArgs
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.io.File
import kotlin.system.measureTimeMillis
import kotlin.time.DurationUnit
import kotlin.time.toDuration

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class JadxScriptPluginTest {

	@BeforeAll
	fun disableCache() {
		System.setProperty("JADX_SCRIPT_CACHE_ENABLE", "false")
	}

	@AfterAll
	fun clear() {
		System.clearProperty("JADX_SCRIPT_CACHE_ENABLE")
	}

	@Test
	fun integrationTest() {
		val args = JadxArgs()
		args.inputFiles.run {
			add(getSampleFile("hello.smali"))
			add(getSampleFile("test.jadx.kts"))
			add(getSampleFile("test-deps.jadx.kts"))
		}
		val elapsed = measureTimeMillis {
		}
		println("Elapsed time: ${elapsed.toDuration(DurationUnit.MILLISECONDS)}")
	}

	private fun getSampleFile(file: String): File {
		val resFile = javaClass.classLoader.getResource("samples/$file")
		return File(resFile!!.toURI())
	}
}
