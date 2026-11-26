# حالة تنفيذ وثيقة التدقيق (TADAFFUQ_CODE_AUDIT)

**ملاحظة أساسية:** التدقيق أُجري على أرشيف `main` (0.1.0)، بينما العمل كله على فرع `dev`. بعض ما وصفه التدقيق ناقصاً (بلاطة النسخ، إشعار لكل ملف، الإعدادات، البروتوكولات، التورنت…) كان منفذاً في `dev` قبل التدقيق؛ الجدول يذكر ذلك صراحة.

**الحالات:** موجود = منفذ ومختبر آلياً · جزئي = منفذ لكن ينقصه جزء أو اختبار · ناقص = غير منفذ · يحتاج هاتفاً = لا يمكن إثباته دون جهاز حقيقي.
لا يعني «موجود» أنه جُرّب على هاتف؛ لم يُجرَّب أي بند على جهاز حقيقي بعد.

**الإصدار:** `1.0.0-rc5` (versionCode 6) — يظهر مع الـ commit في «حول» وفي أسماء الحزم. لم يُرفع إلى 1.0.0.

## P0 — سلامة البيانات والتنزيل

كل اختبارات الانحدار أدناه كُتبت قبل الإصلاح وفشلت عليه (المحرك: 6 من 9؛ المدير: 8 من 9 — اختبار ترحيل الجزئي القديم نجح قبل الإصلاح لأنه كان يستعمل المسار القديم نفسه).

| الرمز | الحالة | ملف التنفيذ | الاختبار | النتيجة | commit |
|---|---|---|---|---|---|
| P0-01 مجلد غير موجود = قرص ممتلئ | موجود | `HttpDownloader.kt`، `SpaceLedger.kt` | `AuditRegressionTest.p0_01_*`، `LargeRealSpaceTest` (1.38GB و5GB في مجلدات غير موجودة بفحص مساحة حقيقي) | ناجح | `60a73e0` |
| P0-02 ملف جزئي مشترك وحجز الاسم | موجود | `StorageDirs.partFile(dir,name,id)`، `DownloadManager.enqueue/finalize/migrateLegacyPart` | `AuditManagerTest.p0_02_sameNameConcurrent…`، `…deletingOneOfTwo…`، `…legacyPartIsMigrated` | ناجح | `93bb6c7` |
| P0-03 اختفاء المعرّف القوي | موجود | `Prober.sameContent` | `p0_03_vanishedIdentifierForcesRestart`، `PureLogicTest.sameContentRules` | ناجح | `60a73e0` |
| P0-04 جزئي مقتطع | موجود | `HttpDownloader` (الطول = الحجم شرط للاستئناف) | `p0_04_truncatedPartialIsNotTrusted` | ناجح | `60a73e0` |
| P0-05 الإنهاء والبصمة | موجود | `DownloadManager.finalize/recoverInterrupted`، `Notifier.completed` | `p0_05_checksumFailureIsNotCompleted`، `…invalidReferenceIsReported`، `…deathAfterRenameRecovers`؛ `ManagerIntegrationTest` (موت العملية أثناء التنزيل) | ناجح. لم يُختبر فشل rename فعلي (الفرع موجود ويضع FAILED/STORAGE) | `93bb6c7` |
| P0-06 الإلغاء أثناء بث متوقف | موجود | `Http.withResponse` (onCancelling) | `p0_06_cancelDuringStalledBodyIsImmediate` (< 3 ث بدل 30) | ناجح | `60a73e0` |
| P0-07 416 و206 وContent-Range | موجود | `Prober.read`، `HttpDownloader.fetch` | `p0_07_*` (4 اختبارات) | ناجح | `60a73e0` |
| P0-08 Wi-Fi لكل مهمة أثناء العمل | موجود | `DownloadManager.schedule` | `p0_08_perTaskWifiOnlyStopsOnCellular` | ناجح (Robolectric؛ الانتقال الحقيقي يحتاج هاتفاً) | `93bb6c7` |
| P0-09 حدود البيانات | موجود | `UsageMeter` (المعلّق يُحتسب، مفتاح يوم+شبكة، منتصف الليل، فشل الكتابة)، `DownloadManager.onCellularBytes` | `p0_09_dailyLimitStopsRunningDownload` | ناجح. منتصف الليل منطق بلا اختبار زمني مستقل | `93bb6c7` |
| P0-10 الكوكيز والأسرار | موجود | `Http.SecretsGuard`، `RequestSpec.secretsHost`، `FieldCrypt` (Keystore)، `SessionScope` | `CookieScopeTest` (خادمان)، `p0_10_cookiesAreScopedAndEncrypted`، `PureAppLogicTest.sessionSecretsScopedToHost`، `BackupTest` (بلا أسرار) | ناجح. في Robolectric لا Keystore، فالمسار المختبر هو «في الذاكرة فقط» لا التشفير الفعلي | `058a7d4`، `93bb6c7` |

