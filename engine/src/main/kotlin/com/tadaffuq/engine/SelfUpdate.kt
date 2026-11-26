package com.tadaffuq.engine

/**
 * التحديث من داخل التطبيق (نسخة GitHub فقط): يختار من إصدارات هذا المستودع أحدث APK يناسب الجهاز.
 * شروط القبول: الأصل من صفحة إصدارات المستودع نفسه فقط، واسمه بصيغة حزم تدفّق، وversionCode أعلى من المثبّت،
 * وبصمته SHA-256 منشورة (من SHA256SUMS أو digest). بلا بصمة لا تحديث. التوقيع يُتحقق منه لاحقاً على الجهاز.
 */
object SelfUpdate {
    const val REPO = "Hussein199800/tadaffuq"
    const val DOWNLOAD_PREFIX = "https://github.com/$REPO/releases/download/"
    const val RELEASES_PAGE = "https://github.com/$REPO/releases/latest"

    /** tadaffuq-<الإصدار>-<versionCode>-<commit>-github-<المعمارية>-release.apk */
    private val NAME = Regex("""^tadaffuq-(\d+\.\d+\.\d+(?:-[A-Za-z0-9.]+)?)-(\d+)-([0-9a-f]{7,40}|local)-github-(arm64-v8a|armeabi-v7a|x86_64|x86|universal)-release\.apk$""")

    data class Candidate(
        val versionName: String,
        val versionCode: Int,
        val abi: String,
        val asset: ReleaseAsset,
        val sha256: String,
        val notes: String,
        val tag: String,
    )

    data class Parsed(val versionName: String, val versionCode: Int, val abi: String)

    fun parseName(name: String): Parsed? = NAME.matchEntire(name)?.let { m ->
        Parsed(m.groupValues[1], m.groupValues[2].toIntOrNull() ?: return null, m.groupValues[4])
    }

    /**
     * @param abis معماريات الجهاز بالترتيب (Build.SUPPORTED_ABIS)؛ «universal» احتياط أخير.
     * @param checksums اسم الأصل → SHA-256 (من [GitHubReleases.checksums]).
     */
    fun pick(releases: List<Release>, currentCode: Int, abis: List<String>, checksums: (Release) -> Map<String, String>, allowPrerelease: Boolean = false): Candidate? {
        val order = abis + "universal"
        return releases.asSequence()
            .filter { allowPrerelease || !it.prerelease }
            .flatMap { rel ->
                rel.assets.asSequence().mapNotNull { a ->
                    val p = parseName(a.name) ?: return@mapNotNull null
                    if (!a.url.startsWith(DOWNLOAD_PREFIX) || p.versionCode <= currentCode || p.abi !in order) return@mapNotNull null
                    Triple(rel, a, p)
                }
            }
            .sortedWith(compareByDescending<Triple<Release, ReleaseAsset, Parsed>> { it.third.versionCode }.thenBy { order.indexOf(it.third.abi) })
            .firstNotNullOfOrNull { (rel, a, p) ->
                val sha = (a.sha256 ?: checksums(rel)[a.name])?.lowercase()?.takeIf { Regex("^[0-9a-f]{64}$").matches(it) } ?: return@firstNotNullOfOrNull null
                Candidate(p.versionName, p.versionCode, p.abi, a, sha, rel.notes.take(4000), rel.tag)
            }
    }

    /** ملاحظات الإصدار نصاً عادياً (بلا عناوين Markdown ولا **). */
    fun plainNotes(md: String): String = md
        .replace(Regex("(?m)^#{1,6}\\s+"), "")
        .replace(Regex("\\*\\*(.+?)\\*\\*"), "$1")
        .replace(Regex("(?m)^\\s*[-*]\\s+"), "• ")
        .replace(Regex("\n{3,}"), "\n\n")
        .trim()

    /** هل مضى وقت كافٍ منذ آخر فحص تلقائي. */
    fun shouldCheck(lastAtMs: Long, nowMs: Long, hours: Int = 12) = lastAtMs <= 0 || nowMs - lastAtMs >= hours * 3_600_000L
}
