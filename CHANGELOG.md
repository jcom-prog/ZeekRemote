# Changelog ZeekRemote

Wat je per versie merkt, in gewone taal. ZeekRemote is een privé-afsplitsing van [OpenZeekr](https://github.com/borconi/openzeekr) (splitpunt: OpenZeekr 0.1.4) voor één telefoon en één auto; de nadruk ligt op betrouwbaar openen bij het naderen en sluiten bij het weglopen, met een simulator en een regressiepoort als bewaker. Dit logboek bevat geen meetwaarden, tijden, plaatsen of persoonsgegevens; technische details staan in de commits.

## 0.4.6 (alleen telefoon)

- **Stille zones.** In de Security-tab kun je tot drie plekken vastleggen (knop "Add zone here" terwijl de auto er staat; straal en naam per zone aanpasbaar) waar de automaat de bewaking na het sluiten uit laat. Je eigen schakelaar werkt daar gewoon: naar rechts schuiven zet de bewaking voor die parkering toch aan. Met "Skip in zones" uit gelden de zones tijdelijk niet. Is de positie van de auto onbekend of niet te vertrouwen, dan schakelt de automaat voor de zekerheid wél in. De zones blijven alleen op de telefoon; in de export staat hooguit een zonenummer.
- **Dagelijkse stille controle.** Op een door jou gekozen uur leest de app de auto één keer uit. Staat de bewaking uit terwijl de auto gesloten staat en niets de automaat tegenhield, dan wordt hij ingeschakeld; een open deur, motorkap, achterklep, raam of dak en een accu onder je grens worden gemeld; is alles in orde, dan hoor je niets.
- **Open raam of dak bij het inschakelen.** De app meldt het met een knop "Close windows" (telefoon ontgrendeld); sluiten doet hij nooit uit zichzelf, en de bewaking gaat gewoon aan. Uit te zetten met "Warn when a window or the sunroof is open".
- **De schakelaar volgt je hand.** Na het schuiven staat de schakelaar meteen in de nieuwe stand terwijl de auto wordt gevraagd; bevestigt de auto niet binnen de wachttijd, dan springt hij terug en staat eronder waarom. De auto wordt sneller teruggelezen (na tien seconden, daarna elke twintig).
- **Woordkeuze.** "Hood" heet nu "bonnet" (motorkap), zodat het niet als open dak gelezen kan worden. De horloge-app verandert niet en blijft 0.4.3.

## 0.4.5 (alleen telefoon)

- **Sentry guard automatisch aan na het sluiten.** De auto zet zijn bewaking nooit zelf aan; tot nu toe moest dat na elke parkering met de hand. In de stand *Auto* (Security-tab) doet ZeekRemote het: twee minuten na een bevestigde sluiting, als de auto gesloten is met alle deuren, de motorkap en de achterklep dicht en de accu boven de door jou gekozen grens, stuurt de app de opdracht en leest hij terug of de auto het bevestigt (de cloud doet daar ongeveer een minuut over); na één herhaling zonder bevestiging krijg je een melding. Je eigen schakelaar, in de app of op het scherm van de auto, wint tot de volgende sluiting. *Handmatig* laat alles aan de schakelaar; *Uit* houdt de app bij de bewaking vandaan.
- **Accugrens die je zelf typt.** Onder het percentage dat je in de Security-tab invult, zet de automaat de bewaking uit (met een melding) en slaat hij het inschakelen over; hij komt terug zodra de accu vijf punten boven de grens staat of de auto laadt. De controle loopt elk uur mee met de bestaande verbindingsronde van de app.
- **De schakelaar toont wat de auto zegt.** De stand van de bewaking wordt elke keer opnieuw gelezen als de Security-tab naar voren komt (voorheen één keer, waarna de tab bleef staan op een verouderd "Armed"); onder de rij staat wat de auto het laatst meldde, met het tijdstip en de accustand. Elke stap van de automaat staat zonder plaats, naam of waarde in de diagnose-export. De horloge-app verandert niet en blijft 0.4.3.

## 0.4.4 (alleen telefoon)

- **Bestemming naar de auto sturen is nu zichtbaar.** Na "Send to Zeekr" in het deelmenu van Google Maps toont ZeekRemote meteen dat hij de gedeelde plek leest (een korte Maps-koppeling wordt via het netwerk opgelost), vraagt om bevestiging met de volledige naam van de plek (straat en nummer, niet alleen het huisnummer) en meldt in een venster dat blijft staan of de bestemming is verzonden, of waarom niet, met een knop om het opnieuw te proberen. De stappen staan zonder plaats, naam of koppeling in de diagnose-export. De horloge-app verandert niet en blijft 0.4.3.

## 0.4.3 (telefoon + horloge)

- **Horloge en telefoon vechten niet meer om de auto.** Zolang het horloge de auto bedient, doet de telefoon niets met de verbinding: geen herverbinding, geen eigen Lock en geen cloud-Lock als uitwijk, zodat een opening met het horloge nooit seconden later door de telefoon ongedaan wordt gemaakt. Elke overdracht heeft een nummer; een melding van het horloge telt alleen voor de overdracht waar hij bij hoort, en de wachttijd van de telefoon (45 s) loopt pas vanaf het moment dat de auto echt is vrijgegeven. Na een overdracht die met de auto open eindigt, kijkt de telefoon opnieuw met verse gegevens.
- **Horloge zonder telefoon.** Zonder gegevens van de telefoon toont het horloge twee knoppen (Openen, Sluiten) in plaats van een schuifknop die de verkeerde kant op kon sturen; na een handeling laat het horloge de auto los, zodat de telefoon er weer bij kan; bevestigingen van klimaat, laden en zoeken horen weer bij de juiste knop; bij een mislukte overdracht geeft het horloge de verbinding meteen terug; de sleutel wordt één keer per start gevraagd.
- **"Watch key" uit wist het horloge ook als het op dat moment buiten bereik was** (het horloge leest de stand bij elk contact). In het dagboek staan nu ook de noodrem van de telefoon en genegeerde meldingen.

## 0.4.2 (alleen horloge)

- **Horloge-icoon.** De horloge-app toonde nog het icoon van OpenZeekr; hij draagt nu het ZeekRemote-ontwerp "Oranje wiel" uit de icoonreeks van de telefoon-app. De telefoon-app verandert niet en blijft 0.4.1.

## 0.4.1 (telefoon + horloge)

- **Horloge-app (Galaxy Watch, Wear OS).** De horloge-app haalt op uw verzoek een kopie van de digitale sleutel van de telefoon en kan de auto zelf openen en sluiten, ook als de telefoon niet in de buurt is; daarnaast toont hij accu, bereik en laadstand en bedient hij klimaat, laden, laadklep en zoeken via de telefoon. Het horloge en de telefoon spreken af wie de auto heeft: houdt de telefoon de auto verbonden met naderen aan, dan doet het horloge niets; anders laat de telefoon de verbinding even los, handelt het horloge en meldt het de uitkomst, waarna de telefoon de bewaking weer oppakt (een horloge-Lock wordt nooit ongedaan gemaakt bij de deur; na een horloge-Unlock blijft de telefoon waken of de auto dicht gaat). In Instellingen staat de schakelaar "Watch key": uit betekent dat het horloge de sleutel wist.
- **Telefoon.** Verder gelijk aan 0.4.0g; de horloge-handelingen staan in het dagboek en de export (alleen namen en uitkomsten).

## 0.4.0g

- **Luchtfoto zonder grijze blokken.** Met de luchtfoto aan worden de gebouwlagen van de kaartstijl verborgen (de kaartmotor tekende de 3D-blokken dwars door de foto heen); u ziet nu de echte daken, met straatnamen en de speld erbovenop. Luchtfoto uit: de gebouwen komen terug.

## 0.4.0f

- **Kaart met meer detail.** De grote kaart opent dichterbij (zoom 18) en licht gekanteld, zodat gebouwen als blokken zichtbaar zijn; straatnamen zijn 20 % groter; de auto staat op een donkere speld met auto-icoon. Onder het kaartje en in de grote kaart staat het adres waar de auto volgens de cloud staat, opgehaald via de adresdienst van de telefoon; het adres wordt nergens opgeslagen of gelogd. In de grote kaart zet de knop met het satellietje de luchtfoto van PDOK (Nederlandse overheid, open data, zonder sleutel) onder de straatnamen; die keuze wordt onthouden. Buiten Nederland blijft de gewone kaart. "Navigate to car" is ongewijzigd.

## 0.4.0e

- **Waar staat mijn auto: een kaart die je echt kunt gebruiken.** Tik op het kaartje in het tabblad Location om het schermvullend te openen; daar kun je knijpen of de knoppen gebruiken om in en uit te zoomen, de kaart verschuiven en met één tik weer op de auto centreren. De speld blijft op de plek van de auto staan, ook bij zoomen en schuiven (voorheen stond hij vast in het midden van het kaartje). De kaart meldt het als de kaartgegevens nog laden of niet te laden zijn, en onder het kaartje staat hoe oud de positie is. "Navigate to car" is ongewijzigd.

## 0.4.0d (meetversie)

- **De plaats van de auto mag het openen nu ook aankondigen, maar beslist nog niet.** Komt de telefoon volgens de positiepeiling tot binnen zes meter van de onthouden plek van de auto, neemt die afstand af terwijl je loopt en zit het signaal al vlak onder het openniveau, dan noteert de app (bij twee peilingen op rij) dat het portier nu open zou kunnen. In deze meetversie staat dat alleen in het logboek en in het dagboek; de beslissing blijft die van 0.4.0c. Na een waargenomen vertrek doet de regel niets.

## 0.4.0c (meetversie)

- **De plaats van de auto doet mee, maar beslist nog niet.** De app onthoudt waar de auto stond (de locatiepeiling bij de auto vlak na het openen, de laatste vijf bezoeken samengenomen; alleen op de telefoon, nooit in een export) en vergeet dat zodra je met de auto wegrijdt. Komt de Bluetooth-verbinding ineens sterk binnen terwijl je net begint te lopen — zoals bij een voordeur met vrij zicht op de auto — dan vraagt de app zijn positie op en schrijft in het logboek of hij de opening had tegengehouden (te ver van de auto) of juist had laten gaan. Ook tijdens een gewone nadering kijkt hij kort mee. Het gedrag van de auto is dat van 0.3.30; de schaduw uit 0.4.0b blijft.
- Positiepeilingen op de gesloten kant zijn begrensd (zolang de verbinding in de buurt van het openniveau is, ten hoogste een minuut per keer); het logboek toont alleen afstanden in meters.

## 0.4.0b (meetversie)

- **De eigen stappenteller rekent mee, maar beslist nog niet.** De regels uit de simulatorstudie (de eigen teller als loopbewijs bij naderen en weglopen, een korte wachtregel tegen openen van ver, en een loopkrediet dat die wachtregel overslaat na een echte aanloop) draaien als schaduw naast de bestaande beslissingen en schrijven in het logboek wat ze anders gedaan zouden hebben. Het gedrag van de auto is dat van 0.3.30.
- De eigen teller draait daarvoor ook tijdens het naderen (alleen zolang je loopt; een stilliggende telefoon houdt de sensor niet aan).

## 0.3.30

- **Laadsnelheid bij DC-snelladen.** Aan een snellader toonde de app "Laden" zonder kW; de stroom en spanning van de snellaadpaal werden gedeclareerd maar nooit uit de status gelezen. Nu wel (overgenomen uit OpenZeekr 0.2.2).
- **Langer logboek.** De interne logring bewaart 5000 regels in plaats van 500, zodat een export na een voorval de hele loop heen en terug dekt (overgenomen uit OpenZeekr 0.2.2).
- **Dit changelog.**

## 0.3.29

- Een alarm over een open auto wordt pas gedempt als er loopbewijs is: een gunstige plek op zo'n tien meter leest soms als "bij de auto", maar zonder passen blijft het alarm klinken tot de Lock, je tik of de eigen time-out.

## 0.3.28

- Na een automatische Lock opent de auto niet meer op het signaal alleen: een stilstaande telefoon die door signaalvariatie op het terugkeerniveau komt, laat de auto dicht; teruglopen met passen opent wel.

## 0.3.27

- Meetmodus (vijf minuten) laat de eigen stappenteller van de app ook zonder auto meedraaien, zodat een loopje de teller, de draaidetectie en het accuverbruik kan meten.

## 0.3.26

- De meetregels van de eigen stappenteller en de dagelijkse accustand komen in het privé-dagboek van de diagnose-export; de export vermeldt zijn bronnen en voegt het systeemlog en de buffer samen.

## 0.3.25

- Het vertrekbewijs met locatie controleert of een positie bij de gelopen passen past; een springende fix wordt tijdelijk genegeerd. Een stilliggende telefoon houdt zijn laatst zekere positie vast.

## 0.3.24

- Alleen-lezen diagnose: een knop in Instellingen leest de actuele regelstanden van de auto uit (niets wordt gewijzigd).

## 0.3.23

- Volledig klimaatpaneel naar het voorbeeld van de officiële app: snel koelen, snel verwarmen, onthouden looptijden, stoelverwarming en -ventilatie per stoel.

## 0.3.22

- Meetversie: de eigen stappenteller en draaidetectie van de app en een accumeting draaien mee (alleen loggen, geen beslissingen).

## 0.3.21

- Een lopende achtergrondscan wordt niet meer gestopt voor een herstart die Android weigert (maximaal vijf scanstarts per halve minuut); na een mislukte verbinding aan de rand van het bereik wordt netjes opnieuw gezocht.
- Als het weglopen gezien is maar het onafhankelijke bewijs nog loopt, biedt de app de Lock meteen aan (knop op het vergrendelscherm).

## 0.3.20

- Een vertrekschema dat je in de officiële app verwijdert, verdwijnt hier ook.
- Thuiskomen met de auto: het alarm wacht op de Lock; de "beweging" die Google nog minutenlang na een rit meldt, geldt niet als lopen.

## 0.3.19

- Terugkomen bij de auto zonder sterke lezing (telefoon achter het lichaam) start de passentelling voor het Lock-veto opnieuw.

## 0.3.18

- De schemalijst wordt opnieuw gelezen bij tonen, bij terugkomen en elke halve minuut; een uitgeschakelde stoel wordt correct getoond.

## 0.3.17

- Vertrekschema's: stoelverwarming per stoel (bestuurder, bijrijder, links- en rechtsachter; uit of stand I–III), temperatuur en stoelkeuze.

## 0.3.16

- Een vertrekschema wissen schrijft de plek als ongebruikt terug (zoals de officiële app); de lijst toont per keer zijn vorm.

## 0.3.15

- Een nieuw vertrekschema is terugkerend zoals de auto het opslaat.

## 0.3.14

- Een nieuw vertrekschema wordt op de vrije plek geschreven die de auto meldt.

## 0.3.13

- De regressiepoort controleert de terugwandeling naar de auto apart; de "sterke plek" op afstand zit nu ook tijdens het lopen in de poort.

## 0.3.12

- Niet meer heropenen van veraf op een sterke plek; nooit stil met een open auto zodra het weglopen is begonnen; poort met twaalf werelden.

## 0.3.11

- Vertrekschema toevoegen via de vrije plek; de poort meet een echte loop na.

## 0.3.10

- Een alarm over een open auto stopt niet meer te vroeg.

## 0.3.9

- Alarmgaten gedicht bij een trage, stille of dode stappenteller; meetlog voor stappen, beweging en scherm; poort met de ergste gevallen van de eigenaar.

## 0.3.8

- Schemawijzigingen wachten op de auto en melden eerlijk wat er gebeurde.

## 0.3.7

- Geen persoonsnamen in de credits.

## 0.3.6

- Vertrekschema's zoals de officiële app; berichtenbel weg; kalibratietest verwijderd.

## 0.3.5

- Privacy-opschoning: geen gegevens uit praktijklogs in de code.

## 0.3.4

- Parkeren op afstand grijs en uitgeschakeld (de auto weigert het voor een software-sleutel); proefknoppen en de update-check van de bron verwijderd; README in de app.

## 0.3.3

- Vast app-pictogram "Profiel"; vijf eigen pictogrammen.

## 0.3.2

- Naderen met een dip in het signaal; de stappenteller meldt wanneer hij levert; poortbaseline.

## 0.3.1

- Zelfkalibratie per telefoon uit bezoeken; vertrek met de telefoon in de achterzak; Bluetooth- en accuverbeteringen.

## 0.3.0

- Veiligheidsrelease: alleen sluiten op bewezen vertrek; nooit stil als de auto open is en jij weg bent; Lock-veto bij terugkeer; herstel na een procesherstart.

## 0.2.0 – 0.2.17

- Lock geldt pas als de auto zelf "gesloten" meldt; hoorbaar "Auto gesloten"; Lock-knop op waarschuwingen (ook vanaf het vergrendelscherm).
- Vertrekbewijs zonder stappen (scherm uit); cloud-Lock meteen als de sleutel-Lock aan de rand van het bereik onbeantwoord blijft; geen verbindingen aan de rand van het bereik; alarm na verlies buiten bereik.
- Eigen sleutelmodule onthouden en andere Zeekrs overslaan; heropenen na bewezen weg-en-terug; hardware-stappenteller voor het vertrekbewijs; geen alarm als je al terug bent.
- "Deel diagnose": een leesbaar, gefilterd foutrapport; ritafstanden en laadsoort in het rittenlogboek.

## 0.1.5 – 0.1.67

- Vroege nadering op de 7GT, sneller bemonsteren, herstel van passive entry na lang stilstaan, sleutel wekken, ontbrekende adverts herkend; opschoning van bouw en geheimen.
- Driefasig laadvermogen, ventilatiezones achterbank, snelheid op de kaart, aanpasbare navigatiebalk, ontwikkelgereedschap (overgenomen uit OpenZeekr 0.1.8/0.1.9).
- Cloud-sessie vernieuwt zichzelf zonder de sleutel te raken; detectie van een uitlog door een andere app; Updates-tab en Frunk-tegel verwijderd.
- Terugkeer na een Lock of een zelfvergrendeling herkend uit het signaal; de "niet gesloten"-waarschuwing bruikbaar vanaf het vergrendelscherm; een auto die open ging terwijl de verbinding weg was, wordt bij de volgende sessie weer bewaakt.
