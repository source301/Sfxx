# Manhwa SFX Reader

## البناء
1. Android Studio (Koala أو أحدث) > File > Open > مجلد SfxReader، واقبل مزامنة Gradle.
2. Build > Build APK(s)، أو Run على موبايل موصول.

## الوضع المباشر (الأساسي)
1. اختر لغة المؤثرات، اضغط "ابدأ"، ووافق على نافذة تسجيل الشاشة.
2. افتح أي تطبيق مانهوا واقرأ: الصوت يطلع لما يظهر المؤثر على الشاشة.
3. للإيقاف: من التطبيق أو من زر "إيقاف" بالإشعار.

## وضع القارئ الداخلي
استيراد صور/CBZ وتحليلها مرة وحدة ثم القراءة داخل التطبيق.

## التخصيص
- كلمات المؤثرات: SfxDictionary.kt
- الأصوات: app/src/main/res/raw/sfx_*.wav
- سرعة الالتقاط: delay(600) داخل LiveService.loop

## البناء بدون Android Studio (مجاني، من المتصفح)
1. سوّي حساب على github.com وأنشئ Repository جديد.
2. ارفع محتويات المجلد (Add file > Upload files)، مع مجلد .github.
3. تبويب Actions > Build APK > Run workflow، وانتظر ~5-10 دقائق.
4. افتح التشغيل الناجح ونزّل SfxReader-apk (فيه app-debug.apk) ثبّته على موبايلك.
