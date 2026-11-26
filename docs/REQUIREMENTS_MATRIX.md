# مصفوفة المتطلبات — تدفّق 1.0.0-rc5

الحالات: ✅ منفّذ ومختبر آلياً · 🟡 منفّذ ويحتاج تجربة على هاتف · ⚠️ منفّذ بقيد موثّق · ❌ غير منفّذ · ⛔ مستبعد صراحة.
المراجع: `docs/ORIGINAL_SPEC.md` و`docs/RELEASE_PLAN.md`. أسماء الاختبارات تشير إلى ملفات `src/test`.

## الخطة §3 — المحرك والشبكات
| المتطلب | الحالة | الدليل |
|---|---|---|
| HTTP/HTTPS متعدد الاتصالات حتى 32 | ✅ | `HttpDownloaderTest`، `PerformanceTest` |
| FTP/FTPS/SFTP مع مصادقة آمنة | ✅ | `RemoteProtocolsTest` (خوادم حقيقية) |
| Proxy HTTP وSOCKS5، Headers/Cookies/Referer/User-Agent | ✅ منطق · 🟡 Proxy على الهاتف | `HttpClients`، `ClientProvider` |
| تقسيم ديناميكي متكيف، احترام حدود الخادم، تراجع 429/503، تأخير متزايد | ✅ | `dynamicSplitting…`، `tooManyRequestsReducesConcurrency`، `serverErrorsAreRetried…` |
| ملفات كبيرة، حجم مجهول، دون Range، تحويلات، Content-Range | ✅ | `resumesBeyondFourGigabytes`، `unknownSizeStream…`، `serverWithoutRange…` |
| استئناف آمن بعد الانقطاع/قتل العملية/إعادة التشغيل، تقدم متسق مع المكتوب | ✅ · 🟡 إعادة التشغيل | `pauseResumeAndProcessRestart…`، `offlineHoldsQueue…` |
| ETag قوي أو دليل موثوق قبل دمج الجلسات، وإلا بداية آمنة | ✅ | `sameContentRules`، `changedFileOnServer…`، `contentChangingMidDownload…` |
| تحديث الروابط المنتهية والمرايا دون خلط النسخ | ✅ | `MirrorTest`، «تحديث الرابط» |
| حدود سرعة عامة وفردية، أولويات، متزامن، حزم، إجراءات جماعية | ✅ | `speedLimitIsRespected`، المدير |
| SHA-256/MD5، المساحة، تعارض الأسماء، التكرار، لا «مكتمل» قبل التحقق | ✅ | `checksumMismatchIsFlagged`، `nameConflictNeverOverwrites`، `insufficientSpace…` |
| إنترنت ضعيف، ملفات تعريف الشبكة، اختبار المصدر، المرآة الأسرع، وقت متبقٍّ واقعي | ✅ | `mobileProfileApplies…`، `MirrorSelector` |
| RPC محلي محمي، دون ادعاء توافق aria2 | ✅ | `RpcServerTest`، `docs/RPC.md` |

## §4 — التورنت
| Magnet و.torrent، اختيار الملفات، DHT/PEX/Trackers، الأقران، حدود الرفع، النسبة، إعادة الفحص، الاستئناف | ✅ محلياً · 🟡 على الإنترنت | `TorrentEngineTest` |
|---|---|---|
| توضيح الرفع قبل البدء وقابليته للتحكم | ✅ | نافذة إضافة التورنت، الرفع معطّل بعد الاكتمال افتراضياً |

## §5 — المتصفح والروابط
| متصفح متعدد التبويبات، سجل ومفضلة وخاص، بحث، سطح مكتب، حجب، التقاط روابط الملفات | 🟡 | `BrowserTabs` |
|---|---|---|
| المشاركة، فتح الروابط، روابط متعددة، TXT/CSV، الاستخراج، المتسلسل، إزالة التكرار، مركز روابط | ✅ | `linkExtraction…`، `sequenceExpansion`، `csvImport` |
| بلاطة النسخ (أولوية) | 🟡 | `QuickTileService` + `QuickAddActivity` |
| لا مراقبة حافظة في الخلفية | ✅ | تُقرأ عند البلاطة أو فتح التطبيق فقط |
| استقبال روابط الحاسوب باقتران آمن | ⚠️ | RPC برمز؛ لا إضافة متصفح منشورة |
| إدارة الكوكيز | ⚠️ | تُلتقط تلقائياً مع التنزيل؛ لا شاشة لتحرير كوكيز المتصفح |

