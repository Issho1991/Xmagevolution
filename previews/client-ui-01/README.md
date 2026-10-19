# Anteprima 01 dell'interfaccia XMage

Copia separata per valutare una home più chiara e la creazione guidata dei tavoli.
La distribuzione stabile e i cataloghi firmati non vengono modificati. Prima di una
promozione serve una prova reale su Windows e il riscontro dell'utente.

- Home con Nuova partita, Mazzi, Laboratorio e accesso ai tavoli esistenti.
- Impostazioni essenziali e avanzate separate; profili 2/3/4 giocatori, 30 vite,
  2 vittorie. Il formato dei mazzi non viene sostituito dai profili.
- Stessi controlli XMage, MatchOptions, validazione, salvataggio/caricamento.
- Campo di gioco, IA e logica delle partite invariati rispetto a Lab 10.0.
- Modalità classica e nuova selezionabili dall'avviatore di prova.
- Server separato su loopback 17182; dati, user.home e preferenze Java in questa
  cartella anche su Windows. Nessuna lettura del registro condiviso o dei file personali.
- Nessun updater o chiave di firma nell'avviatore della prova.

## Riproduzione

JDK 17, Python 3.9+. Su Linux usare Xvfb per le verifiche grafiche:

```sh
xvfb-run -a -s '-screen 0 1280x900x24' python3 previews/client-ui-01/build_preview.py --work preview-work --gui
```

Lo script verifica archivio ufficiale e commit del sorgente, applica prima l'overlay
stabile e poi quello di prova, compila Java 8, esegue i 147 test attivi esistenti
senza modificarli e la verifica installazione/backup/server/ritorno. Compila poi
l'avviatore separato, verifica persistenza delle preferenze tra JVM e cambio di modalità,
avvia i client nuovo e classico contro il server di prova, controlla profili e
salvataggio delle impostazioni, e produce screenshot dell'applicazione reale.
La validazione automatica non equivale a una partita completa giocata su Windows.

L'overlay sperimentale è confinato qui: non si aggiunge all'overlay di produzione
né all'integrazione automatica delle future release ufficiali.
Le prove grafiche lavorano su una copia e non entrano nello ZIP distribuito.
Il workflow pubblica soltanto una prerelease con tag basato sul commit. Non accede
all'ambiente di firma né ai cataloghi. In caso di fallimento non pubblica il pacchetto.

## Uso e ritorno alla versione stabile

Scaricare lo ZIP della prerelease, estrarlo in una **cartella separata** e aprire
`Avvia_Anteprima.cmd`. Copiare soltanto i mazzi e le immagini che si vogliono provare.
Per tornare alla versione abituale, chiudere client e server di prova e riaprire
il launcher normale. Non importare questo ZIP nel launcher stabile.

Da verificare manualmente prima della promozione: leggibilità e ridimensionamento
sullo schermo reale, creazione di tavoli con mazzi propri, selezione carte, attacchi,
blocchi, richieste di scelta, report, immagini e riapertura della copia stabile.
