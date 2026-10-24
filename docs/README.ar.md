<div dir="rtl">

# Glassio — دليل الاستخدام

لانشر لـ Google TV / Android TV بشكل Apple TV: زجاج سائل حقيقي، كروسيل من كتلوكات نوفيو، "متابعة المشاهدة" من
Nuvio Legacy، أيقونات بأسلوب tvOS، طقس، سكرين سيفر، وربط ويه Home Assistant.

[English](../README.md)

## التثبيت
1. نزّل `GlassLauncher-x.y.z.apk` من صفحة [Releases](https://github.com/mohammadmrh12/glassio/releases).
2. ثبّته على التلفزيون عن طريق تطبيق Downloader، أو فلاشة، أو من الكمبيوتر:
   `adb connect <ip>:5555` ثم `adb install -r GlassLauncher-x.y.z.apk`
3. افتح Glassio من التطبيقات.

## خليه اللانشر الأساسي (زر الهوم)
كوكل تي في دائماً يوجّه زر الهوم للانشر مالته، فاللانشر يستخدم خدمة إمكانية الوصول:
1. الإعدادات ← **System** ← *Turn on the accessibility service* ← Glassio ← تشغيل.
2. مُستحسن (حتى تبقى الخدمة شغالة بعد التحديثات): من الكمبيوتر مرة وحدة
   `adb shell pm grant com.glasslauncher.tv android.permission.WRITE_SECURE_SETTINGS`
3. أول مرة يستخدم اللانشر adb مال التلفزيون (سكرين سيفر التلفزيون، متابعة المشاهدة، التحديث الصامت) تطلع رسالة
   **Allow USB debugging** — فعّل **Always allow** واضغط **Allow**. لازم *Network debugging* يكون مفعّل من خيارات المطور.

## الكتلوكات (إضافات نوفيو)
- الإعدادات ← **Catalogs** ← *Add add-ons from my phone (QR)*: امسح الكود بالموبايل (نفس الواي فاي) والصق روابط
  `manifest.json` وتوكن TMDB.
- أو *Connect to Nuvio*: بنوفيو افتح Add-ons ← Manage from phone أولاً، بعدين اضغط Connect.
- اختار الكتلوكات اللي تريدها.

## توكن TMDB (لوكوات، صور كاملة، ساينوبسس، تقييم، معرض الصور)
سجّل بموقع themoviedb.org ← Settings ← API ← انسخ **API Read Access Token**، وحطه بالإعدادات ← Catalogs ← TMDB token.

## متابعة المشاهدة
- تشتغل ويه **Nuvio Legacy**: نوفيو ليكسي يكتب شنو تشوف (العنوان، الحلقة، وين وصلت) بسجل التلفزيون، واللانشر يقراه
  عن طريق اتصال التصحيح مال التلفزيون نفسه (ADB، شوف تحت). ماكو شي يطلع من التلفزيون.
- **Trakt**: الإعدادات ← Continue Watching ← *Connect Trakt account* يطلعلك كود، تكتبه بـ trakt.tv/activate من الموبايل
  (بدون باسورد على التلفزيون). بعدها أي شي توقفه بتطبيق مربوط بـ Trakt (نوفيو الأصلي، Nuvio Arc، Re-Shaped، Stremio، Kodi…)
  يطلع بمتابعة المشاهدة. نوفيو الأصلي و Arc و Re-Shaped ما يكتبون سجل Legacy، فلهم استخدم Trakt.
  باقي التطبيقات (نتفلكس، يوتيوب…) كوكل تي في ما يسمح بقراءة متابعتها.
- الإعدادات ← **Continue Watching**: ببداية الكروسيل / مدموجة / مطفية، كم سلايد بالبداية، يفتح على آخر شي شفته،
  شريط التقدم، مسح القائمة.
- العنوان يطلع من القائمة لما تخلصه (أكثر من 92%) أو تحذفه. صور هالعناوين تبقى محفوظة بالذاكرة، فالرجوع إلها ما يحمّل من جديد.

## تفعيل ADB (مرة وحدة)
متابعة المشاهدة، تعيين Aerial Views كسكرين سيفر، تشغيل السكرين سيفر والتحديث الصامت تحتاج ADB. الإعدادات ← **System**:
1. **Open About** ← اضغط OK على *Android TV OS build* (أو *Build*) **7 مرات** لحد ما يگلك صرت مطور.
2. **Open Developer options** ← فعّل **USB debugging** (ببعض الشاشات هم *Network debugging*).
3. **Allow Glassio** ← التلفزيون يسأل *Allow USB debugging?* ← أشّر **Always allow from this computer** ← OK. تنسأل مرة وحدة بس.

## فتح العناوين
الإعدادات ← Nuvio & Home Assistant ← *Open titles in*: **Auto** (أول تطبيق منصّب بهالترتيب: Nuvio Legacy، نوفيو الأصلي،
Nuvio Arc، Nuvio Re-Shaped) أو تختار واحد بنفسك. لغة الساينوبسس: الإعدادات ← Carousel ← *Synopsis language* (24 لغة).

## القائمة، المكتبة، الثيمات والخلفية (1.9.5)
- **القائمة**: من تصعد لزر الإعدادات تنفتح قائمة مثل ابل تي في: الساعة، التاريخ، الطقس، ثم Library، Glassio settings، Android settings، Wi-Fi، Themes، Background. تنفتح تلقائي أو بـ OK أو تنطفي (الإعدادات ← Menu, Library & themes)، وكل جزء بيها إله زر إخفاء.
- **المكتبة**: زر **+** بين Play والتالي يحفظ العنوان. 5 بوسترات بالصف، OK يفتح العنوان، OK مطوّل يحذفه. تطلع بيها **مكتبة حساب Nuvio** (Legacy والأصلي والفوركات اللي تتزامن ويا الحساب، تسجيل بـ QR) و**Watchlist مال Trakt**، و+ يحفظ بيهن هم.
- **الثيمات**: Black on white، Black AMOLED، Liquid، Clear، Glass still، Glass is glass للدوك والأيقونات والقائمة، ويا *Colour on focus*.
- **الخلفية**: خلفية كل عنوان، أو **صورتك**، أو **فيديوك** (رابط أو ملف بمجلد الخلفيات اللي مكتوب بالإعدادات ← Clock & glass)، أو **فيديوات السكرين سيفر**. ويا خلفيتك الخاصة تختفي التفاصيل والأزرار، والكلاس يتحرك ويا الفيديو.
- **تفعيل خدمة الأكسسبلتي بـ ADB** من الإعدادات ← System للأجهزة اللي تمنعها (Google TV Streamer).

## الكروسيل
- الإعدادات ← Carousel ← **Slide animation**: **Apple TV** (الافتراضي: الصورة الجاية تنفتح من اليمين مثل ابل تي في، والرجوع بالعكس، ويه الكلاس)، *Slide*، أو *Fade*.
- **Buttons**: **Play + Next** (الافتراضي: يمين من Play يحدد الزر الدائري، يمين أو OK عليه يبدّل للعنوان الجاي، يسار على Play للي قبله، ومكان الزرين ثابت)، *Play only*، أو مخفية.
- **Dots**: نقاط عادية، **Liquid glass dots** (قطرات كلاس صافية على كبسولة كلاس، تتمطّ وتلتحم وتنفصل مثل الماي)، شريط تقدم، أو مخفية.

## الأيقونات
- مجلد التطبيقات: الإعدادات ← Dock & icons ← *Show Apps folder* (أو OK مطوّل على المجلد ← *Hide from the dock*).
- اضغط **OK** مطولاً على أي تطبيق ← **Change icon**: المقترحة، أيقونات Apple TV (أصلي/دارك/وايت/كلير)، أي حزمة
  أيقونات (Projectivy, Blueprint, Nova)، صور التلفزيون، رابط، الموبايل، أو الأصلية.
- الإعدادات ← Clock & glass: **Colour on focus** (الأيقونات بيضة وتتلون عند التحديد)، لون البلاطة فاتح/غامق،
  حركة الحافة، رفع الأيقونة.

## الطقس
الإعدادات ← Clock & glass ← **Weather** (كلشي / الحرارة + السماء / الحرارة + المدينة / الحرارة / إطفاء)،
**Weather city** (عربي أو إنكليزي — المكان اللي ينلكه يطلع يمه)، فهرنهايت. مكان الساعة: يسار مثل أبل أو يم الإعدادات.

## السكرين سيفر
الإعدادات ← **Screensaver** ← *Type*: سكرين سيفر التلفزيون (مثلاً Aerial Views — يشتغل فوراً)، فيديوهات أبل الجوية،
أو بوسترات ويه اللوكو. وزر الرجوع بالهوم يشغّله مثل أبل.

## الأداء
الإعدادات ← **Performance**: *Performance* (أسلس: صور 1280، انتقال سريع، زجاج مسطح)، *Balanced*، *Quality* (صور أصلية،
زجاج كامل). تكدر تغيّر كل إعداد لحاله (يصير الوضع *Custom*).

## ربط Home Assistant
اللانشر يرسل JSON لويب هوك بالهوم اسستنت كل ما تفتح عنوان من الكروسيل:
`{"event":"launch","title":"…","type":"movie|series","id":"tt…","app":"…"}`

1. بالهوم اسستنت: Settings ← Automations ← Create ← Edit in YAML والصق:

</div>

```yaml
alias: Glassio – title opened
mode: queued
triggers:
  - trigger: webhook
    webhook_id: glass_launcher_CHANGE_ME
    allowed_methods: [POST]
    local_only: true
actions:
  - event: glass_launcher
    event_data:
      title: "{{ trigger.json.title }}"
      type: "{{ trigger.json.type }}"
      id: "{{ trigger.json.id }}"
  - if: "{{ trigger.json.event == 'launch' }}"
    then:
      - action: light.turn_off
        target: { area_id: living_room }
```

<div dir="rtl">

2. الرابط يكون: `http://<IP الهوم اسستنت>:8123/api/webhook/glass_launcher_CHANGE_ME`
   حطه بالإعدادات ← **Nuvio & Home Assistant** ← Home Assistant webhook (أو من صفحة الموبايل).
3. شغّل **Tell Home Assistant when a title opens** واضغط **Send a test event** للتجربة.

استخدم IP الهوم اسستنت مو `homeassistant.local`.

## التحديثات
اللانشر يفحص آخر إصدار على GitHub عند التشغيل وكل 12 ساعة. تطلع علامة **Update** يم الساعة ويه بانر صغير بالجديد:
اصعد لها واضغط **OK** ينزّل وينصّب مباشرة (أو من الإعدادات).
الإعدادات ← **About** ← **Install update**: ينزّل التحديث ويثبته — بصمت ويرجع يفتح لحاله إذا وافقت على USB debugging،
وإلا يطلب منك تأكيد (فعّل *Install unknown apps* لـ Glassio).

## الخصوصية
بدون حسابات أو تتبع أو إعلانات. الإعدادات تبقى بالتلفزيون. صفحة الموبايل تشتغل بس وهي مفتوحة وكل طلب يحتاج الرمز اللي
بالكيو آر. adb مال التلفزيون يُستخدم بس لتشغيل السكرين سيفر، قراءة سجل نوفيو، وتثبيت تحديثات اللانشر.

## الرخصة
Glassio برنامج حر تحت رخصة [GPL-3.0](../LICENSE)، نفس رخصة نوفيو. تكدر تستخدمه وتعدّله وتشاركه، وأي نسخة معدّلة تنشرها لازم تبقى مفتوحة المصدر بنفس الرخصة.

</div>
