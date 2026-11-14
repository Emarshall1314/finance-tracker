package ca.emarshall.finance.core

/** Loads synthetic fixtures. Every file under fixtures/ is made up and only mimics a bank's layout. */
object Fixtures {
    fun bytes(name: String): ByteArray =
        requireNotNull(javaClass.classLoader.getResourceAsStream("fixtures/$name")) { "missing fixture $name" }.use { it.readBytes() }

    fun text(name: String): String = String(bytes(name), Charsets.UTF_8)
}