## P1 — الاستقرار والدقة

| البند | الحالة | التنفيذ | الاختبار / الدليل | commit |
|---|---|---|---|---|
| التقدم يرجع للخلف | موجود | `dao.stats` (قياسات فقط)، الأرقام مع نقطة الحفظ | اختبارات المدير كلها | `59352fb` |
| updateUrl بنسخة قديمة | موجود | `dao.setUrl` بعد join | — (لا اختبار تزامن مخصص) | `59352fb` |
| عدادات غير ذرية | موجود | `AtomicIntegerArray` | — | `59352fb` |
| خفض الاتصالات وAutoTune | موجود في dev قبل التدقيق | `liveMaxConnections`، `Yield`، `AutoTune.reopen` | `LiveSettingsTest` | — |
| إعدادات حية أثناء التنزيل | جزئي | حد الاتصالات والحرارة حيّان؛ المحاولات ووضع السرعة تُلتقط عند البدء | `LiveSettingsTest` | — |
| ميزانية المحاولات وفحص الرابط | موجود | تقدم ≥256KB يجدد الميزانية، سقف إجمالي، `probeWithRetry` وRetry-After بصيغتيه | `RetryBudgetTest` (فشلا قبل الإصلاح)، `EngineHardeningTest` (429) | `59352fb` |
| RateLimiter | جزئي | — | لم تُضف اختبارات تغيير الحد أثناء الانتظار/العدالة | — |
| ترتيب الإقلاع | موجود | الإعدادات ← الاستعادة ← الجدولة (`ready`) | اختبارات المدير | `59352fb` |
| WorkManager/الخدمة Android 12–16 | يحتاج هاتفاً | `ResumeWorker`، `tapToResume` | `PHONE_TEST_CHECKLIST` | — |
| hasWork مع Wi-Fi لكل مهمة | موجود | `pendingCount` يستثني المحجوب | — | `93bb6c7` |
| onLost أثناء الانتقال/VPN | جزئي | إعادة سؤال الشبكة الفعلية | يحتاج هاتفاً | `59352fb` |
| معنى Wi-Fi فقط | موجود | الوصف: «شبكة غير محدودة» | — | `54d8b70` |
| عمليات الملفات | موجود | السلة/الاستعادة/الحذف تتحقق من النتيجة؛ السجل يبقى عند الفشل؛ الاستبدال إلى السلة | — (لا اختبار آلي) | `59352fb` |
| CSV formula injection | موجود | `Csv.cell` | `PureAppLogicTest.csvCellsAreSafe` | `59352fb` |
| فتح الملف من الإشعار | موجود | البرامج والملف غير المتحقق منه تفتح التطبيق لا الملف | — | `c9a58a1` |

## P1 — ما يهم المستخدم يومياً

