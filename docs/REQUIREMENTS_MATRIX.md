# مصفوفة المتطلبات — تدفّق

الحالات: ✅ منفّذ ومختبر آلياً · 🟡 منفّذ ويحتاج تجربة على هاتف · ⏳ قيد العمل · ❌ غير منفّذ · ⛔ مستبعد صراحة.
المرجع: `docs/ORIGINAL_SPEC.md` و`docs/RELEASE_PLAN.md`. يُحدَّث هذا الملف مع كل مرحلة.

## تدقيق المرحلة الأولى (قبل التوسع)

إعادة تشغيل الاختبارات الخمسين في المستودع الجديد: 49 نجحت و**فشل اختبار واحد** (`pauseResumeAndProcessRestartKeepIntegrity`).
السبب: محاكاة «موت العملية» في الاختبار تركت كود الإلغاء يكتب حالة «متوقف» بعد بدء المدير الجديد. في الموت الحقيقي لا يعمل أي كود، فصُحّحت المحاكاة (`DownloadManager.kill`). أُعيد الاختبار 5 مرات مع `--rerun` ونجح.

| المتطلب | التنفيذ | الدليل | الحالة |
|---|---|---|---|
| حتى 32 اتصالاً | `DownloadConfig.MAX_CONNECTIONS`، شريط الإعدادات 1..32 | `PureLogicTest`، الواجهة | ✅ |
| التقسيم الديناميكي | `SegmentPlanner.splitLargest` | `dynamicSplittingKeepsConnectionsBusy` | ✅ |
| اختيار عدد الاتصالات تلقائياً | `autoConnections` + `AutoTune` | `autoTuneBacksOffWhenNoGain` | ✅ |
| حد السرعة لكل مهمة وعام | `RateLimiter` | `speedLimitIsRespected` | ✅ |
| إيقاف/استئناف | `DownloadManager.pause/resume` | `pauseThenResume…`، `pauseResumeAndProcessRestart…` | ✅ |
| الاستئناف بعد قتل العملية | `moveStatus(RUNNING→QUEUED)` + أجزاء Room | `pauseResumeAndProcessRestart…` | ✅ |
| الاستئناف بعد إعادة تشغيل الجهاز | `BootReceiver` → `ResumeWorker` | بناء فقط | 🟡 |
| رفض الاستئناف غير الآمن | `Prober.sameContent` + If-Range | `changedFileOnServer…`، `contentChangingMidDownload…` | ✅ (يُشدَّد في المرحلة 2) |
| خوادم دون Range / حجم مجهول | مسار اتصال واحد | `serverWithoutRange…`، `unknownSizeStream…` | ✅ |
| إعادة المحاولة التصاعدية | `HttpDownloader.backoff` | `serverErrorsAreRetried…`، `connectionDrops…` | ✅ |
| فحص المساحة | قبل الكتابة | `insufficientSpace…` | ✅ |
| عدم الكتابة فوق ملف | `FileNames.uniqueIn` | `nameConflictNeverOverwrites` | ✅ |
| SHA-256/MD5 | `Hashes` | `hashDetectAndVerify`، `checksumMismatchIsFlagged` | ✅ |
| الطابور والأولويات والمتزامن | `DownloadManager.schedule` | تكامل | ✅ |
| الشروط (Wi-Fi/شحن/بطارية/حرارة/حدود بيانات) | `Policy.hold` | `PureAppLogicTest.holds`، `offlineHoldsQueue…` | ✅ (منطق) 🟡 (على الجهاز) |
| المشهد الحي بقراءات حقيقية | `LiveScene` من `live` | لقطات | ✅ |
| طبيب التحميل | `Doctor` | `doctorFindings` | ✅ (يُوسَّع: DNS/TLS/مهلة) |
| المتصفح (تبويبات، خاص، مفضلة، سجل، بحث، سطح مكتب، حجب) | `BrowserTabs`، `BrowserScreen` | بناء فقط | 🟡 |
| الملفات والسلة والتنظيف | `FilesScreen`، `MainViewModel` | لقطة | 🟡 |
| التقارير وCSV | `ReportsScreen` | لقطة | 🟡 |
| العربية/الإنجليزية، فاتح/داكن | موارد + `TadaffuqTheme` | 11 لقطة | ✅ |
| الإشعارات والخدمة الأمامية والبلاطة | `DownloadService`، `QuickTileService` | lint فقط | 🟡 |

