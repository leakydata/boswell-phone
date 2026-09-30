package net.boswell.phone

import net.boswell.phone.sync.SpoolDrainer
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** Local only: drain a copy of a real desktop spool file (-Dparity.raw=..., -Dparity.out=dir). */
class RealSpoolTest {
    @Test fun drainRealSpool() {
        val raw = System.getProperty("parity.raw")?.let(::File)
        val out = System.getProperty("parity.out")?.let(::File)
        assumeTrue(raw != null && raw.exists() && out != null)
        out!!.mkdirs()
        val copy = File(out, raw!!.name).also { raw.copyTo(it, overwrite = true) }   // never touch the original
        val r = SpoolDrainer(out, "c4b3fd7f1e91").drain(copy, File(out, "kept"))
        File(out, "result.txt").writeText("clips=${r.clips.size} frames=${r.frames} bad=${r.bad} undated=${r.undated} kept=${r.kept}\n" +
            r.clips.take(5).joinToString("\n") { it.name })
    }
}