| البند | الحالة | ملاحظة |
|---|---|---|
| بلاطة النسخ | موجود في dev + زر إضافتها | `QuickTileService` + `QuickAddActivity` (قراءة الحافظة عند التركيز فقط)؛ `TileRequest` (Android 13+) وخطوات يدوية للأقدم. يحتاج هاتفاً (Samsung، 9/13/14/15/16) |
| الإشعارات الحية | موجود في dev | لكل ملف: الاسم والنسبة والحجم والسرعة والوقت وإيقاف/استئناف/إلغاء، وقنوات الصوت والاهتزاز (`NotifierTest`). يحتاج هاتفاً |
| إعدادات التحميل | موجود في dev | المجلد (SAF)، Proxy، ملف بيانات الهاتف، المحاولات والانتظار، الاستئناف التلقائي، لوحة سريعة. `autoVerifyHash` حُذف (`59352fb`) |
| التخزين | جزئي | مجلد احتياطي خاص بالتطبيق عند تعذّر الكتابة + تنبيه، عرض مجلد الحفظ، انتظار إذن Android 9 (`54d8b70`). **ناقص:** كتابة عبر MediaStore، وتنظيف الجزئيات اليتيمة |
| المتصفح والخاص | موجود | عزل بـ WebView Profiles حيث يدعمها الجهاز، ووصف صادق حيث لا؛ أُزيل `clearCache(true)` العام (`54d8b70`). يحتاج هاتفاً |

## P1 — التصميم والتفاعل
يحتاج هاتفاً: insets وIME والتداخل مع شريط النظام، Samsung S25 Ultra، الخط الكبير، الأفقي واللوحي، 60/120Hz، TalkBack. اللقطات الآلية (`ScreenshotTest`، 15 شاشة) راجعتها بعيني لكنها ليست بديلاً.

## بوابات الاختبار والتسليم

| البوابة | الحالة | الدليل |
|---|---|---|
| الاختبارات | ناجح | engine 71، torrent 2، data 2، app 61 — **136 اختباراً، 0 فشل** |
| lintRelease | ناجح | 0 أخطاء (55 تحذيراً بعد مراجعة الـ 71؛ المتبقي مشروح في FINAL_REPORT) |
| assembleDebug/Release، bundleRelease | ناجح | `tadaffuq-1.0.0-rc3-4-<sha>-*.apk` و`.aab` |
| تشغيل release المصغر على جهاز | يحتاج هاتفاً | لا محاكي: البيئة بلا KVM |
| معيار أداء مقابل ADM | ناقص | يحتاج هاتفاً والملف والخادم نفسيهما؛ القياس الحالي JVM (`docs/PERFORMANCE.md`) |
| CI: lint وAAB وفحص الأسرار والاعتماديات | أُضيف، لم يُشغَّل بعد | `ci.yml`: gitleaks + منع ملفات التوقيع + Dependency submission. لم أشغّل gitleaks محلياً |
| الهاتف الحقيقي | ناقص | الخطوات في `docs/PHONE_TEST_CHECKLIST.md` |
| رقم الإصدار والـ commit | موجود | «حول» + أسماء الحزم + اسم artifact في CI |

## تدقيق ما بعد الدمج (TADAFFUQ_RC3_FINAL_REVIEW)

«قبل» = اختبار فشل على الكود القديم قبل الإصلاح. اختبارات أخرى كُتبت مع الإصلاح وتغطي السيناريو لكنها لم تُشغَّل على الكود القديم، ويُذكر ذلك.

