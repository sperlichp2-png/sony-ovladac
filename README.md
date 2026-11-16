# Sony ovladač (Android, IR)

Aplikace pro telefon s IR vysílačem (Poco M6 Pro), která ovládá televizi Sony KD-55XD7005.
Tlačítka jsou rozložená stejně jako na ovladači (typ RMT-TX300E).

## Jak získat APK

### A) Přes GitHub
Postup je v chatu s Claudem: nahraješ tento ZIP do repozitáře a přidáš soubor sestavení.

### B) Android Studio na počítači
1. **File → Open** a vyber tuto složku. Počkej, až se dokončí synchronizace Gradle.
2. **Build → Build App Bundle(s) / APK(s) → Build APK(s)**.
3. Nebo připoj telefon s povoleným laděním přes USB a klikni na ▶ Run.

## Používání
- 🎤 nahoře = hlasové povely, např. „zesil“, „zesil o pět“, „ztlum“, „vypni zvuk“, „kanál 5“,
  „další program“, „Netflix“, „domů“, „zpět“, „pauza“, „vypni televizi“.
- Míř telefonem horní hranou (tam je IR dioda) na televizi.
- Hlasitost, programy a šipky se při podržení opakují.
- **✎ vpravo nahoře = režim úprav.** Když nějaké tlačítko nefunguje, zapni úpravy,
  klepni na tlačítko a změň jeho kód. Tlačítko „Zkusit“ kód vyšle bez uložení,
  „Uložit“ ho uloží natrvalo a „Výchozí“ vrátí původní kód.

## Kódy
Protokol je Sony SIRC s nosnou frekvencí 40 kHz. Většina kódů pochází z oficiální tabulky Sony
(SIRCS Code List na pro-bravia.sony.net): napájení, vstup, číslice, šipky, OK,
HOME, BACK, ACTION MENU (OPTIONS), hlasitost, programy, ztlumení, JUMP a i+ (DISPLAY).

Ostatní kódy (barvy, přehrávání, NETFLIX, GUIDE, titulky, AUDIO, text, EXIT,
SYNC MENU, TV/RADIO) jsou podle známých kódů IRCC pro televize Bravia.

**Neověřené odhady**, které se mohou lišit: DIGITAL/ANALOG, HELP, DISCOVER, TITLE LIST.
