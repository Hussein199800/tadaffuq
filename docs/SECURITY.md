# تقرير الأمان — تدفّق 1.0.0-rc4

مراجعة يدوية مقابل OWASP MASVS (التخزين، التشفير، الشبكة، المنصة، الشيفرة، الخصوصية) على الـ Manifest النهائي المدمج لنسخة Release والشيفرة. لم يُجرَ اختبار اختراق آلي ولا فحص ثغرات بقاعدة NVD (يتطلب شبكة مفتوحة)؛ الاعتماديات بأحدث إصداراتها المستقرة وقت التنفيذ (انظر `docs/sbom.json`).

## المكوّنات المصدَّرة (من الـ Manifest المدمج)
| المكوّن | لماذا مصدَّر | الحماية |
|---|---|---|
| `MainActivity` | المشغّل، المشاركة، «فتح باستخدام»، Magnet و`.torrent` | كل رابط وارد يفتح نافذة إضافة تنتظر موافقة المستخدم (لا تنزيل تلقائي). ملف `.torrent` الوارد محدود بـ 20 م.ب. الإجراء الداخلي «استئناف الكل» لا يُنفَّذ إلا برمز عشوائي خاص بالتطبيق (`internalNonce`) — **أُصلح أثناء هذه المراجعة**. |
| `QuickTileService` | بلاطة الإعدادات السريعة | `BIND_QUICK_SETTINGS_TILE` (النظام فقط). من شاشة القفل يُطلب فتح القفل أولاً. |
| `SpeedWidgetReceiver` | تحديثات الـ Widget من النظام | يعالج إجراءات Glance فقط، ولا يقبل بيانات خارجية. |
| مكونات WorkManager/Glance/ProfileInstaller | من مكتبات AndroidX | محمية بصلاحيات النظام (`BIND_JOB_SERVICE`، `BIND_REMOTEVIEWS`، `DUMP`). |

غير مصدَّرة: `QuickAddActivity`، `DownloadService`، `NotificationActionReceiver`، `BootReceiver`، `FileProvider` (مشاركة بمنح مؤقت فقط).

## الصلاحيات
`INTERNET`، `ACCESS_NETWORK_STATE`، `FOREGROUND_SERVICE(_DATA_SYNC)`، `POST_NOTIFICATIONS`، `RECEIVE_BOOT_COMPLETED`، `WAKE_LOCK`، `USE_BIOMETRIC`، و`WRITE_EXTERNAL_STORAGE` حتى Android 9 فقط.
**لا** `MANAGE_EXTERNAL_STORAGE` ولا `QUERY_ALL_PACKAGES` ولا المنبهات الدقيقة ولا الموقع ولا الكاميرا. الوصول لبطاقة SD/USB عبر SAF باختيار المستخدم.

## التخزين والأسرار
- كلمات مرور Proxy وFTP/SFTP ورمز Hugging Face ورمز RPC: AES-256-GCM بمفتاح في Android Keystore (`SecretStore`). لا تُخزَّن في Room ولا في الروابط (كلمة المرور داخل `ftp://u:p@h` تُنقل وتُحذف من الرابط).
- المجلد الخاص: AES-256-GCM بكتل مع AAD (رقم الكتلة + علامة الأخيرة) يكشف التعديل والحذف وإعادة الترتيب والاقتطاع (`VaultCryptoTest`). المفتاح يتطلب البصمة أو رمز الجهاز.
- `allowBackup=false` ونقل الجهاز معطّل؛ النسخ الاحتياطي اليدوي لا يتضمن كوكيز ولا Referer ولا أسرار (`BackupTest`).
- الكوكيز الملتقطة من المتصفح تُحفظ مع المهمة في قاعدة بيانات التطبيق الخاصة (غير مشفّرة على مستوى الحقل؛ محمية بعزل التطبيق). **قيد معروف.**
- السجل التقني في الذاكرة فقط ومنقّح (الاستعلامات، userinfo، Bearer، كوكيز، hf_) — اختبار `techLogRedactsSecrets` كشف خللاً في الترتيب وأُصلح. لا استدعاءات `Log` في الشيفرة.

## الشبكة
- TLS بشهادات النظام فقط (`network_security_config`)، بلا شهادات مستخدم، بلا أي تجاوز للتحقق في HTTP أو FTPS (`ftpsExplicitTlsWithTrustedCertAndRejectsUntrusted` يثبت رفض الشهادة غير الموثوقة).
- HTTP العادي مسموح لأن خوادم ملفات كثيرة لا تدعم HTTPS، ونافذة الإضافة تعرض «اتصال غير مشفّر».
- SFTP: ثقة عند أول استعمال ببصمة SHA-256 يوافق عليها المستخدم، والمفتاح المتغيّر يُرفض دائماً.
- رمز Hugging Face يُرسل لـ `huggingface.co` فقط؛ OkHttp يحذف `Authorization` عند التحويل لمضيف آخر.
- الاستئناف لا يدمج نسختين: ETag قوي أو Last-Modified مطابق وإلا بداية آمنة، وIf-Range على كل جزء.

## WebView
JavaScript مفعّل (لازم للمواقع)، `allowFileAccess=false`، `allowContentAccess=false`، `MIXED_CONTENT_NEVER_ALLOW`، الكوكيز الخارجية معطّلة، لا مخططات غير http(s) (`intent:`/`file:`/`javascript:` تُمنع)، لا Geolocation، لا جسور JavaScript. التبويب الخاص: لا سجل ولا تخزين مؤقت، والكوكيز مشتركة (قيد WebView موثّق للمستخدم).

## RPC
مغلق افتراضياً؛ يستمع على `127.0.0.1` إلا بموافقة صريحة؛ رمز 48 خانة سداسية ومقارنة بزمن ثابت؛ حد 256 ك.ب للجسم و8 اتصالات متزامنة و50 ترويسة؛ بلا CORS (صفحات الويب لا تستطيع استدعاءه). (`RpcServerTest`)

## الأرشيفات والملفات
منع Zip Slip والمسارات المطلقة والروابط الرمزية، وحد للحجم والنسبة وعدد العناصر، وفحص المساحة أثناء الفك (`ArchivesTest`). لا كتابة فوق ملف موجود. تنبيه قبل فتح الملفات التنفيذية، ولا ادعاء بفحص فيروسات.

## نتائج Android Lint (Release)
- `TrustAllX509TrustManager` ×2: داخل `commons-net-3.12.0.jar` (`TrustManagerUtils.getAcceptAllTrustManager`) — أداة في المكتبة **لا يستدعيها تدفّق**؛ `FtpDownloader` يضبط `trustManager` على مدير شهادات النظام صراحة مع التحقق من اسم المضيف، واختبار FTPS يثبت رفض الشهادة غير الموثوقة.
- `InsecureBaseConfiguration`: السماح بـ HTTP العادي مقصود لخوادم الملفات (انظر «الشبكة»).
- `BidiSpoofing` و`ByteOrderMark`: محارف اتجاه وBOM كانت مكتوبة حرفياً في الشيفرة؛ استُبدلت بصيغة `\uXXXX` — **أُصلحت**.
- بقية التحذيرات أسلوبية (PluralsCandidate، UseKtx، موارد غير مستعملة).

## ما يحتاج متابعة
1. تشغيل OWASP Dependency-Check أو `osv-scanner` على `docs/sbom.json` في CI بشبكة مفتوحة.
2. تشفير حقل الكوكيز بـ `SecretStore` (حالياً محمي بعزل التطبيق فقط).
3. اختبار ديناميكي على جهاز (Drozer/MobSF) للمكوّنات المصدَّرة.