## وظائف سقطت أو أُجّلت في المرحلة الأولى (تُعالج الآن)
FTP/FTPS/SFTP · Proxy/SOCKS5 من الواجهة · المرايا · تراجع الاتصالات عند 429/503 · ملفات > 4GB (اختبار) · التورنت · الجدولة وملفات التعريف والقواعد · الإجراءات بعد الاكتمال · الأرشيفات · SAF/SD/USB · المجلد المشفّر · النسخ الاحتياطي · قسم المطورين · RPC والاقتران · Widget · إدارة الكوكيز · تقليل الحركة والشاشات الكبيرة · SBOM والتراخيص · حزم الإنتاج.

## طلب التحسينات (الإشعارات، بلاطة النسخ، الإعدادات، لوحة التحكم، التخصيص)

| المتطلب | التنفيذ | الدليل | الحالة |
|---|---|---|---|
| إشعار لكل ملف: الاسم والنسبة والشريط والسرعة والمنجز/الإجمالي والوقت المتبقي | `Notifier.task` | `NotifierTest.perTaskNotificationShowsRealProgressAndActions` | ✅ منطق · 🟡 على الهاتف |
| إيقاف/استئناف/إلغاء من الإشعار | `NotificationActionReceiver` (PAUSE/RESUME/CANCEL) | بناء | 🟡 |
| إشعار إجمالي للتنزيلات المتعددة | ملخص `setGroupSummary` | بناء | 🟡 |
| الصوت والاهتزاز وإشعارات الاكتمال والفشل | قنوات `done_{s|q}{v|n}` + إعدادات | `NotifierTest.soundAndVibrationChannels` | ✅ |
| بلاطة النسخ | `QuickTileService` ← `QuickAddActivity` شفاف يقرأ الحافظة عند التركيز (قيد Android 10+) | بناء | 🟡 يحتاج هاتفاً |
| مجلد الحفظ | الافتراضي + مجلد SAF ينقل إليه بعد الاكتمال + مجلد فرعي عند الإضافة | بناء | 🟡 |
| المتزامن/الاتصالات/السرعة | إعدادات + حدّ اتصالات حيّ في المحرك | `LiveSettingsTest.loweringConnectionsMidDownload…` | ✅ |
| التحميل الذكي والتقسيم الديناميكي (قابلان للتعطيل) | `DownloadConfig.autoTune/dynamicSplit` | `LiveSettingsTest.dynamicSplitCanBeDisabled` | ✅ |
| الاستئناف التلقائي | `autoResume` | `withoutAutoResumeAnInterruptedTaskStaysPaused` | ✅ |
| المحاولات ومدة الانتظار | `maxRetries/retryDelaySec` → المحرك | تكامل المحرك | ✅ |
| Wi-Fi وبيانات الهاتف مستقلان | `Settings.forNetwork` | `mobileProfileAppliesOnlyOnCellular` | ✅ |
| Proxy وUser-Agent في المتقدمة | `AdvancedSettingsScreen` | لقطة | ✅ |
| لوحة التحكم السريعة | `QuickPanel` في التنزيلات | لقطة | ✅ |
| فاتح/داكن/تلقائي، Material You، لون التمييز، عرض مضغوط، خريطة الأجزاء في القائمة، تقليل الحركة | `TadaffuqTheme` + إعدادات | لقطات | ✅ |
| صفحة «حول» بنمط سَيْل | `AboutScreen` | لقطة | ✅ |
