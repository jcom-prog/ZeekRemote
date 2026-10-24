# Changelog ZeekRemote

Wat je per versie merkt, in gewone taal. ZeekRemote is een privé-afsplitsing van [OpenZeekr](https://github.com/borconi/openzeekr) (splitpunt: OpenZeekr 0.1.4) voor één telefoon en één auto; de nadruk ligt op betrouwbaar openen bij het naderen en sluiten bij het weglopen, met een simulator en een regressiepoort als bewaker. Dit logboek bevat geen meetwaarden, tijden, plaatsen of persoonsgegevens; technische details staan in de commits.

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