## §6 — الملفات والتخزين
| تصنيف، فرز، بحث، تحديد متعدد، إعادة تسمية، حذف، مشاركة، نقل/نسخ | ✅ · 🟡 SAF | `FilesScreen` |
|---|---|---|
| قواعد تنظيم تلقائية، سلة، التكرار والكبير والمؤقت | ✅ | `RulesTest`، أداة التنظيف |
| SD وUSB عبر SAF دون وصول شامل | 🟡 | `TaskFiles` |
| فك ZIP/RAR/7Z وإنشاء ZIP بحمايات | ✅ · ⚠️ RAR5 غير مدعوم | `ArchivesTest` |
| مجلد خاص مشفّر بـ Keystore وبصمة، مع توضيح حدود الاستعادة | ✅ تشفير · 🟡 البصمة | `VaultCryptoTest` |
| نسخ احتياطي دون أسرار | ✅ | `BackupTest` |

## §7 — الجدولة والأتمتة
| موعد مرة واحدة، نافذة يومية متكررة، Wi-Fi/شحن/بطارية/حرارة/مساحة/بيانات، استئناف عند تحقق الشروط | ✅ منطق · 🟡 التوقيت | `timeWindowAndStorageFloor`، `holds` |
|---|---|---|
| قواعد حسب النوع والمصدر والحجم، فك بعد الاكتمال، إشعار | ✅ | `RulesTest`، `postActions` |
| توضيح أن الجدولة تقريبية | ✅ | نص الواجهة |

## §8 — المطورون
| GitHub Releases حسب المعمارية مع البصمات | ✅ | `DevSourcesTest` |
|---|---|---|
| Hugging Face وGGUF المجزأ، رمز محمي، حدود API، روابط موقّعة | ✅ | `DevSourcesTest`، `hubAuth` |
| سجلات تقنية منقّحة | ✅ | `techLogRedactsSecrets` |

## §9 — الإحصائيات والتفاعلية
| سرعة لحظية/متوسط/ذروة، المنقول والمتبقي، الاستهلاك، النجاح والفشل، CSV | ✅ | `ReportsScreen`، لقطات |
|---|---|---|
| مشهد حي من قراءات حقيقية يتوقف مع التوقف | ✅ | `LiveScene` |
| طبيب يفرق DNS/TLS/الخادم/المهلة/التخزين/الأذونات/الخلفية | ✅ | `doctorFindings`، `NetFail` |
| إشعارات حقيقية بأزرار، Widget، بلاطة، اختصارات | ✅ · 🟡 | `NotifierTest` |

## §10 — الهوية وتجربة الاستخدام
| التبويبات الخمسة، RTL/LTR، فاتح/داكن، ألوان قابلة للتخصيص | ✅ | `ScreenshotTest` |
|---|---|---|
| تكبير الخط، قارئ الشاشة، أهداف اللمس، الشاشات الكبيرة، تقليل الحركة | ✅ تنفيذ · 🟡 تجربة | شريط جانبي، `LocalReduceMotion` |
| إعدادات متقدمة منفصلة | ✅ | `AdvancedSettingsScreen` |

## §12–14 — الأمان والإصدار
| مراجعة المكونات المصدّرة وWebView وRPC والأسرار | ✅ | `docs/SECURITY.md` |
|---|---|---|
| SBOM وإشعارات التراخيص | ✅ | `docs/sbom.json`، `THIRD_PARTY_NOTICES.md` |
| Release مصغّر وAAB وإشارة 16KB | ✅ بناء · 🟡 تشغيل | Lint 0 أخطاء |
| CI للبناء والاختبار وحزم موقّعة مع بصماتها | ✅ | `ci.yml`، `release.yml` |
| بطاقة المتجر عربي/إنجليزي، سياسة الخصوصية، Data Safety، سجل التغييرات | ✅ | `docs/store/`، `PRIVACY.md`، `CHANGELOG.md` |
| لقطات المتجر الفعلية | ❌ | تُؤخذ من الهاتف |
| النشر | ⛔ بانتظار موافقتك | — |

## §15 — المستبعد
⛔ استخراج الفيديو/الصوت/الصور، قوائم المنصات، البث، التحويل، الضغط، التحرير، التشغيل. تنزيل ملف وسائط برابط عادي مسموح.

---

# سجل التدقيق

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
