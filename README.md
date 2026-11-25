# تدفّق | Tadaffuq

مدير تنزيل عربي أصلي لأندرويد (9 فأحدث): يسرّع تنزيل الملفات بتعدد الاتصالات، ويستأنفها بأمان، ويديرها في طابور ذكي — بلا حساب ولا إعلانات ولا متتبعات.

> **الحالة: 1.0.0-rc3 (مرشح إصدار، غير منشور).** أُصلحت عيوب P0 العشرة في وثيقة التدقيق وأغلب P1، والوظائف مختبرة آلياً (117 اختباراً)، لكن **لم تُجرَّب على هاتف حقيقي** بعد. انظر [حالة التدقيق](AUDIT_STATUS.md) و[قائمة اختبار الهاتف](docs/PHONE_TEST_CHECKLIST.md) و[التقرير النهائي](docs/FINAL_REPORT.md).

## المزايا
- **المحرك:** حتى 32 اتصالاً، تقسيم ديناميكي، تحميل ذكي، حد اتصالات يتغير أثناء التنزيل، تراجع عند 429/503، ملفات تتجاوز 4 غ.ب، مرايا بمقارنة عيّنات، تحقق SHA-256/SHA-1/MD5.
- **استئناف آمن:** بعد انقطاع الشبكة أو قتل العملية أو إعادة التشغيل، ولا يدمج نسختين مختلفتين (ETag قوي أو Last-Modified + If-Range).
- **بروتوكولات:** HTTP/HTTPS، FTP/FTPS، SFTP، تورنت وMagnet؛ Proxy HTTP وSOCKS5.
- **الالتقاط:** بلاطة النسخ في الإعدادات السريعة، المشاركة، «فتح باستخدام»، روابط متعددة ومتسلسلة، TXT/CSV، متصفح متعدد التبويبات يلتقط روابط الملفات، مركز روابط.
- **التحكم:** طابور وأولويات وحزم، حد سرعة لكل ملف وعام، لوحة تحكم سريعة، Wi-Fi فقط، ملف مستقل لبيانات الهاتف، الشحن والبطارية والحرارة والمساحة، حدود البيانات، نافذة زمنية، موعد بدء.
- **الملفات:** تصنيف وقواعد تنظيم، فك ZIP/7Z/RAR4/TAR بحمايات وإنشاء ZIP، نسخ ونقل إلى SD/USB عبر SAF، سلة، مجلد خاص مشفّر بالبصمة، نسخ احتياطي.
- **المطورون:** GitHub Releases حسب معمارية الجهاز، Hugging Face وGGUF المجزأ، RPC محلي برمز، سجل تقني منقّح.
- **الواجهة:** عربية RTL أولاً وإنجليزية، فاتح/داكن/تلقائي، Material You ولون تمييز، عرض مضغوط، تقليل الحركة، شريط جانبي للأجهزة اللوحية، إشعار لكل ملف بأزرار، Widget، مشهد حي وطبيب تحميل بقراءات حقيقية.

## البناء والاختبار
```bash
./gradlew :engine:test :torrent:test :data:testDebugUnitTest :app:testDebugUnitTest   # 117 اختباراً
./gradlew :app:assembleDebug                      # APK لكل معمارية + شامل
./gradlew :app:assembleRelease                    # مصغّر بـ R8، موقّع إن وُجد المفتاح
./gradlew :app:bundleRelease                      # AAB لـ Google Play (أمر منفصل)
./gradlew :app:cyclonedxBom                       # SBOM
TADAFFUQ_SHOTS=$PWD/build/shots ./gradlew :app:testDebugUnitTest --tests '*ScreenshotTest*'
```
يحتاج JDK 17+ وAndroid SDK 36 (`local.properties` → `sdk.dir`).

**التوقيع:** `keystore.properties` (مستثنى من git) أو متغيرات `TADAFFUQ_KEYSTORE` و`TADAFFUQ_KEYSTORE_PASSWORD` و`TADAFFUQ_KEY_ALIAS`. في GitHub: السرّان `TADAFFUQ_KEYSTORE_BASE64` و`TADAFFUQ_KEYSTORE_PASSWORD` ثم تشغيل «حزم الإصدار» يدوياً (`.github/workflows/release.yml`) فينتج APK وAAB وSBOM مع `SHA256SUMS`. لا نشر تلقائي.

## البنية
```
engine/   Kotlin/JVM خالص: HTTP وFTP وSFTP، التقسيم، الاستئناف، المرايا، الأرشيفات، التشفير، GitHub/HF، RPC، الطبيب
torrent/  غلاف libtorrent4j (Magnet، اختيار الملفات، الأقران، الاستئناف)
data/     Room (v4 بترحيلات مختبرة) + DataStore + قواعد التنظيم
app/      المدير والخدمة الأمامية وWorkManager والبلاطة والإشعارات والـ Widget وواجهات Compose
```

## الوثائق
[مصفوفة المتطلبات](docs/REQUIREMENTS_MATRIX.md) · [التقرير النهائي](docs/FINAL_REPORT.md) · [الأمان](docs/SECURITY.md) · [الأداء](docs/PERFORMANCE.md) · [قائمة اختبار الهاتف](docs/PHONE_TEST_CHECKLIST.md) · [الخصوصية](docs/PRIVACY.md) · [RPC](docs/RPC.md) · [بطاقة المتجر](docs/store/) · [التراخيص](docs/THIRD_PARTY_NOTICES.md) · [المراجع](REFERENCE_REVIEW.md) · [سجل التغييرات](CHANGELOG.md)

## الدعم والإبلاغ عن الأخطاء
افتح مشكلة في https://github.com/Hussein199800/tadaffuq/issues مع: طراز الهاتف وإصدار Android، الخطوات، والسجل التقني (المزيد ← قسم المطورين ← السجل التقني ← مشاركة؛ منقّح تلقائياً من الروابط الموقّعة والرموز والكوكيز).

تطوير: **حسين محمد علي الطويل**
