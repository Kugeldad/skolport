# Skolport

En liten Android-app med två knappar, **InfoMentor** och **Skola24**. Varje knapp öppnar
tjänstens vanliga webbsida i telefonens inbyggda webbvy (Android System WebView). Appen
kräver inga Google-tjänster och fungerar därför också på telefoner utan Google, till exempel
GrapheneOS och Mudita Kompakt.

> **Inofficiell app.** Skolport har ingen koppling till InfoMentor eller Skola24. Namnen tillhör
> sina respektive ägare. Appen visar bara tjänsternas egna webbsidor och sparar ingenting
> utanför telefonen.

## Installera

Hämta `Skolport.apk` under [Releases](../../releases), eller lägg till repots adress i
[Obtainium](https://github.com/ImranR98/Obtainium) så att appen uppdateras automatiskt.

Alla versioner signeras med samma nyckel. Du kan kontrollera signaturen, till exempel med
AppVerifier:

```
se.skolport
34:0C:40:71:1C:F5:95:4B:03:3F:4A:12:F6:7A:20:30:D4:1B:C1:77:52:CE:5E:5A:8B:C7:8C:12:D4:D3:08:3A
```

- **GrapheneOS och annan Android:** Öppna APK-filen på telefonen och tillåt installation
  från den källan när telefonen frågar.
- **Mudita Kompakt:** Använd Mudita Center ("lägg till tredjepartsappar") eller kör
  `adb install Skolport.apk` med USB-felsökning påslagen.

## Användning

- **InfoMentor** öppnar `https://hub.infomentor.se`.
- **Skola24** frågar första gången efter det som står före `.skola24.se` i kommunens
  Skola24-adress (t.ex. `uppsala`). Därefter öppnas `https://<namn>.skola24.se`. Om kommunen
  har en särskild adress för BankID kan du skriva den i stället, t.ex. `uppsala-sso`. Väljer
  du "Välj i lista" visas skola24.se:s lista över domäner.
- **Håll in en knapp** för att ändra eller återställa adressen.
- **Logga ut från allt** raderar alla inloggningar och sparad webbdata. Adresserna ligger kvar.

## BankID

Välj **"BankID på den här enheten"** (inte QR-kod). Appen startar då BankID-appen med
`redirect=null`, som BankID rekommenderar för Android. När du har legitimerat dig stängs
BankID och du hamnar tillbaka i Skolport, där inloggningen gör klart av sig själv. BankID-appen
behöver finnas på samma telefon. Om den saknas kan du välja QR-kod och skanna med en annan enhet.

## Bygga själv

Appen byggs utan Gradle, med plattformens `android.jar` och de klassiska byggverktygen:

```
sudo apt install android-sdk-platform-23 aapt dalvik-exchange zipalign apksigner openjdk-17-jdk-headless
./build.sh
```

Första gången `build.sh` körs skapas en signeringsnyckel i `keystore/`. Mappen ligger i
`.gitignore` och ska aldrig checkas in. Med nyckeln kan vem som helst signera en "uppdatering"
som Android installerar ovanpå appen. Spara nyckeln säkert: en ny version måste signeras med
samma nyckel. Höj `VERSION_CODE` i `build.sh` vid varje release.

## Begränsningar

- Inga push-notiser. Det kräver tjänsternas egna appar.
- Filer hämtas med systemets nedladdningshanterare till Hämtade filer.
- Säkerheten beror på telefonens WebView-version.

## Licens

MIT. Programvaran levereras i befintligt skick, utan garantier. Se [LICENSE](LICENSE).