| البند | الحالة | التنفيذ | الاختبار | commit |
|---|---|---|---|---|
| P0 تسرب حجز SpaceLedger عند فشل الفتح/التهيئة | موجود | `HttpDownloader.run`: الحجز والفتح والتثبيت والإغلاق في try/finally واحد | `SpaceLeakTest` (فشل قبل: بقي 300000 بايت محجوزاً) | `1479ce0` |
| P0 النقل إلى SAF قبل تثبيت URI | موجود | `Publisher` (سجل `publishUri/publishLocal` في Room v5) + `recover` عند الإقلاع؛ النقل اليدوي من شاشة الملفات بالمسار نفسه | `PublisherTest` (موت بعد الإنشاء، بعد النسخ، بعد التثبيت؛ فشل الكتابة) — كُتب مع الإصلاح | `d15251b` |
| P1 وجهة SAF تُقرأ لحظة الاكتمال | موجود | `TaskEntity.treeUri` مثبّت عند الإضافة، يُغيَّر من تفاصيل المهمة؛ ترحيل لمرة واحدة للمهام السابقة؛ `MigrationTest` 1→5 | `MigrationTest` | `d15251b` |
| P1 التورنت خارج حدود الاستهلاك | موجود | فرق `downloaded+uploaded` كل ثانية → `recordExternalBytes` | `rc3_torrentBytesCountTowardLimit` | `f8e004b` |
| P1 فجوة UsageMeter | موجود | يُطرح المعلّق بعد قراءة المجموع؛ قفل يمنع الكتابة المكررة؛ فشل القراءة لا ينقص العداد | `UsageMeterTest` — كُتب مع الإصلاح | `f8e004b` |
| P1 إيقاف/استئناف الكل ومهلة الخدمة لا تشمل التورنت | موجود | `externalPauseAll/ResumeAll`؛ يُحفظ PAUSED فلا يعود وحده | — (يحتاج libtorrent على جهاز؛ البند 0.7 في قائمة الهاتف) | `f8e004b` |
| P1 الإشعار الإجمالي بلا التورنت | موجود | `ExternalLoad` في `Notifier.summary` | `NotifierTest` (التوقيع) — لا اختبار مخصص للتورنت | `f8e004b` |
| P1 MediaStore | جزئي | النشر عبر MediaStore (IS_PENDING) حين تتعذر الكتابة المباشرة (المجلد الاحتياطي)؛ الكتابة المباشرة في Download/تدفق تبقى المسار الأساسي حين تُتاح فعلاً | منطق النشر مختبر بـ `PublisherTest`؛ MediaStore الفعلي يحتاج جهازاً | `d15251b` |
| P1 تنظيف .tdq اليتيمة | موجود | `orphanParts/cleanOrphans` بمعرّفات المهام؛ الحجم والتأكيد في الإعدادات ← التخزين | `rc3_orphanCleanupIsSafe` | `f8e004b`، `2937f50` |
| P1 ذرية الإنهاء (الاسم والبصمة) | موجود | الجزئي `.<id>.tdq` مستقل عن الاسم (مع ترحيل مسارات rc2/rc3)؛ البصمة تُحفظ قبل rename وتُستعاد | `rc3_deathBeforeRenameAfterNameChange`، `rc3_deathAfterRenameKeepsHashResult` — كُتبا مع الإصلاح | `d15251b` |
| الإعدادات والتخصيص (§5) | موجود | مركز بستة مداخل، شرح سطر و«كيف يعمل؟»، رقائق بدل المنزلقات، 4 ثيمات بمعاينة، حجم خط، لوحة بثلاثة خيارات، QuickAdd بالثيم نفسه | 24 لقطة (قبل/بعد) راجعتها بعيني | `2937f50` |
| المراجع (§4) | موجود | قُرئت ملفات واجهات «طاقتي» و«سَيْل» لا README وحده | `REFERENCE_REVIEW.md` | هذا الـ commit |
| Lint | موجود | 0 أخطاء، 71→55 | `lintRelease` | `69af19c` |
| الهاتف، ADM، البطارية | ناقص | لا جهاز ولا محاكي | `docs/PHONE_TEST_CHECKLIST.md` §0 | — |
| التوقيع النهائي | ناقص (يخصك) | `release.yml` يوقّع من السرّين؛ المرسَل موقّع بمفتاح اختبار | — | — |

## إغلاق rc4 (TADAFFUQ_RC4_CLOSEOUT)
الحالة التفصيلية والأدلة في [`RELEASE_GATE.md`](RELEASE_GATE.md). باختصار: حد السرعة (موجود، اختباران فشلا قبله)، التورنت ضمن الحد (موجود)، MediaStore افتراضياً في Android 10+ (موجود، الفعلي يحتاج هاتفاً)، المساحة للنشر (موجود)، إيقاف العامل بأمان (موجود، يحتاج هاتفاً)، صيغ الجمع (موجود)، 16 KB (موجود، فحص الحزم)، OSV (صفر بعد junrar 7.6.1)، الهاتف وADM وR8 على جهاز (ناقص: لا جهاز).
