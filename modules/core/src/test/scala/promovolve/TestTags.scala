package promovolve

import org.scalatest.Tag

/**
 * Tests that reach outside the JVM: a live LLM provider (needs a real API
 * key and the network) or a host tool such as ffmpeg. They are NOT part of
 * the behavioral gate — `build.sbt` excludes this tag from `sbt test` so a
 * clean checkout is deterministic with no credentials and no host setup.
 *
 * Run them deliberately, with the relevant keys in the environment:
 * {{{
 * sbt "testOnly * -- -n promovolve.Integration"
 * }}}
 *
 * A test carrying this tag may still `cancel`/`assume` itself when its key
 * or tool is missing — the tag is what keeps it out of CI, the cancel is
 * what keeps a local full run honest.
 */
object Integration extends Tag("promovolve.Integration")
